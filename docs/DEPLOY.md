# Video Reader 服务器部署指南（Docker 全栈）

面向单台 Linux 服务器（推荐 Ubuntu 22.04，4 核 8G）的完整部署文档。
**本指南不改动任何本地文件**——所有部署配置均为仓库内新增文件。

## 0. 部署架构

```
用户浏览器 ──HTTPS(443)──► Nginx 容器（前端静态 + /api 反代）
                            └──► backend 容器(8081) ──► MySQL / Redis / Kafka / MinIO / Qdrant（内网）
                            └──► embedding(8000) / asr(8001) / ocr(8002) 推理容器（CPU）
ASR 转写：后端 → asr 容器 → 科大讯飞 API（服务器不部署本地 ASR 模型）
LLM：用户自带 Key（服务器可不配 LLM_API_KEY）
```

- 仅 Nginx 暴露 80/443；其余容器只在内网互通（`vr-net`），不映射公网端口
- 数据持久化：全部挂命名卷（`mysql_data` / `minio_data` 等），容器重建不丢数据
- BGE-M3 模型在镜像构建期下载并固化（~2GB，走 hf-mirror）

## 1. 服务器选型

- 地域：**海外（香港/首尔等）免 ICP 备案**；大陆需备案（1-3 周）
- 配置：4 核 8G 起步（Embedding 常驻 ~2GB + 后端 JVM + 5 个中间件）
- 带宽：5Mbps+（视频上传吃带宽）；系统 Ubuntu 22.04 LTS
- 防火墙/安全组：**只放行 22 / 80 / 443**

## 2. 域名解析（DNS）

域名在阿里云 → 云解析 DNS 添加两条 A 记录（指向服务器公网 IP）：

| 主机记录 | 类型 | 记录值 |
|---|---|---|
| @ | A | 服务器公网 IP |
| www | A | 服务器公网 IP |

生效验证：`nslookup 你的域名 8.8.8.8` 返回服务器 IP 即可。

## 3. 一键部署

```bash
# 1. 登录服务器
ssh root@<服务器IP>

# 2. 装 Docker（脚本自动检测；或手动）并克隆仓库
apt update && apt install -y git curl
git clone https://github.com/wby0721/video-reader.git
cd video-reader

# 3. 配置 .env（关键！）
cp .env.example .env
vi .env
#    必填：MYSQL_ROOT_PASSWORD、MINIO_ROOT_USER、MINIO_ROOT_PASSWORD
#          JWT_SECRET（openssl rand -base64 32）、LLM_MASTER_KEY（同上生成）
#          XF_APPID / XF_APIKEY / XF_APISECRET（讯飞）
#    可选：LLM_API_KEY（留空 = 用户自带 Key 方案）、LLM_MODEL、RATE_LIMIT_*

# 4. 准备 BGE-M3 模型（推荐：从本机 scp 上传，最稳；或让脚本自动下载）
#    本机（Windows PowerShell）执行，把本地已有的模型传上去：
#      scp -r "E:\agent_projct\.tools\models\bge-m3" root@<服务器IP>:/root/video-reader/data/models/
#    服务器上确认：
#      ls data/models/bge-m3     # 应看到 pytorch_model.bin / config.json 等
#    若本机没有该模型，setup-server.sh 会用 huggingface.co / hf-mirror.com 自动尝试下载
#    （海外服务器连 HF 镜像可能不稳定，上传本机模型是最稳路径）

# 5. 一键构建 + 启动（含模型检查）
bash scripts/setup-server.sh
```

首次构建 5-15 分钟（后端编译 + Python 依赖）。完成后：
```bash
docker compose -f docker-compose.prod.yml ps   # 8 个容器应全部 healthy/running
```

## 4. HTTPS（Let's Encrypt，免费自动续期）

```bash
apt install -y certbot
# 确认 80 端口可访问后再执行（nginx 容器已监听 80 并开放 ACME 路径）
certbot certonly --webroot -w data/certbot/www \
    -d jasonsweb.xyz -d www.jasonsweb.xyz
docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
```

自动续期（每 3 个月，加入 crontab）：
```bash
crontab -e
# 添加一行（路径按实际）：
0 3 * * * certbot renew --webroot -w /root/video-reader/data/certbot/www --quiet && docker compose -f /root/video-reader/docker-compose.prod.yml exec nginx nginx -s reload
```

## 5. 验证

```bash
curl -I https://jasonsweb.xyz                       # 200 + 前端页面
curl https://jasonsweb.xyz/api/health               # 后端健康检查
```
浏览器打开 https://jasonsweb.xyz → 注册账号 → 个人设置提交自己的 DeepSeek Key 与讯飞凭据 → 上传视频 → 分析 → 追问。

## 6. 日常运维

```bash
# 查看状态
docker compose -f docker-compose.prod.yml ps
# 看日志
docker compose -f docker-compose.prod.yml logs -f backend
# 更新部署（拉新代码后）
git pull && docker compose -f docker-compose.prod.yml build && docker compose -f docker-compose.prod.yml up -d
```

### 备份（建议 cron 每日）

```bash
# MySQL 备份（-p 后接 .env 里的 MYSQL_ROOT_PASSWORD）
docker exec vr-mysql mysqldump -uroot -p'<MYSQL_ROOT_PASSWORD>' video_agent \
    | gzip > /backup/video_agent_$(date +%F).sql.gz
# MinIO 视频数据（用户资产）
tar czf /backup/minio_$(date +%F).tar.gz /var/lib/docker/volumes/video-reader-prod_minio_data/
```

### 安全基线

- 只开 22/80/443；SSH 建议改密钥登录、禁用 root 密码登录
- `.env` 权限 `chmod 600 .env`，不进 git（已 gitignore）
- `JWT_SECRET` / `LLM_MASTER_KEY` 必须强随机；数据库/MinIO 口令不用默认值
- 演示期建议观察用量：`docker compose logs backend | grep -i telemetry`

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| 后端起不来，Flyway 报错 | 检查 .env 里 MYSQL_ROOT_PASSWORD 与 compose 一致；看 `docker compose logs backend` |
| 上传大视频慢/超时 | 带宽瓶颈；分片 5MB 已适配，检查服务器带宽与 MinIO 健康 |
| SSE 进度不实时 | 确认 nginx.conf `proxy_buffering off` 生效（reload 过） |
| 推理容器反复重启 | 看门狗探测 backend 失败 → 确认 backend healthy 后再起；或 `BACKEND_URL` 配置正确 |
| BGE-M3 下载慢/失败 | 构建期走 hf-mirror；网络差可预下载后 COPY 进镜像 |

## 8. 本地开发不受影响

部署产物全部是**新增文件**（Dockerfile / docker-compose.prod.yml / nginx.conf / setup 脚本 / 本文档），
本地仍用 `scripts/start-all.ps1` 启动，二者完全独立、互不影响。
