"""科大讯飞极速录音转写客户端（HTTP 异步任务式，非实时流）。

文档: https://www.xfyun.cn/doc/asr/speedTranscription/API.html
流程: 上传音频(≤30M 小文件接口) → 创建转写任务 → 轮询查询 → 解析结果(句子级时间戳)
鉴权: HMAC-SHA256 签名
  signature_origin = "host: {host}\\ndate: {date}\\nPOST {path} HTTP/1.1\\ndigest: {digest}"
  signature = base64(hmac-sha256(signature_origin, APISecret))
  authorization = api_key="..", algorithm="hmac-sha256", headers="host date request-line digest", signature=".."
音频: 16k 16bit 单声道（本服务上传 PCM raw，encoding=raw）
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.request
import uuid
import wave
from email.utils import formatdate

UPLOAD_URL = "https://upload-ost-api.xfyun.cn/file/upload"
CREATE_URL = "https://ost-api.xfyun.cn/v2/ost/pro_create"
QUERY_URL = "https://ost-api.xfyun.cn/v2/ost/query"
UPLOAD_HOST = "upload-ost-api.xfyun.cn"
OST_HOST = "ost-api.xfyun.cn"

# 浏览器 UA：讯飞 WAF 对「海外 IP + 无浏览器标识的 Python 请求」会选择性连接重置
# （本机国内 IP 不触发，服务器海外 IP 必撞；实测加 UA 后请求穿透）
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")


def _build_auth_headers(host: str, path: str, body: bytes, api_key: str, api_secret: str) -> dict:
    date = formatdate(time.time(), usegmt=True)
    digest = "SHA-256=" + base64.b64encode(hashlib.sha256(body).digest()).decode()
    origin = f"host: {host}\ndate: {date}\nPOST {path} HTTP/1.1\ndigest: {digest}"
    sig = base64.b64encode(
        hmac.new(api_secret.encode("utf-8"), origin.encode("utf-8"), hashlib.sha256).digest()).decode()
    auth = (f'api_key="{api_key}", algorithm="hmac-sha256", '
            f'headers="host date request-line digest", signature="{sig}"')
    return {"Host": host, "Date": date, "Digest": digest, "Authorization": auth}


class _HttpStatusError(RuntimeError):
    """HTTP 业务错误（4xx/5xx）：不重试。"""


def _http_json(url: str, headers: dict, body: bytes, timeout: float,
               retries: int = 0, backoff: float = 3.0) -> dict:
    headers.setdefault("User-Agent", USER_AGENT)
    last_err: Exception | None = None
    for attempt in range(retries + 1):
        try:
            return _curl_post(url, headers, body, timeout)
        except _HttpStatusError:
            raise
        except Exception as e:  # 网络超时/连接失败 → 重试
            last_err = e
            if attempt < retries:
                time.sleep(backoff * (attempt + 1))
    raise RuntimeError(f"请求失败（重试 {retries} 次）: {last_err}") from last_err


def _curl_post(url: str, headers: dict, body: bytes, timeout: float) -> dict:
    """用 curl 子进程发 POST（绕开 Python ssl 的 TLS 指纹——讯飞 WAF 会选择性重置
    海外 IP 的 Python 请求，curl 的 TLS 指纹常见放行；body 原样字节传输，Digest 签名不受影响）。"""
    import subprocess
    import tempfile
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(body)
            tmp = f.name
        cmd = ["curl", "-s", "-X", "POST",
               "--max-time", str(int(timeout)),
               "-w", "\n%{http_code}",
               "--data-binary", "@" + tmp,
               url]
        for k, v in headers.items():
            cmd += ["-H", f"{k}: {v}"]
        proc = subprocess.run(cmd, capture_output=True, timeout=timeout + 30)
        if proc.returncode != 0:
            raise ConnectionError(
                f"curl 失败 rc={proc.returncode}: {proc.stderr.decode('utf-8', 'ignore')[:200]}")
        out = proc.stdout.decode("utf-8", "ignore")
        parts = out.rsplit("\n", 1)
        code_line = parts[1].strip() if len(parts) == 2 else ""
        http_code = int(code_line) if code_line.isdigit() else 0
        payload = parts[0] if code_line.isdigit() else out
        if http_code >= 400:
            raise _HttpStatusError(f"HTTP {http_code}: {payload[:300]}")
        return json.loads(payload)
    finally:
        if tmp:
            try:
                os.unlink(tmp)
            except OSError:
                pass


def _post_json(url: str, host: str, path: str, body_obj: dict, api_key: str, api_secret: str) -> dict:
    body = json.dumps(body_obj).encode("utf-8")
    headers = _build_auth_headers(host, path, body, api_key, api_secret)
    headers["Content-Type"] = "application/json"
    return _http_json(url, headers, body, timeout=60, retries=1)


def _upload_small_file(wav_path: str, appid: str, api_key: str, api_secret: str) -> str:
    """上传音频（小文件），返回 audio_url。"""
    with wave.open(wav_path, "rb") as w:
        pcm = w.readframes(w.getnframes())  # 16k 16bit 单声道 PCM

    boundary = uuid.uuid4().hex
    request_id = str(int(time.time() * 1000))
    body = b"".join([
        f'--{boundary}\r\nContent-Disposition: form-data; name="request_id"\r\n\r\n{request_id}\r\n'.encode(),
        f'--{boundary}\r\nContent-Disposition: form-data; name="app_id"\r\n\r\n{appid}\r\n'.encode(),
        (f'--{boundary}\r\nContent-Disposition: form-data; name="data"; filename="audio.pcm"\r\n'
         f'Content-Type: application/octet-stream\r\n\r\n').encode() + pcm + b'\r\n',
        f'--{boundary}--\r\n'.encode(),
    ])
    headers = _build_auth_headers(UPLOAD_HOST, "/file/upload", body, api_key, api_secret)
    headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    # 上传大文件易超时：长超时 + 重试
    result = _http_json(UPLOAD_URL, headers, body, timeout=180, retries=2)
    if result.get("code") != 0:
        raise RuntimeError(f"音频上传失败: {result.get('code')} {result.get('message')}")
    return result["data"]["url"]


def _upload_mp3_file(mp3_path: str, appid: str, api_key: str, api_secret: str) -> str:
    """上传 MP3（压缩格式，体积小、上传快，避免大 PCM 上传超过签名时效）。"""
    with open(mp3_path, "rb") as f:
        data_bytes = f.read()
    boundary = uuid.uuid4().hex
    request_id = str(int(time.time() * 1000))
    body = b"".join([
        f'--{boundary}\r\nContent-Disposition: form-data; name="request_id"\r\n\r\n{request_id}\r\n'.encode(),
        f'--{boundary}\r\nContent-Disposition: form-data; name="app_id"\r\n\r\n{appid}\r\n'.encode(),
        (f'--{boundary}\r\nContent-Disposition: form-data; name="data"; filename="audio.mp3"\r\n'
         f'Content-Type: audio/mpeg\r\n\r\n').encode() + data_bytes + b'\r\n',
        f'--{boundary}--\r\n'.encode(),
    ])
    headers = _build_auth_headers(UPLOAD_HOST, "/file/upload", body, api_key, api_secret)
    headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    result = _http_json(UPLOAD_URL, headers, body, timeout=180, retries=2)
    if result.get("code") != 0:
        raise RuntimeError(f"音频上传失败: {result.get('code')} {result.get('message')}")
    return result["data"]["url"]


def _create_task(audio_url: str, appid: str, api_key: str, api_secret: str,
                 audio_format: str = "audio/L16;rate=16000", encoding: str | None = "raw") -> str:
    data = {"audio_url": audio_url, "audio_src": "http", "format": audio_format}
    if encoding:
        data["encoding"] = encoding
    body = {
        "common": {"app_id": appid},
        "business": {
            "request_id": str(uuid.uuid4()),
            "language": "zh_cn",
            "domain": "pro_ost_ed",
            "accent": "mandarin",
        },
        "data": data,
    }
    result = _post_json(CREATE_URL, OST_HOST, "/v2/ost/pro_create", body, api_key, api_secret)
    if result.get("code") != 0:
        raise RuntimeError(f"创建任务失败: {result.get('code')} {result.get('message')}")
    return result["data"]["task_id"]


def _query_task(task_id: str, appid: str, api_key: str, api_secret: str) -> dict:
    body = {"common": {"app_id": appid}, "business": {"task_id": task_id}}
    return _post_json(QUERY_URL, OST_HOST, "/v2/ost/query", body, api_key, api_secret)


def _parse_result(result: dict):
    """解析查询结果的 lattice → 句子级时间戳片段。"""
    segments = []
    for lat in result.get("lattice") or []:
        jb = lat.get("json_1best") or {}
        st = jb.get("st") or {}
        try:
            bg = int(st.get("bg", 0) or 0)
            ed = int(st.get("ed", 0) or 0)
        except (TypeError, ValueError):
            bg, ed = 0, 0
        text_parts = []
        for rt in st.get("rt") or []:
            for ws in rt.get("ws") or []:
                for cw in ws.get("cw") or []:
                    w = (cw.get("w") or "").strip()
                    if w:
                        text_parts.append(w)
        text = "".join(text_parts).strip()
        if text:
            segments.append({"start_ms": bg, "end_ms": ed, "text": text})
    return segments


async def transcribe_file(wav_path: str, appid: str, api_key: str, api_secret: str,
                          poll_interval: float = 5.0, timeout: float = 300.0):
    """转写 wav（16k 16bit 单声道），返回 [{start_ms, end_ms, text}]（句子级时间戳）。
    timeout=300：3 分钟切片正常 1-2 分钟完成，轮询超时留 5 分钟上限，避免跨境链路挂起过长。"""
    return await asyncio.to_thread(_transcribe_sync, wav_path, appid, api_key, api_secret,
                                   poll_interval, timeout)


def _transcribe_sync(audio_path: str, appid: str, api_key: str, api_secret: str,
                     poll_interval: float, timeout: float):
    is_mp3 = audio_path.lower().endswith(".mp3")
    t0 = time.time()
    if is_mp3:
        url = _upload_mp3_file(audio_path, appid, api_key, api_secret)
        print(f"[xfyun] upload(mp3) {time.time() - t0:.1f}s")
        t0 = time.time()
        task_id = _create_task(url, appid, api_key, api_secret,
                               audio_format="audio/mpeg", encoding=None)
    else:
        url = _upload_small_file(audio_path, appid, api_key, api_secret)
        print(f"[xfyun] upload {time.time() - t0:.1f}s")
        t0 = time.time()
        task_id = _create_task(url, appid, api_key, api_secret)
    print(f"[xfyun] create {time.time() - t0:.1f}s task={task_id}")

    deadline = time.time() + timeout
    t0 = time.time()
    while time.time() < deadline:
        time.sleep(poll_interval)
        try:
            resp = _query_task(task_id, appid, api_key, api_secret)
        except Exception as e:
            # 轮询瞬时断连（WAF/网络）→ 打印后继续等待，不放弃任务（任务在讯飞侧仍在处理）
            print(f"[xfyun] 轮询瞬时失败，继续等待 task={task_id}: {str(e)[:120]}")
            continue
        if resp.get("code") != 0:
            raise RuntimeError(f"查询任务失败: {resp.get('code')} {resp.get('message')}")
        status = str(resp.get("data", {}).get("task_status", ""))
        if status in ("3", "4"):  # 处理完成 / 回调完成
            result = resp.get("data", {}).get("result") or {}
            segments = _parse_result(result)
            print(f"[xfyun] done poll={time.time() - t0:.1f}s segs={len(segments)}")
            if not segments:
                raise RuntimeError("极速转写返回空结果")
            return segments
        # 1 待处理 / 2 处理中 → 继续轮询

    raise RuntimeError(f"极速转写超时（{int(timeout)}s）")
