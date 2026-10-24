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

# ★ 缺陷名（C-102）**判据在产物之前跑** ——
#   而这一族它为什么有两边：一边是「**产物自带的那一份** component-meta.json 的 entry」
#   （=> 而那是唯一真源），另一边是「**落位结果**算出来的那个 entry」（=> 而那是同一件事的另一种形态）。
#
# 真机读数（02 与 05，2026-10-25）：
#   · 判据那一处是 verify-component-artifact.sh:6 逐字
#     `ENTRY=$(bash "$ROOT/scripts/registry/read-component-entry.sh" "$TOOL")`
#     与 :78 逐字 `judge_shape "$ENTRY" "入口"`；
#   · 打包那一处是 package-component.sh:15 逐字同一句；
#   · 而 workflow 那一处逐字是 build-sqlite3.yml:107 判据、:109 打包；
#   · 而**打包那一支才是产出那份 zip 的那一个**（=> 而 package-component.sh:70 逐字
#     `ZIP="dist/component-${TOOL}-${VER}-${SHA}-android-arm64.zip"`）
#     ⇒ 而判据在 :107 要读那份 zip 里的 meta ⇒ 而那时 zip **还不存在**
#     ⇒ 而 read-component-entry.sh 走 ② 那一支答空 ⇒ 而 :78 拿空串去 judge_shape
#     ⇒ 而报「入口 dist/ 没产出」。
#
# ⇒ 而成因是 **0a57629**（2026-10-20）把这一格从**读表**（component-verify.json）
#   换成**读产物**，⇒ 而它**没有**把 workflow 里那两步的顺序倒过来；
#   ⇒ 而改前那一版（3a6b8625，2026-10-10 最后绿的那一趟）逐字是
#   `F="$HERE/../component-verify.json"`（=> 而读表 ⇒ 而判据在打包之前也能读到），
#   ⇒ 而那一版 workflow 的顺序**逐字相同**。
# ⇒ 而那是 C-104 那一族「改了生成路径而没改依赖它的那几处」的形态。
#
# ⇒ 而**处置**：这一处**从落位结果算**（=> 而不只从产物那份 zip 读）。
#   ⇒ 而「落位结果」是 dist/ 下那一批字节（=> 而打包那一支与判据那一处**都**有它），
#   ⇒ 而算它的是 gen-component-meta.js 的 computeEntry（=> 而那**就是**唯一真源那一份算法）。
#   ⇒ 而**顺序不用倒**（=> 而那要改 10 个 workflow，=> 而而那治的是症状不是成因）。

# ① 落位结果（=> 而 dist/ 下那一批字节）—— **先**算它（=> 而它此刻就在）
if [ -d "$ROOT/dist" ]; then
  e="$(node "$ROOT/scripts/recipes/gen-component-meta.js" --print-entry "$key" "$ROOT/dist" 2>/dev/null || true)"
  if [ -n "$e" ]; then printf '%s\n' "$e"; exit 0; fi
  # 纯库件：entry 合法地是空 —— 而落位结果存在 ⇒ 答「空」
  if [ -d "$ROOT/dist/lib" ] || [ -d "$ROOT/dist/bin" ]; then
    printf '\n'; exit 0
  fi
fi

# ② 产物自带的那一份（=> 而那是**上一次**打出来的那份 zip，=> 而而它在时 answer 的是**上一次**的答案）
for z in "$ROOT"/dist/component-"$key"-*.zip; do
  [ -f "$z" ] || continue
  e="$(unzip -p "$z" component-meta.json 2>/dev/null | node -e '
    let d = "";
    process.stdin.on("data", c => d += c); process.stdin.on("end", () => {
      try { process.stdout.write(String((JSON.parse(d).entry) || "")); } catch (e) {}
    });
  ' 2>/dev/null)"
  if [ -n "$e" ]; then printf '%s\n' "$e"; exit 0; fi
  # 纯库件：entry 合法地是空 —— 而那一份 meta 存在 ⇒ 答「空」
  if unzip -p "$z" component-meta.json 2>/dev/null | grep -q '"entry"'; then
    printf '\n'; exit 0
  fi
done

# ③ 读不到产物 ⇒ 答「没有可比对的声明」
printf '\n'
exit 0
