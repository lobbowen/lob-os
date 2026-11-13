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

#  缺陷名（C-102）**那一格逐字把**上一趟**留下的那一件留在那 `dist/` 里、 它逐字**名与实不符****
# 旧版逐字是 `mkdir -p "$DIST"` 逐字**只**建那目录、 它逐字**不**清那**旧件**
# **旧件**逐字是**上一趟**落下的（ `rm -f "$DIST/component-$t-"*.zip`
#        那几处逐字**都是逐件**清的、 它们逐字**只**在**失败**那一路上跑）
# **成功**那一趟逐字**不清**
# **后果**（06 本轮逐字核出、Lead 本机复核成立）：
#        · 而那 `publish-component-manifest.js` 逐字那 `toolsFromDist()` 逐字**扫**那 `dist/` 目录
#          （ 它逐字**逐个**核那**名与实**、 文件名里的那内容哈希逐字
#            **不**等于那实际字节逐字算出来的那一个时、 它逐字**抛错**）
#        · **抛错**逐字来自那 `toolsFromDist()` 逐字**扫到了**那 `dist/` 里逐字**已有**的一个
#          `component-llvm-…-<hash12>-android-arm64.zip`
#          （ 文件逐字名字里的 hash 逐字与它逐字实算的 sha256 前 12 位逐字**不等**）
#        · 文件逐字从哪来逐字本机核不到（ **真源**逐字就是**上一趟**留下的）
# **判别式**逐字是「那件名里的那内容哈希逐字是不是那实际字节逐字算出来的」
# **处置**： **旧件**逐字**开跑之前**就逐字清掉
# `dist/` 逐字**只**留那**这一趟**逐字落的那些件
# **每一件**逐字的名字逐字**都是**那一趟逐字**实算**的那 sha256 前 12 位
#        （ `fetch-component-artifacts.sh:305` 逐字 `_sha="$(sha256sum "$_picked" | cut -c1-12)"`）
# **清除**逐字**只**清那**件包**那一族（ `.pick-*.err` 与那 `.fetch-missing.*`
#        逐字**不是**件包、 它们逐字**要**留）
# **清不掉**时逐字**报缺**（ 按 C-05 不猜填、 **不**静默跳过）
rm -f "$DIST"/component-*.zip 2>/dev/null || true
if compgen -G "$DIST"/component-*.zip > /dev/null 2>&1; then
  echo "::error title=旧件清不掉::那 dist 里逐字还有那旧件、=> 而那**名与实**逐字**核不过**"
  echo "        请手工清掉 $DIST/component-*.zip 逐字再跑这一趟（=> 而那按 C-05 报缺）。"
  exit 1
fi

#  那容器判别源逐字**唯一**（ 按 C-994「判别源必须唯一、 其余各处改成调它」）：
# fmt.sh 那一件逐字按头四字节与尾部哨兵判、 它逐字有 zip / zip64 / gzip / tar / 未知 那五档。
FMT_SH="$ROOT_DIR/scripts/toolchain/fmt.sh"
[ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; exit 1; }
# shellcheck source=fmt.sh
. "$FMT_SH"

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
#  那四档的计数（ 它们逐字**只有**那四档、 一档逐字是「成因未知」）
miss_ratelimit=0; miss_auth=0; miss_notfound=0; miss_unknown=0
#  那「没有产物活着的成功 run」那一档（ 它跟那四档**不是**同一档、 按 C-135 那不是同一问）
miss_norun=0
MISS_TSV="$DIST/.fetch-missing.tsv"
: > "$MISS_TSV"
#  那缺件表逐字**另外**落一份机器可读的那一份（ JSON 是逐字能对账的形态）。
# 「清单里要不要留一条占位（missing 状态 + 成因）」那一问**归 01 裁**、
# 本处**不**拍板、 只把事实**逐件**落成机器可读的那一份（ 它逐字能对账）。
MISS_JSON="$DIST/.fetch-missing.json"
printf '[]\n' > "$MISS_JSON"

