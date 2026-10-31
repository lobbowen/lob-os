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
#  那四档的计数（=> 而它们逐字**只有**那四档、=> 而那一档逐字是「成因未知」）
miss_ratelimit=0; miss_auth=0; miss_notfound=0; miss_unknown=0
#  那「没有产物活着的成功 run」那一档（=> 而它跟那四档**不是**同一档、=> 而按 C-135 那不是同一问）
miss_norun=0
MISS_TSV="$DIST/.fetch-missing.tsv"
: > "$MISS_TSV"

#  缺件逐字**落**那显式缺件表（=> 而那一件**不许**静默从清单里消失）。
#  => 而那表逐字是三列：件名 / 那一档 / 那一档的原话。
miss_row() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" | tee -a "$MISS_TSV"
}
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
    miss_row "$t" "missing-norun" "没有产物活着的成功 run（$WF / $ART）"
    missed=$((missed+1))
    miss_norun=$((miss_norun+1))
    continue
  fi

  if ! gh run download "$picked" --repo "$REPO" --name "$ART" --dir "$DIST" >/dev/null 2>"$DIST/.gh-dl.err"; then
    #  缺陷名（C-102）**那一档把「下载被拒」压成一句「可能被并发清理」** ——
    #    => 而那旧版逐字是「（可能被并发清理），本次清单不含这件」。
    #    => 而「可能被并发清理」逐字是**猜**的成因、=> 而按 C-05「缺字段就报缺、不许猜填」，
    #      而那一档逐字**没有**任何证据支撑那个猜测。
    #    => 而而那一趟逐字真的那一档是（run 38077130287 的 component-node）：
    #      `error downloading component-node: HTTP 503: 503 Egress is over the account limit.`
    #      => 而那一档**不是**「被并发清理」、=> 而是「服务端限流」。
    #    => 而按 C-103 先答「它为什么有两边」：=> 而那两边是「那一次读**有没有发生**」
    #      与「那一次读**是什么档**」=> 而而它们各答一件事、=> 而而处置**不一样**。
    #    => 而现在：**四档分开**（=> 而那四档逐字从那**响应体**判、=> 而不猜），
    #      => 而「成因未知」逐字是那一档的**正当取值**（=> 而不是猜）。
    DL_EVID="$(tr -d '\r' < "$DIST/.gh-dl.err" 2>/dev/null | head -5)"
    #  => 而那判别逐字**调**那唯一判别源（=> 而按 C-104「判别源必须唯一」、
    #    => 而那 scripts/verify/classify-download-failure.sh 逐字是那唯一一处、
    #    => 而那它逐字带**阳性对照**（=> 而那 --selftest 那一档逐字有六个用例））。
    DL_KIND="$(printf '%s' "$DL_EVID" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --kind)"
    [ -n "$DL_KIND" ] || DL_KIND="unknown"
    #  => 而那**响应头**那一档逐字**也要**判（=> 而「资源不在」与「限流」怎么区分、=> 而那要靠响应头）：
    #    => 而那判据逐字落在**请求**上（=> 而按 C-993 的第二件：
    #      => 而「检查里过了但请求里没带身份」逐字是同一形状、=> 而那判据必须落在请求上）。
    #    => 而那 gh run download 逐字**不给**那响应头、=> 而那 gh api --include 逐字给。
    DL_HDR_KIND="unknown"
    DL_HDR="$(gh api --include "repos/$REPO/actions/runs/$picked/artifacts" 2>/dev/null | tr -d '\r' | head -40)"
    if [ -n "$DL_HDR" ]; then
      DL_HDR_KIND="$(printf '%s' "$DL_HDR" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --headers)"
      [ -n "$DL_HDR_KIND" ] || DL_HDR_KIND="unknown"
      #  => 而那响应头逐字**说了算**（=> 而那 ratelimit 那一档逐字是配额那一族、=> 而不是这一件的问题）
      if [ "$DL_HDR_KIND" = "ratelimit" ]; then DL_KIND="ratelimit"; fi
    fi
    #  => 而那 HTTP 那一档逐字**从**那响应体里**取**（=> 而不是猜）
    DL_HTTP="$(printf '%s' "$DL_EVID" | sed -n 's/.*HTTP \([0-9][0-9][0-9]\).*/\1/p' | head -1)"
    [ -n "$DL_HTTP" ] || DL_HTTP="none"
    rm -f "$DIST/.gh-dl.err"
    echo "::warning title=$t 下载被拒（$DL_KIND）::run $picked 的 $ART 没能下下来 —— HTTP $DL_HTTP、档位 $DL_KIND。"
    echo "::notice:: 那响应体逐字是：${DL_EVID:-（空）}"
    echo "::notice:: 那响应头那一档逐字是：${DL_HDR_KIND}（=> 而那判据逐字落在**请求**上）"
    case "$DL_KIND" in
      ratelimit) echo "::notice:: 那一档是**服务端限流**（=> 而那不是这一件的问题、=> 而那是配额那一族）。*" ;;
      auth)      echo "::notice:: 那一档是**鉴权不对**（=> 而那令牌逐字没被认、=> 而那不是这一件的问题）。*" ;;
      notfound)  echo "::notice:: 那一档是**资源不在**（=> 而那 artifact 已删或已过保留期、=> 而那不是这一件的问题）。*" ;;
      *)         echo "::notice:: 那一档是**成因未知**（=> 而那响应体逐字没给出可判的那一档、=> 而按 C-05 不猜）。*" ;;
    esac
    echo "::notice:: 本次清单不含这一件 —— 而它逐字**进**那显式缺件表（=> 而不会静默消失）。*"
    miss_row "$t" "missing-$DL_KIND" "run $picked 下载被拒（HTTP $DL_HTTP / $DL_KIND）"
    missed=$((missed+1))
    case "$DL_KIND" in
      ratelimit) miss_ratelimit=$((miss_ratelimit+1)) ;;
      auth)      miss_auth=$((miss_auth+1)) ;;
      notfound)  miss_notfound=$((miss_notfound+1)) ;;
      *)         miss_unknown=$((miss_unknown+1)) ;;
    esac
    continue
  fi
  rm -f "$DIST/.gh-dl.err"

  # 名字里必须有版本号：component-<件>-<版>-<哈希>-android-arm64.zip
  # （打包侧在读不到版本时写 unknown，见 recipes/package-component.sh:9 —— 那颗不是本轮编的）
  mapfile -t zips < <(compgen -G "$DIST/component-$t-*-android-arm64.zip" | grep -v -- "-unknown-" || true)
  if [ "${#zips[@]}" -eq 0 ]; then
    echo "::warning title=$t 产物不合契约::run $picked 的 $ART 里没有 component-$t-<版>-<哈希>-android-arm64.zip"
    miss_row "$t" "missing-contract" "artifact 里没有符合命名契约的 zip"
    rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
    missed=$((missed+1))
    miss_unknown=$((miss_unknown+1))
    continue
  fi
  if [ "${#zips[@]}" -gt 1 ]; then
    echo "::error title=$t 一次 run 给出多颗件::$ART 里有 ${#zips[@]} 颗 ${t} 的 zip:"
    for z in "${zips[@]}"; do echo "::error::  $(basename "$z")"; done
    echo "::error::逐件判断它们哪颗是本轮编的 —— 那份 artifact 不该收进清单"
    exit 1
  fi

  printf '%s\trun\t%s\n' "$t" "$picked"
  got=$((got+1))
