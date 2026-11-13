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

#  缺陷名 C-102：旧版 `mkdir -p "$DIST"` 只建目录、不清旧件，上一趟留下的件留在 dist/ 里、名与实不符
#  （`rm -f "$DIST/component-$t-"*.zip` 那几处逐件清、只在失败那一路跑，成功那趟不清）。
#  后果（06 核出、Lead 复核）：publish-component-manifest.js 的 toolsFromDist() 扫 dist/ 并按名与实核对，名字里的
#  内容哈希不等于实算就抛错（component-llvm-…-<hash12>-android-arm64.zip 里 hash 与实算 sha256 前 12 位不等）。
#  处置：开跑之前清旧件，dist/ 只留这一趟落的件、名字都是那一趟实算的 sha256 前 12 位（:305）；
#  只清件包那一族（.pick-*.err 与 .fetch-missing.* 要留），清不掉时报缺（C-05，不猜填、不静默跳过）。
rm -f "$DIST"/component-*.zip 2>/dev/null || true
if compgen -G "$DIST"/component-*.zip > /dev/null 2>&1; then
  echo "::error title=旧件清不掉::那 dist 里逐字还有那旧件、**名与实**逐字**核不过**"
  echo "        请手工清掉 $DIST/component-*.zip 逐字再跑这一趟（按 C-05 报缺）。"
  exit 1
fi

#  容器判别源唯一（按 C-994）：fmt.sh 按头四字节与尾部哨兵判，有 zip / zip64 / gzip / tar / 未知 五档。
FMT_SH="$ROOT_DIR/scripts/toolchain/fmt.sh"
[ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; exit 1; }
# shellcheck source=fmt.sh
. "$FMT_SH"

COMPONENTS="$(node scripts/registry/list-bucket-components.js)"
[ -n "${COMPONENTS// /}" ] || { echo "::error title=件清单为空::bucket_for 没解析出任何件"; exit 1; }

# base / apkonly 筐的件随 APK 内置，控制面板拿不到它们、也不该在清单里。
# 那筐的判据在 cache-key.sh 的 bucket_for 里（唯一真相），这里直接问它、不另抄一份。
# 此前这里写死三个（bash rg busybox），于是 curl / zlib / openssl / crypto / jq / flock / posix / ptyprobe /
# ptysession 全被当成待取的件，脚本转头去找 build-curl.yml —— 那个文件不存在，于是 404，
# warning 却写「没有成功 run 」，把「文件名错」说成了「没编出来」。而且 rg 也不对：件名是 ripgrep。
NATIVE="$(for t in $(node scripts/registry/list-bucket-components.js); do
  b="$(bash scripts/toolchain/cache-key.sh tag "$t" 2>/dev/null || true)"
  case "$b" in base-*|apkonly-*) printf '%s ' "$t" ;; esac
done)"

got=0; missed=0; skipped=0
#  四档计数（只有这四档，其中一档是「成因未知」）。
miss_ratelimit=0; miss_auth=0; miss_notfound=0; miss_unknown=0
#  「没有产物活着的成功 run」那一档跟那四档不是同一档（按 C-135 不是同一问）。
miss_norun=0
MISS_TSV="$DIST/.fetch-missing.tsv"
: > "$MISS_TSV"
#  缺件表另外落一份机器可读的（JSON 能对账）。「清单里要不要留一条占位（missing 状态 + 成因）」归 01 裁，
# 本处不拍板、只把事实逐件落成能对账的那一份。
MISS_JSON="$DIST/.fetch-missing.json"
printf '[]\n' > "$MISS_JSON"

