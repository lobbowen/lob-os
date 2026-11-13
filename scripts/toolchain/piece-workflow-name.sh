#!/usr/bin/env bash

set -euo pipefail
id="${1:?usage: piece-workflow-name.sh <件名>}"
case "$id" in
  ripgrep)   echo "build-rg.yml" ;;
  pkgconf)   echo "build-pkg-config.yml" ;;
  python)    echo "build-python3.yml" ;;
  sqlite)    echo "build-sqlite3.yml" ;;
  crypto)    echo "build-openssl.yml" ;;
  *)         echo "build-$id.yml" ;;
esac
