#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
cd "$ROOT_DIR"

REPO="${REPO:-${GITHUB_REPOSITORY:-}}"
[ -n "$REPO" ] || { echo "::error title=不知道仓库::设 REPO 或 GITHUB_REPOSITORY"; exit 1; }
[ -n "${GH_TOKEN:-}" ] || [ -n "${GITHUB_TOKEN:-}" ] || {
  echo "::error title=缺令牌::查 run / 下 artifact 都要令牌"; exit 1; }
export GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"

DIST="${DIST:-dist}"
if [ "$#" -gt 0 ]; then
  echo "::error title=不认的入参::这个脚本没有入参（收到: $*）—— 取件口径由 bucket_for 决定"
  exit 2
fi

mkdir -p "$DIST"

COMPONENTS="$(node scripts/registry/list-bucket-components.js)"
[ -n "${COMPONENTS// /}" ] || { echo "::error title=件清单为空::bucket_for 没解析出任何件"; exit 1; }

# base / apkonly 筐的件**随 APK 内置**，控制面板拿不到它们、也不该在清单里
# 看到它们 —— 清单只列「由 CI 编译、控制面板按它下发安装」的那些件。
#
# 那筐的判据在 cache-key.sh 的 bucket_for 里（唯一真相），这里直接问它，
# 不另抄一份。此前这里写死三个（bash rg busybox），于是 curl / zlib / openssl /
# crypto / jq / flock / posix / ptyprobe / ptysession 全被当成了待取的件，
# 脚本转头去找 build-curl.yml —— 那个文件根本不存在，于是 404，
# warning 却写成「没有成功 run」，把「文件名错」说成了「没编出来」。
# 而且 rg 也不对：件名是 ripgrep。
NATIVE="$(for t in $(node scripts/registry/list-bucket-components.js); do
  b="$(bash scripts/toolchain/cache-key.sh tag "$t" 2>/dev/null || true)"
  case "$b" in base-*|apkonly-*) printf '%s ' "$t" ;; esac
done)"

got=0; missed=0; skipped=0
for t in $COMPONENTS; do
  case " $NATIVE " in
    *" $t "*) echo -e "$t\tskipped-native\t随 APK 内置，不在控制面板清单里"; skipped=$((skipped+1)); continue ;;
  esac

  # 件名 → workflow 文件名。**不是 build-<件名>.yml 就对不上** ——
  # 六个件的 workflow 文件另有名字（rg / pkg-config / sqlite3 / python3 /
  # llvmtoolchain），此前一律按 build-<件名>.yml 拼，于是 gh 收到 404
  # （workflow not found），warning 说的是「没有成功 run」，把文件名错说成了
  # 「没编出来」——两回事。
  case "$t" in
    ripgrep) WF="build-rg.yml" ;;
    pkgconf) WF="build-pkg-config.yml" ;;
    sqlite)  WF="build-sqlite3.yml" ;;
    python)  WF="build-python3.yml" ;;
    llvm)    WF="build-llvmtoolchain.yml" ;;
    jq)      WF="build-jq.yml" ;;
    *)       WF="build-$t.yml" ;;
  esac
  # 拼出来的名字得真的存在 —— 否则又是 404，而 warning 会把它说成「没成功 run」
  if [ ! -f "$ROOT_DIR/.github/workflows/$WF" ]; then
    echo "::error title=$t 没有对应的 workflow::.github/workflows/$WF 不存在"
    echo "         件名与 workflow 文件名不是 build-<件名>.yml 的关系 —— 在上面那张表里补一条。"
    exit 1
  fi
  ART="component-$t"

  picked=""
  for rid in $(gh run list --repo "$REPO" --workflow "$WF" --status success \
               --limit 5 --json databaseId --jq '.[].databaseId'); do
    live="$(gh api "repos/$REPO/actions/runs/$rid/artifacts?per_page=100" \
            --jq "[.artifacts[] | select(.name == \"$ART\" and .expired_at == null)][0].name" 2>/dev/null || true)"
    if [ "$live" = "$ART" ]; then picked="$rid"; break; fi
  done

  if [ -z "$picked" ]; then
    echo "::warning title=$t 没有可取的件::$WF 还没有成功 run，或其 artifact 已过保留期 —— 本次清单不含这件"
    echo -e "$t\tmissing\t没有产物活着的成功 run（$WF / $ART）"
    missed=$((missed+1))
    continue
  fi

  if ! gh run download "$picked" --repo "$REPO" --name "$ART" --dir "$DIST" >/dev/null; then
    echo "::warning title=$t 取件失败::run $picked 的 $ART 没能下下来（可能被并发清理），本次清单不含这件"
    echo -e "$t\tmissing\trun $picked 下载失败"
    missed=$((missed+1))
    continue
  fi

  mapfile -t zips < <(compgen -G "$DIST/component-$t-*-android-arm64.zip" || true)
  if [ "${#zips[@]}" -eq 0 ]; then
    echo "::warning title=$t 产物不合契约::run $picked 的 $ART 里没有 component-$t-<版>-<哈希>-android-arm64.zip"
    echo -e "$t\tmissing\tartifact 里没有符合命名契约的 zip"
    rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
    missed=$((missed+1))
    continue
  fi
  if [ "${#zips[@]}" -gt 1 ]; then
    echo "::error title=$t 一次 run 给出多颗件::$ART 里有 ${#zips[@]} 颗 ${t} 的 zip:"
    for z in "${zips[@]}"; do echo "::error::  $(basename "$z")"; done
    echo "::error::逐件判断它们哪颗是本轮编的 —— 那份 artifact 不该收进清单"
    exit 1
  fi

  echo -e "$t\trun\t$picked"
  got=$((got+1))
done

echo "---- 取件汇总：取到 $got 件 · 缺 $missed 件 · 原生跳过 $skipped 件"
if [ "$got" -eq 0 ]; then
  echo "::error title=一件也没取到::清单投影会把「没有件」和「清单为空」混为一谈，这里先停"
  exit 1
fi