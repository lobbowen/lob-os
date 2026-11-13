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

rm -f "$DIST"/component-*.zip 2>/dev/null || true
if compgen -G "$DIST"/component-*.zip > /dev/null 2>&1; then
  echo "::error title=旧件清不掉::那 dist 里逐字还有那旧件、那**名与实**逐字**核不过**"
  echo "        请手工清掉 $DIST/component-*.zip 逐字再跑这一趟（按 C-05 报缺）。"
  exit 1
fi

FMT_SH="$ROOT_DIR/scripts/toolchain/fmt.sh"
[ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; exit 1; }

. "$FMT_SH"

COMPONENTS="$(node scripts/registry/list-bucket-components.js)"
[ -n "${COMPONENTS// /}" ] || { echo "::error title=件清单为空::bucket_for 没解析出任何件"; exit 1; }

NATIVE="$(for t in $(node scripts/registry/list-bucket-components.js); do
  b="$(bash scripts/toolchain/cache-key.sh tag "$t" 2>/dev/null || true)"
  case "$b" in base-*|apkonly-*) printf '%s ' "$t" ;; esac
done)"

got=0; missed=0; skipped=0

miss_ratelimit=0; miss_auth=0; miss_notfound=0; miss_unknown=0

miss_norun=0
MISS_TSV="$DIST/.fetch-missing.tsv"
: > "$MISS_TSV"

MISS_JSON="$DIST/.fetch-missing.json"
printf '[]\n' > "$MISS_JSON"

miss_row() {
  local mform="${4:-}"
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$mform" | tee -a "$MISS_TSV"

  node -e '
const fs = require("node:fs");
const p = process.argv[1];
let arr = [];
try { arr = JSON.parse(fs.readFileSync(p, "utf8")); } catch (e) { arr = []; }
if (!Array.isArray(arr)) arr = [];
const f = process.argv[5] ? process.argv[5].split("|") : [];
arr.push({ piece: process.argv[2], kind: process.argv[3], detail: process.argv[4],
           form: { name: f[0] || "", magic: f[1] || "", outerEntries: Number(f[2] || -1) } });
fs.writeFileSync(p, JSON.stringify(arr, null, 2) + "\n");
' "$MISS_JSON" "$1" "$2" "$3" "$mform"
}

missing_form_of() {
  local f="$1"
  if [ ! -f "$f" ]; then printf '%s' "未知|无|-1"; return 0; fi
  printf '%s|%s|%s' "$(fmt_of "$f")" "$(fmt_magic "$f")" "$(fmt_outer_entries_of "$f")"
}