#  缺件落显式缺件表（一件不许静默从清单里消失）。表是四列：件名 / 档位 / 档位原话 / 容器形态。
#  第四列是三样（按 S607-01-CONTAINER-FORM-RULING.md §4：「形态档落在缺件表里要带形态名、头四字节、外层中央目录条目数」），
#  三样用竖线隔开写进一列（竖线是机器可读分隔符）。档位取自枚举（按 S606-02-MISSING-PIECE-FORM-RULING.md）：
#  成因轴四档 ratelimit / auth / notfound / unknown；另有 missing-norun / missing-contract 两档，与「下载被拒」不是同一问。
miss_row() {
  local mform="${4:-}"
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$mform" | tee -a "$MISS_TSV"
  # 机器可读的那份同步落（JSON 是那几件的数组）。
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

# 容器形态那一格（三样：形态名 / 头四字节 / 外层中央目录条目数）。形态按字节判（判别源唯一：fmt.sh）。
# 文件不在时（压根没下下来）报缺，不猜。
missing_form_of() {
  local f="$1"
  if [ ! -f "$f" ]; then printf '%s' "未知|无|-1"; return 0; fi
  printf '%s|%s|%s' "$(fmt_of "$f")" "$(fmt_magic "$f")" "$(fmt_outer_entries_of "$f")"
}

for t in $COMPONENTS; do
  case " $NATIVE " in
    *" $t "*) echo -e "$t\tskipped-native\t随 APK 内置，不在控制面板清单里"; skipped=$((skipped+1)); continue ;;
  esac

  # 件名 → workflow 文件名。不是 build-<件名>.yml 就对不上——六个件的 workflow 文件另有名字
  # （rg / pkg-config / sqlite3 / python3 / llvmtoolchain）；此前一律按 build-<件名>.yml 拼，于是 gh 收到 404，
  # warning 说「没有成功 run」，把文件名错说成了「没编出来」——两回事。
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
    miss_row "$t" "missing-norun" "没有产物活着的成功 run（$WF / $ART）" "未知|无|-1"
    missed=$((missed+1))
    miss_norun=$((miss_norun+1))
    continue
  fi

  #  缺陷名 C-102：旧版按「最近 5 趟成功」挑 artifact（`gh run list --status success --limit 5` 取第一趟有的），
  #  既不看 artifact 是哪一趟产的、也不看那一趟的代码是不是现在这份。后果：node 那一件挑到 run 38077130287 /
  #  head=3a6b8625 / 2026-10-10 的 artifact（没有 component-meta.json 与 piece-shape.json），现在这一趟产不出来。
  #  按 01 裁的（乙）「逐件核构建标识、旧字节不进清单」：构建标识是 buildSha / buildRun，已记在
  #  .github/native-capabilities-pin.json；判据是 artifact 的 buildSha 等于仓当前 HEAD 等于 pin 记的 buildSha。
  #  读不到那两格时报缺（C-05：「读不到」不是「那件是旧的」，是「无法判定」）。
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
  # 期望值从哪来（01 的 S609-01 §3.3：「那一格本件不裁、是 05 的实现选择」）：本处取 artifact 那一趟的 head_sha
  # （run 那一趟就是产它的那一趟），不是当前 HEAD（当前 HEAD 会把别的 workflow 那些合法的旧件一律拒掉，
  # 那问的是「这一轮取件要不要所有件都是这一轮产的」，属挑件策略，按 C-104 不并）。
  # 真正要核的是「件包里的字节是不是那一趟产的」，由件自己记的 buildSha 答（在下面那一处核）。

  if ! gh run download "$picked" --repo "$REPO" --name "$ART" --dir "$DIST" >/dev/null 2>"$DIST/.gh-dl.err"; then
    #  缺陷名 C-102：旧版把「下载被拒」压成一句「（可能被并发清理）」，那是猜的成因（C-05 不许猜填），没有任何证据。
    #  真的一档是 run 38077130287 的 component-node：`HTTP 503: 503 Egress is over the account limit.`，
    #  不是「被并发清理」，是服务端限流。按 C-103 两边：「那一次读有没有发生」与「那一次读是什么档」各答一件事、处置不同。
    #  现在四档分开（从那响应体判、不猜），「成因未知」是正当取值。
    DL_EVID="$(tr -d '\r' < "$DIST/.gh-dl.err" 2>/dev/null | head -5)"

    #  缺陷名 C-102：旧版只走那四档（都是服务端那一族），没有「客户端自己拒了」这一档。
    #  后果（06 复现、Lead 复核）：llvm 那一件是 gh 自己拒——`error downloading component-llvm: would result in path traversal`。
    #  真因是 artifact 8362750697 字节（8.36 GB、> 4 GiB）、zip64，中央目录写着 uncompressed size 7881526981155055，
    #  gh 判成路径穿越、拒了；四档都不是（expired: false、expires_at 2027-01-25T01:30:56Z、不限流不鉴权不过期）。
    #  判据：客户端为什么自己拒了（与服务端四档不是同一族，按 C-104 不并）。
    #  处置：绕开 gh 直连 artifact API 的 archive_download_url，字节数按 API 给的对上。
    _direct_ok=0
    if printf '%s' "$DL_EVID" | grep -qi 'path traversal'; then
      echo "::notice:: 那客户端（gh）逐字自己拒了：**真因**逐字是那 artifact 逐字太大"
      echo "::notice:: （ zip64 逐字那中央目录逐字写着那个 absurd uncompressed size、"
      echo "::notice::    gh 逐字把它**判**成路径穿越）。**服务端**那四档逐字**都不是**。"
      echo "::notice:: 现在逐字**绕开**那 gh 逐字自己下（**直连**那 artifact API）。"
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
            # artifact 本身是 zip，件在里面那一层；取件靠内容判、不靠名字。解那层用 unzip，
            # 落用那件包自己的名字（取件靠它找）。
            if command -v unzip >/dev/null 2>&1; then
              _adir="$DIST/.artifact-direct"
              rm -rf "$_adir" && mkdir -p "$_adir"
              if unzip -qq -o "$DIST/.artifact-direct.zip" -d "$_adir" 2>/dev/null; then
                _found="$(find "$_adir" -maxdepth 2 -name 'component-*.zip' -type f | head -1)"
                if [ -n "$_found" ]; then
                  cp -f "$_found" "$DIST/$(basename "$_found")"
                  echo "::notice:: 直连逐字**成**了、件逐字 = $(basename "$_found")"
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
    # 判别调唯一判别源（按 C-104「判别源必须唯一」）：scripts/verify/classify-download-failure.sh，
    # 它带阳性对照（--selftest 六个用例）。
    DL_KIND="$(printf '%s' "$DL_EVID" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --kind)"
    [ -n "$DL_KIND" ] || DL_KIND="unknown"
    # 响应头那一档也要判（「资源不在」与「限流」怎么区分要靠响应头）。判据落在请求上（按 C-993 第二件：
    # 「检查里过了但请求里没带身份」是同一形状）。gh run download 不给响应头，gh api --include 给。
    DL_HDR_KIND="unknown"
    DL_HDR="$(gh api --include "repos/$REPO/actions/runs/$picked/artifacts" 2>/dev/null | tr -d '\r' | head -40)"
    if [ -n "$DL_HDR" ]; then
      DL_HDR_KIND="$(printf '%s' "$DL_HDR" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --headers)"
      [ -n "$DL_HDR_KIND" ] || DL_HDR_KIND="unknown"
      # 响应头说了算（ratelimit 是配额那一族、不是这一件的问题）。
      if [ "$DL_HDR_KIND" = "ratelimit" ]; then DL_KIND="ratelimit"; fi
    fi
    # HTTP 档从响应体里取（不是猜）。
    DL_HTTP="$(printf '%s' "$DL_EVID" | sed -n 's/.*HTTP \([0-9][0-9][0-9]\).*/\1/p' | head -1)"
    [ -n "$DL_HTTP" ] || DL_HTTP="none"
    rm -f "$DIST/.gh-dl.err"
    echo "::warning title=$t 下载被拒（$DL_KIND）::run $picked 的 $ART 没能下下来 —— HTTP $DL_HTTP、档位 $DL_KIND。"
    echo "::notice:: 那响应体逐字是：${DL_EVID:-（空）}"
    echo "::notice:: 那响应头那一档逐字是：${DL_HDR_KIND}（判据逐字落在**请求**上）"
    case "$DL_KIND" in
      ratelimit) echo "::notice:: 那一档是**服务端限流**（不是这一件的问题、是配额那一族）。*" ;;
      auth)      echo "::notice:: 那一档是**鉴权不对**（令牌逐字没被认、不是这一件的问题）。*" ;;
      notfound)  echo "::notice:: 那一档是**资源不在**（ artifact 已删或已过保留期、不是这一件的问题）。*" ;;
      *)         echo "::notice:: 那一档是**成因未知**（ 响应体逐字没给出可判的那一档、 按 C-05 不猜）。*" ;;
    esac
    if [ "$_direct_ok" = "1" ]; then
      echo "::notice:: **直连**逐字**成**了、这一件逐字**不**进那缺件表。"
      rm -f "$DIST/.gh-dl.err"
    else
      echo "::notice:: 本次清单不含这一件 —— 而它逐字**进**那显式缺件表（不会静默消失）。*"
      # 形态那一格从下载下来的那份读（可能已经半截，读不出来时是「未知|无|-1」，不猜）。
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

  #  缺陷名 C-102：这一路没有容器分支、按名字 glob 取那一颗件（旧版 `compgen -G "$DIST/component-$t-*-android-arm64.zip"`），
  #  按名字、不看字节。名字与内容是两件事：CI artifact 是「zip 套 zip」（外层装着内层那一个件包），
  #  Release 资产是「gzip 套 zip」（外层 1f8b0800）。按名字取在这两种形状上都会取错（取到外层、外层不是那一颗件）。
  #  按 S607-01-CONTAINER-FORM-RULING.md §2.1 那张表 + §3.1：调 fmt.sh 的 fmt_of（判别源唯一）+ 三步
  #  （1. 先判外层自己是不是那一颗件；2. 外层没有 component-meta.json 才走「外层套内层」；3. gzip/tar 先解 tar 再走 1 与 2）。
  #  挑那一格只有一处（fmt.sh 的 fmt_pick_piece），期望形态与实际形态都报出来。
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
        # 外层套内层那一族：内层才是那一颗件（外层留着、不进清单）。
        echo "::notice:: $t 那一颗是**外层套内层**：外层=$_cand、内层=${_picked##*/}"
      else
        echo "::notice:: $t 那一颗**外层自己**就是那一颗件"
      fi
      # 落名照契约（发布投影按 component-<件>-<版>-<哈希12位>-android-arm64.zip 挑、哈希要实算，
      # 不对就判红「文件名里的内容哈希与实际不符」）。
      _ver="$(node -e '
