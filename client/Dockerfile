# ============================================================
# Video Reader 前端 + Nginx 镜像（两阶段）
# 阶段1：Node 构建 Vue 源码 → dist 静态文件
# 阶段2：Nginx 托管 dist + 反代 /api → 后端（SSE 支持）
# 构建上下文为仓库根目录（需要 nginx/nginx.conf）
# ============================================================

# ---- 构建阶段 ----
FROM node:20-alpine AS build
WORKDIR /app

COPY client/package.json client/package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com \
    || npm install --registry=https://registry.npmmirror.com

COPY client/ ./
RUN npm run build

# ---- 运行阶段 ----
FROM nginx:1.27-alpine
COPY nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80 443
