#!/usr/bin/env bash
set -euo pipefail
cmd="${1:?usage: ensure-tool.sh <command> <packages...>}"
shift
if command -v "$cmd" >/dev/null 2>&1; then
  exit 0
fi
echo "[info] 缺少 $cmd，安装: $* …"
sudo apt-get update -qq
sudo apt-get install -y -qq "$@"
command -v "$cmd" >/dev/null 2>&1 || { echo "::error::$cmd 安装后仍找不到"; exit 1; }
