#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/.." && pwd)"

NDK_VER="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --ndk)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK" ] || { echo "::error title=无 ANDROID_SDK_ROOT::要用 sdkmanager 装 NDK 就得知道 SDK 装在哪"; exit 1; }

if [ -d "$SDK/ndk/$NDK_VER" ]; then
  echo "[ndk-cache] $SDK/ndk/$NDK_VER 已在位（runner 自带，无需缓存与安装）"
  exit 0
fi

if [ -d "$HOME/.cache/actions-setup-ndk" ]; then
  echo "[ndk-cache] 缓存命中 → $HOME/.cache/actions-setup-ndk"
  mkdir -p "$SDK/ndk"
  cp -a "$HOME/.cache/actions-setup-ndk" "$SDK/ndk/$NDK_VER"
  echo "[ndk-cache] 已还原到 $SDK/ndk/$NDK_VER"
  exit 0
fi

echo "[ndk-cache] 缓存未命中，装 ndk;$NDK_VER …"
SDK_LOG="$(mktemp)"
if ! yes 2>/dev/null | sdkmanager --sdk_root="$SDK" "ndk;$NDK_VER" >"$SDK_LOG" 2>&1; then
  echo "::error title=sdkmanager 装 NDK 失败::exit=$? 下面是它自己的输出（末 30 行）"
  tail -30 "$SDK_LOG" | sed 's/^/::error::/'
  rm -f "$SDK_LOG"
  exit 1
fi
if [ ! -d "$SDK/ndk/$NDK_VER" ]; then
  echo "::error title=装 NDK 失败::sdkmanager 退出码是 0，但 $SDK/ndk/$NDK_VER 不在"
  tail -20 "$SDK_LOG" | sed 's/^/::error::/'
  echo "::error::确认它在源里：https://dl.google.com/android/repository/repository2-3.xml"
  rm -f "$SDK_LOG"
  exit 1
fi
rm -f "$SDK_LOG"

mkdir -p "$HOME/.cache/actions-setup-ndk"
cp -a "$SDK/ndk/$NDK_VER/." "$HOME/.cache/actions-setup-ndk/"
echo "[ndk-cache] 已缓存 $HOME/.cache/actions-setup-ndk（下次同版本直接命中）"