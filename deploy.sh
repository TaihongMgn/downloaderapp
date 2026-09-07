#!/usr/bin/env bash
# dy-dl 部署: 本地 -> 首尔服务器 /opt/dy-dl
set -euo pipefail

HOST="${DYDL_HOST:-root@your-server-ip}"
KEY="${DYDL_KEY:-~/.ssh/id_ed25519}"
PORT="${DYDL_PORT:-22}"
REMOTE_DIR="/opt/dy-dl"

cd "$(dirname "$0")"

echo "==> 上传文件..."
ssh -i "$KEY" -p "$PORT" "$HOST" "mkdir -p $REMOTE_DIR/static $REMOTE_DIR/bin $REMOTE_DIR/tmp"
scp -i "$KEY" -P "$PORT" -q app.py abogus.py "$HOST:$REMOTE_DIR/"
scp -i "$KEY" -P "$PORT" -q static/index.html "$HOST:$REMOTE_DIR/static/"

echo "==> 语法检查 + 重启服务..."
ssh -i "$KEY" -p "$PORT" "$HOST" "python3 -m py_compile $REMOTE_DIR/app.py $REMOTE_DIR/abogus.py && \
  systemctl restart dy-dl && sleep 1 && \
  curl -sf http://127.0.0.1:8788/api/health && echo && echo 'deploy OK'"