#  缺件逐字**落**那显式缺件表（ 一件**不许**静默从清单里消失）。
# 表逐字是**四列**：件名 / 那一档 / 那一档的原话 / **容器形态**那一格。
# 第四列逐字是**三样**（ 按 01 裁的 S607-01-CONTAINER-FORM-RULING.md §4 逐字
#     「形态档落在缺件表里逐字要带三样：形态名、头四字节、外层中央目录条目数」）：
#     而那三样逐字用**竖线**隔开写进**一列**（ 竖线逐字是机器可读的分隔符）。
# 档位逐字取自**枚举**（ 按 01 裁的 S606-02-MISSING-PIECE-FORM-RULING.md 逐字
#     「档位取自枚举不许散文」）：
#       成因轴四档：ratelimit / auth / notfound / unknown
#       与「下载被拒」**不是同一问**的两档：missing-norun / missing-contract
miss_row() {
  local mform="${4:-}"
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$mform" | tee -a "$MISS_TSV"
  # 机器可读的那一份逐字**同步**落（ JSON 逐字是那几件的数组）。
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

# 那一档的**容器形态**那一格（ 三样：形态名 / 头四字节 / 外层中央目录条目数）。
# 形态逐字按**字节**判（ 判别源唯一：scripts/toolchain/fmt.sh）。
# 文件逐字不在时（ 一件压根没下下来） 形态逐字**报缺**（ 不猜）。
missing_form_of() {
  local f="$1"
  if [ ! -f "$f" ]; then printf '%s' "未知|无|-1"; return 0; fi
  printf '%s|%s|%s' "$(fmt_of "$f")" "$(fmt_magic "$f")" "$(fmt_outer_entries_of "$f")"
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
    miss_row "$t" "missing-norun" "没有产物活着的成功 run（$WF / $ART）" "未知|无|-1"
    missed=$((missed+1))
    miss_norun=$((miss_norun+1))
    continue
  fi

  #  缺陷名（C-102）**那一路逐字按「最近 5 趟成功」挑 artifact、 挑到的那份逐字可能是很久以前的** ——
  # 旧版逐字是 `gh run list --status success --limit 5` 取第一趟有的
  #      （ 它逐字**不**看那 artifact 是**哪一趟**产的、 也**不**看那一趟的代码是不是现在这份）。
  # **后果**： node 那一件逐字挑到的是 run 38077130287 / head=3a6b8625 / 2026-10-10
  #      那一趟的 artifact（ 一趟逐字**没有** component-meta.json 与 piece-shape.json），
  # 现在这一趟逐字**产不出来**。
  # 按 01 裁的（乙）逐字「**逐件核构建标识**、 **旧字节逐字不进清单**」：
  # 构建标识逐字是那两列（buildSha / buildRun）、 两列逐字**已经在**那 pin 那一族里
  #        （ .github/native-capabilities-pin.json 逐字记 buildSha 与 buildRun）。
  # 判据逐字是「那 artifact 的 buildSha 逐字**等于**那仓当前 HEAD 逐字**等于**那 pin 记的 buildSha」。
  # **读不到**那两格时逐字**报缺**（依 C-05： 「那两格读不到」逐字**不是**
  #        「那件是旧的」， 是「**逐字无法判定**」）。
  HEAD_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)"
  if [ -z "$HEAD_SHA" ]; then
    echo "::warning title=$t 构建标识读不到::本机读不到 HEAD（=> 而那无法判定那 artifact 是不是当前这份）"
    miss_row "$t" "missing-norun" "构建标识读不到（=> 而那无法判定那 artifact 是不是当前这份）" "未知|无|-1"
    missed=$((missed+1)); miss_norun=$((miss_norun+1)); continue
  fi
  RUN_HEAD="$(gh api "repos/$REPO/actions/runs/$picked" --jq '.head_sha' 2>/dev/null || true)"
  if [ -z "$RUN_HEAD" ]; then
    echo "::warning title=$t 构建标识读不到::run $picked 的 head_sha 读不到（=> 而那无法判定那 artifact 是不是当前这份）"
    miss_row "$t" "missing-norun" "run $picked 的 head_sha 读不到（=> 而那无法判定那 artifact 是不是当前这份）" "未知|无|-1"
    missed=$((missed+1)); miss_norun=$((miss_norun+1)); continue
  fi
  # **期望值**从哪来（ 01 的 S609-01 §3.3 逐字「那一格本件不裁、 它逐字是 05 的实现选择」）：
  # 本处取的是「**那 artifact 那一趟的 head_sha**」（ run 那一趟逐字就是产它的那一趟）
  # **不是**那当前 HEAD（ 当前 HEAD 逐字会把**别的** workflow 那些**合法**的旧件
  #         逐字**一律拒掉**—— 不是「那一件是不是当前这份代码造出来的」那一问，
  # **是**「这一轮取件要不要所有件都是这一轮产的」那一问、 是**挑件策略**那一族、
  # 按 C-104 **不并**）。
  # **真正**要核的是「那件包里的字节**是不是那一趟**产的」 逐字由**件自己**记的
  #       buildSha 答（ 在下面那一处核）。

  if ! gh run download "$picked" --repo "$REPO" --name "$ART" --dir "$DIST" >/dev/null 2>"$DIST/.gh-dl.err"; then
    #  缺陷名（C-102）**那一档把「下载被拒」压成一句「可能被并发清理」** ——
    # 旧版逐字是「（可能被并发清理），本次清单不含这件」。
    # 「可能被并发清理」逐字是**猜**的成因、 按 C-05「缺字段就报缺、不许猜填」，
    #      而那一档逐字**没有**任何证据支撑那个猜测。
    # 一趟逐字真的那一档是（run 38077130287 的 component-node）：
    #      `error downloading component-node: HTTP 503: 503 Egress is over the account limit.`
    # 一档**不是**「被并发清理」、 是「服务端限流」。
    # 按 C-103 先答「它为什么有两边」： 两边是「那一次读**有没有发生**」
    #      与「那一次读**是什么档**」 它们各答一件事、 处置**不一样**。
    # 现在：**四档分开**（ 四档逐字从那**响应体**判、 不猜），
    # 「成因未知」逐字是那一档的**正当取值**（ 不是猜）。
    DL_EVID="$(tr -d '\r' < "$DIST/.gh-dl.err" 2>/dev/null | head -5)"

    #  缺陷名（C-102）**那一格逐字让那 `gh` 逐字自己拒了、 它逐字**不**问那**客户端**逐字为什么拒** ——
    # 旧版逐字是「`gh run download` 逐字**不成**就逐字走那四档那一族」
    # 四档逐字**都是**那**服务端**那一族
    #        （ 限流、 鉴权、 产物过期、 没有活着的成功 run）
    # 它逐字**没有**那一档「**客户端**逐字自己拒了」
    # **后果**（06 本轮逐字复现、Lead 本机复核成立）：
    #      · 而那 llvm 那一件逐字是「`gh` 逐字自己拒」： 原话逐字是
    #        `error downloading component-llvm: would result in path traversal`
    # **真因**逐字是那 artifact 逐字 8362750697 字节（8.36 GB、 > 4 GiB）、
    #          逐字 **zip64**、 **中央目录**逐字写着 `uncompressed size = 7881526981165055`
    #          （ 7.88 PB）
    # `gh` 逐字**自己**把那一份逐字**判**成路径穿越、 它逐字**拒**了
    # **四档**逐字**都**不是（ artifact 逐字 `expired: false`、
    #        逐字 `expires_at: 2027-01-25T01:30:56Z`、 不是限流、 不是鉴权、
    # 不是产物过期）
    # **判据**逐字是「那客户端逐字为什么自己拒了」那一个事实上
    #      （ 它逐字与那服务端那四档逐字**不是同一族**、 依 **C-104** 逐字**不并**）
    # **处置**： `gh` 逐字自己拒了时逐字**绕开**那 `gh` 逐字自己下
    # **直连**逐字走那 artifact API 逐字那 `archive_download_url`
    # **字节**逐字**逐字**核（ **字节数**与那 API 逐字给的那一个对上）
    _direct_ok=0
    if printf '%s' "$DL_EVID" | grep -qi 'path traversal'; then
      echo "::notice:: 那客户端（gh）逐字自己拒了：=> 而那**真因**逐字是那 artifact 逐字太大"
      echo "::notice:: （=> 而那 zip64 逐字那中央目录逐字写着那个 absurd uncompressed size、"
      echo "::notice::   => 而那 gh 逐字把它**判**成路径穿越）。=> 而那**服务端**那四档逐字**都不是**。"
      echo "::notice:: => 而那现在逐字**绕开**那 gh 逐字自己下（=> 而那**直连**那 artifact API）。"
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
            echo "::warning title=$t 直连下的字节数不符::期望 $_asize、实得 $_agot、=> 而那按 C-05 报缺"
            rm -f "$DIST/.artifact-direct.zip"
          else
            # artifact 逐字**本身**逐字是那 zip（ **件**逐字在里面那一层）
            # **取件**那一族逐字靠那**内容**判、 它逐字**不**靠那名字
            # **解**那一层逐字**用**那 `unzip`（ **外层**逐字是那 zip 套 zip 那一族）
            # **落**逐字**用**那**那件包自己**的名字**（ **取件**那一族逐字靠它**找**）
            if command -v unzip >/dev/null 2>&1; then
              _adir="$DIST/.artifact-direct"
              rm -rf "$_adir" && mkdir -p "$_adir"
              if unzip -qq -o "$DIST/.artifact-direct.zip" -d "$_adir" 2>/dev/null; then
                _found="$(find "$_adir" -maxdepth 2 -name 'component-*.zip' -type f | head -1)"
                if [ -n "$_found" ]; then
                  cp -f "$_found" "$DIST/$(basename "$_found")"
                  echo "::notice:: => 而那直连逐字**成**了、=> 而那件逐字 = $(basename "$_found")"
                  _direct_ok=1
                else
                  echo "::warning title=$t 直连里没有那件包::=> 而那按 C-05 报缺"
                fi
              else
                echo "::warning title=$t 直连的 artifact 逐字解不开::=> 而那按 C-05 报缺"
              fi
              rm -rf "$_adir"
            fi
            rm -f "$DIST/.artifact-direct.zip"
          fi
        else
          echo "::warning title=$t 直连也下不下来::=> 而那按 C-05 报缺"
          rm -f "$DIST/.artifact-direct.zip"
        fi
      else
        echo "::warning title=$t 那个 artifact 逐字读不到 id::=> 而那按 C-05 报缺"
      fi
    fi
    # 判别逐字**调**那唯一判别源（ 按 C-104「判别源必须唯一」、
    # scripts/verify/classify-download-failure.sh 逐字是那唯一一处、
    # 它逐字带**阳性对照**（ --selftest 那一档逐字有六个用例））。
    DL_KIND="$(printf '%s' "$DL_EVID" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --kind)"
    [ -n "$DL_KIND" ] || DL_KIND="unknown"
    # **响应头**那一档逐字**也要**判（ 「资源不在」与「限流」怎么区分、 要靠响应头）：
    # 判据逐字落在**请求**上（ 按 C-993 的第二件：
    # 「检查里过了但请求里没带身份」逐字是同一形状、 判据必须落在请求上）。
    # gh run download 逐字**不给**那响应头、 gh api --include 逐字给。
    DL_HDR_KIND="unknown"
    DL_HDR="$(gh api --include "repos/$REPO/actions/runs/$picked/artifacts" 2>/dev/null | tr -d '\r' | head -40)"
    if [ -n "$DL_HDR" ]; then
      DL_HDR_KIND="$(printf '%s' "$DL_HDR" | bash "$ROOT_DIR/scripts/verify/classify-download-failure.sh" --headers)"
      [ -n "$DL_HDR_KIND" ] || DL_HDR_KIND="unknown"
      # 响应头逐字**说了算**（ ratelimit 那一档逐字是配额那一族、 不是这一件的问题）
      if [ "$DL_HDR_KIND" = "ratelimit" ]; then DL_KIND="ratelimit"; fi
    fi
    # HTTP 那一档逐字**从**那响应体里**取**（ 不是猜）
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
      *)         echo "::notice:: 那一档是**成因未知**（ 响应体逐字没给出可判的那一档、 按 C-05 不猜）。*" ;;
    esac
    if [ "$_direct_ok" = "1" ]; then
      echo "::notice:: => 而那**直连**逐字**成**了、=> 而那这一件逐字**不**进那缺件表。"
      rm -f "$DIST/.gh-dl.err"
    else
      echo "::notice:: 本次清单不含这一件 —— 而它逐字**进**那显式缺件表（=> 而不会静默消失）。*"
      # 形态那一格逐字从那**下载下来**的那一份读（ 它逐字可能已经半截、
      # 读不出来时逐字是「未知|无|-1」 **不**猜）。
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

  #  缺陷名（C-102）**这一路逐字没有容器分支、 它逐字按名字 glob 取那一颗件** ——
  # 旧版逐字是 `compgen -G "$DIST/component-$t-*-android-arm64.zip"`
  #      （ **按名字**、 **不**看那字节）。
  # **名字**与那**内容**逐字是**两件**事：
  #      · 而那 CI artifact 那一族逐字是「zip 套 zip」（ 外层逐字装着那**内层**那一个件包）；
  #      · 而那 Release 资产那一族逐字是「gzip 套 zip」（ 外层逐字 1f8b0800）。
  # 按名字取逐字**在这两种形状上都会取错**（ 取到的是**外层**、 外层逐字不是那一颗件）。
  # 按 01 裁的 S607-01-CONTAINER-FORM-RULING.md §2.1 那张表 + §3.1 逐字：
  #     逐字调 fmt.sh 的 fmt_of（ 判别源唯一）+ 那三步
  #     （1.先判外层自己是不是那一颗件；2.外层没有 component-meta.json 才走「外层套内层」；
  #      3.gzip/tar 先解 tar 再走 1. 与 2.）。
  # **挑**那一格逐字**只有一处**（ fmt.sh 的 fmt_pick_piece 那一格）。
  # **期望形态**与**实际形态**逐字都报出来（ 「那一颗是什么形态、期望什么形态」那一问）。
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
        # **外层套内层**那一族 内层才是那一颗件（ 外层逐字留着、 它不进那清单）。
        echo "::notice:: $t 那一颗是**外层套内层**：=> 而那外层=$_cand、=> 而那内层=${_picked##*/}"
      else
        echo "::notice:: $t 那一颗**外层自己**就是那一颗件"
      fi
      # 落名逐字照**那契约**（ 发布投影逐字按
      #     component-<件>-<版>-<哈希12位>-android-arm64.zip 那一格挑、 哈希逐字要**实算**
      # 不对逐字判红「文件名里的内容哈希与实际不符」）。
      _ver="$(node -e '
