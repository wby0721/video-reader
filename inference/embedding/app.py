"""Video Agent Embedding 推理服务：BGE-M3（本地加载，OpenAI 兼容 /embeddings）。

输出 1024 维稠密向量；配合后端 EmbeddingClient（OpenAI 兼容协议）使用。
模型目录（pytorch_model.bin + tokenizer + sentencepiece）由 EMBEDDING_MODEL_PATH 指定。
"""
import os
import threading
import time

import numpy as np
import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Union

MODEL_PATH = os.environ.get("EMBEDDING_MODEL_PATH", r"E:\agent_projct\.tools\models\bge-m3")
# 默认 CPU：给 ASR/OCR 让显存（8GB 卡）；短文本 CPU 嵌入 ~0.3-0.8s 可接受。
# 显存充足（12GB+）可设 EMBEDDING_DEVICE=cuda 提速。
DEVICE = os.environ.get("EMBEDDING_DEVICE", "cpu")

app = FastAPI(title="Video Agent Embedding (BGE-M3)")

_model = None
_load_lock = threading.Lock()


def _load():
    global _model
    if _model is None:
        with _load_lock:  # 并发首请求安全（否则多线程同时加载会竞态损坏进程状态）
            if _model is None:
                from sentence_transformers import SentenceTransformer
                _model = SentenceTransformer(MODEL_PATH, device=DEVICE or None)


class EmbeddingRequest(BaseModel):
    model: str = "bge-m3"
    input: Union[str, List[str]]


class EmbeddingData(BaseModel):
    object: str = "embedding"
    index: int
    embedding: List[float]


class EmbeddingUsage(BaseModel):
    prompt_tokens: int = 0
    total_tokens: int = 0


class EmbeddingResponse(BaseModel):
    object: str = "list"
    data: List[EmbeddingData]
    model: str
    usage: EmbeddingUsage


@app.post("/embeddings", response_model=EmbeddingResponse)
def embeddings(req: EmbeddingRequest):
    _load()
    texts = req.input if isinstance(req.input, list) else [req.input]
    vecs: np.ndarray = _model.encode(texts, normalize_embeddings=True, batch_size=8)
    data = [
        EmbeddingData(index=i, embedding=[float(x) for x in v.tolist()])
        for i, v in enumerate(vecs)
    ]
    return EmbeddingResponse(data=data, model=req.model, usage=EmbeddingUsage())


@app.get("/health")
def health():
    return {"status": "UP", "model": "BGE-M3", "device": DEVICE, "model_path": MODEL_PATH}


def _start_backend_watchdog():
    """后端失联自动退出：释放资源（同 asr 服务）。"""
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
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("EMBEDDING_PORT", "8000")))
