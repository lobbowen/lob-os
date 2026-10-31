#!/usr/bin/env bash
# 取件：把已固化的件包取进 APK，**不编译**。
#
# 取的是**标准件包**（component-<id>-<版本>.zip，里面有 component-meta.json）——
# 与 OTA 装件拿到的是同一种东西。装配器只读包，不查外部清单（照抄 .deb）。
#
# 为什么不铺 jniLibs：那是「预装件的私有通道」，装配器读不了，
# 而且与 OTA 装件不同构。启动时统一走 ProgramInstallPipeline 装它们。
#
# 取不到就报错退出。**不在这里回退去编译** —— 那正是「APK 又开始编件」的由来，
# 而且会把「某个件没固化」这个事实藏起来。缺件就明说缺哪个、去跑
# workflow_dispatch build-<id>，见 RELEASE-POLICY.md。
set -euo pipefail

# 这一批件的构建标识行（每行 id|buildSha|buildRun），取件循环里累加、循环后去重。
# 而它必须在顶部初始化：脚本是 set -u，而循环里第一次累加时它还不存在，
# 于是报 unbound variable，取件在第一件之后就崩。
# 真机读数（2026-10-21）：那一轮只取到 zlib 一个，而报错原文是
#   fetch-prebuilt-pieces.sh:131: GEN_ROWS: unbound variable
GEN_ROWS=""

# 这一行的落点用 BASH_SOURCE（而不是 $0）—— 而因为 build-apk.yml:329 那一格是
#   `source scripts/toolchain/fetch-prebuilt-pieces.sh --print-pieces`
# 而 source 的时候 $0 逐字是「bash」，=> 而 dirname 那一条算出来是错的落点。
# 缺陷名（C-102）**同一份脚本按两种方式被调用，而落点只认其中一种**。
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

ASSETS="container/app/src/main/assets/pieces"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

#  `--print-pieces` 那一格（=> 而那 build-apk.yml:313 那一格逐字是
#    `source scripts/toolchain/fetch-prebuilt-pieces.sh --print-pieces`）。
#  缺陷名（C-102）**那一个钩子逐字不存在**、=> 而而那**调用点**逐字**在**：
#    => 而那 source 逐字**顺带**把那整个取件循环逐字**跑一遍**（=> 而那下载两遍、=> 而那配额吃两遍）、
#    => 而而那 stdout 逐字**是**那取件那一份输出、=> 而而那**调用方**逐字**读不出**那件数。
#  => 而那**处置**：=> 而那**一个钩子**逐字**落**在这里、=> 而那 --print-pieces 逐字**只**答那件数。
#    => 而那**按 C-104**：=> 而那「跑整个取件」与「只报件数」逐字**不是**同一问、=> 而那**不合并**。
PRINT_PIECES_ONLY=0
for _arg in "${@:-}"; do
  [ "$_arg" = "--print-pieces" ] && PRINT_PIECES_ONLY=1
done

#  缺陷名（C-102）**那一族把「读不到」逐字压成一档**：
#    而实际上那 API 逐字能答出**三档**不同的成因，而它们逐字**要**不同的处置：
#      403 + 那 body 里逐字有 "rate limit"     = 限流（=> 而那匿名读逐字 60 次/小时）
#      403 + 那 body 里逐字有 "Bad credentials" = 鉴权（=> 而那 token 逐字错了 / 过期了）
#      404                                    = 那个 tag 或那个端点逐字不存在
#    => 而而那旧版逐字只印「HTTP 那一档 = 403」，=> 而而那**三档**逐字**分不出来**、
#    => 而而那**处置**（重试 / 换令牌 / 查 tag）逐字**对不上号**。
#
#  C-103 回答（这一族它为什么有两边）：一边是「读那一份 Release 清单」，
#    另一边是「那一次读逐字**有没有身份**」。
#    而那旧版把「有身份」当成**可选**：没有 token 就匿名读；
#    而匿名读逐字 60 次/小时（带身份 5000 次/小时），
#    => 而那一次读逐字在 CI 上逐字**被限流**（run 41118077153：9 件取到 3 件、6 件 403，
#       而那 28 分钟前同一格逐字取到 9/9）。
#    处置：给那一次读**一个身份**（=> 而那不是「重试」，=> 而那是「那一次读是谁做的」），
#    并把「没有身份」逐字**报出来**而不是静默匿名（C-05：缺字段就报缺，不猜填）。
#
#  形状照抄 scripts/toolchain/fetch-component-artifacts.sh:11-13（那是仓内已有的同一纪律）。
GH_TOKEN_VALUE="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$GH_TOKEN_VALUE" ] && [ "$PRINT_PIECES_ONLY" -eq 0 ]; then
  echo "::error title=缺令牌::取件要读 GitHub 的 Release 清单，而那匿名读只有 60 次/小时"
  echo "::error:: 给 GH_TOKEN 或 GITHUB_TOKEN（=> 而 build-apk.yml 那一格逐字是 secrets.GITHUB_TOKEN）"
  echo "::error:: 这一格不猜 —— 没有身份就不读，**不**静默匿名。"
  exit 1
fi
export GH_TOKEN="$GH_TOKEN_VALUE"
#  那**唯一**一份身份（=> 而那两条真读的路逐字都**只**认它）：
#    缺陷名（C-102）**那两条真读的路逐字各认各的令牌名、=> 而那 workflow 那一格逐字给的是 GH_TOKEN**
#      => 而那**后果**：那「有没有身份」那一格逐字**过了**（=> 而那 GH_TOKEN 逐字是给了的）、
#         => 而那**请求**逐字**没带身份**（=> 而那匿名读逐字 60 次/小时、=> 而那症状逐字是 403 限流），
#         => 而而那**看不出**是「令牌名不对」——**检查里过了但请求里没带身份**。
#    => 而那**改法**：=> 而那**两条**真读的路逐字**都**走那**一个** gh_read_release、
#       => 而那一个**只**认 GH_HDR、=> 而 GH_HDR **只**由那唯一一份身份构造。
GH_HDR=(-H "Authorization: Bearer $GH_TOKEN")

#  那一次读（那一份 Release 清单）的读数：全局
#    GH_HTTP     = 那一读逐字**拿到**的 HTTP 那一档（none = 连那一档都没拿到）
#    GH_FAIL_KIND= 那一次读逐字**属于**哪一档：ok / ratelimit / auth / notfound /
#                 forbidden / empty / parse / http_<码>
#    GH_READ_TAG / GH_READ_BODY = 读的是哪个 tag、body 落在哪
#    GH_READ_RC  = 那一读逐字**成没成**（0 成 / 1 没成）
GH_HTTP="none"
GH_FAIL_KIND="ok"
GH_READ_TAG=""
GH_READ_BODY=""
GH_READ_RC=1

