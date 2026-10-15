#!/usr/bin/env bash
# workflow 里的 run: 必须是合法 shell。
#
# 为什么需要：`bash -n` 查单个脚本查不出这个。
# 我在批量替换 workflow 的某个 step 时，新旧两行被挤成一行
#（echo "…"  → $TAG"          echo "…"），引号提前闭合，
# bash -n 在 workflow 文件上不报错，只有 CI 跑起来才炸成
# 「unexpected EOF while looking for matching `"」——
# 而且 GitHub 会裁剪日志，那行常被截断成「unexp…」，看不出是哪一行。
#
# 这里把每个 run: 抠出来单独 bash -n，语法错在提交前就红。
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

DIR=".github/workflows"
[ -d "$DIR" ] || { echo "::error title=门禁::找不到 $DIR"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

node -e '
const fs = require("fs"), path = require("path");
let yaml;
try { yaml = require("js-yaml"); }
catch (e) {
  console.error("[FAIL] 缺少依赖 js-yaml：本门禁无法解析 workflow，**未检查任何 run 块**。");
  console.error("        它跑在 work 根的 node_modules 上；装它：cd work && npm i js-yaml");
  process.exit(2);
}
const out = [];
for (const f of fs.readdirSync(".github/workflows").filter(x => x.endsWith(".yml")).sort()) {
  let d;
  try { d = yaml.load(fs.readFileSync(path.join(".github/workflows", f), "utf8")); }
  catch (e) { console.log(["BADYAML", f, "-", "-", ""].join("\t")); continue; }
  const jobs = d.jobs || {};
  for (const jn of Object.keys(jobs)) {
    (jobs[jn].steps || []).forEach((s, i) => {
      const body = typeof s.run === "string" ? s.run : "";
      if (!body.trim()) return;
      const tag = jn + " 第" + (i + 1) + "步 " + (s.name || "(无名)");
      const q = (body.match(/"/g) || []).length;
      const key = f.replace(/[^A-Za-z0-9]/g, "_") + "_" + jn + "_" + i;
      fs.writeFileSync(path.join(process.argv[1], key + ".sh"), body);
      console.log([key, f, tag, q % 2 === 0 ? "even" : "odd"].join("\t"));
    });
  }
}
' "$WORK" > "$WORK/list.tsv" 2>/dev/null

TOTAL=0
BAD=0

# 「检查了 0 个 run 块」必须判红，不许判绿。
# 判据的有效性不依赖它发现了什么，而依赖它确实在检查；
# 一个检查了 0 个对象的判据报绿，等于把「它绿了」与「它没在跑」压成同一个信号。
BAD=0

while IFS=$'\t' read -r key file tag quote; do
  [ -z "$key" ] && continue
  TOTAL=$((TOTAL+1))
  if [ "$quote" = "odd" ]; then
    echo "[error] $file  $tag  run: 里双引号数为奇数"
    BAD=$((BAD+1))
    continue
  fi
  if ! bash -n "$WORK/$key.sh" 2>/dev/null; then
    echo "[error] $file  $tag  run: 不是合法 shell"
    bash -n "$WORK/$key.sh" 2>&1 | head -2 | sed 's/^/          /'
    BAD=$((BAD+1))
  fi
done < "$WORK/list.tsv"

echo "[wf] 检查了 $TOTAL 个 run: 块"
if [ "$TOTAL" = "0" ]; then
  echo "::error title=一个 run 块都没检查到::本门禁未检查任何对象，不能报绿。"
  echo "        两种成因：(a) 依赖缺失（js-yaml 装了吗）；(b) .github/workflows 下没有可解析的 yml。"
  echo "        两者都意味着「它绿了」与「它没在跑」被压成同一个信号 —— 不可接受。"
  exit 1
fi
if [ "$BAD" != "0" ]; then
  echo "::error title=workflow run 块语法错::$BAD 处"
  exit 1
fi
echo "[ok] 全部合法"
