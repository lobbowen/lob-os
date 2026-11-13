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
# fetch-prebuilt-pieces.sh:131: GEN_ROWS: unbound variable
GEN_ROWS=""

# 这一行的落点用 BASH_SOURCE（而不是 $0）—— 而因为 build-apk.yml:329 那一格是
# `source scripts/toolchain/fetch-prebuilt-pieces.sh --print-pieces`
# 而 source 的时候 $0 逐字是「bash」，dirname 那一条算出来是错的落点。
# 缺陷名（C-102）**同一份脚本按两种方式被调用，而落点只认其中一种**。
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.."&& pwd)"

ASSETS="container/app/src/main/assets/pieces"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

# `--print-pieces` 那一格（那 build-apk.yml:313 那一格逐字是
# `source scripts/toolchain/fetch-prebuilt-pieces.sh --print-pieces`）。
# 缺陷名（C-102）**那一个钩子逐字不存在**、那**调用点**逐字**在**：
# 那 source 逐字**顺带**把那整个取件循环逐字**跑一遍**（下载两遍、配额吃两遍）、
# 那 stdout 逐字**是**那取件那一份输出、那**调用方**逐字**读不出**那件数。
# 那**处置**：那**一个钩子**逐字**落**在这里、那 --print-pieces 逐字**只**答那件数。
# 那**按 C-104**：那「跑整个取件」与「只报件数」逐字**不是**同一问、那**不合并**。
PRINT_PIECES_ONLY=0
for _arg in "${@:-}"; do
  [ "$_arg" = "--print-pieces" ] && PRINT_PIECES_ONLY=1
done

# 缺陷名（C-102）**那一族把「读不到」逐字压成一档**：
# 而实际上那 API 逐字能答出**三档**不同的成因，而它们逐字**要**不同的处置：
# 403 + 那 body 里逐字有 "rate limit" = 限流（匿名读逐字 60 次/小时）
# 403 + 那 body 里逐字有 "Bad credentials" = 鉴权（那 token 逐字错了 / 过期了）
# 404 = 那个 tag 或那个端点逐字不存在
# 旧版逐字只印「HTTP 那一档 = 403」，那**三档**逐字**分不出来**、
# 那**处置**（重试 / 换令牌 / 查 tag）逐字**对不上号**。
#
# C-103 回答（这一族它为什么有两边）：一边是「读那一份 Release 清单」，
# 另一边是「那一次读逐字**有没有身份**」。
# 而那旧版把「有身份」当成**可选**：没有 token 就匿名读；
# 而匿名读逐字 60 次/小时（带身份 5000 次/小时），
# 一次读逐字在 CI 上逐字**被限流**（run 41118077153：9 件取到 3 件、6 件 403，
# 而那 28 分钟前同一格逐字取到 9/9）。
# 处置：给那一次读**一个身份**（不是「重试」，是「那一次读是谁做的」），
# 并把「没有身份」逐字**报出来**而不是静默匿名（C-05：缺字段就报缺，不猜填）。
#
# 形状照抄 scripts/toolchain/fetch-component-artifacts.sh:11-13（那是仓内已有的同一纪律）。
GH_TOKEN_VALUE="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$GH_TOKEN_VALUE" ] && [ "$PRINT_PIECES_ONLY" -eq 0 ]; then
  echo "::error title=缺令牌::取件要读 GitHub 的 Release 清单，而那匿名读只有 60 次/小时"
  echo "::error:: 给 GH_TOKEN 或 GITHUB_TOKEN（build-apk.yml 那一格逐字是 secrets.GITHUB_TOKEN）"
  echo "::error:: 这一格不猜 —— 没有身份就不读，**不**静默匿名。"
  exit 1
fi
export GH_TOKEN="$GH_TOKEN_VALUE"
# 那**唯一**一份身份（两条真读的路逐字都**只**认它）：
# 缺陷名（C-102）**那两条真读的路逐字各认各的令牌名、那 workflow 那一格逐字给的是 GH_TOKEN**
# 那**后果**：那「有没有身份」那一格逐字**过了**（那 GH_TOKEN 逐字是给了的）、
# 那**请求**逐字**没带身份**（匿名读逐字 60 次/小时、症状逐字是 403 限流），
# 那**看不出**是「令牌名不对」——**检查里过了但请求里没带身份**。
# 那**改法**：那**两条**真读的路逐字**都**走那**一个** gh_read_release、
# 一个**只**认 GH_HDR、GH_HDR **只**由那唯一一份身份构造。
GH_HDR=(-H "Authorization: Bearer $GH_TOKEN")

# 那一次读（那一份 Release 清单）的读数：全局
# GH_HTTP = 那一读逐字**拿到**的 HTTP 那一档（none = 连那一档都没拿到）
# GH_FAIL_KIND= 那一次读逐字**属于**哪一档：ok / ratelimit / auth / notfound /
# forbidden / empty / parse / http_<码>
# GH_READ_TAG / GH_READ_BODY = 读的是哪个 tag、body 落在哪
# GH_READ_RC = 那一读逐字**成没成**（0 成 / 1 没成）
GH_HTTP="none"
GH_FAIL_KIND="ok"
GH_READ_TAG=""
GH_READ_BODY=""
GH_READ_RC=1

# 那 list_release_assets 那一族的读数：**只**落那一个档（档才是真读）。
# 缺陷名（C-102）**那三个变量逐字只写不读、它们逐字不是真相** ——
# 那 LRA_HTTP / LRA_RC / LRA_KIND 三个逐字**只**在 :96-:98 初始化、
# 在下面那 list_release_assets 那一族**赋值**（两处逐字各赋一次）、
# 那**全文再无任何读取点**（grep 逐行核过：三个名字逐字只出现在那六行里）。
# 那**成因**：那 list_release_assets 逐字**被装在 `$(...)` 里面**被调（那**子壳**），
# 子壳逐字**改不到**那调用方的变量、调用方逐字**改用**那一个档（${STAGE}/.lra-readings）取读数。
# 所以那三个变量逐字是**上一版那个办法留下的残留**（上一版那个办法根本走不通）。
# 按 C-993（新写的判别式必须有调用点、「检查里过了但没人用」逐字是同一形状）：
# 那三个逐字**删掉**、读数逐字**只**从那一个档取（档逐字有真读点）。
# 三个**不是**「信息丢了」：那 HTTP 那一档与那**那一档**逐字都在那档里（见下面那个读取点）。

