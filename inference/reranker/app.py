"""Video Reader Cross-Encoder 精排服务：BAAI/bge-reranker-v2-m3。"""
import os
import threading
import time
import asyncio
from contextlib import asynccontextmanager
from typing import List

import numpy as np
import uvicorn
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

MODEL_PATH = os.environ.get(
    "RERANKER_MODEL_PATH", r"E:\agent_projct\.tools\models\bge-reranker-v2-m3"
)
DEVICE = os.environ.get("RERANKER_DEVICE", "cpu")

@asynccontextmanager
async def lifespan(app):
    global _ready
    _ready = False
    await asyncio.to_thread(_warmup)
    _ready = True
    try:
        yield
    finally:
        _ready = False


app = FastAPI(title="Video Reader Reranker (BGE v2 M3)", lifespan=lifespan)

_model = None
_ready = False
_load_lock = threading.Lock()
_predict_lock = threading.Lock()


def _load():
    global _model
    if _model is None:
        with _load_lock:
            if _model is None:
                from sentence_transformers import CrossEncoder
                from torch.nn import Sigmoid
                _model = CrossEncoder(MODEL_PATH, device=DEVICE or None, activation_fn=Sigmoid())


def _warmup():
    _load()
    with _predict_lock:
        _model.predict([["启动预热", "视频检索证据"]], batch_size=1)


class DocumentInput(BaseModel):
    id: str
    text: str


class RerankRequest(BaseModel):
    query: str
    documents: List[DocumentInput]
    top_n: int = Field(default=5, alias="topN", ge=1)

    model_config = {"populate_by_name": True}


class RankedDocument(BaseModel):
    id: str
    score: float


class RerankResponse(BaseModel):
    results: List[RankedDocument]
    model: str = "bge-reranker-v2-m3"


class BatchRerankRequest(BaseModel):
    requests: List[RerankRequest]


class BatchRerankResponse(BaseModel):
    results: List[RerankResponse]
    model: str = "bge-reranker-v2-m3"


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if not req.documents:
        return RerankResponse(results=[])
    _load()
    pairs = [[req.query, document.text] for document in req.documents]
    with _predict_lock:
        scores = np.asarray(_model.predict(pairs, batch_size=8)).reshape(-1)
    ranked = sorted(
        (
            RankedDocument(id=document.id, score=float(score))
            for document, score in zip(req.documents, scores)
        ),
        key=lambda item: item.score,
        reverse=True,
    )
    return RerankResponse(results=ranked[: min(req.top_n, len(ranked))])


@app.post("/rerank/batch", response_model=BatchRerankResponse)
def rerank_batch(req: BatchRerankRequest):
    if not req.requests:
        return BatchRerankResponse(results=[])
    _load()
    pairs = []
    spans = []
    for item in req.requests:
        start = len(pairs)
        pairs.extend([[item.query, document.text] for document in item.documents])
        spans.append((start, len(pairs)))
    if pairs:
        with _predict_lock:
            scores = np.asarray(_model.predict(pairs, batch_size=8)).reshape(-1)
    else:
        scores = np.asarray([])

    responses = []
    for item, (start, end) in zip(req.requests, spans):
        ranked = sorted(
            (
                RankedDocument(id=document.id, score=float(score))
                for document, score in zip(item.documents, scores[start:end])
            ),
            key=lambda result: result.score,
            reverse=True,
        )
        responses.append(RerankResponse(
            results=ranked[: min(item.top_n, len(ranked))]
        ))
    return BatchRerankResponse(results=responses)


@app.get("/health")
def health():
    return JSONResponse(status_code=200 if _ready else 503, content={
        "status": "UP" if _ready else "STARTING",
        "ready": _ready,
        "model": "bge-reranker-v2-m3",
        "device": DEVICE,
        "model_path": MODEL_PATH,
        "score_type": "RERANKER_SIGMOID",
    })


def _start_backend_watchdog():
    """后端失联后自动退出，避免推理进程长期占用资源。"""
    if os.environ.get("WATCH_BACKEND", "1") != "1":
        return
    backend = os.environ.get("BACKEND_URL", "http://localhost:8081/health")
    timeout = int(os.environ.get("WATCH_BACKEND_TIMEOUT", "60"))

    def _run():
        import urllib.error
        import urllib.request
        seen = False
        last_ok = time.time()
        while True:
            try:
                urllib.request.urlopen(backend, timeout=3)
                ok = True
            except urllib.error.HTTPError:
                ok = True
            except Exception:
                ok = False
            if ok:
                seen = True
                last_ok = time.time()
            elif seen and time.time() - last_ok > timeout:
                print(f"[watchdog] 后端失联超过 {timeout}s，自动退出", flush=True)
                os._exit(0)
            time.sleep(10)

    threading.Thread(target=_run, daemon=True, name="backend-watchdog").start()


_start_backend_watchdog()


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("RERANKER_PORT", "8003")))