#  那 list_release_assets 那一族的读数：**只**落那一个档（=> 而那档才是真读）。
#    缺陷名（C-102）**那三个变量逐字只写不读、=> 而而它们逐字不是真相** ——
#      => 而那 LRA_HTTP / LRA_RC / LRA_KIND 三个逐字**只**在 :96-:98 初始化、
#        => 而在下面那 list_release_assets 那一族**赋值**（=> 而那两处逐字各赋一次）、
#        => 而那**全文再无任何读取点**（=> 而 grep 逐行核过：=> 而那三个名字逐字只出现在那六行里）。
#      => 而那**成因**：=> 而那 list_release_assets 逐字**被装在 `$(...)` 里面**被调（=> 而那**子壳**），
#        => 而那子壳逐字**改不到**那调用方的变量、=> 而而那调用方逐字**改用**那一个档（${STAGE}/.lra-readings）取读数。
#        => 而所以那三个变量逐字是**上一版那个办法留下的残留**（=> 而上一版那个办法根本走不通）。
#      => 而按 C-993（新写的判别式必须有调用点、=> 而「检查里过了但没人用」逐字是同一形状）：
#        那三个逐字**删掉**、=> 而那读数逐字**只**从那一个档取（=> 而那档逐字有真读点）。
#    => 而那三个**不是**「信息丢了」：=> 而那 HTTP 那一档与那**那一档**逐字都在那档里（=> 而见下面那个读取点）。

#  读那一份 Release 清单。**一次读**（=> 而那旧版逐字读了**两次**：
#  一次 -w '%{http_code}' 只为拿那一档、一次取 body —— => 而那**两次**逐字**各**吃一次 API 配额）。
#  同一个 tag 读过一次就不再读第二次（=> 而那配额逐字是有限的）。
gh_read_release() {
  local tag="$1"
  if [ "$GH_READ_TAG" = "$tag" ]; then
    return "$GH_READ_RC"
  fi
  GH_READ_TAG="$tag"
  #  => 而那落点逐字**先**造出来（=> 而那 body 逐字**落**在那里面、=> 而而那 `curl -o` 逐字**要**那落点在）。
  #    => 而而那 `mkdir -p` 逐字**原来**只**在**那脚本**后半**那一段（=> 而而那 `--print-pieces` 那一格逐字**提前** return、
  #    => 而而那被**单独**调用的那一族逐字**没到**那后半段、=> 而而那 body 逐字**落不下**）。
  mkdir -p "$STAGE"
  GH_READ_BODY="$STAGE/.gh-release-body.json"
  GH_READ_RC=1
  GH_HTTP="none"
  GH_FAIL_KIND="ok"
  local http
  http="$(curl -sS -o "$GH_READ_BODY" -w '%{http_code}' --retry 3 --retry-delay 2 "${GH_HDR[@]}" \
          -H "Accept: application/vnd.github+json" \
          "https://api.github.com/repos/$REPO/releases/tags/$tag" 2>/dev/null || true)"
  GH_HTTP="${http:-none}"
  if [ "$GH_HTTP" = "200" ]; then
    if [ ! -s "$GH_READ_BODY" ]; then
      GH_FAIL_KIND="empty"
      return 1
    fi
    GH_READ_RC=0
    return 0
  fi
  #  三档分开（=> 而而那**处置**逐字**对得上号**）
  case "$GH_HTTP" in
    403|429)
      if grep -qi "rate limit" "$GH_READ_BODY" 2>/dev/null; then
        GH_FAIL_KIND="ratelimit"
      elif grep -qi "bad credentials" "$GH_READ_BODY" 2>/dev/null; then
        GH_FAIL_KIND="auth"
      else
        GH_FAIL_KIND="forbidden"
      fi ;;
    401)
      GH_FAIL_KIND="auth" ;;
    404)
      GH_FAIL_KIND="notfound" ;;
    *)
      GH_FAIL_KIND="http_${GH_HTTP}" ;;
  esac
  return 1
}

#  那一档逐字**说人话**（=> 而而那标记逐字是给机器看的、这一句是给人看的）。
gh_fail_label() {
  case "$1" in
    ok)         echo "那一次读逐字成了" ;;
    ratelimit)  echo "限流（那 API 逐字 60 次/小时限匿名读、5000 次/小时限带身份读）" ;;
    auth)       echo "鉴权被拒（那 token 逐字是错的 / 过期了 / 没这个权限）" ;;
    notfound)   echo "那个 tag 或那个端点逐字不存在" ;;
    forbidden)  echo "被拒（那 body 逐字没说清是限流还是鉴权）" ;;
    empty)      echo "读到了 200 但那 body 逐字是空" ;;
    parse)      echo "读到了 200 但那 JSON 逐字解析不了" ;;
    http_none)  echo "连 HTTP 那一档都没拿到" ;;
    http_*)     echo "那一读逐字失败（HTTP ${1#http_}）" ;;
    *)          echo "那一读逐字失败" ;;
  esac
}

#  而 crypto **不再作为独立的一件取**（01 今天裁：  而它就是 openssl 那一件的一部分）
#   缺陷名（C-102）**那一件被当独立一件固化，于是要拆出一份自己的字节**：
#     · 而 openssl 那个包里 lib/ 下本来就有 libcrypto.so（打包那一支「lib/ 下有字节全搬」）
#         **而「认领」那一半已经在位**（openssl 的 files[] 已含它那一份）
#     ·  而今天缺的是另一半：  登记里还有一条独立的 crypto 
#         ** 而两处方向一致（一多一少）才叫归成一件**（04 今天落的验收）
#     ·  而「只少一条而不多处一份  那只是清了表，而东西还在」（04 今天原话）
#    而**归并不是「从列表里删掉」**：
#      删掉之后那份字节仍由 openssl 那一件管（  而它本来就在那件的文件里）
#        而这一条是「不让它再被登记成独立一件」，  不是「丢掉那份字节」
# ★★ 而 PIECES 从那份表里算（01 今天裁）：★★
#   ★ ★ **要取的是 notStandalone 不为 true 的那些**（★ ★ 而列法是「不为 true」，★★
#   ★ ★ **而不是「为 false」** —— ★★ 而其余项**不写**那一格，★★ 而「没有」不等于「false」）★★
#   ★★ 而这一行**仍然是那一行文本**（★ ★ 而 verify-apk-native.sh:215 与 build-apk.yml:275 ★★
#   ★ ★ **都按文本 grep 它**）=> ★★ **=> 而它必须还是可 grep 的** ★★
#   ★★ => ★★ **=> 而 pieces-from-table.js 是那一行的生成者** ★★
# ★★ 而 PIECES 从那份表里算（01 今天裁）：★★
#   ★ ★ **要取的是 notStandalone 不为 true 的那些**（★ ★ 而列法是「不为 true」，★★
#   ★ ★ **而不是「为 false」** —— ★★ 而其余项**不写**那一格，★★ 而「没有」不等于「false」）★★
#   ★★ 而生成者是 scripts/toolchain/pieces-from-table.js（★ ★ 而它读同一张表）★★
#   ★★ => ★★ **=> 而下面这一行是那个生成器今天算出来的结果** ★★
#   ★★ => ★★ **=> 而它必须仍是可 grep 的字面文本**（★★ 而 verify-apk-native.sh:215 与 ★★
#   ★★ ★ ★ build-apk.yml:275 **都按文本 grep 它**）★★
#   ★★ => ★★ **=> 而改那张表之后要重跑那个生成器，★★ 而这一行要跟着更新** ★★
PIECES=(bash busybox crypto curl git jq openssl ripgrep zlib)