# 读那一份 Release 清单。**一次读**（旧版逐字读了**两次**：
# 一次 -w '%{http_code}' 只为拿那一档、一次取 body ——那**两次**逐字**各**吃一次 API 配额）。
# 同一个 tag 读过一次就不再读第二次（配额逐字是有限的）。
gh_read_release() {
  local tag="$1"
  if [ "$GH_READ_TAG" = "$tag" ]; then
    return "$GH_READ_RC"
  fi
  GH_READ_TAG="$tag"
  # 落点逐字**先**造出来（那 body 逐字**落**在那里面、那 `curl -o` 逐字**要**那落点在）。
  # 那 `mkdir -p` 逐字**原来**只**在**那脚本**后半**那一段（那 `--print-pieces` 那一格逐字**提前** return、
  # 被**单独**调用的那一族逐字**没到**那后半段、那 body 逐字**落不下**）。
  mkdir -p "$STAGE"
  GH_READ_BODY="$STAGE/.gh-release-body.json"
  GH_READ_RC=1
  GH_HTTP="none"
  GH_FAIL_KIND="ok"
  local http
  http="$(curl -sS -o "$GH_READ_BODY"-w '%{http_code}' --retry 3 --retry-delay 2 "${GH_HDR[@]}" \
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
  # 三档分开（那**处置**逐字**对得上号**）
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

# 那一档逐字**说人话**（标记逐字是给机器看的、这一句是给人看的）。
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
    *) echo "那一读逐字失败" ;;
  esac
}

# 而 crypto **不再作为独立的一件取**（01 今天裁： 而它就是 openssl 那一件的一部分）
# 缺陷名（C-102）**那一件被当独立一件固化，于是要拆出一份自己的字节**：
# · 而 openssl 那个包里 lib/ 下本来就有 libcrypto.so（打包那一支「lib/ 下有字节全搬」）
# **而「认领」那一半已经在位**（openssl 的 files[] 已含它那一份）
# · 而今天缺的是另一半： 登记里还有一条独立的 crypto 
# ** 而两处方向一致（一多一少）才叫归成一件**（04 今天落的验收）
# · 而「只少一条而不多处一份 那只是清了表，而东西还在」（04 今天原话）
# 而**归并不是「从列表里删掉」**：
# 删掉之后那份字节仍由 openssl 那一件管（ 而它本来就在那件的文件里）
# 而这一条是「不让它再被登记成独立一件」， 不是「丢掉那份字节」
# 而 PIECES 从那份表里算（01 今天裁）： 
# **要取的是 notStandalone 不为 true 的那些**（ 而列法是「不为 true」， 
# **而不是「为 false」** —— 而其余项**不写**那一格， 而「没有」不等于「false」） 
# 而这一行**仍然是那一行文本**（ 而 verify-apk-native.sh:215 与 build-apk.yml:275 
# **都按文本 grep 它**）=> **它必须还是可 grep 的** 
# => **pieces-from-table.js 是那一行的生成者** 
# 而 PIECES 从那份表里算（01 今天裁）： 
# **要取的是 notStandalone 不为 true 的那些**（ 而列法是「不为 true」， 
# **而不是「为 false」** —— 而其余项**不写**那一格， 而「没有」不等于「false」） 
# 而生成者是 scripts/toolchain/pieces-from-table.js（ 而它读同一张表） 
# => **下面这一行是那个生成器今天算出来的结果** 
# => **它必须仍是可 grep 的字面文本**（ 而 verify-apk-native.sh:215 与 
# build-apk.yml:275 **都按文本 grep 它**） 
# => **改那张表之后要重跑那个生成器， 而这一行要跟着更新** 
#
# C-995：这一行是「该取哪几件」的唯一真源。生成器 pieces-from-table.js 从钉值表算出它，
# 而「有几件」这个数由 --print-pieces 直接从下面这个数组的个数吐出来 ——
# 算式就在这个脚本里，与取件用的是同一处，不再另有一条路。
# 以前 build-apk.yml 那边还有第二条路：grep 出这一行再数词数。那条路把「一行文本」
# 当成了真源，于是数组和文本可以各说各话；两边碰巧一样时看不出来，不一样时又说不清
# 是哪边错。现在那条路已经删掉，只剩 --print-pieces 这一条。
PIECES=(bash busybox crypto curl git jq openssl ripgrep zlib)

# 缺陷名（C-102）**那一读逐字只说「没取到」、它不说是哪一种**
# —— 而实际上那**三处** return 1 逐字**分不出来**：
# :71 那一处 = 那个**名字**逐字**没找到**（那 url 逐字是空）
# —— 而那**可能**是「那名字**不在**那筐里」、也**可能**是「那名字**在**那筐里、
# 而那**读**逐字**没读到**（那 json 逐字解析不了 / 那读逐字失败）
# :72 那一处 = **下载**逐字**失败**
# :73 那一处 = 下载到**空文件**
# 三处逐字**都**是 return 1、那**调用方**逐字**只**看到「没取到」。
# 那 40214257950 那一趟逐字就是**第三**个形状：
# 而那 `others` 那一列逐字**非空**、个**名字**逐字**在里面**、
# 那**一次**逐字**没取到**、那**两条**分支逐字**都不覆盖**它。
# 按 C-102：逐字是「那**空**逐字**同时**表示**两件**不同的事、
# 那**第三个**形状**根本**没有分支」那个形状。
# 那**改法**：那**三处**逐字**各**带一个**标记**、那**调用方**逐字**分得开**。
# 按 C-220：04 那边要落**内容稿**、这一笔是**实现**那一族。
fetch_asset() {
  local tag="$1" asset="$2" dir="$3" url fa_err
  # 缺陷名（C-102）**那一次读逐字只认 GITHUB_TOKEN、那 workflow 那一格逐字给的是 GH_TOKEN**
  # 那**后果**：那「有没有身份」那一格逐字**过了**（那 GH_TOKEN 逐字是给了的）、
  # 那**请求**逐字**没带身份**（匿名读逐字 60 次/小时、症状逐字是 403 限流），
  # 那**看不出**是「令牌名不对」——**检查里过了但请求里没带身份**。
  # 那**改法**：一次读逐字**只**走 gh_read_release、一个**只**认 GH_HDR、
  # GH_HDR **只**由那唯一一份身份（GH_TOKEN 或 GITHUB_TOKEN 择一）构造。
  if ! gh_read_release "$tag"; then
    # 那一读逐字**失败**了（限流 / 鉴权 / 端点错那**三档**逐字**分得开**了）。
    echo "__FA_READ_FAILED__ http=${GH_HTTP:-none} kind=${GH_FAIL_KIND}" >&2
    return 1
  fi
  # 那一次读逐字**成了**、那 body 逐字**已经**落在那一份落地的档里（不用再读一次）。
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
          ' "$GH_READ_BODY" "$asset" 2>"$fa_err")"|| { cat "$fa_err" >&2; return 1; }
  if [ -s "$fa_err" ]; then
    # 缺陷名（C-102）**那解析失败那一族逐字把 stderr 逐字吞掉**（旧版逐字是 2>/dev/null）、
    # 那**调用方**逐字**只**看到「那 url 逐字是空」、那**分不出**是「那名字不在」还是「那读没读到」。
    cat "$fa_err" >&2
    return 1
  fi
  if [ -z "$url" ]; then
    # 那个**名字**逐字**没**被找到：那**可能**是「那名字**不在**那筐里」、
    # 那**也**可能是「那名字**在**那筐里、而那**读**逐字**没读到**」。
    # 两种逐字**分不出来**、那**调用方**逐字**要**那 `others` 那一列来分。
    echo "__FA_URL_NOT_FOUND__" >&2
    return 1
  fi
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset" "$url" 2>/dev/null || { echo "__FA_DOWNLOAD_FAILED__" >&2; return 1; }
  [ -s "$dir/$asset" ] || { echo "__FA_EMPTY_FILE__" >&2; return 1; }
  # 顺手取那份伴随的 .sha256（ 它与 zip 同名同 tag）。
  # 而不取的后果（ 缺陷名 C-102）：下面那个校验分支的判据是 
  # `[ -f "$dir/$asset.sha256" ]` —— 而它今天恒为假 校验被跳过 
  # 取到的字节**从没被校验过**（ 而 AGENTS.md 第七节要求：取件必须核字节数与声明一致）
  sha_url="${url}.sha256"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset.sha256" "$sha_url" 2>/dev/null || true
  return 0
}

