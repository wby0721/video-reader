"""Video Agent OCR 推理服务：RapidOCR（PP-OCRv4 onnxruntime）。

方案备选实现：无 paddle 框架依赖、部署更轻，中文识别效果接近原生 PaddleOCR；
模型随 rapidocr_onnxruntime 包内置（PP-OCRv4 检测+识别），无需额外下载。
独立部署，后端通过 HTTP 调用（POST /ocr）。
"""
import os
import sys
import tempfile
import threading
import time

import uvicorn
from fastapi import FastAPI, File, UploadFile
from pydantic import BaseModel
from typing import List

from rapidocr_onnxruntime import RapidOCR

app = FastAPI(title="Video Agent OCR (RapidOCR PP-OCRv4)")


def _ensure_cuda_dll_path():
    """onnxruntime-gpu 需要 CUDA 13 运行时 dll（cublasLt64_13.dll / cudnn 9）；
    本机 torch 自带一套，把 torch/lib 加入 DLL 搜索路径，否则 CUDA EP 创建失败静默回退 CPU。"""
    try:
        base = os.path.dirname(sys.executable)  # ...\python312
        torch_lib = os.path.join(base, "Lib", "site-packages", "torch", "lib")
        if os.path.isdir(torch_lib):
            os.add_dll_directory(torch_lib)
    except Exception as e:
        print(f"[OCR] 加载 torch CUDA dll 路径失败: {e}", flush=True)


_ensure_cuda_dll_path()

_engine = None
_engine_lock = threading.Lock()


def get_engine() -> RapidOCR:
    global _engine
    if _engine is None:
        with _engine_lock:  # 并发首请求安全
            if _engine is None:
                # 优先 GPU（需安装 onnxruntime-gpu + CUDA dll 可加载）；不可用时自动回退 CPU
                use_cuda = False
                try:
                    import onnxruntime as ort
                    use_cuda = "CUDAExecutionProvider" in ort.get_available_providers()
                except Exception:
                    use_cuda = False
                _engine = RapidOCR(det_use_cuda=use_cuda, cls_use_cuda=use_cuda, rec_use_cuda=use_cuda)
                print(f"[OCR] providers: CUDA={use_cuda}", flush=True)
    return _engine


class OcrResponse(BaseModel):
    lines: List[str]


@app.post("/ocr", response_model=OcrResponse)
async def ocr(file: UploadFile = File(...)):
    data = await file.read()
    suffix = os.path.splitext(file.filename or "frame.jpg")[1] or ".jpg"
    path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(data)
            path = tmp.name
        result, _elapse = get_engine()(path)
        lines = []
        if result:
            for _box, text, _score in result:
                if text and text.strip():
                    lines.append(text.strip())
        return OcrResponse(lines=lines)
    finally:
        if path:
            try:
                os.unlink(path)
            except OSError:
                pass


@app.get("/health")
def health():
    return {"status": "UP", "engine": "rapidocr-ppocrv4"}


def _start_backend_watchdog():
    """后端失联自动退出：释放 GPU/CPU 资源（同 asr 服务）。"""
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
    # 默认 1 worker：GPU 下 ~0.5-1s/帧已足够快（并行在 ASR 后面被掩盖），
    # 且 onnxruntime CUDA 每个进程约 0.7GB 上下文，8GB 卡上要给 ASR 让显存。
    # 纯 CPU 环境可调 OCR_WORKERS=3~4。
    uvicorn.run(
        "app:app",
        host="0.0.0.0",
        port=int(os.environ.get("OCR_PORT", "8002")),
        workers=int(os.environ.get("OCR_WORKERS", "1")),
        app_dir=os.path.dirname(os.path.abspath(__file__)),
    )
