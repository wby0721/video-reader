#!/usr/bin/env bash
# ============================================================
# Video Reader 服务器一键部署脚本（Ubuntu 22.04 / Debian）
# 用法：
#   git clone https://github.com/wby0721/video-reader.git
#   cd video-reader
#   cp .env.example .env && vi .env        # 填好所有必填项
#   bash scripts/setup-server.sh
# 之后按 docs/DEPLOY.md 完成 HTTPS（certbot）与域名验证。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."   # 切到仓库根目录
echo "==> 仓库根目录: $(pwd)"

# ---------- 1. 安装 Docker + Compose 插件（若缺失） ----------
if ! command -v docker >/dev/null 2>&1; then
    echo "==> 未检测到 Docker，开始安装..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker || true
fi
docker --version
docker compose version

# ---------- 2. 检查 .env ----------
if [ ! -f .env ]; then
    echo "!! 未找到 .env，已从 .env.example 复制。请先编辑 .env 填好必填项再重跑。"
    cp .env.example .env
    echo "    必填：MYSQL_ROOT_PASSWORD / MINIO_ROOT_USER / MINIO_ROOT_PASSWORD"
    echo "          JWT_SECRET / LLM_MASTER_KEY / XF_APPID / XF_APIKEY / XF_APISECRET"
    echo "    （LLM_API_KEY 可留空——用户自带 Key 方案）"
    exit 1
fi

# 快速校验必填项（环境变量缺一即提示）
for key in MYSQL_ROOT_PASSWORD MINIO_ROOT_USER MINIO_ROOT_PASSWORD JWT_SECRET LLM_MASTER_KEY; do
    if grep -qE "^$key=" .env; then
        :
    else
        echo "!! .env 缺少 $key，请补上后重跑"
        exit 1
    fi
done

# ---------- 3. 构建镜像 ----------
echo "==> 构建镜像（首次较久：后端编译 + BGE-M3 模型下载 ~2GB）..."
docker compose -f docker-compose.prod.yml build

# ---------- 4. 生成自签占位证书（让 nginx 能先启动，随后 certbot 替换） ----------
mkdir -p data/certbot/conf data/certbot/www
if [ ! -f data/certbot/conf/live/jasonsweb.xyz/fullchain.pem ]; then
    echo "==> 生成自签占位证书（certbot 正式证书签发后自动替换）..."
    mkdir -p data/certbot/conf/live/jasonsweb.xyz
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
        -keyout data/certbot/conf/live/jasonsweb.xyz/privkey.pem \
        -out data/certbot/conf/live/jasonsweb.xyz/fullchain.pem \
        -subj "/CN=jasonsweb.xyz"
fi

# ---------- 5. 启动全部服务 ----------
echo "==> 启动服务..."
docker compose -f docker-compose.prod.yml up -d

# ---------- 6. 状态与下一步 ----------
echo ""
echo "============================================================"
echo " 部署完成！接下来："
echo " 1) 验证服务：docker compose -f docker-compose.prod.yml ps"
echo " 2) 确认域名已解析到本机（阿里云云解析 A 记录 → 本机公网 IP）"
echo " 3) 签发正式 HTTPS 证书："
echo "      apt install -y certbot"
echo "      certbot certonly --webroot -w data/certbot/www \\"
echo "          -d jasonsweb.xyz -d www.jasonsweb.xyz"
echo "      docker compose -f docker-compose.prod.yml exec nginx nginx -s reload"
echo " 4) 定时续期（certbot renew 每 3 个月）：crontab -e 添加"
echo "      0 3 * * * certbot renew --webroot -w $(pwd)/data/certbot/www --quiet && docker compose -f $(pwd)/docker-compose.prod.yml exec nginx nginx -s reload"
echo " 5) 访问 https://jasonsweb.xyz"
echo "============================================================"