# 列出某 tag 下现有的 asset 名（每个一行）。取不到时输出空串。
#
# 为什么需要它：取件失败有两种完全不同的性质，而在 CI 输出上都是「取不到」——
# 缺货 ：该 tag 一个 asset 都没有（从没固化 / 被删了） 处置是 dispatch
# 旧产物 ：有 asset 但名字对不上（改过 recipe，指纹变了） **旧产物不能冒充新代码的产物**，
# 而 dispatch 之前必须让人看见这一点，否则它会「取到旧包、打出 APK、全绿」。
# 2026-10-16 实测：run 1065 就是后者，而当时的输出与「8/8 件没固化」无法区分。
# 缺陷名（C-102）**那空逐字同时表示「那筐真的一件 asset 都没有」与「那一读逐字没读到」**、
# 两个逐字混在**同一个**空里、一族逐字**分不出**是哪一种
# —— 而实际上那 2>/dev/null 与那 catch (e) {} 逐字把**所有**失败**吞掉**了：
# 而那 curl 逐字失败（那 4xx / 5xx / 限流 / 鉴权过期 / 网络）逐字**静默**、
# 而那 JSON 逐字解析不了逐字**静默**、那 stdout 逐字**空**、
# 那 stdout **空**逐字与「那筐**真的**一个 asset 都没有」逐字**完全一样**。
# 本机**实测**过（读失败时逐字返回码 = 22、输出逐字长度 = 0、那 stderr 逐字**空**）。
# 按 C-104：那 list_release_assets 那一族逐字**只有**一条路（那 REST 那一族与那 gh api 那一族逐字**不是**两路）。
# 那**改法**：那 stdout **空**逐字与「那一读逐字没读到」逐字**分开**——
# 一读逐字**失败**时逐字**走**另一条路、那 stdout 逐字**带**一个**标记**。
# 按 C-220：04 那边要落**内容稿**、这一笔是**实现**那一族。
list_release_assets() {
  local tag="$1"
  local body rc http kind
  # 缺陷名（C-102）**那读失败那一族逐字只带一个 curl 逐字自己的码、那 HTTP 那一档逐字要另取**
  # —— 而实际上那 rc 逐字**只是** curl 那一族逐字**自己**的退出码（那 22 逐字是 curl 的「HTTP >= 400」那个码），
  # 那 401 与那 404 与那 429 那**几档**逐字**共用**同一个 22、它们逐字**分不出来**。
  # 那**改法**：一次读逐字**只**做一次（旧版逐字读了**两次**：
  # 一次 -w '%{http_code}' 只为拿那一档、一次取 body ——那**两次**逐字**各**吃一次 API 配额），
  # 那 HTTP 那一档与那 body 逐字**一次**取回来、那**三档**（限流 / 鉴权 / 端点错）逐字**分得开**。
  # 那**令牌名**逐字**只有** GH_HDR 那**一个**（旧版逐字只认 GITHUB_TOKEN、
  # 那 workflow 那一格逐字给的是 GH_TOKEN、那**后果**是「检查里过了但请求里没带身份」）。
  if ! gh_read_release "$tag"; then
    rc=1
    http="${GH_HTTP:-none}"
    kind="${GH_FAIL_KIND:-http_none}"
    # 标记逐字**带着**那 HTTP 那一档与那**那一档**（旧版逐字只有 rc 与 http）。
    echo "__LRA_READ_FAILED__ rc=$rc http=${http:-none} kind=${kind}" >&2
    # 读数逐字**落**那**一个**档（三个全局变量逐字**只**在**那函数自己**的壳里**有值**、
    # 调用方逐字把它装在 `$(...)` 里面、那**子壳**逐字**改不到**那调用方的变量）。
    # 那**唯一**的读点逐字在下面那个循环里（处逐字从**这个**档取）。
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

# `--print-pieces` 那一格（那 build-apk.yml:329 那一格逐字是
# `source scripts/toolchain/fetch-prebuilt-pieces.sh --print-pieces; echo ${#PIECES[@]}`）。
# 缺陷名（C-102）**那一个钩子逐字不存在**、那**调用点**逐字**在**：
# 那 source 逐字**顺带**把那整个取件循环逐字**跑一遍**（下载两遍、配额吃两遍）、
# 那 stdout 逐字**是**那取件那一份输出、那**调用方**逐字**读不出**那件数。
# 那**处置**：那**一个钩子**逐字**落**在这里、那 --print-pieces 逐字**只**答那件数。
# 那 stdout 逐字**不**多写（调用方逐字自己 `echo ${#PIECES[@]}`、那 stdout 逐字**只**是那一个数）。
# 那**停法**：那**被** source 的时候逐字**只** `return`（那 $0 逐字是「bash」、
# 那 `exit` 逐字会把那**调用方**的壳逐字**一起**带走）、那**被**直接执行的时候逐字 `exit`。
# 那**位置**：那**这一段**逐字**落**在那**所有**函数逐字**定义完**之后、
# **不**是**在**那 PIECES 那一行**之后**（那 PIECES 那一行**之后**逐字是**那函数定义之前**、
# 那**被** source 的时候逐字**提前** return、那 gh_read_release / list_release_assets
# => 逐字**还不存在**、调用方逐字**调不到**它们）。
if [ "$PRINT_PIECES_ONLY" -eq 1 ]; then
  # C-995：这一格要能被调用方**信任**。
  # 调用方原来写的是 `source ... --print-pieces; echo ${#PIECES[@]}`，但这条路有毛病：
  # source 失败时 PIECES 这个数组根本没建立，${#PIECES[@]} 算出来是 **0**，
  # 而 0 长得像一个正常的数（「该取 0 件」），不是「没读出来」。调用方拿 -z 判空判不出来。
  # 现在改成：脚本自己把件数**打到 stdout**，调用方只读 stdout。
  # 这样「脚本没跑起来」的表现就是**没有输出**（空串），而不是一个假的 0，
  # 调用方的 -z 判空就能真的判到。
  # 数组空了也是缺件，同样不能交 0 出去，报错走 stderr，stdout 保持干净。
  if [ "${#PIECES[@]}" -eq 0 ]; then
    echo "[error] PIECES 是空的 —— 该取几件没定下来，报缺，不交 0" >&2
    if [ "${BASH_SOURCE[0]}" = "$0" ]; then exit 1; else return 1; fi
  fi
  echo "${#PIECES[@]}"
  if [ "${BASH_SOURCE[0]}" = "$0" ]; then exit 0; else return 0; fi
fi

mkdir -p "$ASSETS" "$STAGE"

missing=0
miss_stock=0
miss_stale=0
miss_read=0
ok=0

# 那**反查**那一族：那 run id 逐字**反查**到那产出它的那一趟的 head sha
# 那**期望值**逐字是那**产出它的那一趟**（它逐字**不**是那当前 HEAD）
# 那**问不到**时逐字**答不出**（空串、按 C-05 不猜填）
# 那**身份**逐字**只**用那 GH_HDR（唯一一份身份、按 C-104 逐字**不并**）
run_head_sha_of() {
  local run="$1"
  [ -n "$run" ] || { printf ''; return 0; }
  local body="$STAGE/.run-$run.json"
  local http
  http="$(curl -sS -o "$body"-w '%{http_code}' --retry 3 --retry-delay 2 "${GH_HDR[@]}" \
    "https://api.github.com/repos/$REPO/actions/runs/$run" 2>/dev/null || true)"
  if [ "$http" != "200" ]; then
    rm -f "$body"
    printf ''
    return 0
  fi
  local sha
  sha="$(node -e '
let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>{
  try{process.stdout.write(String(JSON.parse(d).head_sha||""))}catch(e){process.stdout.write("")}
})' < "$body" 2>/dev/null)"
  rm -f "$body"
  printf '%s' "$sha"
}