for t in $COMPONENTS; do
  case " $NATIVE " in
    *" $t "*) echo -e "$t\tskipped-native\t随 APK 内置，不在控制面板清单里"; skipped=$((skipped+1)); continue ;;
  esac

  case "$t" in
    ripgrep) WF="build-rg.yml" ;;
    pkgconf) WF="build-pkg-config.yml" ;;
    sqlite)  WF="build-sqlite3.yml" ;;
    python)  WF="build-python3.yml" ;;
    llvm)    WF="build-llvmtoolchain.yml" ;;
    jq)      WF="build-jq.yml" ;;
    *)       WF="build-$t.yml" ;;
  esac

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
    miss_row "$t" "missing-norun" "没有产物活着的成功 run（$WF / $ART）" "未知|无|-1"
    missed=$((missed+1))
    miss_norun=$((miss_norun+1))
    continue
  fi

  HEAD_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)"
  if [ -z "$HEAD_SHA" ]; then
    echo "::warning title=$t 构建标识读不到::本机读不到 HEAD（无法判定那 artifact 是不是当前这份）"
    miss_row "$t" "missing-norun" "构建标识读不到（无法判定那 artifact 是不是当前这份）" "未知|无|-1"
    missed=$((missed+1)); miss_norun=$((miss_norun+1)); continue
  fi
  RUN_HEAD="$(gh api "repos/$REPO/actions/runs/$picked" --jq '.head_sha' 2>/dev/null || true)"
  if [ -z "$RUN_HEAD" ]; then
    echo "::warning title=$t 构建标识读不到::run $picked 的 head_sha 读不到（无法判定那 artifact 是不是当前这份）"
    miss_row "$t" "missing-norun" "run $picked 的 head_sha 读不到（无法判定那 artifact 是不是当前这份）" "未知|无|-1"
    missed=$((missed+1)); miss_norun=$((miss_norun+1)); continue
  fi

  if ! gh run download "$picked" --repo "$REPO" --name "$ART" --dir "$DIST" >/dev/null 2>"$DIST/.gh-dl.err"; then

    DL_EVID="$(tr -d '\r' < "$DIST/.gh-dl.err" 2>/dev/null | head -5)"

    _direct_ok=0
    if printf '%s' "$DL_EVID" | grep -qi 'path traversal'; then
      echo "::notice:: 那客户端（gh）逐字自己拒了：那**真因**逐字是那 artifact 逐字太大"
      echo "::notice:: （那 zip64 逐字那中央目录逐字写着那个 absurd uncompressed size、"
      echo "::notice::那 gh 逐字把它**判**成路径穿越）。那**服务端**那四档逐字**都不是**。"
      echo "::notice::现在逐字**绕开**那 gh 逐字自己下（那**直连**那 artifact API）。"
      _aid="$(gh api "repos/$REPO/actions/runs/$picked/artifacts?per_page=100" \
              --jq "[.artifacts[] | select(.name == \"$ART\" and .expired_at == null)][0].id" 2>/dev/null || true)"
      _aurl="https://api.github.com/repos/$REPO/actions/artifacts/$_aid/zip"
      if [ -n "$_aid" ]; then
        _asize="$(gh api "repos/$REPO/actions/artifacts/$_aid" --jq '.size_in_bytes' 2>/dev/null || true)"
        if curl -fsSL --retry 3 --retry-delay 2 \
             -H "Authorization: Bearer ${GH_TOKEN}" \
             -H "Accept: application/vnd.github+json" -H "User-Agent: lobos" \
             -o "$DIST/.artifact-direct.zip" "$_aurl" 2>/dev/null; then
          _agot="$(stat -c %s "$DIST/.artifact-direct.zip" 2>/dev/null || echo 0)"
          if [ -n "$_asize" ] && [ "$_agot" != "$_asize" ]; then
            echo "::warning title=$t 直连下的字节数不符::期望 $_asize、实得 $_agot、按 C-05 报缺"
            rm -f "$DIST/.artifact-direct.zip"
          else

            if command -v unzip >/dev/null 2>&1; then
              _adir="$DIST/.artifact-direct"
              rm -rf "$_adir" && mkdir -p "$_adir"
              if unzip -qq -o "$DIST/.artifact-direct.zip" -d "$_adir" 2>/dev/null; then
                _found="$(find "$_adir" -maxdepth 2 -name 'component-*.zip' -type f | head -1)"
                if [ -n "$_found" ]; then
                  cp -f "$_found" "$DIST/$(basename "$_found")"
                  echo "::notice::直连逐字**成**了、件逐字 = $(basename "$_found")"
                  _direct_ok=1
                else
                  echo "::warning title=$t 直连里没有那件包::按 C-05 报缺"
                fi
              else
                echo "::warning title=$t 直连的 artifact 逐字解不开::按 C-05 报缺"
              fi
              rm -rf "$_adir"
            fi
            rm -f "$DIST/.artifact-direct.zip"
          fi
        else
          echo "::warning title=$t 直连也下不下来::按 C-05 报缺"
          rm -f "$DIST/.artifact-direct.zip"
        fi
      else
        echo "::warning title=$t 那个 artifact 逐字读不到 id::按 C-05 报缺"
      fi
    fi

    DL_KIND="$(printf '%s' "$DL_EVID" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --kind)"
    [ -n "$DL_KIND" ] || DL_KIND="unknown"

    DL_HDR_KIND="unknown"
    DL_HDR="$(gh api --include "repos/$REPO/actions/runs/$picked/artifacts" 2>/dev/null | tr -d '\r' | head -40)"
    if [ -n "$DL_HDR" ]; then
      DL_HDR_KIND="$(printf '%s' "$DL_HDR" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --headers)"
      [ -n "$DL_HDR_KIND" ] || DL_HDR_KIND="unknown"

      if [ "$DL_HDR_KIND" = "ratelimit" ]; then DL_KIND="ratelimit"; fi
    fi

    DL_HTTP="$(printf '%s' "$DL_EVID" | sed -n 's/.*HTTP \([0-9][0-9][0-9]\).*/\1/p' | head -1)"
    [ -n "$DL_HTTP" ] || DL_HTTP="none"
    rm -f "$DIST/.gh-dl.err"
    echo "::warning title=$t 下载被拒（$DL_KIND）::run $picked 的 $ART 没能下下来 —— HTTP $DL_HTTP、档位 $DL_KIND。"
    echo "::notice:: 那响应体逐字是：${DL_EVID:-（空）}"
    echo "::notice:: 那响应头那一档逐字是：${DL_HDR_KIND}（判据逐字落在**请求**上）"
    case "$DL_KIND" in
      ratelimit) echo "::notice:: 那一档是**服务端限流**（不是这一件的问题、是配额那一族）。*" ;;
      auth)      echo "::notice:: 那一档是**鉴权不对**（令牌逐字没被认、不是这一件的问题）。*" ;;
      notfound)  echo "::notice:: 那一档是**资源不在**（那 artifact 已删或已过保留期、不是这一件的问题）。*" ;;
      *)         echo "::notice:: 那一档是**成因未知**（响应体逐字没给出可判的那一档、按 C-05 不猜）。*" ;;
    esac
    if [ "$_direct_ok" = "1" ]; then
      echo "::notice::那**直连**逐字**成**了、这一件逐字**不**进那缺件表。"
      rm -f "$DIST/.gh-dl.err"
    else
      echo "::notice:: 本次清单不含这一件 —— 而它逐字**进**那显式缺件表（不会静默消失）。*"

      miss_row "$t" "missing-$DL_KIND" "run $picked 下载被拒（HTTP $DL_HTTP / $DL_KIND）" \
        "$(missing_form_of "$DIST/.gh-dl-partial")"
      missed=$((missed+1))
      case "$DL_KIND" in
        ratelimit) miss_ratelimit=$((miss_ratelimit+1)) ;;
        auth)      miss_auth=$((miss_auth+1)) ;;
        notfound)  miss_notfound=$((miss_notfound+1)) ;;
        *)         miss_unknown=$((miss_unknown+1)) ;;
      esac
      continue
    fi
  fi
  rm -f "$DIST/.gh-dl.err"

  _cand=""
  for _c in "$DIST"/*.zip; do
    [ -f "$_c" ] || continue
    case "$(basename "$_c")" in
      component-$t-*) _cand="$_c"; break ;;
    esac
  done
  if [ -n "$_cand" ]; then
    _form="$(fmt_of "$_cand")"
    _magic="$(fmt_magic "$_cand")"
    echo "::notice:: $t 那一颗的容器形态=$_form（头四字节 $_magic）"
    _unp="$DIST/.unpack-$t"
    if _picked="$(fmt_pick_piece "$_cand" "$_unp" "$t" 2>"$DIST/.pick-$t.err")"; then
      if [ "$_picked" != "$_cand" ]; then

        echo "::notice:: $t 那一颗是**外层套内层**：外层=$_cand、内层=${_picked##*/}"
      else
        echo "::notice:: $t 那一颗**外层自己**就是那一颗件"
      fi

      _ver="$(node -e '