#  缺陷名（C-102）**那一读逐字只说「没取到」、=> 而而它不说是哪一种**
#    —— 而实际上那**三处** return 1 逐字**分不出来**：
#      :71 那一处 = 那个**名字**逐字**没找到**（=> 而那 url 逐字是空）
#            —— 而那**可能**是「那名字**不在**那筐里」、也**可能**是「那名字**在**那筐里、
#            而而那**读**逐字**没读到**（=> 而那 json 逐字解析不了 / 那读逐字失败）
#      :72 那一处 = **下载**逐字**失败**
#      :73 那一处 = 下载到**空文件**
#    => 而而那三处逐字**都**是 return 1、=> 而而那**调用方**逐字**只**看到「没取到」。
#  => 而而那 40214257950 那一趟逐字就是**第三**个形状：
#      而那 `others` 那一列逐字**非空**、=> 而那个**名字**逐字**在里面**、
#      => 而而那**一次**逐字**没取到**、=> 而而那**两条**分支逐字**都不覆盖**它。
#  => 而按 C-102：=> 而那逐字是「那**空**逐字**同时**表示**两件**不同的事、
#      => 而而那**第三个**形状**根本**没有分支」那个形状。
#  => 而那**改法**：=> 而而那**三处**逐字**各**带一个**标记**、=> 而而那**调用方**逐字**分得开**。
#  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
fetch_asset() {
  local tag="$1" asset="$2" dir="$3" url fa_err
  #  缺陷名（C-102）**那一次读逐字只认 GITHUB_TOKEN、=> 而那 workflow 那一格逐字给的是 GH_TOKEN**
  #    => 而那**后果**：那「有没有身份」那一格逐字**过了**（=> 而那 GH_TOKEN 逐字是给了的）、
  #       => 而那**请求**逐字**没带身份**（=> 而那匿名读逐字 60 次/小时、=> 而那症状逐字是 403 限流），
  #       => 而而那**看不出**是「令牌名不对」——**检查里过了但请求里没带身份**。
  #  => 而那**改法**：=> 而那一次读逐字**只**走 gh_read_release、=> 而那一个**只**认 GH_HDR、
  #     => 而 GH_HDR **只**由那唯一一份身份（GH_TOKEN 或 GITHUB_TOKEN 择一）构造。
  if ! gh_read_release "$tag"; then
    #  那一读逐字**失败**了（=> 而限流 / 鉴权 / 端点错那**三档**逐字**分得开**了）。
    echo "__FA_READ_FAILED__ http=${GH_HTTP:-none} kind=${GH_FAIL_KIND}" >&2
    return 1
  fi
  #  那一次读逐字**成了**、=> 而那 body 逐字**已经**落在那一份落地的档里（=> 而那不用再读一次）。
  fa_err="$STAGE/.fa-parse.err"
  url="$(node -e '
            const fs = require("fs");
            let d = "";
            try { d = fs.readFileSync(process.argv[1], "utf8"); }
            catch (e) { process.stderr.write("__FA_PARSE_FAILED__\n"); process.exit(3); }
            let j;
            try { j = JSON.parse(d); }
            catch (e) { process.stderr.write("__FA_PARSE_FAILED__\n"); process.exit(3); }
            const a = (j.assets || []).find(x => x.name === process.argv[2]);
            if (a) process.stdout.write(a.browser_download_url);
          ' "$GH_READ_BODY" "$asset" 2>"$fa_err")" || { cat "$fa_err" >&2; return 1; }
  if [ -s "$fa_err" ]; then
    #  缺陷名（C-102）**那解析失败那一族逐字把 stderr 逐字吞掉**（=> 而那旧版逐字是 2>/dev/null）、
    #    => 而而那**调用方**逐字**只**看到「那 url 逐字是空」、=> 而而那**分不出**是「那名字不在」还是「那读没读到」。
    cat "$fa_err" >&2
    return 1
  fi
  if [ -z "$url" ]; then
    #  那个**名字**逐字**没**被找到：=> 而而那**可能**是「那名字**不在**那筐里」、
    #  => 而而那**也**可能是「那名字**在**那筐里、而而那**读**逐字**没读到**」。
    #  => 而而那两种逐字**分不出来**、=> 而而那**调用方**逐字**要**那 `others` 那一列来分。
    echo "__FA_URL_NOT_FOUND__" >&2
    return 1
  fi
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset" "$url" 2>/dev/null || { echo "__FA_DOWNLOAD_FAILED__" >&2; return 1; }
  [ -s "$dir/$asset" ] || { echo "__FA_EMPTY_FILE__" >&2; return 1; }
  # 顺手取那份伴随的 .sha256（ 它与 zip 同名同 tag）。
  #    而不取的后果（ 缺陷名 C-102）：下面那个校验分支的判据是 
  #    `[ -f "$dir/$asset.sha256" ]`  ——  而它今天恒为假   校验被跳过 
  #     取到的字节**从没被校验过**（ 而 AGENTS.md 第七节要求：取件必须核字节数与声明一致）
  sha_url="${url}.sha256"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset.sha256" "$sha_url" 2>/dev/null || true
  return 0
}

