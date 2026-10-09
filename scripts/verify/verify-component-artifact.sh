#!/usr/bin/env bash
set -euo pipefail

TOOL="${1:?需要工具名}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENTRY=$(bash "$ROOT/scripts/registry/read-component-entry.sh" "$TOOL")

STATIC_LIST=""

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
    # ★ 静态件**不判红**，但要如实记下它不受语义层覆盖。
    #   判据原先是「必须 dynamically linked，否则 LD_PRELOAD 失效 → 判红」。
    #   那条对 base 筐的 busybox 就是错的：它**故意**静态编
    #   （build-native-busybox.sh 明确判「无 PT_DYNAMIC」——静态件不依赖
    #   任何共享库，这正是我们要的底座形态）。
    #   对 tool 筐也过宽：clang/lld 这类工具链默认就是静态链接的
    #   （LLVM 的 CMake 里 BUILD_SHARED_LIBS 默认 OFF，而我们没开它）。
    #
    #   事实是：静态件拿不到那四个 LD_PRELOAD hook
    #   （CompatSemantics.kt：exec-path 查解释器 · open-fallback EACCES
    #   回退 HOME · link-interpose 拦 link/linkat · tmp-paths 改 /tmp），
    #   所以它不享受 Linux 语义层。这是**已知的形态差别**，不是构建错误。
    #
    #   所以：记下来、提示，但不拦。真要判「这个件必须能被语义层覆盖」，
    #   那是 per-件 的要求，该写进 component-verify.json 那一格，
    #   而不是对所有件一刀切。
    case "$INFO" in
      *"dynamically linked"*|*"shared object"*) : ;;
      *) echo "[$TOOL] $label 静态链接 —— 不受 LD_PRELOAD 语义层覆盖（那四个 hook 拿不到）"
         STATIC_LIST="$STATIC_LIST $label" ;;
    esac
    if [ -n "${LLVM_READELF:-}" ] && [ -f "$LLVM_READELF" ]; then
      bash "$ROOT/scripts/verify/check-elf-deps.sh" "$BIN" "$TOOL/$label"
    else
      echo "[$TOOL] $label 跳过依赖闭包判据：LLVM_READELF 未注入（没有它就分不清系统库与缺失库，判据无依据）"
    fi
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

# 静态链接的件汇总一次 —— 它们不受 LD_PRELOAD 语义层覆盖，
# 是已知的形态差别（见上面 judge_shape 里的说明），不判红，但要看得见。
if [ -n "$STATIC_LIST" ]; then
  echo "[$TOOL] 静态链接的件：$STATIC_LIST"
  echo "[$TOOL] 它们不享受 Linux 语义层（exec-path / open-fallback / link-interpose / tmp-paths 四个 hook）"
fi
