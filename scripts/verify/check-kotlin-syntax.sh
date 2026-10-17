#!/usr/bin/env bash
# C-13：本地必须能拦住「至少语法级」的错。
#
# 2026-10-16 实测（本机）：裸 kotlinc 可以只做语法检查，不要求依赖解析。
#   少一个右括号         → "expecting }"          与依赖无关，能拦
#   字符串里的 ** 触发误解析 → 同样是语法级，能拦
#   firstOrNull 谓词返回 String? → "type mismatch ... Boolean was expected"
#     只用 kotlin 标准库，不需要任何 Android 依赖，能拦
#
# 而它拦不住的是「依赖未解析」那一类（今天第 4 条 BaseItem 那种）。
# ⇒ 所以本脚本判的是「语法/类型级错」，不是「能否编译」。
#
# 没有 kotlinc 时判【未验证】exit 2，而不是静默通过。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

KC="${KOTLINC_CMD:-}"
if [ -z "$KC" ]; then
  if command -v kotlinc >/dev/null 2>&1; then KC="$(command -v kotlinc)"; fi
fi
if [ -z "$KC" ] || ! "$KC" -version >/dev/null 2>&1; then
  echo "::error title=C-13 未生效::本机没有 kotlinc —— 语法级错只能等 CI，而那会让人以为门禁全绿"
  echo "::error:: 装一个 kotlin-compiler，或设 KOTLINC_CMD 指向它"
  exit 2
fi

CHECKED=0
BAD=0
TMP="$(mktemp -d)"
trap "rm -rf $TMP" EXIT

# 逐文件单独编译：单文件时「依赖未解析」是可预期的噪声，
# 而语法错误与它无关 —— 所以只取非 unresolved 的那些。
# 只在**我改过的文件**上跑：全量 109 个文件 × 约 6 秒 = 11 分钟，
# 那不是「提交前跑」的量级。而判据形态是「我改完 .kt 之后能发现多少类错」——
# 所以对象是**本次改动的文件**（git status / --cached 给出），那通常只有个位数。
# ★ 判别形式：这条判据检查了几个对象？为 0 时状态是【未验证】而不是通过。
CHANGED="$(git status --porcelain 2>/dev/null | awk "{print \$2}" | grep "\.kt$" || true)"
if [ -z "$CHANGED" ]; then
  CHANGED="$(git diff --name-only HEAD 2>/dev/null | grep "\.kt$" || true)"
fi
if [ -z "$CHANGED" ]; then
  echo "[c13] 本次没有改 .kt —— 状态是【未验证】（不是通过：没有对象可检查）"
  exit 2
fi

for f in $CHANGED; do
  [ -f "$f" ] || continue
  base="$(basename "$f")"
  cp "$f" "$TMP/$base"
  out="$("$KC" -J-Xmx2g -nowarn "$TMP/$base" 2>&1 | grep -E "error:" \
    | grep -vE "unresolved reference|cannot infer a type|not enough information to infer|overload resolution ambiguity|none of the following functions|is ambiguous for this expression|not allowed here|must be exhaustive|should return Unit" || true)"
  CHECKED=$((CHECKED+1))
  if [ -n "$out" ]; then
    BAD=$((BAD+1))
    echo "::error title=语法级错（不依赖类型解析）::$f"
    printf "%s\n" "$out" | head -6 | sed "s|^|  |"
  fi
done

echo "[c13] 逐文件语法检查了 $CHECKED 个 .kt（不含依赖解析）"
if [ "$CHECKED" -eq 0 ]; then
  echo "::error title=检查了 0 个对象::状态是【未验证】而不是通过"
  exit 2
fi
if [ "$BAD" -gt 0 ]; then
  echo ""
  echo "::error title=有 $BAD 个文件存在语法或类型级错::它们今天全部只能靠 CI 发现"
  exit 1
fi
echo "[ok] 语法级检查通过"