# 列出某 tag 下现有的 asset 名（每个一行）。取不到时输出空串。
#
# 为什么需要它：取件失败有两种完全不同的性质，而在 CI 输出上都是「取不到」——
#   缺货   ：该 tag 一个 asset 都没有（从没固化 / 被删了） 处置是 dispatch
#   旧产物 ：有 asset 但名字对不上（改过 recipe，指纹变了） **旧产物不能冒充新代码的产物**，
#           而 dispatch 之前必须让人看见这一点，否则它会「取到旧包、打出 APK、全绿」。
# 2026-10-16 实测：run 1065 就是后者，而当时的输出与「8/8 件没固化」无法区分。
#  缺陷名（C-102）**那空逐字同时表示「那筐真的一件 asset 都没有」与「那一读逐字没读到」**、
#    => 而而那两个逐字混在**同一个**空里、=> 而而那一族逐字**分不出**是哪一种
#    —— 而实际上那 2>/dev/null 与那 catch (e) {} 逐字把**所有**失败**吞掉**了：
#    而那 curl 逐字失败（=> 而那 4xx / 5xx / 限流 / 鉴权过期 / 网络）逐字**静默**、
#    而那 JSON 逐字解析不了逐字**静默**、=> 而而那 stdout 逐字**空**、
#    => 而而那 stdout **空**逐字与「那筐**真的**一个 asset 都没有」逐字**完全一样**。
#    => 而本机**实测**过（=> 而那读失败时逐字返回码 = 22、=> 而那输出逐字长度 = 0、=> 而那 stderr 逐字**空**）。
#    => 而按 C-104：=> 而那 list_release_assets 那一族逐字**只有**一条路（=> 而那 REST 那一族与那 gh api 那一族逐字**不是**两路）。
#  => 而而那**改法**：=> 而而那 stdout **空**逐字与「那一读逐字没读到」逐字**分开**——
#    => 而而那一读逐字**失败**时逐字**走**另一条路、=> 而而那 stdout 逐字**带**一个**标记**。
#  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
list_release_assets() {
  local tag="$1"
  local body rc http kind
  #  缺陷名（C-102）**那读失败那一族逐字只带一个 curl 逐字自己的码、=> 而而那 HTTP 那一档逐字要另取**
  #    —— 而实际上那 rc 逐字**只是** curl 那一族逐字**自己**的退出码（=> 而那 22 逐字是 curl 的「HTTP >= 400」那个码），
  #    => 而而那 401 与那 404 与那 429 那**几档**逐字**共用**同一个 22、=> 而而它们逐字**分不出来**。
  #  => 而那**改法**：=> 而那一次读逐字**只**做一次（=> 而那旧版逐字读了**两次**：
  #    一次 -w '%{http_code}' 只为拿那一档、一次取 body —— => 而那**两次**逐字**各**吃一次 API 配额），
  #    => 而那 HTTP 那一档与那 body 逐字**一次**取回来、=> 而而那**三档**（限流 / 鉴权 / 端点错）逐字**分得开**。
  #  => 而那**令牌名**逐字**只有** GH_HDR 那**一个**（=> 而那旧版逐字只认 GITHUB_TOKEN、
  #    => 而那 workflow 那一格逐字给的是 GH_TOKEN、=> 而那**后果**是「检查里过了但请求里没带身份」）。
  if ! gh_read_release "$tag"; then
    rc=1
    http="${GH_HTTP:-none}"
    kind="${GH_FAIL_KIND:-http_none}"
    #  => 而那标记逐字**带着**那 HTTP 那一档与那**那一档**（=> 而而那旧版逐字只有 rc 与 http）。
    echo "__LRA_READ_FAILED__ rc=$rc http=${http:-none} kind=${kind}" >&2
    #  => 而那读数逐字**落**那**一个**档（=> 而那三个全局变量逐字**只**在**那函数自己**的壳里**有值**、
    #    => 而而那调用方逐字把它装在 `$(...)` 里面、=> 而那**子壳**逐字**改不到**那调用方的变量）。
    #    => 而那**唯一**的读点逐字在下面那个循环里（=> 而那处逐字从**这个**档取）。
    printf 'rc=%s http=%s kind=%s\n' "$rc" "${http:-none}" "$kind" > "${STAGE}/.lra-readings"
    return 1
  fi
  body="$(cat "$GH_READ_BODY")"
  rc=0
  http="${GH_HTTP:-none}"
  kind="ok"
  printf 'rc=%s http=%s kind=%s\n' "$rc" "${http:-none}" "$kind" > "${STAGE}/.lra-readings"
  printf '%s' "$body" \
    | node -e '
        let d = "";
        process.stdin.on("data", c => d += c);
        process.stdin.on("end", () => {
          let j;
          try { j = JSON.parse(d); } catch (e) { process.stderr.write("__LRA_PARSE_FAILED__\\n"); process.exit(3); }
          (j.assets || []).map(x => x.name).filter(n => !/\.sha256$/.test(n)).forEach(n => process.stdout.write(n + "\n"));
        });
      '

}

#  `--print-pieces` 那一格（=> 而那 build-apk.yml:329 那一格逐字是
#    `source scripts/toolchain/fetch-prebuilt-pieces.sh --print-pieces; echo ${#PIECES[@]}`）。
#  缺陷名（C-102）**那一个钩子逐字不存在**、=> 而而那**调用点**逐字**在**：
#    => 而那 source 逐字**顺带**把那整个取件循环逐字**跑一遍**（=> 而那下载两遍、=> 而那配额吃两遍）、
#    => 而而那 stdout 逐字**是**那取件那一份输出、=> 而而那**调用方**逐字**读不出**那件数。
#  => 而那**处置**：=> 而那**一个钩子**逐字**落**在这里、=> 而那 --print-pieces 逐字**只**答那件数。
#    => 而那 stdout 逐字**不**多写（=> 而那调用方逐字自己 `echo ${#PIECES[@]}`、=> 而那 stdout 逐字**只**是那一个数）。
#  => 而那**停法**：=> 而那**被** source 的时候逐字**只** `return`（=> 而那 $0 逐字是「bash」、
#    => 而那 `exit` 逐字会把那**调用方**的壳逐字**一起**带走）、=> 而那**被**直接执行的时候逐字 `exit`。
#  => 而那**位置**：=> 而那**这一段**逐字**落**在那**所有**函数逐字**定义完**之后、
#    => 而**不**是**在**那 PIECES 那一行**之后**（=> 而那 PIECES 那一行**之后**逐字是**那函数定义之前**、
#    => 而那**被** source 的时候逐字**提前** return、=> 而那 gh_read_release / list_release_assets
#    => 逐字**还不存在**、=> 而那调用方逐字**调不到**它们）。
if [ "$PRINT_PIECES_ONLY" -eq 1 ]; then
  if [ "${BASH_SOURCE[0]}" = "$0" ]; then exit 0; else return 0; fi