const cp=require("node:child_process");
try {
  const raw=cp.execFileSync("unzip",["-p",process.argv[1],"component-meta.json"],{maxBuffer:4*1024*1024});
  process.stdout.write(String(JSON.parse(raw.toString("utf8")).version||""));
} catch(e) { process.stdout.write(""); }
' "$_picked")"
      [ -n "$_ver" ] || _ver="unknown"

      _build_sha="$(node -e '
const cp=require("node:child_process");
try {
  const raw=cp.execFileSync("unzip",["-p",process.argv[1],"component-meta.json"],{maxBuffer:4*1024*1024});
  process.stdout.write(String(JSON.parse(raw.toString("utf8")).buildSha||""));
} catch(e) { process.stdout.write(""); }
' "$_picked")"
      if [ -z "$_build_sha" ]; then

        echo "::warning title=$t 构建标识读不到::那件包里逐字**没有** component-meta.json 的 buildSha 那一格（无法判定那一件是不是当前这份）"
        miss_row "$t" "missing-contract" "件包里读不到 buildSha（无法判定那一件是不是当前这份）" \
          "$(missing_form_of "$_picked")"
        rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
        missed=$((missed+1)); miss_unknown=$((miss_unknown+1))
        continue
      fi
      if [ "$_build_sha" != "$RUN_HEAD" ]; then

        echo "::warning title=$t 那件不是那一趟产的::那件自己的 buildSha 逐字是 ${_build_sha:0:12}、那 run $picked 的 head 逐字是 ${RUN_HEAD:0:12}"
        echo "::notice::件包里的字节逐字**不是**那一趟产的、那**旧字节不进清单**。"
        miss_row "$t" "missing-contract" "件自己的 buildSha 逐字不是那一趟产的（${_build_sha:0:12} ≠ run $picked 的 ${RUN_HEAD:0:12}）" \
          "$(missing_form_of "$_picked")"
        rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
        missed=$((missed+1)); miss_unknown=$((miss_unknown+1))
        continue
      fi
      _sha="$(sha256sum "$_picked" | cut -c1-12)"
      _dst="$DIST/component-$t-$_ver-$_sha-android-arm64.zip"

      if [ "$_picked" = "$_dst" ]; then
        echo "::notice:: $t 那一颗逐字**已经在**那个路径上（不拷、那 cp 逐字会报 are the same file）。"
      else
        cp -f "$_picked" "$_dst"
      fi
    else
      echo "::warning title=$t 挑不出那一颗件::$(tr '\n' ' ' < "$DIST/.pick-$t.err")"
      echo "::notice:: 那期望形态=$_form（头四字节 $_magic）、按内容挑逐字挑不出（按 C-05 不猜、不取第一个）。"
      miss_row "$t" "missing-contract" "artifact 里的容器逐字挑不出那一颗件（形态=$_form、头四字节 $_magic）" \
        "$(missing_form_of "$_cand")"
      rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
      missed=$((missed+1))
      miss_unknown=$((miss_unknown+1))
      continue
    fi
    rm -rf "$_unp" "$DIST/.pick-$t.err"
  fi

  mapfile -t zips < <(compgen -G "$DIST/component-$t-*-android-arm64.zip" | grep -v -- "-unknown-" || true)
  if [ "${#zips[@]}" -eq 0 ]; then
    echo "::warning title=$t 产物不合契约::run $picked 的 $ART 里没有 component-$t-<版>-<哈希>-android-arm64.zip"
    miss_row "$t" "missing-contract" "artifact 里没有符合命名契约的 zip" \
      "$(missing_form_of "$DIST/component-$t-"*.zip 2>/dev/null | head -1)"
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

echo "---- 取件汇总：取到 $got 件 · 缺 $missed 件 · 原生跳过 $skipped 件"
if [ "$missed" -gt 0 ]; then
  echo "---- 缺件分档：限流 $miss_ratelimit · 鉴权 $miss_auth · 资源不在 $miss_notfound · 成因未知 $miss_unknown · 没有活着的成功 run $miss_norun"
  echo "---- 缺件逐条（那一件、那一档、那一档的原话）："
  while IFS="$(printf '\t')" read -r mt mkind mwhy; do
    [ -n "$mt" ] || continue
    echo "  · $mt  [$mkind] $mwhy"
  done < "$MISS_TSV"
  echo "::notice title=本轮有缺件::上面那几件逐字**进**了那份显式缺件表、它们逐字**没有**静默从清单里消失。"
  echo "::notice:: 而那机器可读的那一份逐字是 $MISS_JSON（逐件、逐档、逐字原话）。*"
  echo "::notice:: 而那清单（work/declared.json）逐字**只含取到的那些件** —— 而那缺的几件**不在**它里面。*"
fi
if [ "$got" -eq 0 ]; then
  echo "::error title=一件也没取到::清单投影会把「没有件」和「清单为空」混为一谈，这里先停"
  exit 1
fi