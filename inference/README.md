# Video Agent — 本地推理服务

- Embedding：BGE-M3（默认端口 8000）
- ASR：Qwen3-ASR 或讯飞在线引擎（默认端口 8001）
- OCR：RapidOCR PP-OCRv4（默认端口 8002）
- Reranker：bge-reranker-v2-m3 Cross-Encoder（默认端口 8003）

## 安装（Python 3.10+）

```bash
python -m pip install -r requirements.txt
```

## 启动

```bash
# ASR（首次启动会自动从 HF 下载模型，建议设置 HF_ENDPOINT=https://hf-mirror.com）
set HF_ENDPOINT=https://hf-mirror.com
python asr/app.py            # http://localhost:8001

# OCR（模型随包内置，无需额外下载）
python ocr/app.py            # http://localhost:8002

# Embedding 与 Reranker（模型路径可用环境变量覆盖）
python embedding/app.py      # http://localhost:8000
python reranker/app.py       # http://localhost:8003
```

后端通过 `EMBEDDING_BASE_URL` / `ASR_BASE_URL` / `OCR_BASE_URL` / `RERANKER_BASE_URL` 环境变量接入。

## 接口

| 服务 | 路径 | 说明 |
|:---|:---|:---|
| ASR | POST /transcribe | multipart file → `{segments:[{start,end,text}]}`（秒） |
| OCR | POST /ocr | multipart file → `{lines:["..."]}` |
| Embedding | POST /embeddings | 批量文本 → OpenAI 兼容向量响应 |
| Reranker | POST /rerank | 一个查询 + 最多 10 个候选 → 排序结果 |
| Reranker | POST /rerank/batch | 多个 Planner Task 合并为一次 Cross-Encoder 推理请求 |
| 全部 | GET /health | 健康检查 |