for id in "${PIECES[@]}"; do
  tag="$(bash scripts/toolchain/cache-key.sh tag "$id")"
  asset="$(bash scripts/toolchain/cache-key.sh asset "$id")"

  rm -rf "${STAGE:?}/$id"; mkdir -p "$STAGE/$id"

  if ! fetch_asset "$tag" "$asset" "$STAGE/$id"; then
    echo "::error title=$id 没有固化产物::Release $tag 里取不到 $asset —— 先去构建这一件："
    echo "::error:: gh workflow run $(bash scripts/toolchain/piece-workflow-name.sh "$id") （见 RELEASE-POLICY.md）"
    # 缺陷名（C-102）**那赋值在 set -e 下逐字把整趟掐掉**、
    # 那**分族**那一支逐字**永远**走不到、一族逐字**永远**没被**验过**
    # —— 而实际上那脚本逐字第 13 行是 `set -euo pipefail`，
    # 那 `others="$(…)"` 逐字**自己**就是一条简单命令、
    # 被替换进去的那个命令逐字**非零**时、那**整趟**逐字**当场退出**、
    # 那 `lra_rc=$?` 那一行逐字**根本到不了**。
    # 本机**实测**过：那**错**的 REPO 那一趟逐字只印出那三个标记、
    # 那「清单读失败」那一档逐字**没**印出来、一趟逐字**直接退**了。
    # 那**改法**：赋值逐字**带** `|| true`、返回码逐字**另外**收。
    # 那一读逐字**只**做一次：那 stdout 与那 stderr 逐字**分**开取、那 HTTP 那一档逐字**在**那 stderr 里。
    # 那 stderr 逐字**落**一个临时的档、那 HTTP 那一档逐字**从**那档**取**。
    lra_err="${STAGE}/lra.err"
    lra_out="$(list_release_assets "$tag"2>"$lra_err")" && lra_rc=0 || lra_rc=$?
    others="$lra_out"
    lra_http="none"
    lra_kind="none"
    # 读数逐字**从**那个档**取**（三个全局变量逐字**只**在**那函数自己**的壳里**有值**、
    # 调用方逐字把它装在 `$(...)` 里面、那**子壳**逐字**改不到**那调用方的变量）。
    if [ -f "${STAGE}/.lra-readings" ]; then
      case "$(cat "$STAGE/.lra-readings")" in
        *"http="*) lra_http="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^http=//p' | head -1)" ;;
      esac
      case "$(cat "$STAGE/.lra-readings")" in
        *"kind="*) lra_kind="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^kind=//p' | head -1)" ;;
      esac
    fi
    if [ "$lra_rc" -ne 0 ]; then
      # 那一读逐字**失败**了（限流 / 鉴权 / 端点变了 / 网络 那一族）：
      # 那**不是**「那筐真的一件 asset 都没有」、那**不**能按「缺货」处置。
      # 那 HTTP 那一档逐字**已经**被带出来（那 401 与那 404 与那 429 那**几档**逐字**分得开**了）。
      echo "::notice title=$id 清单读失败::Release $tag 那一份**没读到**（那 stdout 逐字是空、那**不是**『那筐一个 asset 都没有』）。"
      echo "::notice:: HTTP 那一档 = ${lra_http:-未知}、那**那一档** = ${lra_kind:-未知}、那 curl 逐字自己的码逐字**在**那标记里、那**两档**逐字**不是**同一档。*"
      echo "::notice:: 那一档逐字说人话：$(gh_fail_label "$lra_kind")。*"
      echo "::notice:: 处置：先查那一次读（token / 限流 / 端点），那**不是** dispatch 那一件能解决的。*"
      miss_read=$((miss_read+1))
    elif [ -z "$others" ]; then
      echo "::notice title=$id 缺货::Release $tag **一个 asset 都没有** —— 这一件从来没固化过，或固化被删了。"
      echo "::notice:: 处置：dispatch 那一件即可。*"
      miss_stock=$((miss_stock+1))
    elif printf "%s\n""$others"| grep -qxF "$asset"; then
      # **第三**个形状：那**名字逐字在里面**、那**一次**逐字**没取到**。
      # 那**两条**分支逐字**都不覆盖**它、旧版逐字把它误判成「旧产物拦住了」。
      # 那**真的成因**逐字**在**那 fetch_asset 那一族**里面**（三处 return 1 逐字**分不出来**）。
      echo "::notice title=$id 在筐里但这一次没取到::Release $tag 里**有** $asset，那**这一次**逐字**没取到**。"
      echo "::notice:: 处置：那是**取件**那一族的问题（限流 / 鉴权 / 传输 / 空文件），那**不是**『没固化过』、也**不是**『旧产物』。*"
      printf "%s\n""$others"| sed "s/^/::notice::   /"
      miss_read=$((miss_read+1))
    else
      echo "::notice title=$id 旧产物拦住了::Release $tag 里有 asset，但**没有一个叫 $asset** —— 那说明这一件改过 recipe，"
      echo "::notice:: 而固化产物还是旧的。**旧产物不能冒充新代码的产物**，所以这里必须拦。"
      echo "::notice:: 该 tag 下现有的 asset 名字："
      printf "%s\n""$others"| sed "s/^/::notice::   /"
      echo "::notice:: 处置：dispatch 那一件重新固化（同名不同内容会被覆盖）。*"
      miss_stale=$((miss_stale+1))
    fi
    missing=$((missing+1))
    continue
  fi

  # 校验字节与声明一致（AGENTS.md 第七节）。
  # 而那份 .sha256 的内容是**裸摘要 + 换行**（ 07 今天在 Release 上逐个核过， 65 字节），
  # 而 sha256sum -c 要的是「摘要␣␣文件名」那种格式 直接喂给它会失败 
  # 所以这里不喂给它：自己取第一段（摘要）与实算的摘要比。
  if [ -f "$STAGE/$id/$asset.sha256" ]; then
    want="$(tr -d '\r\n ' < "$STAGE/$id/$asset.sha256" | head -c 64)"
    got="$(sha256sum "$STAGE/$id/$asset"| cut -d' ' -f1)"
    if [ -z "$want" ] || [ "$want" != "$got" ]; then
      echo "::error title=$id 校验失败::$asset 的 sha256 对不上（声明 ${want:-空} / 实算 $got）—— Release 里的产物被改过或截断"
      missing=$((missing+1)); continue
    fi
    printf "  · %-9s sha256 已核（%s…）\n""$id""${got:0:12}"
  else
    echo "::notice title=$id 无伴随摘要::Release 上没有 $asset.sha256 —— 这一件**没被校验过**（ 而取件必须核字节数与声明一致）"
  fi

  # 整包放进 assets/pieces/ —— 启动时由装配器解包安装，与 OTA 装件同路
  # 整包放进 assets/pieces/ —— 启动时由装配器解包安装，与 OTA 装件同路。
  # 落名 .zip：件包就用 zip（照抄 .deb 的 ar 换成 Android 原生就有的 zip），
  # 装配器 java.util.zip 直接解，不需要任何第三方库。
  # 别用 .tar.gz —— aapt2 会把 assets 下的 .tar.gz 解开并剥掉 .gz 后缀。
  # 缺陷名（C-102）**那取件**逐字把**那资产名**的**后缀**当成**那格式**的保证**。
  # 那**成因**：服务端那 tool-git 底下那**八**件逐字那名字**尾**都**是**那「.zip」、
  # 那**内容**逐字**是**那 gzip、那 **unzip -p** 逐字**必**报**那**「End-of-central-directory signature not found」、
  # 那 meta_json 逐字**就是**那**空**、那**后果**逐字 = 那 git **那一件**逐字**进不了**那 APK **那一族**。
  # 那**处置**：那**取件**逐字**按**那**字节**的**真**形态**分支**（那**判别形式**是「那**取件**逐字**是**不是**按**那**字节**的**真**形态**分**支**」）。
  # 那**它**们**逐字是**那**两**件**事**（「那**名字**是**什么**」与「那**内容**是**什么**」）。
  # 那**第一**处逐字 = 那**解包**逐字**按**那**字节**分支**（那 gzip / 那 zip）。
  # 那**第二**处逐字 = 那 meta_json 逐字**从**那**内层**那 zip 逐字**再**取**一次**。
  # 那**第三**处逐字 = 那**校验**那一支逐字**不**看**那格式**（它逐字**对**那**下载**下来那**整包**算**那 sha256**）。
  SRC="$STAGE/$id/$asset"
  cp -f "$SRC" "$ASSETS/$id.zip"
  # 缺陷名（C-102）**这里逐字是第二套容器判别源、仓内的判别源逐字已经有一份** ——
  # 旧版逐字是 `MAGIC="$(od -An -tx1 -N4 "$SRC" ...)"` 加一条三值分支
  # （1f8b0800 / 1f8b0801 / 1f8b0808）、
  # 那 scripts/toolchain/fmt.sh 那一族逐字**已经**判那同一件事
  # （它逐字 :14 那行 head -c 4 | od -An -tx1 | tr -d ' \n'、:16 的 504b* 是 zip、
  # :17 的 1f8b* 是 gzip），
  # 两套逐字**各判各的**（一套**没有** zip64 那一档）。
  # 按 C-994「判别源必须唯一、其余各处改成调它」：
  # 这一处逐字**改成调 fmt.sh**、那 MAGIC 那一格逐字**删掉**。
  # 那**判别的内容**逐字不变（那 gzip 那一支还是解包、那 zip 那一支还是直接用）。
  # 新增的 zip64 那一档逐字**不影响**这一支（那 zip64 逐字**也是** zip 容器、
  # 那 zip 分支逐字照样直接用、它逐字**不需要**解包）。
  FMT_SH="$(cd "$(dirname "$0")"&& pwd)/fmt.sh"
  [ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; missing=$((missing+1)); continue; }
  # shellcheck source=../toolchain/fmt.sh
  . "$FMT_SH"
  MAGIC="$(fmt_magic "$SRC")"
  SRC_FMT="$(fmt_of "$SRC")"
  if [ "$SRC_FMT" = "gzip" ]; then
    # 那**它**逐字**是**那 gzip（那**它**们**逐字是**那**两**件**事**：「那**资产名**是**什么**」与「那**字节**是**什么**」））。
    # 那**它**逐字**是**那 tar 的**那**一**层**（那**它**里面逐字**有**那**内层**那 zip）。
    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! tar xzf "$SRC" -C "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 gzip（那头四字节 $MAGIC），那 tar xzf 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi
    # 那**内层**那 zip 逐字**按内容**挑（按 01 裁的 S607-01-CONTAINER-FORM-RULING.md §3.1 逐字
    # 「**内层那一颗件包才算**。**外层不是那一颗件**（那**除非外层自己逐字含 component-meta.json**）」
    # 与 §3.1 那挑选规则逐字「**按内容挑** —— 内层容器的中央目录里**逐字含 `component-meta.json`
    # 且其 `id` 逐字等于要取的件名**；**名字 glob 只当第一道粗筛**」、
    # 「**零个候选与多过一个候选都判红、不取第一个、不猜、不许退回外层凑数**」）。
    # 那**判别源唯一**（按 C-994「判别源必须唯一、其余各处改成调它」）：
    # 那 fmt_pick_piece 那一格逐字是那**唯一**一处、它逐字走那三步
    # （第 1 步外层自己是不是那一颗件、第 2 步外层套内层、第 3 步 gzip/tar 先解开）。
    # 那**三步**逐字治的是这一族那**八件**（它们逐字是「外层即件包」那一档、
    # 旧版逐字只有「解开外层找内层」那一步它们逐字被判成「候选逐字是 无」）。
    INNER=""
    if ! INNER="$(fmt_pick_piece "$SRC""$STAGE/$id/unpacked""$id"2>"$STAGE/$id/pick.err")"; then
      echo "::error title=$id 挑不出那一颗件::$(tr '\n'' ' < "$STAGE/$id/pick.err")"
      echo "::error:: 而那按内容挑逐字挑不出、按 C-05 不猜、不取第一个。"
      missing=$((missing+1)); continue
    fi
    cp -f "$INNER" "$ASSETS/$id.zip"
    printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 gzip，那内层取出来）\n""$id""$asset""$id"
  elif [ "$SRC_FMT" = "zip" ] || [ "$SRC_FMT" = "zip64" ]; then
    # 那**它**逐字是那 zip（那 CI artifact 那一族逐字是「zip 套内层」那个形状：
    # 外层逐字装着那**内层**那一个件包、内层才是那一颗件）。
    # 旧版逐字**没有**这一支（它逐字把那外层当件包直接用、
    # 那 unzip -p 逐字**读不到** component-meta.json、那 meta_json 逐字是那空）。
    # 缺陷名（C-102）**「外层套内层」那一档逐字按「外层中央目录条目数 = 1」判** ——
    # 旧版逐字是 `if [ "$OUTER_ENTRIES" = "1" ]`，
    # 那**一个判据要判两件不同的事**（按 C-135 那不是同一问）：
    # · 「那 CI artifact 那一族」逐字外层**只有一条**条目（一条逐字是那内层那一个 .zip）；
    # · 而那 git 那一件的真件包逐字外层**有 239 条**条目（它**就是**那一颗件、它**不是**套着）。
    # 一个判据判两件事、它在别的形状上**会错**。
    # 那**处置**：一档逐字**按字节**判（按 C-994 的立论：形态按头字节魔数判、不按名字）——
    # 那**内层**逐字**解出来**、它的头四字节逐字**是** 504b* 才算它是那 zip 容器。
    # 「哪一层才算那一颗件」那一格**归 01 裁**（本处**不**拍板、只把事实摆出来）。
    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! unzip -q -o "$SRC" -d "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 zip（头四字节 $MAGIC），那 unzip 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi
    # 那**判别源唯一**（按 C-994「判别源必须唯一、其余各处改成调它」）：
    # 那 fmt_pick_piece 那一格逐字是那**唯一**一处、它逐字走那三步
    # （第 1 步外层自己是不是那一颗件、第 2 步外层套内层、第 3 步 gzip/tar 先解开）。
    # 那**第 1 步**逐字治的是这一族那**八件**（它们逐字是「外层即件包」那一档：
    # 那 Release 资产逐字**就是**那件包本身、旧版逐字只有「解开外层找内层」那一步
    # 它们逐字被判成「那候选逐字是 无」）。
    # 那 zip 那一档**不**解开（第 1 步逐字看那**外层自己**）。
    INNER=""
    if ! INNER="$(fmt_pick_piece "$SRC""$STAGE/$id/unpacked""$id"2>"$STAGE/$id/pick.err")"; then
      echo "::error title=$id 挑不出那一颗件::$(tr '\n'' ' < "$STAGE/$id/pick.err")"
      echo "::error:: 而那按内容挑逐字挑不出、按 C-05 不猜、不取第一个。"
      missing=$((missing+1)); continue
    fi
    if [ "$INNER" = "$SRC" ]; then
      # 那**外层自己**就是那一颗件（它逐字已经在 $ASSETS/$id.zip 了、不用再搬）。
      printf "  ✓ %-9s %s → %s.zip（那资产逐字**就是**那一颗件）\n""$id""$asset""$id"
    else
      cp -f "$INNER" "$ASSETS/$id.zip"
      printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 zip 套内层，那内层取出来）\n""$id""$asset""$id"
    fi
  else
    # 那**它**逐字**既不是** gzip **也不是** zip（形态逐字是那 fmt.sh 逐字给的）。
    # 旧版逐字把它**当**那 zip 那一支用（那 unzip -p 逐字读不到）。
    # 现在逐字**报出来**（按 C-05「缺字段就报缺」、不猜填）。
    echo "::error title=$id 容器形态不认::那资产逐字是那 $SRC_FMT（头四字节 $MAGIC），既不是 zip 也不是 gzip。"
    echo "::error:: 处置：先核那一份的容器形态（scripts/verify/probe-container-form.sh），那**不是** dispatch 能解决的。"
    missing=$((missing+1)); continue
  fi

  # 缺陷名（C-102）**那一格逐字只核那**名字**、它逐字**不**核那件自己的 buildSha** —
  # 旧版逐字**没有**这一格
  # 那**旧产物**那一档（那 miss_stale 那一族）逐字**只**比那**资产名**
  # （那「Release $tag 里没有**一个**叫 $asset」那一族）
  # 那**名字**逐字**对得上**时、那**字节**逐字**可能**是**旧代码**产的
  # 那**后果**（06 本轮逐字核出、Lead 本机实读那 job log 逐字核过）：
  # · 那 manifest 那一趟逐字红在这一格：
  # `::[error]文件名里的内容哈希与实际不符: component-llvm-21.1.0-e1b5dcff3b3c-android-arm64.zip（名 e1b5dcff3b3c vs 实 ac60a820aaaf）`
  # 那**判据**逐字是「那取到的那一份逐字是不是那当前代码逐字该有的那一份」那一个事实上
  # （它逐字**不**由「那一件逐字是什么」顶掉）
  # 那**处置**：取件那一族逐字要逐字核那件自己的 buildSha
  # 那**期望值**逐字**不**是那 pin 记录（那 pin 记录逐字是那**上一次**固化那一份的、
  # 它在「刚固化完」那一趟逐字**必然陈旧**、拿它当判据逐字**恒红**）
  # （那 `scripts/verify/check-frozen-freshness.sh` 那一族逐字**已经**是那一族）
  # 那**期望值**逐字是「那产出它的那一次构建」的代码 sha：
  # · 首选：那**当前**那一趟构建逐字给的 `GITHUB_SHA`
  # · 次选：那**产物自己**逐字带的那 buildRun 逐字**反查**到的那一次构建
  # · 兜底：那**两者**逐字**都**没有时逐字**不判**（按 C-05 报缺、依 C-104 逐字**不并**）
  #
  # 那**读法**逐字**在这一层**（那**判据**逐字是「那件包自己那份 component-meta.json 逐字**在哪一层**」）
  # 件包逐字**可能**是「外层即件包」那一档（那 meta 逐字**在**那根部）、
  # 和那也**可能**是「外层套内层」那一档（那 meta 逐字**在**那**里面**那一层）
  # 那**这两**档逐字**已经**在**上面**逐字**摘**出来了（那 `fmt_pick_piece` 那一族逐字
  # 逐字把那**内层**那一份逐字**放**到那 `$ASSETS/$id.zip`、根部逐字**就是**那一颗件）
  # 那 `unzip -p` 逐字**读**那根部、那**判据**逐字**成立**
  # 缺陷名（C-102）**那一格逐字在**那件包**落位之前**逐字读**、它逐字**读不到****
  # 旧版逐字**没有**这一格（那**旧**那一格逐字**在**那 `:451` 到 `:462` 那一段）
  # 那**旧**那一格逐字**在**那 `cp -f "$INNER" "$ASSETS/$id.zip"` **之前**逐字读
  # （那**落位**逐字在 `:524` / `:568` / `:607`）
  # 那**旧**那一格逐字读到的是**上一趟**留下的那一份、值逐字**是空**
  # （那 `.fetch` 那一族逐字**每趟**都清那目录、那 `$ASSETS` 逐字**不**清）
  # 那**后果**（Lead 本机实读那 job log 逐字核过）：
  # `::[error]那件自己的 buildSha 逐字是 、产出它的那一次构建逐字是 7a600c072b43`
  # ——那**值**逐字**是空**的、那**不是**「那字节不配」、
  # 它逐字是「那值逐字**根本**没读到」
  # （那**九件**逐字**都**是这一句、那「清单读失败 8」逐字归零了、
  # 那 buildSha 那一族逐字**生效**了、它逐字**生效过头**了）
  # 那**处置**：一格逐字**落**在那件包**落位之后**
  # 那**读法**逐字**走**那**同一**个 `$ASSETS/$id.zip`（根部逐字**已经**是那一颗件）
  # 那**读法**逐字**只**认那**根部**、它逐字**不**另找一路（按 C-104 逐字**不并**）
  _pkg_sha=""
  _pkg_run=""
  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$STAGE/$id/bsha.json" 2>/dev/null \
      && [ -s "$STAGE/$id/bsha.json" ]; then
    _pkg_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$STAGE/$id/bsha.json")"
    _pkg_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$STAGE/$id/bsha.json")"
  fi
  rm -f "$STAGE/$id/bsha.json"

  # 缺陷名（C-102）**那一格逐字拿那**当前**那一趟构建逐字当那**期望值**、它逐字**恒红****
  # 旧版逐字是 `_want_sha="${GITHUB_SHA:-}"`（那**第一档**逐字**总是**那当前 HEAD）
  # 那**第一档**逐字只适用「那件包逐字就是**这一趟**固化出来的那一件」
  # （当前 HEAD 逐字每一次逐字都在变、那**旧**一轮固化的件包逐字
  # **legitimately** 逐字不是那当前 HEAD 产的）
  # 那**后果**（Lead 本机实读那 job log 逐字核过）：
  # `::[error]那件自己的 buildSha 逐字是 6ee61a0f6ed2、产出它的那一次构建逐字是 fc80bf01e090`
  # `::[error]那件自己的 buildSha 逐字是 6aceba012cde、产出它的那一次构建逐字是 fc80bf01e090`
  # （…九件逐字都是这一句、那「旧产物 9」逐字**恒**红）
  # 那**判据**逐字是「那一格逐字要核的那 buildSha 逐字、它逐字要跟**产出它的那一趟**逐字比、
  # 它逐字**不**跟那**当前**那一趟逐字比」那一个事实上
  # （它逐字**不**由「那一件逐字是什么」顶掉）
  # 那**处置**：那**第一档**逐字只在该件包逐字就是**这一趟**固化出来的那一件逐字时才适用
  # · 判「是不是这一趟产的那一件」：那**产物自己**逐字带的那 `buildRun` 逐字**等于**那当前那一趟的 run id
  # （当前 run id 逐字是那 `GITHUB_RUN_ID`）
  # · 否则：和那**一律**走那 `buildRun` **反查**（件包逐字自带 `buildRun` 逐字
  # 就是产出它的那一趟 run）
  # · 反查逐字问不到时逐字**不判**、它逐字**不**是「那不是」
  _want_sha=""
  _want_src=""
  _cur_run="${GITHUB_RUN_ID:-}"
  if [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ] && [ -n "$_cur_run" ] && [ "$_pkg_run" = "$_cur_run" ]; then
    # 件包逐字就是**这一趟**固化出来的那一件（它逐字自带的那 buildRun 逐字等于那当前 run）
    _want_sha="${GITHUB_SHA:-}"
    _want_src="GITHUB_SHA（件包逐字就是这一趟产的那一件）"
  elif [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ]; then
    # 和那**一律**走那 buildRun **反查**（件包逐字自带 buildRun 逐字就是产出它的那一趟 run）
    _want_sha="$(run_head_sha_of "$_pkg_run")"
    _want_src="产物自带的 buildRun=$_pkg_run 反查（产出它的那一趟）"
  fi
  if [ -n "$_want_sha" ]; then
    if [ "$_pkg_sha" != "$_want_sha" ]; then
      echo "::error title=$id 不是当前这份代码产的::那件自己的 buildSha 逐字是 ${_pkg_sha:0:12}、产出它的那一次构建逐字是 ${_want_sha:0:12}"
      echo "::error:: （那**旧字节不能冒充新代码的产物**、那**不**许拿那旧的那一份凑）"
      echo "::error:: 处置：dispatch 那一件重新固化（新的指纹逐字**才**是当前这份代码的）。"
      miss_stale=$((miss_stale+1)); missing=$((missing+1))
      rm -rf "${STAGE:?}/$id"
      continue
    fi
    echo "[ok] $id 那件自己的 buildSha 逐字与那产出它的那一次构建一致（$_want_src）"
  elif [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ]; then
    # 那**判据**逐字**自洽**（它逐字**不**依赖那陈旧的 pin 记录）
    echo "[ok] $id 那件逐字**是**它自己带的那一次构建造出来的（buildRun=$_pkg_run）"
  else
    # 那**期望值**逐字**读不到**时逐字**不判**（按 C-05 报缺、依 C-104 逐字**不并**）
    echo "::notice title=$id 那期望值读不到::那 GITHUB_SHA 逐字**没有**、产物自带的 buildRun 逐字**也**没有"
    echo "::notice:: （那**判据**逐字**答不出**、它逐字**不**判成「那不是当前这份」、按 C-05 报缺）"
  fi

  # 格三（是不是我们要的那一代）：把这一件的两个标识读出来，供循环后那一格用。
  # buildSha 同一 commit 上的多次构建相同 => 它标「同一份代码」
  # buildRun 每次流水线都不同 => 它标「哪一次流水线造了它」
  # 而 01 今天裁：这一格保证不了同代（ 每件各自 dispatch 一条 run 
  # 八件按构造就不可能同代）， 而「同代还是不同代」才是有用的那一格 
  # 不保证，只说出来（取件那一支是唯一一次看见全部八件的地方）。
  meta_json="$STAGE/$id/peek.json"
  rm -f "$meta_json"
  # 那 meta_json 逐字**从**那**件包**（那**它**们**逐字是**那**两**件**事**：「那**来源**是**什么**」与「那**内容**是**什么**」）取**一次**。
  # 那**它**逐字**从**那**内层**那 zip 取**（那**它**们**逐字是**那**两**件**事**）。
  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$meta_json" 2>/dev/null && [ -s "$meta_json" ]; then
    g_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$meta_json")"
    g_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$meta_json")"
  else
    g_sha=""; g_run=""
  fi
  rm -f "$meta_json"
  GEN_ROWS="${GEN_ROWS:-}${id}|${g_sha}|${g_run}\n"

  # 缺陷名（C-102）**那个 ok 计数器逐字恒是 0、一行输出逐字恒印 0** ——
  # 旧版逐字是 `ok=0`、那**全文再无任何** `ok=$((ok+1))`（旧版的 :171 同样没有），
  # 那 :542 那一行逐字「[fetch] 已取到 $ok 个」逐字**恒印 0**。
  # 那**后果**：一行落在**缺件**那一支（missing > 0 时才印）、
  # 一趟逐字「取到 6 件 · 缺 4 件」却**同时**逐字印「已取到 0 个」
  # 那**两行互相矛盾**、读的人只能信其中一行。
  # 那**处置**：这一行逐字**加一次**、它逐字只在这一件**真的落到 $ASSETS 之后**才加
  # （上面那几处 `continue` 逐字都是失败、些**不**算取到）。
  ok=$((ok+1))

