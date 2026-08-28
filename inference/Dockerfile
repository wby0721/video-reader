# ============================================================
# Video Reader 推理服务镜像（CPU，无本地 ASR 模型）
# 三个服务共用此镜像，compose 中通过 working_dir + command 区分：
#   embedding : /app/embedding :8000（BGE-M3，CPU）
#   asr       : /app/asr       :8001（仅讯飞在线引擎，不加载本地模型）
#   ocr       : /app/ocr       :8002（RapidOCR，CPU）
#
# 与本地差异（本地文件零改动）：
#   - torch 装 CPU 版（~200MB，非 CUDA 2GB）
#   - 不安装 qwen-asr 包、不下载 Qwen 模型（ASR 走讯飞 API）
#   - BGE-M3 模型在构建期下载并固化进镜像（国内走 HF 镜像）
# ============================================================

FROM python:3.11-slim

WORKDIR /app

# 系统依赖：ffmpeg（ASR 音频切片）+ OCR 运行库
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# 依赖：CPU 版 torch + FastAPI 全家桶 + 推理库
# （不直接使用 requirements.txt，避免引入 qwen-asr / CUDA torch）
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir \
        fastapi \
        "uvicorn[standard]" \
        python-multipart \
        numpy \
        sentence-transformers \
        rapidocr_onnxruntime

# BGE-M3 模型固化进镜像（构建期一次性下载，国内走 hf-mirror）
ENV HF_ENDPOINT=https://hf-mirror.com
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3').save('/app/models/bge-m3')"

COPY asr ./asr
COPY ocr ./ocr
COPY embedding ./embedding

# 默认进入 embedding；其余服务由 compose 覆盖 working_dir/command
WORKDIR /app/embedding
CMD ["python", "app.py"]
