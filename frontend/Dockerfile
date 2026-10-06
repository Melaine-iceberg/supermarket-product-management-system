# =====================================================================
# SMMS 前端镜像（NFR-CMP-05 / AC-D04）
# 多阶段构建：pnpm 构建 Vue 产物，Nginx 托管静态资源并反代 /api
# 构建：docker build -t smms/frontend ./frontend
# =====================================================================
FROM node:24-alpine AS build
WORKDIR /app

RUN npm install -g pnpm@11

# 先复制依赖清单安装，源码变更不影响依赖层缓存
# --ignore-scripts：pnpm 11 默认拦截依赖构建脚本（如 @parcel/watcher 仅监听用途），
# esbuild/rolldown 平台二进制经 optionalDependencies 直接安装，不影响 vite 构建
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --ignore-scripts

COPY . .
RUN pnpm run build

# ---------------------------------------------------------------------
FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
# 须用 127.0.0.1：busybox wget 将 localhost 解析为 ::1，而 nginx 仅监听 IPv4
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD wget -qO /dev/null http://127.0.0.1/ || exit 1
