#!/usr/bin/env bash
# 件构建失败时把现场写进 Job Summary。
#
# 为什么：GitHub 会裁剪 stdout，我这几轮拿到过三次残缺信息 ——
#   line 14: unexpected EOF while looking f…
#   /home/runner/work/_temp/…sh: line 9: ASSET: unbound variable
# 都看不出是哪一行、哪一步。Job Summary 不裁剪，是给「人」看的正式出口。
#
# stdout 仍然是给 CI 判定用的；这里是给人看的。
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

ID="${1:?需要件名}"
OUT="${GITHUB_STEP_SUMMARY:-}"
# 总是先落一个文件：Summary 在 Actions API 上不好取回，
# 而 artifact 我这边能直接下载 —— 两条路都得有。
FILE="${RUNNER_TEMP:-/tmp}/piece-diag-$ID.md"
mkdir -p "$(dirname "$FILE")"

{
  echo ""
  echo "## 件构建失败现场：$ID"
  echo ""

  echo "### 这一步做了什么"
  echo ""
  echo "- 固化判据：\`reusable-asset.sh\`（验 meta / entry / 那个路径在不在包里）"
  echo "- 编译：\`build-bundle-*.sh\` → \`emit_piece\` → \`package-bundle-zip.sh\`"
  echo ""

  echo "### 环境"
  echo ""
  echo "| 项 | 值 |"
  echo "|---|---|"
  echo "| bash | $(bash --version 2>/dev/null | head -1) |"
  echo "| node | $(node --version 2>/dev/null || echo 缺) |"
  echo "| CC | \${CC:-（未设置 —— 若为空说明 bundle-env.sh 的 NDK 定位没跑通）} |"
  echo "| 钉值表里该件 | $(bash scripts/toolchain/cache-key.sh key "$ID" 2>&1 | head -1) |"
  echo "| 期望 asset | $(bash scripts/toolchain/cache-key.sh asset "$ID" 2>&1 | head -1) |"
  echo "| 期望 tag | $(bash scripts/toolchain/cache-key.sh tag "$ID" 2>&1 | head -1) |"
  echo ""

  echo "### work/$ID 下有什么"
  echo ""
  echo '```'
  ls -la "work/$ID" 2>/dev/null | head -25 || echo "（目录不存在 —— 脚本可能在 source bundle-env.sh 那步就挂了）"
  echo '```'
  echo ""

  echo "### dist/"
  echo ""
  echo '```'
  ls -la dist/ 2>/dev/null | head -20 || echo "（没有 dist/ —— emit_piece 没跑到或 package 失败）"
  echo '```'
  echo ""

  echo "### 编译日志末尾"
  echo ""
  # 缺陷名（C-102）**$ROOT 在这个脚本里从未被定义** —— 而它只出现这一处。
  #   而 set -u 下那一句是「unbound variable」：
  #   真机读数（07，run 39531522481 等三件绿的日志里都有这一句）
  #   => **而后半边 `work/` 是相对路径**（而 cwd 是仓根，而它能取到）
  #   => **=> 而前半边那个 $ROOT 是笔误，=> 而正确的是 work/kernel-compat/**
  for lg in work/kernel-compat/cc-*.log work/"$ID"/*.log; do
    [ -f "$lg" ] || continue
    echo "**$lg**"
    echo ""
    echo '```'
    tail -25 "$lg"
    echo '```'
    echo ""
  done

  echo "### 已产出的件包（若有）"
  echo ""
  echo '```'
  for p in dist/component-*.zip; do
    [ -f "$p" ] || continue
    echo "$p  $(stat -c%s "$p" 2>/dev/null) 字节"
    tar -tzf "$p" 2>/dev/null | head -15
  done
  echo '```'
} | tee "$FILE" >> "$OUT" 2>/dev/null || true

echo "[diag] 现场已写入 Job Summary（$ID）"
