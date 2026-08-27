# Video Agent — 本地推理服务
# ASR：faster-whisper（独立 HTTP 服务，默认端口 8001）
# OCR：RapidOCR（PP-OCRv4 onnxruntime，无 paddle 框架依赖，默认端口 8002）

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
```

后端通过 `ASR_BASE_URL` / `OCR_BASE_URL` 环境变量接入。

## 接口

| 服务 | 路径 | 说明 |
|:---|:---|:---|
| ASR | POST /transcribe | multipart file → `{segments:[{start,end,text}]}`（秒） |
| OCR | POST /ocr | multipart file → `{lines:["..."]}` |
| 两者 | GET /health | 健康检查 |
