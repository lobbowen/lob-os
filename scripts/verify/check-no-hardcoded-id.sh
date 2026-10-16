#!/usr/bin/env bash
# C-01 概念准入：硬编码的「程序 id」不得存在，除非它真的在清单里。
#
# 判别形式：这个字面量是「一个 id」还是「一个协议名 / 文件名 / 环境键」？
#   判据：它若被当成 programId 用（出现在 role 匹配、stateDirOf、binOf 这类调用里），
#         那它必须能在 scripts/component-sources.json 的 sources 里找到。
#
# 为什么：2026-10-16 实测，InstalledRuntime.RUNTIME = "runtime" 是一个真机上不存在的 id，
# 而它被当成兜底值用（declared?.id ?: RUNTIME）——
# 于是「找不到运行时」变成「去找一个不存在的东西」，故障延迟到别处才暴露，
# 并被包装成一条误导性的诊断（"node 运行时未就位"）。
#
# 白名单：常量名 空格分隔。每一条都在下面写明「它为什么不是件 id」。
# ★ 白名单里的每一条都必须有理由 —— 而理由只有一种形态通过：
#   「它是 <类别>」—— 类别指 文件名 / 环境键 / Android action / 权限 tier / 协议值 / 角色字面量。
#   「它是清单里的 id」—— 那一类不该进白名单（它本来就查得过）。
#
# 为什么 LIBRARY 与 RUNTIME 在白名单里：它们是 role 的**字面量**，不是 id。
# 而 InstalledRuntime 的兜底链（declared?.id ?: RUNTIME）已于 2026-10-16 改成如实报缺，
# 所以 RUNTIME 现在只用于 role 匹配 —— 但它的值仍是个不存在的 id，**而这正是本判据要提醒的**。
# ★ 白名单按「名字=值」匹配，而不是只按名字 ——
#   否则改一个字面量的值就能绕过它（2026-10-16 实测：把 UNKNOWN 改成 nosuchthing 仍然绿）。
C01_ALLOW_DEFAULT="
  ACTION_LAUNCH=lobos.services.app.LAUNCH
  ACTION_START=lobos.SCREEN_CAPTURE_START
  ACTION_UNINSTALLED=lobos.PKG_UNINSTALLED
  BACKEND_DIR=backend
  BASE_SOCKET=lobos_hostbridge
  CATEGORY=kill-audit
  CA_BUNDLE_NAME=ca-bundle.pem
  ENV_INHERIT=inherit
  INSTALLER_ID=lobos.installer
  LIBRARY=library
  PREFIX=qa_
  TIER_SYS=sys
  TYPE_QUICKAPP=quickapp
  UNKNOWN=unknown
  RUNTIME=runtime
"
set -uo pipefail

C01_ALLOW="${C01_ALLOW:-$C01_ALLOW_DEFAULT}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

IDS="$(node scripts/registry/print-piece-ids.js 2>/dev/null)"
[ -n "$IDS" ] || { echo "::error title=读不到件 id 清单::component-sources.json 解析失败"; exit 2; }

CHECKED=0
BAD=0
for f in $(grep -rl "const val" --include=*.kt container/app/src/main/java); do
  lineno=0
  while IFS= read -r line; do
    lineno=$((lineno+1))
    case "$line" in
      *"const val "*"= "*) ;;
      *) continue ;;
    esac
    name="$(printf "%s" "$line" | sed -n 's/.*const val \([A-Z_][A-Z_0-9]*\).*/\1/p')"
    val="$(printf "%s" "$line" | sed -n 's/.*= "\([a-z][a-zA-Z0-9_.-]*\)"$/\1/p')"
    [ -n "$name" ] && [ -n "$val" ] || continue
    case "$val" in
      *.*) case "$val" in *.*.*) ;; *) ;; esac ;;
    esac
    # 只在它被当作 id 用时才判（role 匹配 / stateDirOf / binOf / programId 比较）
    if ! grep -qE "(==|!=|=)\s*$name\b|\b$name\b\s*(==|!=)" "$f"; then
      continue
    fi
    CHECKED=$((CHECKED+1))
    if printf "%s\n" "$IDS" | grep -qx "$val"; then
      continue
    fi
    ALLOW_PAIRS=" $(printf "%s" "$C01_ALLOW" | tr "\n" " ") "
    case "$ALLOW_PAIRS" in *" $name=$val "*) continue ;; esac
    echo "::error title=硬编码的 id::$f:$lineno  $name = \"$val\"，而它不在 component-sources.json 的 sources 里"
    echo "::error::  若它确实是 id，请先把它登记进清单；若它只是协议名/文件名，别拿它当 id 用"
    BAD=$((BAD+1))
  done < "$f"
done

echo "[c01] 检查了 $CHECKED 个被当作 id 用的字符串常量"
if [ "$CHECKED" -eq 0 ]; then
  echo "::error title=检查了 0 个对象::本判据未生效，状态是【未验证】而不是通过"
  exit 2
fi
[ "$BAD" -eq 0 ] || exit 1
echo "[ok] 没有硬编码的、不在清单里的 id"