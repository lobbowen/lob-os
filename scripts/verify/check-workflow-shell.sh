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
const yaml = require("js-yaml");
const out = [];
for (const f of fs.readdirSync(".github/workflows").filter(x => x.endsWith(".yml")).sort()) {
  let d;
  try { d = yaml.load(fs.readFileSync(path.join(".github/workflows", f), "utf8")); }
  catch (e) { console.log("BADYAML\t" + f + "\t-\t-"); continue; }
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
if [ "$BAD" != "0" ]; then
  echo "::error title=workflow run 块语法错::$BAD 处"
  exit 1
fi
echo "[ok] 全部合法"