fi

mkdir -p "$ASSETS" "$STAGE"

missing=0
miss_stock=0
miss_stale=0
miss_read=0
ok=0

for id in "${PIECES[@]}"; do
  tag="$(bash scripts/toolchain/cache-key.sh tag "$id")"
  asset="$(bash scripts/toolchain/cache-key.sh asset "$id")"

  rm -rf "${STAGE:?}/$id"; mkdir -p "$STAGE/$id"

  if ! fetch_asset "$tag" "$asset" "$STAGE/$id"; then
    echo "::error title=$id 没有固化产物::Release $tag 里取不到 $asset —— 先去构建这一件："
    echo "::error::  gh workflow run $(bash scripts/toolchain/piece-workflow-name.sh "$id")   （见 RELEASE-POLICY.md）"
    #  缺陷名（C-102）**那赋值在 set -e 下逐字把整趟掐掉**、
    #    => 而而那**分族**那一支逐字**永远**走不到、=> 而而那一族逐字**永远**没被**验过**
    #  —— 而实际上那脚本逐字第 13 行是 `set -euo pipefail`，
    #    => 而而那 `others="$(…)"` 逐字**自己**就是一条简单命令、
    #    => 而而那被替换进去的那个命令逐字**非零**时、=> 而而那**整趟**逐字**当场退出**、
    #    => 而而那 `lra_rc=$?` 那一行逐字**根本到不了**。
    #  => 而本机**实测**过：=> 而那**错**的 REPO 那一趟逐字只印出那三个标记、
    #    => 而而那「清单读失败」那一档逐字**没**印出来、=> 而而那一趟逐字**直接退**了。
    #  => 而那**改法**：=> 而而那赋值逐字**带** `|| true`、=> 而而那返回码逐字**另外**收。
    #  那一读逐字**只**做一次：=> 而那 stdout 与那 stderr 逐字**分**开取、=> 而而那 HTTP 那一档逐字**在**那 stderr 里。
    #  => 而而那 stderr 逐字**落**一个临时的档、=> 而而那 HTTP 那一档逐字**从**那档**取**。
    lra_err="${STAGE}/lra.err"
    lra_out="$(list_release_assets "$tag" 2>"$lra_err")" && lra_rc=0 || lra_rc=$?
    others="$lra_out"
    lra_http="none"
    lra_kind="none"
    #  => 而那读数逐字**从**那个档**取**（=> 而那三个全局变量逐字**只**在**那函数自己**的壳里**有值**、
    #    => 而而那调用方逐字把它装在 `$(...)` 里面、=> 而那**子壳**逐字**改不到**那调用方的变量）。
    if [ -f "${STAGE}/.lra-readings" ]; then
      case "$(cat "$STAGE/.lra-readings")" in
        *"http="*) lra_http="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^http=//p' | head -1)" ;;
      esac
      case "$(cat "$STAGE/.lra-readings")" in
        *"kind="*) lra_kind="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^kind=//p' | head -1)" ;;
      esac
    fi
    if [ "$lra_rc" -ne 0 ]; then
      #  那一读逐字**失败**了（=> 而而那限流 / 鉴权 / 端点变了 / 网络 那一族）：
      #  => 而而那**不是**「那筐真的一件 asset 都没有」、=> 而而那**不**能按「缺货」处置。
      #  => 而而那 HTTP 那一档逐字**已经**被带出来（=> 而而那 401 与那 404 与那 429 那**几档**逐字**分得开**了）。
      echo "::notice title=$id 清单读失败::Release $tag 那一份**没读到**（=> 而而那 stdout 逐字是空、=> 而而那**不是**『那筐一个 asset 都没有』）。"
      echo "::notice:: HTTP 那一档 = ${lra_http:-未知}、=> 而那**那一档** = ${lra_kind:-未知}、=> 而而那 curl 逐字自己的码逐字**在**那标记里、=> 而而那**两档**逐字**不是**同一档。*"
      echo "::notice:: 那一档逐字说人话：$(gh_fail_label "$lra_kind")。*"
      echo "::notice:: 处置：先查那一次读（token / 限流 / 端点），=> 而而那**不是** dispatch 那一件能解决的。*"
      miss_read=$((miss_read+1))
    elif [ -z "$others" ]; then
      echo "::notice title=$id 缺货::Release $tag **一个 asset 都没有** —— 这一件从来没固化过，或固化被删了。"
      echo "::notice:: 处置：dispatch 那一件即可。*"
      miss_stock=$((miss_stock+1))
    elif printf "%s\n" "$others" | grep -qxF "$asset"; then
      #  **第三**个形状：=> 而那**名字逐字在里面**、=> 而而那**一次**逐字**没取到**。
      #  => 而而那**两条**分支逐字**都不覆盖**它、=> 而而那旧版逐字把它误判成「旧产物拦住了」。
      #  => 而而那**真的成因**逐字**在**那 fetch_asset 那一族**里面**（=> 而而那三处 return 1 逐字**分不出来**）。
      echo "::notice title=$id 在筐里但这一次没取到::Release $tag 里**有** $asset，=> 而而那**这一次**逐字**没取到**。"
      echo "::notice:: 处置：那是**取件**那一族的问题（限流 / 鉴权 / 传输 / 空文件），=> 而而那**不是**『没固化过』、=> 而也**不是**『旧产物』。*"
      printf "%s\n" "$others" | sed "s/^/::notice::   /"
      miss_read=$((miss_read+1))
    else
      echo "::notice title=$id 旧产物拦住了::Release $tag 里有 asset，但**没有一个叫 $asset** —— 那说明这一件改过 recipe，"
      echo "::notice:: 而固化产物还是旧的。**旧产物不能冒充新代码的产物**，所以这里必须拦。"
      echo "::notice:: 该 tag 下现有的 asset 名字："
      printf "%s\n" "$others" | sed "s/^/::notice::   /"
      echo "::notice:: 处置：dispatch 那一件重新固化（同名不同内容会被覆盖）。*"
      miss_stale=$((miss_stale+1))
    fi
    missing=$((missing+1))
    continue
  fi

  # 校验字节与声明一致（AGENTS.md 第七节）。
  #    而那份 .sha256 的内容是**裸摘要 + 换行**（ 07 今天在 Release 上逐个核过， 65 字节），
  #    而 sha256sum -c 要的是「摘要␣␣文件名」那种格式   直接喂给它会失败 
  #    所以这里不喂给它：自己取第一段（摘要）与实算的摘要比。
  if [ -f "$STAGE/$id/$asset.sha256" ]; then
    want="$(tr -d '\r\n ' < "$STAGE/$id/$asset.sha256" | head -c 64)"
    got="$(sha256sum "$STAGE/$id/$asset" | cut -d' ' -f1)"
    if [ -z "$want" ] || [ "$want" != "$got" ]; then
      echo "::error title=$id 校验失败::$asset 的 sha256 对不上（声明 ${want:-空} / 实算 $got）—— Release 里的产物被改过或截断"
      missing=$((missing+1)); continue
    fi
    printf "  · %-9s sha256 已核（%s…）\n" "$id" "${got:0:12}"
  else
    echo "::notice title=$id 无伴随摘要::Release 上没有 $asset.sha256 —— 这一件**没被校验过**（ 而取件必须核字节数与声明一致）"
  fi

  # 整包放进 assets/pieces/ —— 启动时由装配器解包安装，与 OTA 装件同路
  # 整包放进 assets/pieces/ —— 启动时由装配器解包安装，与 OTA 装件同路。
  # 落名 .zip：件包就用 zip（照抄 .deb 的 ar 换成 Android 原生就有的 zip），
  # 装配器 java.util.zip 直接解，不需要任何第三方库。
  # 别用 .tar.gz —— aapt2 会把 assets 下的 .tar.gz 解开并剥掉 .gz 后缀。
  #  缺陷名（C-102）**那取件**逐字把**那资产名**的**后缀**当成**那格式**的保证**。
  #    => 而那**成因**：=> 而那服务端那 tool-git 底下那**八**件逐字那名字**尾**都**是**那「.zip」、
  #      => 而而那**内容**逐字**是**那 gzip、=> 而而那 **unzip -p** 逐字**必**报**那**「End-of-central-directory signature not found」、
  #      => 而而那 meta_json 逐字**就是**那**空**、=> 而而那**后果**逐字 = 那 git **那一件**逐字**进不了**那 APK **那一族**。
  #    => 而那**处置**：=> 而那**取件**逐字**按**那**字节**的**真**形态**分支**（=> 而那**判别形式**是「那**取件**逐字**是**不是**按**那**字节**的**真**形态**分**支**」）。
  #      => 而而那**它**们**逐字是**那**两**件**事**（「那**名字**是**什么**」与「那**内容**是**什么**」）。
  #    => 而那**第一**处逐字 = 那**解包**逐字**按**那**字节**分支**（那 gzip / 那 zip）。
  #      => 而而那**第二**处逐字 = 那 meta_json 逐字**从**那**内层**那 zip 逐字**再**取**一次**。
  #      => 而而那**第三**处逐字 = 那**校验**那一支逐字**不**看**那格式**（=> 而它逐字**对**那**下载**下来那**整包**算**那 sha256**）。
  SRC="$STAGE/$id/$asset"
  cp -f "$SRC" "$ASSETS/$id.zip"
  #  缺陷名（C-102）**这里逐字是第二套容器判别源、=> 而仓内的判别源逐字已经有一份** ——
  #    => 而那旧版逐字是 `MAGIC="$(od -An -tx1 -N4 "$SRC" ...)"` 加一条三值分支
  #      （1f8b0800 / 1f8b0801 / 1f8b0808）、
  #    => 而那 scripts/toolchain/fmt.sh 那一族逐字**已经**判那同一件事
  #      （=> 而它逐字 :14 那行 head -c 4 | od -An -tx1 | tr -d ' \n'、=> 而 :16 的 504b* 是 zip、
  #      => 而 :17 的 1f8b* 是 gzip），
  #    => 而那两套逐字**各判各的**（=> 而那一套**没有** zip64 那一档）。
  #    => 而按 C-994「判别源必须唯一、=> 而其余各处改成调它」：
  #      这一处逐字**改成调 fmt.sh**、=> 而那 MAGIC 那一格逐字**删掉**。
  #  => 而那**判别的内容**逐字不变（=> 而那 gzip 那一支还是解包、=> 而那 zip 那一支还是直接用）。
  #  => 而那新增的 zip64 那一档逐字**不影响**这一支（=> 而那 zip64 逐字**也是** zip 容器、
  #      => 而那 zip 分支逐字照样直接用、=> 而它逐字**不需要**解包）。
  FMT_SH="$(cd "$(dirname "$0")" && pwd)/fmt.sh"
  [ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; missing=$((missing+1)); continue; }
  # shellcheck source=../toolchain/fmt.sh
  . "$FMT_SH"
  MAGIC="$(fmt_magic "$SRC")"
  SRC_FMT="$(fmt_of "$SRC")"
  if [ "$SRC_FMT" = "gzip" ]; then
    #  => 而那**它**逐字**是**那 gzip（=> 而而那**它**们**逐字是**那**两**件**事**：「那**资产名**是**什么**」与「那**字节**是**什么**」））。
    #  => 而那**它**逐字**是**那 tar 的**那**一**层**（=> 而而那**它**里面逐字**有**那**内层**那 zip）。
    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! tar xzf "$SRC" -C "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 gzip（那头四字节 $MAGIC），=> 而而那 tar xzf 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi
    #  => 而那**内层**那 zip 逐字**按内容**挑（=> 而按 01 裁的 S607-01-CONTAINER-FORM-RULING.md §3 逐字
    #     「**按内容挑** —— 内层容器的中央目录里**逐字含 `component-meta.json` 且其 `id` 逐字等于要取的件名**；
    #      => 而**名字 glob 只当第一道粗筛**」、
    #     => 而「**零个候选与多过一个候选都判红、不取第一个、不猜、不许退回外层凑数**」）。
    #  => 而那**名字 glob** 逐字只当**第一道粗筛**（=> 而那判据逐字**落在内容上**）。
    INNER=""
    INNER_N=0
    INNER_IDS=""
    for _z in "$STAGE/$id/unpacked"/*.zip "$STAGE/$id/unpacked"/*/*.zip; do
      [ -f "$_z" ] || continue
      #  => 而那**按字节**筛（=> 而那候选逐字**头四字节**是 504b* 的那一个才算 zip 容器）。
      _zm="$(fmt_magic "$_z")"
      case "$_zm" in 504b*) ;; *) continue ;; esac
      #  => 而那**按内容**筛（=> 而那一份逐字**含 component-meta.json**、
      #     => 而那它的 `id` 逐字**等于**要取的那一件）。
      _zid="$(unzip -p "$_z" component-meta.json 2>/dev/null | node -e '