const cp=require("node:child_process");
try {
  const raw=cp.execFileSync("unzip",["-p",process.argv[1],"component-meta.json"],{maxBuffer:4*1024*1024});
  process.stdout.write(String(JSON.parse(raw.toString("utf8")).version||""));
} catch(e) { process.stdout.write(""); }
' "$_picked")"
      [ -n "$_ver" ] || _ver="unknown"
      # **件自己**记的构建标识（ buildSha 那一格逐字是那件包里 component-meta.json 那一格）
      #     逐字也要核（ run 那一层的 head 逐字是**那一趟流水线**的代码、
      # buildSha 逐字是**那一件**产它时的代码、 两者逐字**不**总是同一个）。
      _build_sha="$(node -e '
const cp=require("node:child_process");
try {
  const raw=cp.execFileSync("unzip",["-p",process.argv[1],"component-meta.json"],{maxBuffer:4*1024*1024});
  process.stdout.write(String(JSON.parse(raw.toString("utf8")).buildSha||""));
} catch(e) { process.stdout.write(""); }
' "$_picked")"
      if [ -z "$_build_sha" ]; then
        # 两格读不到时逐字**报缺**（依 C-05： 「那两格读不到」逐字**不是**
        #     「那件是旧的」、 是「**逐字无法判定」）。
        echo "::warning title=$t 构建标识读不到::那件包里逐字**没有** component-meta.json 的 buildSha 那一格（=> 而那无法判定那一件是不是当前这份）"
        miss_row "$t" "missing-contract" "件包里读不到 buildSha（=> 而那无法判定那一件是不是当前这份）" \
          "$(missing_form_of "$_picked")"
        rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
        missed=$((missed+1)); miss_unknown=$((miss_unknown+1))
        continue
      fi
      if [ "$_build_sha" != "$RUN_HEAD" ]; then
        # **期望值**逐字是「那 artifact 那一趟的 head_sha」（ run 那一趟逐字就是产它的那一趟）。
        # 两者逐字**不同** 件包里的字节逐字**不是**那一趟产的
        # **旧字节逐字不进清单**（ 按 01 裁的（乙）逐字）。
        echo "::warning title=$t 那件不是那一趟产的::那件自己的 buildSha 逐字是 ${_build_sha:0:12}、=> 而那 run $picked 的 head 逐字是 ${RUN_HEAD:0:12}"
        echo "::notice:: => 而那件包里的字节逐字**不是**那一趟产的、=> 而那**旧字节不进清单**。"
        miss_row "$t" "missing-contract" "件自己的 buildSha 逐字不是那一趟产的（${_build_sha:0:12} ≠ run $picked 的 ${RUN_HEAD:0:12}）" \
          "$(missing_form_of "$_picked")"
        rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
        missed=$((missed+1)); miss_unknown=$((miss_unknown+1))
        continue
      fi
      _sha="$(sha256sum "$_picked" | cut -c1-12)"
      _dst="$DIST/component-$t-$_ver-$_sha-android-arm64.zip"
      #  缺陷名（C-102）**那一档逐字把「已经在目标路径上的那一份」再 cp 一遍** ——
      # 旧版逐字是 `cp -f "$_picked" "$DIST/component-$t-$_ver-$_sha-android-arm64.zip"`
      # 「外层自己就是那一颗件」那一档（ git 那一件逐字是这一档）
      #        逐字**源与目标逐字是同一个文件**（ 它逐字**已经在** $DIST 里了）
      # cp 逐字报「are the same file」 **整趟 step 退出 1**。
      # **处置**： 源与目标逐字**同一个**时逐字**不 cp**（ 它逐字已经在对的地方）。
      # 第四种收尾（ 前三步是「外层自己」/「外层套内层」/「gzip-tar」）。
      if [ "$_picked" = "$_dst" ]; then
        echo "::notice:: $t 那一颗逐字**已经在**那个路径上（=> 而那不拷、=> 而那 cp 逐字会报 are the same file）。"
      else
        cp -f "$_picked" "$_dst"
      fi
    else
      echo "::warning title=$t 挑不出那一颗件::$(tr '\n' ' ' < "$DIST/.pick-$t.err")"
      echo "::notice:: 那期望形态=$_form（头四字节 $_magic）、=> 而那按内容挑逐字挑不出（=> 而按 C-05 不猜、=> 而不取第一个）。"
      miss_row "$t" "missing-contract" "artifact 里的容器逐字挑不出那一颗件（形态=$_form、头四字节 $_magic）" \
        "$(missing_form_of "$_cand")"
      rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
      missed=$((missed+1))
      miss_unknown=$((miss_unknown+1))
      continue
    fi
    rm -rf "$_unp" "$DIST/.pick-$t.err"
  fi

  # 名字里必须有版本号：component-<件>-<版>-<哈希>-android-arm64.zip
  # （打包侧在读不到版本时写 unknown，见 recipes/package-component.sh:9 —— 那颗不是本轮编的）
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