done

#  缺陷名（C-102）**件逐字静默从清单里消失、=> 而而那汇总行逐字看不出来** ——
#    => 而那旧版逐字是「取到 6 件 · 缺 4 件」**只有**那一个总数、=> 而那四档逐字**混在一起**。
#    => 而而 run 41122918213 的 step 3 逐字是「取到 6 件 · 缺 4 件 · 原生跳过 10 件」、
#      而那一趟逐字**一件没判红**（=> 而那 :111-:115 旧版逐字只在「一件也没取到」时才 exit 1）。
#    => 而按 C-05「缺字段就报缺、不许猜填」：=> 而那缺的几件逐字**必须**逐件**看得见**、
#      => 而而那**是哪一档**逐字也要**看得见**。
#    => 而那处置：=> 而那汇总行逐字**分档**列出来、=> 而那缺件表逐字落一个档（$MISS_TSV）、
#      => 而那表逐字**在**那汇总行**之后**逐字列出来。
echo "---- 取件汇总：取到 $got 件 · 缺 $missed 件 · 原生跳过 $skipped 件"
if [ "$missed" -gt 0 ]; then
  echo "---- 缺件分档：限流 $miss_ratelimit · 鉴权 $miss_auth · 资源不在 $miss_notfound · 成因未知 $miss_unknown · 没有活着的成功 run $miss_norun"
  echo "---- 缺件逐条（那一件、那一档、那一档的原话）："
  while IFS="$(printf '\t')" read -r mt mkind mwhy; do
    [ -n "$mt" ] || continue
    echo "  · $mt  [$mkind] $mwhy"
  done < "$MISS_TSV"
  echo "::notice title=本轮有缺件::上面那几件逐字**进**了那份显式缺件表、=> 而它们逐字**没有**静默从清单里消失。"
fi
if [ "$got" -eq 0 ]; then
  echo "::error title=一件也没取到::清单投影会把「没有件」和「清单为空」混为一谈，这里先停"
  exit 1
fi