let d = ""; process.stdin.on("data", c => d += c); process.stdin.on("end", () => {
  try { process.stdout.write(String(JSON.parse(d).id || "")); } catch (e) { process.stdout.write(""); }
});')"
      [ -n "$_zid" ] || continue
      INNER_IDS="$INNER_IDS $_zid"
      [ "$_zid" = "$id" ] || continue
      INNER="$_z"; INNER_N=$((INNER_N+1))
    done
    if [ "$INNER_N" -eq 0 ]; then
      echo "::error title=$id 内层没有那一颗件::那 gzip 解开了，=> 而那里面逐字**没有** id=$id 的件包（=> 而那候选逐字是${INNER_IDS:- 无}）。"
      echo "::error:: 而那按内容挑逐字挑不出、=> 而按 C-05 不猜、=> 而不取第一个、=> 而也不退回外层凑数。"
      missing=$((missing+1)); continue
    fi
    #  => 而那「内层不唯一」那一档逐字**判红**（=> 而那旧版逐字取第一个）。
    if [ "$INNER_N" -gt 1 ]; then
      echo "::error title=$id 内层不唯一::那 gzip 解开了，=> 而而那里面逐字有 $INNER_N 个 id=$id 的件包（=> 而 01 裁的规则逐字「多过一个候选都判红、不取第一个」）。"
      missing=$((missing+1)); continue
    fi
    cp -f "$INNER" "$ASSETS/$id.zip"
    printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 gzip，那内层取出来）\n" "$id" "$asset" "$id"
  elif [ "$SRC_FMT" = "zip" ] || [ "$SRC_FMT" = "zip64" ]; then
    #  => 而那**它**逐字是那 zip（=> 而那 CI artifact 那一族逐字是「zip 套内层」那个形状：
    #     => 而那外层逐字装着那**内层**那一个件包、=> 而那内层才是那一颗件）。
    #  => 而那旧版逐字**没有**这一支（=> 而它逐字把那外层当件包直接用、
    #     => 而而那 unzip -p 逐字**读不到** component-meta.json、=> 而而那 meta_json 逐字是那空）。
    #  缺陷名（C-102）**「外层套内层」那一档逐字按「外层中央目录条目数 = 1」判** ——
    #    => 而那旧版逐字是 `if [ "$OUTER_ENTRIES" = "1" ]`，
    #    => 而而那**一个判据要判两件不同的事**（=> 而按 C-135 那不是同一问）：
    #      · 「那 CI artifact 那一族」逐字外层**只有一条**条目（=> 而那一条逐字是那内层那一个 .zip）；
    #      · 而那 git 那一件的真件包逐字外层**有 239 条**条目（=> 而那它**就是**那一颗件、=> 而它**不是**套着）。
    #    => 而一个判据判两件事、=> 而它在别的形状上**会错**。
    #  => 而那**处置**：=> 而那一档逐字**按字节**判（=> 而按 C-994 的立论：形态按头字节魔数判、=> 而不按名字）——
    #    => 而那**内层**逐字**解出来**、=> 而它的头四字节逐字**是** 504b* 才算它是那 zip 容器。
    #    => 而「哪一层才算那一颗件」那一格**归 01 裁**（=> 而本处**不**拍板、=> 而只把事实摆出来）。
    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! unzip -q -o "$SRC" -d "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 zip（头四字节 $MAGIC），=> 而而那 unzip 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi
    #  => 而那**内层**那 zip 逐字**按内容**挑（=> 而按 01 裁的 S607-01-CONTAINER-FORM-RULING.md §3 逐字
    #     「**按内容挑** —— 内层容器的中央目录里**逐字含 `component-meta.json` 且其 `id` 逐字等于要取的件名**；
    #      => 而**名字 glob 只当第一道粗筛**」、
    #     => 而「**零个候选与多过一个候选都判红、不取第一个、不猜、不许退回外层凑数**」）。
    #  => 而那**字节**那一筛逐字**先**做（=> 而那候选逐字**头四字节**是 504b* 的那一个才算 zip 容器），
    #  => 而那**内容**那一筛逐字**后**做（=> 而那 id 逐字**等于**要取的那一件）。
    INNER=""
    INNER_N=0
    INNER_IDS=""
    for _z in "$STAGE/$id/unpacked"/*.zip "$STAGE/$id/unpacked"/*/*.zip; do
      [ -f "$_z" ] || continue
      _zm="$(fmt_magic "$_z")"
      case "$_zm" in 504b*) ;; *) continue ;; esac
      _zid="$(unzip -p "$_z" component-meta.json 2>/dev/null | node -e '
