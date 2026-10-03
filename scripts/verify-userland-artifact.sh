#!/usr/bin/env bash
set -euo pipefail

TOOL="${1:?需要工具名}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENTRY=$(bash "$ROOT/scripts/read-userland-entry.sh" "$TOOL")

judge_shape() {
  local rel="$1" label="$2"
  local BIN="$ROOT/dist/$rel"
  if [ ! -f "$BIN" ]; then
    echo "::error title=缺产物::$label dist/$rel 没产出"; exit 1
  fi
  local MAGIC4 SHAPE
  MAGIC4=$(head -c 4 "$BIN" | od -An -tx1 | tr -d ' \n')
  if [ "$MAGIC4" = "7f454c46" ]; then
    SHAPE=elf
    local INFO
    INFO=$(file -b "$BIN")
    echo "[$TOOL] $label $INFO"
    case "$INFO" in
      *"ARM aarch64"*|*"aarch64"*|*"arm64"*|*"ARM64"*) : ;;
      *) echo "::error title=产物不是 arm64/aarch64::$INFO —— 不同 file 版本措辞不同（AArch64 / aarch64 / arm64），三者都接受"; exit 1 ;;
    esac
    case "$INFO" in
      *"dynamically linked"*|*"shared object"*) : ;;
      *) echo "::error title=产物是静态件::$INFO —— 容器 Linux 语义层（LD_PRELOAD）对静态件失效"; exit 1 ;;
    esac
  else
    local HEAD2 LINE1
    HEAD2=$(head -c 2 "$BIN")
    if [ "$HEAD2" != "#!" ]; then
      local INFO
      INFO=$(file -b "$BIN")
      echo "::error title=产物形状不认识::$label 既不是 ELF（前四字节 $MAGIC4）也不是 shebang 脚本（前二字节 \"$HEAD2\"）；$INFO"
      exit 1
    fi
    SHAPE=shebang
    LINE1=$(head -n 1 "$BIN")
    echo "[$TOOL] $label shebang 入口：$LINE1"
    case "$LINE1" in
      '#! /usr/bin/env '*|'#!/usr/bin/env '*) : ;;
      '#!/usr/bin/'*|'#!/bin/'*) : ;;
      *)
        echo "::error title=shebang 不在兑现范围::$label 解释器写法 $LINE1 —— D1 只按 PATH 兑现 env 形态与 /usr/bin、/bin 标准绝对路径；自写 #!/system/bin/sh 包装是已定罪的「中间多了一层」"
        exit 1 ;;
    esac
  fi
  local SIZE
  SIZE=$(stat -c%s "$BIN")
  echo "[ok] $TOOL $label $SIZE 字节 形状=$SHAPE 入口=$rel"
}

judge_shape "$ENTRY" "入口"

FACES=$(node -e '
const fs = require("node:fs"), path = require("node:path");
const p = path.resolve(process.argv[1]);
if (!fs.existsSync(p)) process.exit(0);
let bin;
try { bin = JSON.parse(fs.readFileSync(p, "utf8")).bin; }
catch (e) { console.error("[FAIL] 根 package.json 读不出: " + e.message); process.exit(1); }
if (!bin || typeof bin === "string") process.exit(0);
const skip = process.argv[2];
for (const [name, rel] of Object.entries(bin)) {
  if (typeof rel !== "string" || rel === skip) continue;
  process.stdout.write(name + "\t" + rel + "\n");
}
' "$ROOT/dist/package.json" "$ENTRY") || exit 1
while IFS="$(printf '\t')" read -r ANAME AREL; do
  [ -n "$ANAME" ] || continue
  judge_shape "$AREL" "别名 $ANAME"
done <<< "$FACES"