done

# 这一格：把这一批的标识列出来去重，并把「同代还是不同代」说出来。
# 而「同代」不是判据，「同代还是不同代」才是（01 今天）。
if [ -n "${GEN_ROWS:-}" ]; then
  echo ""
  echo "--- 这一批件的构建标识（buildSha = 同一份代码 / buildRun = 哪一次流水线）---"
  printf '%s' "$GEN_ROWS" | while IFS='|' read -r gid gsha grun; do
    printf "  %-9s buildSha=%-42s buildRun=%s\n""$gid""${gsha:-(没有)}""${grun:-(没有)}"
  done
  runs="$(printf '%s' "$GEN_ROWS"| cut -d'|' -f3 | sed '/^$/d' | sort -u)"
  shas="$(printf '%s' "$GEN_ROWS"| cut -d'|' -f2 | sed '/^$/d' | sort -u)"
  n_run="$(printf '%s\n' "$runs" | grep -c . || true)"
  n_sha="$(printf '%s\n' "$shas" | grep -c . || true)"
  n_none="$(printf '%s' "$GEN_ROWS"| grep -c '||$' || true)"
  echo "去重后: buildRun $n_run 个 / buildSha $n_sha 个（$n_none 件没带那一格）"
  if [ "$n_none" -gt 0 ]; then
    echo "::notice title=这一批不完整::有 $n_none 件没带构建标识 「是不是这一代」对它们答不出"
  fi
  if [ "$n_run" -gt 1 ]; then
    echo "::notice title=这一批不同代::去重后 buildRun $n_run 个 这一批不是同一次流水线造的"
    echo "::notice:: 而那不是缺陷 —— 每件各自 dispatch 一条 run（01 今天裁定） 按构造就不可能同代。"
  else
    echo "::notice title=这一批同代::去重后 buildRun $n_run 个 这一批是同一次流水线造的"
  fi
  if [ "$n_sha" -gt 1 ]; then
    echo "::notice title=这一批不止一份代码::去重后 buildSha $n_sha 个 这一批跨了多个 commit"
  fi