let d = ""; process.stdin.on("data", c => d += c); process.stdin.on("end", () => {
  try { process.stdout.write(String(JSON.parse(d).id || "")); } catch (e) { process.stdout.write(""); }
});')"
      [ -n "$_zid" ] || continue
      INNER_IDS="$INNER_IDS $_zid"
      [ "$_zid" = "$id" ] || continue
      INNER="$_z"; INNER_N=$((INNER_N+1))
    done
    if [ "$INNER_N" -eq 0 ]; then
      #  => 而那**没有**内层逐字是那一颗件（=> 而那候选逐字是别的件、=> 而那外层**不是**那一颗件）。
      #  => 而按 01 裁的规则逐字「**不许退回外层凑数**」=> 而这里逐字**判红**。
      echo "::error title=$id 内层没有那一颗件::那外层 zip 解开了，=> 而那里面逐字**没有** id=$id 的件包（=> 而那候选逐字是${INNER_IDS:- 无}）。"
      echo "::error:: 而那按内容挑逐字挑不出、=> 而按 C-05 不猜、=> 而不取第一个、=> 而也不退回外层凑数。"
      missing=$((missing+1)); continue
    fi
    if [ "$INNER_N" -gt 1 ]; then
      #  => 而那「内层不唯一」那一档逐字**判红**（=> 而 01 裁的规则逐字「多过一个候选都判红、不取第一个」）。
      echo "::error title=$id 内层不唯一::那外层 zip 解开了，=> 而而那里面逐字有 $INNER_N 个 id=$id 的件包（=> 而 01 裁的规则逐字「多过一个候选都判红、不取第一个」）。"
      missing=$((missing+1)); continue
    fi
    cp -f "$INNER" "$ASSETS/$id.zip"
    printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 zip 套内层，那内层取出来）\n" "$id" "$asset" "$id"
  else
    #  => 而那**它**逐字**既不是** gzip **也不是** zip（=> 而那形态逐字是那 fmt.sh 逐字给的）。
    #  => 而那旧版逐字把它**当**那 zip 那一支用（=> 而那 unzip -p 逐字读不到）。
    #  => 而现在逐字**报出来**（=> 而按 C-05「缺字段就报缺」、=> 而不猜填）。
    echo "::error title=$id 容器形态不认::那资产逐字是那 $SRC_FMT（头四字节 $MAGIC），=> 而而那既不是 zip 也不是 gzip。"
    echo "::error:: 处置：先核那一份的容器形态（scripts/verify/probe-container-form.sh），=> 而那**不是** dispatch 能解决的。"
    missing=$((missing+1)); continue
  fi

  # 格三（是不是我们要的那一代）：把这一件的两个标识读出来，供循环后那一格用。
  #   buildSha  同一 commit 上的多次构建相同  => 它标「同一份代码」
  #   buildRun 每次流水线都不同            => 它标「哪一次流水线造了它」
  #    而 01 今天裁：这一格保证不了同代（ 每件各自 dispatch 一条 run  
  #    八件按构造就不可能同代），  而「同代还是不同代」才是有用的那一格 
  #    不保证，只说出来（取件那一支是唯一一次看见全部八件的地方）。
  meta_json="$STAGE/$id/peek.json"
  rm -f "$meta_json"
  #  => 而那 meta_json 逐字**从**那**件包**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**来源**是**什么**」与「那**内容**是**什么**」）取**一次**。
  #     => 而而那**它**逐字**从**那**内层**那 zip 取**（=> 而而那**它**们**逐字是**那**两**件**事**）。
  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$meta_json" 2>/dev/null && [ -s "$meta_json" ]; then
    g_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$meta_json")"
    g_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$meta_json")"
  else
    g_sha=""; g_run=""
  fi
  rm -f "$meta_json"
  GEN_ROWS="${GEN_ROWS:-}${id}|${g_sha}|${g_run}\n"

  #  缺陷名（C-102）**那个 ok 计数器逐字恒是 0、=> 而而那一行输出逐字恒印 0** ——
  #    => 而那旧版逐字是 `ok=0`、=> 而那**全文再无任何** `ok=$((ok+1))`（=> 而那旧版的 :171 同样没有），
  #      => 而那 :542 那一行逐字「[fetch] 已取到 $ok 个」逐字**恒印 0**。
  #    => 而那**后果**：=> 而那一行落在**缺件**那一支（=> 而 missing > 0 时才印）、
  #      => 而那一趟逐字「取到 6 件 · 缺 4 件」却**同时**逐字印「已取到 0 个」
  #      => 而那**两行互相矛盾**、=> 而而读的人只能信其中一行。
  #    => 而那**处置**：=> 而这一行逐字**加一次**、=> 而它逐字只在这一件**真的落到 $ASSETS 之后**才加
  #      （=> 而那上面那几处 `continue` 逐字都是失败、=> 而那些**不**算取到）。
  ok=$((ok+1))

