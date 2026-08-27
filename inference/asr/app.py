"""Video Agent ASR 推理服务：双引擎。

- 本地（默认）：Qwen3-ASR-0.6B（官方 qwen-asr 包，GPU 优先，离线）
- 在线：科大讯飞实时语音转写（rtasr v2，WebSocket），需 XF_APPID / XF_APIKEY
引擎选择：请求表单字段 engine（local / xfyun），缺省取环境变量 ASR_ENGINE（默认 local）。
"""
import os
import sys
import tempfile
import threading
import time
import wave

import torch
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel
from typing import List

# 本地模块导入：显式把脚本目录加入 sys.path（部分环境以 -P/safe_path 启动时
# 脚本目录不在 sys.path 中，会导致 import xfyun_client 失败）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import xfyun_client

MODEL_PATH = os.environ.get("ASR_MODEL_PATH", r"E:\agent_projct\.tools\models\Qwen3-ASR-0.6B")
XF_APPID = os.environ.get("XF_APPID", "")
XF_APIKEY = os.environ.get("XF_APIKEY", "")
XF_APISECRET = os.environ.get("XF_APISECRET", "")
ASR_ENGINE = os.environ.get("ASR_ENGINE", "local")

app = FastAPI(title="Video Agent ASR (Qwen3-ASR-0.6B / 讯飞 rtasr)")

_model = None
_load_lock = threading.Lock()


def _load():
    global _model
    if _model is None:
        with _load_lock:  # 并发首请求安全
            if _model is None:
                from qwen_asr import Qwen3ASRModel
                cuda = torch.cuda.is_available()
                _model = Qwen3ASRModel.from_pretrained(
                    MODEL_PATH,
                    dtype=torch.bfloat16 if cuda else torch.float32,
                    device_map="cuda:0" if cuda else "cpu",
                    max_inference_batch_size=2,   # 内部 60s 块 2 路 batch，单请求吞吐翻倍
                    max_new_tokens=512,
                )


def _wav_duration(path: str) -> float:
    with wave.open(path, "rb") as w:
        return w.getnframes() / w.getframerate()


class Segment(BaseModel):
    start: float
    end: float
    text: str


class TranscribeResponse(BaseModel):
    segments: List[Segment]


@app.post("/transcribe", response_model=TranscribeResponse)
async def transcribe(file: UploadFile = File(...), engine: str = Form(ASR_ENGINE),
                     appid: str = Form(""), apikey: str = Form(""), apisecret: str = Form("")):
    data = await file.read()
    suffix = os.path.splitext(file.filename or "audio.wav")[1] or ".wav"
    path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(data)
            path = tmp.name
        if engine == "xfyun":
            # 用户级凭据优先（后端按用户解密后随请求透传），未传则用服务端环境变量
            xf_appid, xf_apikey, xf_apisecret = appid or XF_APPID, apikey or XF_APIKEY, apisecret or XF_APISECRET
            if not (xf_appid and xf_apikey and xf_apisecret):
                raise HTTPException(400, "未配置讯飞 APPID/APIKey/APISecret（请在个人设置提交，或配置服务端环境变量 XF_APPID / XF_APIKEY / XF_APISECRET）")
            try:
                raw_segs = await xfyun_client.transcribe_file(path, xf_appid, xf_apikey, xf_apisecret)
            except Exception as e:
                raise HTTPException(502, f"讯飞极速录音转写失败: {e}") from e
            segments = [Segment(start=s["start_ms"] / 1000.0, end=s["end_ms"] / 1000.0, text=s["text"])
                        for s in raw_segs]
            return TranscribeResponse(segments=segments)
        else:
            _load()
            results = _model.transcribe(audio=path, language=None)
            text = (results[0].text or "").strip()
        segments = []
        if text:
            duration = _wav_duration(path)
            segments.append(Segment(start=0.0, end=round(duration, 3), text=text))
        return TranscribeResponse(segments=segments)
    finally:
        if path:
            try:
                os.unlink(path)
            except OSError:
                pass


@app.get("/health")
def health():
    return {"status": "UP", "engine": ASR_ENGINE,
            "model": "Qwen3-ASR-0.6B" if ASR_ENGINE != "xfyun" else "xfyun-speed",
            "xfyun_configured": bool(XF_APPID and XF_APIKEY and XF_APISECRET)}


def _start_backend_watchdog():
    """后端失联自动退出：释放 GPU 资源。

    后端退出（或崩溃）后本服务继续驻留会白白占用显存；
    看门狗每 10s 探测一次后端健康，失联超过阈值即 os._exit(0)。
    可 WATCH_BACKEND=0 关闭；BACKEND_URL 可改探测地址。
    在模块级调用：每个进程（含 uvicorn worker）都自持一个看门狗，保证全部退出。
    """
    if os.environ.get("WATCH_BACKEND", "1") != "1":
        return
    backend = os.environ.get("BACKEND_URL", "http://localhost:8081/health")
    timeout = int(os.environ.get("WATCH_BACKEND_TIMEOUT", "60"))

    def _run():
        import urllib.request
        seen = False
        last_ok = time.time()
        while True:
            ok = False
            try:
                urllib.request.urlopen(backend, timeout=3)
                ok = True
            except urllib.error.HTTPError:
                ok = True  # 有 HTTP 响应即视为存活（即使 4xx/5xx）
            except Exception:
                ok = False  # 连接失败/超时 = 后端失联
            if ok:
                seen = True
                last_ok = time.time()
            elif seen and time.time() - last_ok > timeout:
                print(f"[watchdog] 后端失联超过 {timeout}s，自动退出以释放资源", flush=True)
                os._exit(0)
            time.sleep(10)

    threading.Thread(target=_run, daemon=True, name="backend-watchdog").start()


_start_backend_watchdog()


if __name__ == "__main__":
    # 单进程即可（多 worker 并行收益有限——GPU 吞吐是瓶颈，见 README；
    # 且每个 worker 常驻 ~3.6GB 显存，8GB 卡 1 个最稳妥）。
    # 显存充足且想压调用开销时可调 ASR_WORKERS=2~3。
    uvicorn.run(
        "app:app",
        host="0.0.0.0",
        port=int(os.environ.get("ASR_PORT", "8001")),
        workers=int(os.environ.get("ASR_WORKERS", "1")),
        app_dir=os.path.dirname(os.path.abspath(__file__)),
    )