fi
if [ "$missing" -gt 0 ]; then
  echo ""
  echo "::error title=取件不全::$missing / ${#PIECES[@]} 个件取不到（缺货 $miss_stock · 旧产物 $miss_stale · 清单读失败 $miss_read）"
  if [ "$miss_stock" -gt 0 ]; then
    echo "::error:: **缺货**（该 tag 一个 asset 都没有）→ dispatch 那一件即可"
  fi
  if [ "$miss_read" -gt 0 ]; then
    echo "::error:: **清单读失败**（那一份**没读到**）→ 先查那一次读（token / 限流 / 端点），**不是** dispatch 能解决的"
  fi
  if [ "$miss_stale" -gt 0 ]; then
    echo "::error:: **旧产物**（有 asset 但名字对不上 = 改过 recipe 指纹变了）→ 必须 dispatch 重新固化，"
    echo "::error:: 否则会取到旧包、打出 APK、CI 全绿，而那个产物里没有本次改动的任何东西"
  fi
  echo "[fetch] 已取到 $ok 个。缺的那几个要单独构建，别在 APK 里编 —— 见 RELEASE-POLICY.md"
  exit 1
fi

echo ""
echo "[fetch] ${#PIECES[@]} 个件包全部取自 Release → $ASSETS/<id>.zip"
echo "[fetch] 启动时装它们（与 OTA 装件同一条路），APK 不再编译任何件"
