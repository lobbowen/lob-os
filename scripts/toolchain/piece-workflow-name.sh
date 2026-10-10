#!/usr/bin/env bash
# 件名 → 构建它的 workflow 名。
# 两者不是总一致：ripgrep 在清单里叫 ripgrep，workflow 叫 build-rg；
# pkgconf 在清单里叫 pkgconf，workflow 叫 build-pkg-config。
# 错误提示里写错文件名，人就得照着找一个不存在的东西。
set -euo pipefail
id="${1:?usage: piece-workflow-name.sh <件名>}"
case "$id" in
  ripgrep)   echo "build-rg.yml" ;;
  pkgconf)   echo "build-pkg-config.yml" ;;
  python)    echo "build-python3.yml" ;;
  sqlite)    echo "build-sqlite3.yml" ;;
  crypto)    echo "build-openssl.yml" ;;   # crypto 由 openssl 连带产出
  *)         echo "build-$id.yml" ;;
esac