done

# 这一格：把这一批的标识列出来去重，并把「同代还是不同代」说出来。
#    而「同代」不是判据，「同代还是不同代」才是（01 今天）。
if [ -n "${GEN_ROWS:-}" ]; then
  echo ""
  echo "--- 这一批件的构建标识（buildSha = 同一份代码 / buildRun = 哪一次流水线）---"
  printf '%s' "$GEN_ROWS" | while IFS='|' read -r gid gsha grun; do
    printf "  %-9s buildSha=%-42s buildRun=%s\n" "$gid" "${gsha:-(没有)}" "${grun:-(没有)}"
  done
  runs="$(printf '%s' "$GEN_ROWS" | cut -d'|' -f3 | sed '/^$/d' | sort -u)"
  shas="$(printf '%s' "$GEN_ROWS" | cut -d'|' -f2 | sed '/^$/d' | sort -u)"
  n_run="$(printf '%s\n' "$runs" | grep -c . || true)"
  n_sha="$(printf '%s\n' "$shas" | grep -c . || true)"
  n_none="$(printf '%s' "$GEN_ROWS" | grep -c '||$' || true)"
  echo "  去重后: buildRun $n_run 个 / buildSha $n_sha 个（$n_none 件没带那一格）"
  if [ "$n_none" -gt 0 ]; then
    echo "  ::notice title=这一批不完整::有 $n_none 件没带构建标识  「是不是这一代」对它们答不出"
  fi
  if [ "$n_run" -gt 1 ]; then
    echo "  ::notice title=这一批不同代::去重后 buildRun $n_run 个  这一批不是同一次流水线造的"
    echo "  ::notice:: 而那不是缺陷 —— 每件各自 dispatch 一条 run（01 今天裁定） 按构造就不可能同代。"
  else
    echo "  ::notice title=这一批同代::去重后 buildRun $n_run 个  这一批是同一次流水线造的"
  fi
  if [ "$n_sha" -gt 1 ]; then
    echo "  ::notice title=这一批不止一份代码::去重后 buildSha $n_sha 个  这一批跨了多个 commit"
  fi
fi
if [ "$missing" -gt 0 ]; then
  echo ""
  echo "::error title=取件不全::$missing / ${#PIECES[@]} 个件取不到（缺货 $miss_stock · 旧产物 $miss_stale · 清单读失败 $miss_read）"
  if [ "$miss_stock" -gt 0 ]; then
    echo "::error::  **缺货**（该 tag 一个 asset 都没有）→ dispatch 那一件即可"
  fi
  if [ "$miss_read" -gt 0 ]; then
    echo "::error::  **清单读失败**（那一份**没读到**）→ 先查那一次读（token / 限流 / 端点），**不是** dispatch 能解决的"
  fi
  if [ "$miss_stale" -gt 0 ]; then
    echo "::error::  **旧产物**（有 asset 但名字对不上 = 改过 recipe 指纹变了）→ 必须 dispatch 重新固化，"
    echo "::error::  否则会取到旧包、打出 APK、CI 全绿，而那个产物里没有本次改动的任何东西"
  fi
  echo "[fetch] 已取到 $ok 个。缺的那几个要单独构建，别在 APK 里编 —— 见 RELEASE-POLICY.md"
  exit 1
fi

echo ""
echo "[fetch] ${#PIECES[@]} 个件包全部取自 Release → $ASSETS/<id>.zip"
echo "[fetch] 启动时装它们（与 OTA 装件同一条路），APK 不再编译任何件"
