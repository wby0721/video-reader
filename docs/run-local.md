# 本地演示一键启动：中间件(原生) + 推理服务 + 后端
# 说明：本机无 Docker/WSL 可用时的等效方案（各组件已隔离在项目同级 .tools 目录下，.tools 不参与 Git）。

## 1. 中间件（已在后台运行的跳过）
# MySQL(3307 用户态) / Redis(服务) / Kafka(9092) / MinIO(9000) / Qdrant(6333)
# 详见 docs/local-middleware.md

## 2. 推理服务（需先完成 Python 依赖安装，见 inference/README.md）
powershell -ExecutionPolicy Bypass -File scripts/start-inference.ps1

## 3. 后端（8081，避开本机 CVAT 的 8080）
$env:JAVA_HOME = "<你的 JDK 21+ 路径，例如 C:\Users\<user>\.jdks\openjdk-26.0.2>"
$env:SERVER_PORT = "8081"
$env:MYSQL_URL = "jdbc:mysql://localhost:3307/video_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "root123"
$env:FFMPEG_PATH = "<你的 ffmpeg.exe 路径，本地演示默认为 .tools\ffmpeg\ffmpeg.exe>"
# AI 服务地址默认即 8000/8001/8002，无需额外配置
# LLM（DeepSeek）：key 仅通过环境变量注入，不落任何文件
$env:LLM_API_KEY = "<你的 DeepSeek API Key>"
$env:LLM_MODEL = "deepseek-v4-flash"
# 用户 Key 静态加密主密钥（生产必设；不设则回退派生自 JWT_SECRET）
$env:LLM_MASTER_KEY = "<随机主密钥>"
& "$env:JAVA_HOME\bin\java.exe" -jar server\target\video-reader-server-0.1.0-SNAPSHOT.jar

## 4. 验收
# curl http://localhost:8081/health
# node .tools/upload.js sample-data\1481752539-1-192.mp4 "总结视频的主要内容"
# 证据检索：GET /analysis/evidence-search?mediaId=7&query=计算机网络的功能