const cp=require("node:child_process");
try {
  const raw=cp.execFileSync("unzip",["-p",process.argv[1],"component-meta.json"],{maxBuffer:4*1024*1024});
  process.stdout.write(String(JSON.parse(raw.toString("utf8")).version||""));
} catch(e) { process.stdout.write(""); }
' "$_picked")"
      [ -n "$_ver" ] || _ver="unknown"
      # 件自己记的构建标识（件包里 component-meta.json 的 buildSha）也要核：run 那层的 head 是那一趟流水线的代码，
      # buildSha 是那一件产它时的代码，两者不总是同一个。
      _build_sha="$(node -e '
const cp=require("node:child_process");
try {
  const raw=cp.execFileSync("unzip",["-p",process.argv[1],"component-meta.json"],{maxBuffer:4*1024*1024});
  process.stdout.write(String(JSON.parse(raw.toString("utf8")).buildSha||""));
} catch(e) { process.stdout.write(""); }
' "$_picked")"
      if [ -z "$_build_sha" ]; then
        # 两格读不到时报缺（C-05：「读不到」不是「那件是旧的」，是「无法判定」）。
        echo "::warning title=$t 构建标识读不到::那件包里逐字**没有** component-meta.json 的 buildSha 那一格（无法判定那一件是不是当前这份）"
        miss_row "$t" "missing-contract" "件包里读不到 buildSha（无法判定那一件是不是当前这份）" \
          "$(missing_form_of "$_picked")"
        rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
        missed=$((missed+1)); miss_unknown=$((miss_unknown+1))
        continue
      fi
      if [ "$_build_sha" != "$RUN_HEAD" ]; then
        # 期望值是 artifact 那一趟的 head_sha（run 那一趟就是产它的那一趟）；两者不同就说明字节不是那一趟产的，
        # 旧字节不进清单（按 01 裁的（乙））。
        echo "::warning title=$t 那件不是那一趟产的::那件自己的 buildSha 逐字是 ${_build_sha:0:12}、 run $picked 的 head 逐字是 ${RUN_HEAD:0:12}"
        echo "::notice:: 件包里的字节逐字**不是**那一趟产的、**旧字节不进清单**。"
        miss_row "$t" "missing-contract" "件自己的 buildSha 逐字不是那一趟产的（${_build_sha:0:12} ≠ run $picked 的 ${RUN_HEAD:0:12}）" \
          "$(missing_form_of "$_picked")"
        rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
        missed=$((missed+1)); miss_unknown=$((miss_unknown+1))
        continue
      fi
      _sha="$(sha256sum "$_picked" | cut -c1-12)"
      _dst="$DIST/component-$t-$_ver-$_sha-android-arm64.zip"
      #  缺陷名 C-102：旧版对「已经在目标路径上的那一份」又 cp 一遍——旧版是
      #  `cp -f "$_picked" "$DIST/component-$t-$_ver-$_sha-android-arm64.zip"`，而「外层自己就是那一颗件」那一档
      #  （git 那一件）源与目标是同一个文件（它已经在 $DIST 里），cp 报「are the same file」、整趟 step 退出 1。
      #  处置：源与目标同一个时不 cp。这是第四种收尾（前三步是「外层自己」/「外层套内层」/「gzip-tar」）。
      if [ "$_picked" = "$_dst" ]; then
        echo "::notice:: $t 那一颗逐字**已经在**那个路径上（不拷、 cp 逐字会报 are the same file）。"
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

  # 名字里必须有版本号：component-<件>-<版>-<哈希>-android-arm64.zip（打包侧读不到版本时写 unknown，见
  # recipes/package-component.sh:9，那颗不是本轮编的）。
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

#  缺陷名 C-102：件静默从清单里消失、汇总行看不出来。旧版只有「取到 6 件 · 缺 4 件」一个总数，四档混在一起。
#  run 41122918213 的 step 3 是「取到 6 件 · 缺 4 件 · 原生跳过 10 件」，那一趟一件没判红（旧版只在「一件也没取到」时 exit 1）。
#  按 C-05：缺的几件必须逐件看得见，是哪一档也要看得见。
#  处置：汇总行分档列出来，缺件表落一个档（$MISS_TSV），表在汇总行之后列出来。
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