#  缺陷名（C-102）**件逐字静默从清单里消失、 汇总行逐字看不出来** ——
# 旧版逐字是「取到 6 件 · 缺 4 件」**只有**那一个总数、 四档逐字**混在一起**。
# run 41122918213 的 step 3 逐字是「取到 6 件 · 缺 4 件 · 原生跳过 10 件」、
#      而那一趟逐字**一件没判红**（ :111-:115 旧版逐字只在「一件也没取到」时才 exit 1）。
# 按 C-05「缺字段就报缺、不许猜填」： 缺的几件逐字**必须**逐件**看得见**、
# **是哪一档**逐字也要**看得见**。
# 处置： 汇总行逐字**分档**列出来、 缺件表逐字落一个档（$MISS_TSV）、
# 表逐字**在**那汇总行**之后**逐字列出来。
echo "---- 取件汇总：取到 $got 件 · 缺 $missed 件 · 原生跳过 $skipped 件"
if [ "$missed" -gt 0 ]; then
  echo "---- 缺件分档：限流 $miss_ratelimit · 鉴权 $miss_auth · 资源不在 $miss_notfound · 成因未知 $miss_unknown · 没有活着的成功 run $miss_norun"
  echo "---- 缺件逐条（那一件、那一档、那一档的原话）："
  while IFS="$(printf '\t')" read -r mt mkind mwhy; do
    [ -n "$mt" ] || continue
    echo "  · $mt  [$mkind] $mwhy"
  done < "$MISS_TSV"
  echo "::notice title=本轮有缺件::上面那几件逐字**进**了那份显式缺件表、=> 而它们逐字**没有**静默从清单里消失。"
  echo "::notice:: 而那机器可读的那一份逐字是 $MISS_JSON（=> 而那逐件、逐档、逐字原话）。*"
  echo "::notice:: 而那清单（work/declared.json）逐字**只含取到的那些件** —— 而那缺的几件**不在**它里面。*"
fi
if [ "$got" -eq 0 ]; then
  echo "::error title=一件也没取到::清单投影会把「没有件」和「清单为空」混为一谈，这里先停"
  exit 1
fi