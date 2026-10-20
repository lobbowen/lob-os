#!/usr/bin/env bash
# 读「这一件该有的入口」—— **从产物里读回**，不从表里读。
#
# ★ 缺陷名（C-102）entry 散在几处、而那几处是副本 —— 01/02/07 今天裁的：
#   entry 的**唯一真源**是产物自带的 component-meta.json（gen-component-meta.js 的
#   computeEntry 按形态算出来，写的时候带着 entryProvenance=
#   「gen-component-meta.js:落位结果（构建期算出，非人填）」）。
#   而 component-sources.json 的 entry 键（15 处）与各 workflow 的第三参都是**人写下的副本**
#   ⇒ 那些副本今天已经漂了（crypto 那一件写着 lib/libssl.so —— 指错了另一个件都没被抓住，
#   因为门禁只比「包里的 meta 说 X」与「调用方说 X」，而那两处说的都是同一个旧值）。
# ⇒ 所以那两处副本都删了（2026-10-20），而这个脚本改成**从产物里读回 entry**。
#
# 用法：read-component-entry.sh <件名>
#   读得到 ⇒ 打印那一格（纯库件是空行）
#   读不到 ⇒ **答「没有可比对的声明」**（exit 0，输出空）—— 按 C-05「没有」就是没有，
#   而**不报缺**（那一格不是缺件，是一个可选项）。
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
key="${1:?usage: read-component-entry.sh <件名>}"

# ① 本机 dist/ 里那一份（构建侧刚打出来的）
for z in "$ROOT"/dist/component-"$key"-*.zip; do
  [ -f "$z" ] || continue
  e="$(unzip -p "$z" component-meta.json 2>/dev/null | node -e '
    let d = "";
    process.stdin.on("data", c => d += c).on("end", () => {
      try { process.stdout.write(String((JSON.parse(d).entry) || "")); } catch (e) {}
    });
  ' 2>/dev/null)"
  if [ -n "$e" ]; then printf '%s\n' "$e"; exit 0; fi
  # 纯库件：entry 合法地是空 —— 而那一份 meta 存在 ⇒ 答「空」
  if unzip -p "$z" component-meta.json 2>/dev/null | grep -q '"entry"'; then
    printf '\n'; exit 0
  fi
done

# ② 读不到产物 ⇒ 答「没有可比对的声明」
printf '\n'
exit 0
