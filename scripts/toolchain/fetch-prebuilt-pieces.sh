#!/usr/bin/env bash
# 取件：把已固化的件包取进 APK，不编译。
# 取的是标准件包（component-<id>-<版本>.zip，内含 component-meta.json），与 OTA 装件同一种东西；
# 装配器只读包，不查外部清单（照抄 .deb）。
# 不铺 jniLibs：那是预装件的私有通道，装配器读不了，与 OTA 装件不同构；启动时统一走 ProgramInstallPipeline。
# 取不到就报错退出，不在这里回退去编译——那正是「APK 又开始编件」的由来，还会把「某个件没固化」藏起来。
# 缺件就明说缺哪个、去跑 workflow_dispatch build-<id>，见 RELEASE-POLICY.md。
set -euo pipefail

# 这一批件的构建标识行（每行 id|buildSha|buildRun），取件循环里累加、循环后去重。
# 必须在顶部初始化：脚本是 set -u，第一次累加时它还不存在，会报 unbound variable，取件在第一件之后就崩。
# 真机读数（2026-10-21）：那一轮只取到 zlib 一个，报错原文 fetch-prebuilt-pieces.sh:131: GEN_ROWS: unbound variable。
GEN_ROWS=""

# 落点用 BASH_SOURCE（而不是 $0）：build-apk.yml:329 是
#   `source scripts/toolchain/fetch-prebuilt-pieces.sh --print-pieces`
# source 时 $0 是「bash」，dirname 算出来是错的落点。缺陷名 C-102：同一份脚本按两种方式被调用，落点只认其中一种。
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

ASSETS="container/app/src/main/assets/pieces"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

#  --print-pieces 钩子（调用点 build-apk.yml:313 与 :329 都是 `source ... --print-pieces`）。
#  缺陷名 C-102：旧版没有这个钩子，source 会顺带把整个取件循环跑一遍（下载两遍、配额吃两遍），
#  stdout 是取件那份输出，调用方读不出件数。
#  处置：钩子落在这里，--print-pieces 只答件数。按 C-104：「跑整个取件」与「只报件数」不是同一问、不合并。
PRINT_PIECES_ONLY=0
for _arg in "${@:-}"; do
  [ "$_arg" = "--print-pieces" ] && PRINT_PIECES_ONLY=1
done

#  缺陷名 C-102：旧版把「读不到」压成一档，而 API 能答出三档成因、处置各不同：
#    403+body 含 "rate limit" = 限流（匿名读 60 次/小时）；403+"Bad credentials" = 鉴权（token 错或过期）；404 = tag 或端点不存在。
#    旧版只印「HTTP = 403」，三档分不出来，重试 / 换令牌 / 查 tag 对不上号。
#  C-103：两栏并列不互相顶替——「读 Release 清单」与「那次读有没有身份」。旧版把「有身份」当可选，匿名读 60 次/小时
#    （带身份 5000 次/小时），CI 上被限流（run 41118077153：9 件取到 3 件、6 件 403，而 28 分钟前同一格取到 9/9）；
#    处置：给那次读一个身份，缺身份报缺不静默匿名（C-05）。形状照抄 fetch-component-artifacts.sh:11-13。
GH_TOKEN_VALUE="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$GH_TOKEN_VALUE" ] && [ "$PRINT_PIECES_ONLY" -eq 0 ]; then
  echo "::error title=缺令牌::取件要读 GitHub 的 Release 清单，而那匿名读只有 60 次/小时"
  echo "::error:: 给 GH_TOKEN 或 GITHUB_TOKEN（=> 而 build-apk.yml 那一格逐字是 secrets.GITHUB_TOKEN）"
  echo "::error:: 这一格不猜 —— 没有身份就不读，**不**静默匿名。"
  exit 1
fi
export GH_TOKEN="$GH_TOKEN_VALUE"
#  唯一一份身份：两条真读的路都只走 gh_read_release，只认 GH_HDR，GH_HDR 只由这一份身份构造。
#  缺陷名 C-102：旧版两条路各认各的令牌名，workflow 给的是 GH_TOKEN，于是「有没有身份」那一格过了（GH_TOKEN 给了），
#  请求却没带身份（匿名读 60 次/小时，症状是 403 限流），看不出是令牌名不对。
GH_HDR=(-H "Authorization: Bearer $GH_TOKEN")

#  那一次读（Release 清单）的读数：全局 GH_HTTP（拿到的 HTTP 档，none = 没拿到）、
#    GH_FAIL_KIND（ok / ratelimit / auth / notfound / forbidden / empty / parse / http_<码>）、
#    GH_READ_TAG / GH_READ_BODY（读的是哪个 tag、body 落在哪）、GH_READ_RC（0 成 / 1 没成）。
GH_HTTP="none"
GH_FAIL_KIND="ok"
GH_READ_TAG=""
GH_READ_BODY=""
GH_READ_RC=1

#  list_release_assets 的读数只落一个档 ${STAGE}/.lra-readings，档才是真读。
#  缺陷名 C-102：LRA_HTTP / LRA_RC / LRA_KIND 只写不读，全文无读取点（grep 核过，三个名字只出现在那六行里）。
#  成因：list_release_assets 装在 $(...) 里被调（子壳），改不到调用方变量，调用方改从档里取读数。
#  按 C-993（新写的判别式必须有调用点）：三个变量删掉，读数只从档取。信息不丢：HTTP 档与失败档都在档里。

#  读 Release 清单：一次读（旧版读两次，一次 -w '%{http_code}' 只为拿 HTTP 档、一次取 body，各吃一次 API 配额）。
#  同一个 tag 读过一次就不再读第二次（配额有限）。
gh_read_release() {
  local tag="$1"
  if [ "$GH_READ_TAG" = "$tag" ]; then
    return "$GH_READ_RC"
  fi
  GH_READ_TAG="$tag"
#  body 落点先造出来（curl -o 要落点存在）。
#  mkdir -p 原来只在后半段，--print-pieces 提前 return 时没到那一段，body 落不下。
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
  # 三档分开，处置对得上号。
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

#  失败档说人话（标记是给机器看的，这一句是给人看的）。
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

#  crypto 不再作为独立一件取（它就是 openssl 的一部分）：openssl 包里 lib/ 下已有 libcrypto.so，
#  登记里那条独立 crypto 删掉，字节仍由 openssl 管（不是丢掉那份字节）。缺陷名 C-102。
#  PIECES 从那份表里算（01 裁）：取 notStandalone 不为 true 的那些（列法是「不为 true」，不是「为 false」；
#  其余项不写那一格，「没有」不等于「false」）。
#  下面这一行是 scripts/toolchain/pieces-from-table.js 生成的结果，必须仍是可 grep 的字面文本
#  （verify-apk-native.sh:215 与 build-apk.yml:275 都按文本 grep 它）；改表之后要重跑生成器、这一行跟着更新。
PIECES=(bash busybox crypto curl git jq openssl ripgrep zlib)

#  缺陷名 C-102：旧版那一读只说「没取到」，三处 return 1 分不出来——
#    名字没找到（url 是空）、下载失败、下载到空文件；调用方只看到「没取到」。
#    40214257950 那一趟就是第三个形状：others 非空、名字在里面、一次没取到，两条分支都不覆盖它。
#  按 C-102：空同时表示两件不同的事、第三个形状没有分支。改法：三处各带一个标记，调用方分得开。
#  按 C-220：04 那边落内容稿，这一笔是实现那一族。
fetch_asset() {
  local tag="$1" asset="$2" dir="$3" url fa_err
#  缺陷名 C-102：旧版只认 GITHUB_TOKEN，workflow 给的是 GH_TOKEN。于是「有没有身份」那一格过了，
#  请求却没带身份（匿名读 60 次/小时，症状 403 限流），看不出是令牌名不对。
#  改法：一次读只走 gh_read_release，只认 GH_HDR，GH_HDR 只由唯一一份身份（GH_TOKEN 或 GITHUB_TOKEN 择一）构造。
  if ! gh_read_release "$tag"; then
    #  那一读失败了（限流 / 鉴权 / 端点错三档分得开）。
    echo "__FA_READ_FAILED__ http=${GH_HTTP:-none} kind=${GH_FAIL_KIND}" >&2
    return 1
  fi
  #  那一次读成了，body 已落在落地档里（不用再读一次）。
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
    #  缺陷名 C-102：旧版把解析的 stderr 吞掉（2>/dev/null），调用方只看到「url 是空」，
    #  分不出是「名字不在」还是「那读没读到」。
    cat "$fa_err" >&2
    return 1
  fi
  if [ -z "$url" ]; then
    #  名字没被找到：可能「不在那筐里」，也可能「在而没读到」；两种分不出来，调用方要用 others 那一列来分。
    echo "__FA_URL_NOT_FOUND__" >&2
    return 1
  fi
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset" "$url" 2>/dev/null || { echo "__FA_DOWNLOAD_FAILED__" >&2; return 1; }
  [ -s "$dir/$asset" ] || { echo "__FA_EMPTY_FILE__" >&2; return 1; }
  # 顺手取伴随的 .sha256（与 zip 同名同 tag）。不取的话（缺陷名 C-102）下面校验分支的判据
  # `[ -f "$dir/$asset.sha256" ]` 恒为假、校验被跳过，取到的字节从没被校验过
  # （AGENTS.md 第七节要求：取件必须核字节数与声明一致）。
  sha_url="${url}.sha256"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset.sha256" "$sha_url" 2>/dev/null || true
  return 0
}

# 列出某 tag 下现有的 asset 名（每个一行），取不到时输出空串。
# 为什么需要：取件失败有两种性质，CI 上都是「取不到」——缺货（该 tag 一个 asset 都没有，处置是 dispatch）、
#   旧产物（有 asset 但名字对不上，改过 recipe 指纹变了；旧产物不能冒充新代码的产物，dispatch 之前必须让人看见，
#   否则会「取到旧包、打出 APK、全绿」）。2026-10-16 run 1065 就是后者。
# 缺陷名 C-102：旧版那个空同时表示「一件都没有」与「那一读没读到」，2>/dev/null 与 catch(e){} 吞掉所有失败
#   （实测读失败返回码 22、输出长度 0、stderr 空）。改法：两者分开，失败时 stdout 带标记；按 C-104 只有一条路。
list_release_assets() {
  local tag="$1"
  local body rc http kind
  #  缺陷名 C-102：旧版只带 curl 自己的退出码（22 是 HTTP >= 400），401 / 404 / 429 共用 22，分不出来。
  #  改法：一次读只做一次（旧版读两次各吃一次配额），HTTP 档与 body 一次取回，三档（限流 / 鉴权 / 端点错）分得开；
  #  令牌名只有 GH_HDR 一个（旧版只认 GITHUB_TOKEN，workflow 给的是 GH_TOKEN）。
  if ! gh_read_release "$tag"; then
    rc=1
    http="${GH_HTTP:-none}"
    kind="${GH_FAIL_KIND:-http_none}"
    # 标记带着 HTTP 档与失败档（旧版只有 rc 与 http）。
    echo "__LRA_READ_FAILED__ rc=$rc http=${http:-none} kind=${kind}" >&2
    # 读数落那一个档：三个全局变量只在函数自己的壳里有值，调用方装在 $(...) 里改不到；唯一读点在下面那个循环。
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

#  --print-pieces 钩子（调用点 build-apk.yml:329 `source ... --print-pieces; echo ${#PIECES[@]}`）。
#  缺陷名 C-102：旧版没有钩子，source 会顺带跑整个取件循环（下载两遍、配额吃两遍），调用方读不出件数。
#  处置：钩子落在这里，--print-pieces 只答件数、stdout 不多写（调用方自己 echo ${#PIECES[@]}）。
#  停法：被 source 时只 return（$0 是「bash」，exit 会把调用方的壳一起带走），直接执行时才 exit。
#  位置：落在所有函数定义完之后，不是 PIECES 那行之后（那时函数还没定义，source 会提前 return、调不到）。
if [ "$PRINT_PIECES_ONLY" -eq 1 ]; then
  if [ "${BASH_SOURCE[0]}" = "$0" ]; then exit 0; else return 0; fi
fi

mkdir -p "$ASSETS" "$STAGE"

missing=0
miss_stock=0
miss_stale=0
miss_read=0
ok=0

#  反查那一族：run id 反查到产出它的那一趟的 head sha。
#  期望值是产出它的那一趟，不是当前 HEAD；问不到时答不出（空串，按 C-05 不猜填）。
#  身份只用 GH_HDR（唯一一份身份，按 C-104 不并）。
run_head_sha_of() {
  local run="$1"
  [ -n "$run" ] || { printf ''; return 0; }
  local body="$STAGE/.run-$run.json"
  local http
  http="$(curl -sS -o "$body" -w '%{http_code}' --retry 3 --retry-delay 2 "${GH_HDR[@]}" \
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
    echo "::error::  gh workflow run $(bash scripts/toolchain/piece-workflow-name.sh "$id")   （见 RELEASE-POLICY.md）"
    #  缺陷名 C-102：旧版 `others="$(…)"` 在 set -euo pipefail 下是一条简单命令，被替换的命令非零时整趟当场退出，
    #  `lra_rc=$?` 那一行根本到不了，分族那一支永远走不到。实测：错的 REPO 那一趟只印出三个标记就退了。
    #  改法：赋值带 `|| true`，返回码另外收；stdout 与 stderr 分开取，HTTP 档从 stderr 落下的临时档里取。
    lra_err="${STAGE}/lra.err"
    lra_out="$(list_release_assets "$tag" 2>"$lra_err")" && lra_rc=0 || lra_rc=$?
    others="$lra_out"
    lra_http="none"
    lra_kind="none"
    # 读数从档取：三个全局变量只在函数自己的壳里有值，调用方装在 $(...) 里改不到。
    if [ -f "${STAGE}/.lra-readings" ]; then
      case "$(cat "$STAGE/.lra-readings")" in
        *"http="*) lra_http="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^http=//p' | head -1)" ;;
      esac
      case "$(cat "$STAGE/.lra-readings")" in
        *"kind="*) lra_kind="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^kind=//p' | head -1)" ;;
      esac
    fi
    if [ "$lra_rc" -ne 0 ]; then
      #  那一读失败了（限流 / 鉴权 / 端点变了 / 网络），不是「一件 asset 都没有」，不能按缺货处置。
      #  HTTP 档已被带出来（401 / 404 / 429 分得开了）。
      echo "::notice title=$id 清单读失败::Release $tag 那一份**没读到**（=> 而那 stdout 逐字是空、=> 而那**不是**『那筐一个 asset 都没有』）。"
      echo "::notice:: HTTP 那一档 = ${lra_http:-未知}、=> 而那**那一档** = ${lra_kind:-未知}、=> 而那 curl 逐字自己的码逐字**在**那标记里、=> 而那**两档**逐字**不是**同一档。*"
      echo "::notice:: 那一档逐字说人话：$(gh_fail_label "$lra_kind")。*"
      echo "::notice:: 处置：先查那一次读（token / 限流 / 端点），=> 而那**不是** dispatch 那一件能解决的。*"
      miss_read=$((miss_read+1))
    elif [ -z "$others" ]; then
      echo "::notice title=$id 缺货::Release $tag **一个 asset 都没有** —— 这一件从来没固化过，或固化被删了。"
      echo "::notice:: 处置：dispatch 那一件即可。*"
      miss_stock=$((miss_stock+1))
    elif printf "%s\n" "$others" | grep -qxF "$asset"; then
      #  第三个形状：名字在里面、一次没取到，两条分支都不覆盖它（旧版误判成「旧产物拦住了」）。
      #  真的成因在 fetch_asset 那一族里面（三处 return 1 分不出来）。
      echo "::notice title=$id 在筐里但这一次没取到::Release $tag 里**有** $asset，=> 而那**这一次**逐字**没取到**。"
      echo "::notice:: 处置：那是**取件**那一族的问题（限流 / 鉴权 / 传输 / 空文件），=> 而那**不是**『没固化过』、=> 而也**不是**『旧产物』。*"
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
  #   那份 .sha256 是裸摘要 + 换行（07 在 Release 上逐个核过，65 字节），而 sha256sum -c 要「摘要␣␣文件名」格式，
  #   所以不喂给它：自己取第一段与实算摘要比。
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

  # 整包放进 assets/pieces/，启动时由装配器解包安装，与 OTA 装件同路。
  # 落名 .zip：件包就用 zip（照抄 .deb 的 ar 换成 Android 原生就有的 zip），装配器 java.util.zip 直接解，不需第三方库。
  # 别用 .tar.gz —— aapt2 会把 assets 下的 .tar.gz 解开并剥掉 .gz 后缀。
  # 缺陷名 C-102：旧版把资产名后缀当格式保证。tool-git 底下八件名字尾都是 .zip、内容却是 gzip，
  # unzip -p 报「End-of-central-directory signature not found」，meta_json 是空，git 那一件进不了 APK。
  # 处置：取件按字节真形态分支（解包分 gzip / zip；meta_json 从内层 zip 再取一次；校验对整包算 sha256）。
  SRC="$STAGE/$id/$asset"
  cp -f "$SRC" "$ASSETS/$id.zip"
  #  缺陷名 C-102 / C-994：这里是第二套容器判别源。旧版用 `od -An -tx1 -N4` 加一条三值分支
  #  （1f8b0800 / 1f8b0801 / 1f8b0808），而 scripts/toolchain/fmt.sh 已判同一件事
  #  （:14 的 head -c 4 | od | tr，:16 的 504b* 是 zip、:17 的 1f8b* 是 gzip），两套各判各的，还有一套没有 zip64 档。
  #  处置：这一处改成调 fmt.sh，MAGIC 那一格删掉；判别内容不变。新增的 zip64 也是 zip 容器，zip 分支照样直接用。
  FMT_SH="$(cd "$(dirname "$0")" && pwd)/fmt.sh"
  [ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; missing=$((missing+1)); continue; }
  # shellcheck source=../toolchain/fmt.sh
  . "$FMT_SH"
  MAGIC="$(fmt_magic "$SRC")"
  SRC_FMT="$(fmt_of "$SRC")"
  if [ "$SRC_FMT" = "gzip" ]; then
    # 它是 gzip（外层的 tar，里面有内层 zip）；资产名是什么与字节是什么是两件事。
    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! tar xzf "$SRC" -C "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 gzip（那头四字节 $MAGIC），=> 而那 tar xzf 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi
    # 内层那一颗件按内容挑（01 裁的 S607-01-CONTAINER-FORM-RULING.md §3.1：内层那一颗件包才算，
    #   外层不是那一颗件，除非外层自己含 component-meta.json；挑选按内容——内层中央目录含 component-meta.json
    #   且其 id 等于要取的件名，名字 glob 只当第一道粗筛；零个候选与多过一个候选都判红，不取第一个、不猜）。
    # 判别源唯一（按 C-994）：fmt_pick_piece 是唯一一处，走三步（外层自己是不是那一颗件 / 外层套内层 / gzip 先解开）。
    # 三步治的是那八件「外层即件包」那一档（旧版只有「解开外层找内层」一步，它们被判成「候选是 无」）。
    INNER=""
    if ! INNER="$(fmt_pick_piece "$SRC" "$STAGE/$id/unpacked" "$id" 2>"$STAGE/$id/pick.err")"; then
      echo "::error title=$id 挑不出那一颗件::$(tr '\n' ' ' < "$STAGE/$id/pick.err")"
      echo "::error:: 而那按内容挑逐字挑不出、=> 而按 C-05 不猜、=> 而不取第一个。"
      missing=$((missing+1)); continue
    fi
    cp -f "$INNER" "$ASSETS/$id.zip"
    printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 gzip，那内层取出来）\n" "$id" "$asset" "$id"
  elif [ "$SRC_FMT" = "zip" ] || [ "$SRC_FMT" = "zip64" ]; then
    # 它是 zip（CI artifact 那一族是「zip 套内层」：外层装着内层那一个件包，内层才是那一颗件）。
    # 旧版没有这一支，把外层当件包直接用，unzip -p 读不到 component-meta.json、meta_json 是空。
    #  缺陷名 C-102：旧版按「外层中央目录条目数 = 1」判「外层套内层」，一个判据判两件事（按 C-135 不是同一问）：
    #    CI artifact 外层只有一条条目；而 git 那件的真件包外层有 239 条（它就是那一颗件、不是套着）。别的形状上会错。
    #  处置：按字节判（按 C-994：形态按头字节魔数判、不按名字）——内层解出来头四字节是 504b* 才算 zip 容器。
    #  哪一层才算那一颗件归 01 裁，本处不拍板、只把事实摆出来。
    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! unzip -q -o "$SRC" -d "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 zip（头四字节 $MAGIC），=> 而那 unzip 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi
    # 判别源唯一（按 C-994）：fmt_pick_piece 是唯一一处，走三步（外层自己是不是那一颗件 / 外层套内层 / gzip 先解开）。
    # 第 1 步治的是那八件「外层即件包」那一档（Release 资产就是件包本身；旧版只有「解开外层找内层」一步，被判成「候选是 无」）。
    # zip 那一档不解开（第 1 步看外层自己）。
    INNER=""
    if ! INNER="$(fmt_pick_piece "$SRC" "$STAGE/$id/unpacked" "$id" 2>"$STAGE/$id/pick.err")"; then
      echo "::error title=$id 挑不出那一颗件::$(tr '\n' ' ' < "$STAGE/$id/pick.err")"
      echo "::error:: 而那按内容挑逐字挑不出、=> 而按 C-05 不猜、=> 而不取第一个。"
      missing=$((missing+1)); continue
    fi
    if [ "$INNER" = "$SRC" ]; then
      # 外层自己就是那一颗件（已经在 $ASSETS/$id.zip 了，不用再搬）。
      printf "  ✓ %-9s %s → %s.zip（那资产逐字**就是**那一颗件）\n" "$id" "$asset" "$id"
    else
      cp -f "$INNER" "$ASSETS/$id.zip"
      printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 zip 套内层，那内层取出来）\n" "$id" "$asset" "$id"
    fi
  else
    # 它既不是 gzip 也不是 zip（形态是 fmt.sh 给的）。旧版把它当 zip 那一支用（unzip -p 读不到）。
    # 现在报出来（按 C-05「缺字段就报缺」，不猜填）。
    echo "::error title=$id 容器形态不认::那资产逐字是那 $SRC_FMT（头四字节 $MAGIC），=> 而那既不是 zip 也不是 gzip。"
    echo "::error:: 处置：先核那一份的容器形态（scripts/verify/probe-container-form.sh），=> 而那**不是** dispatch 能解决的。"
    missing=$((missing+1)); continue
  fi

  #  缺陷名 C-102：旧版只核资产名、不核件自己的 buildSha，名字对得上时字节可能是旧代码产的。
  #  后果（06 核出、Lead 实读 job log 核过）：manifest 那一趟红在「文件名里的内容哈希与实际不符」——
  #    component-llvm-21.1.0-e1b5dcff3b3c-android-arm64.zip（名 e1b5dcff3b3c vs 实 ac60a820aaaf）。
  #  处置：核件自己的 buildSha。期望值不是 pin 记录（它是上一次固化那份、刚固化完必然陈旧），而是产出它的那一次构建的 sha：
  #  首选 GITHUB_SHA，次选产物自带 buildRun 反查，都没有时不判（C-05 报缺、C-104 不并）。读法看 component-meta.json 在哪一层
  #  （fmt_pick_piece 已把内层放到 $ASSETS/$id.zip），且必须落在件包落位之后读——旧版在读之前读，值是空（九件都这样）。
  _pkg_sha=""
  _pkg_run=""
  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$STAGE/$id/bsha.json" 2>/dev/null \
      && [ -s "$STAGE/$id/bsha.json" ]; then
    _pkg_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$STAGE/$id/bsha.json")"
    _pkg_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$STAGE/$id/bsha.json")"
  fi
  rm -f "$STAGE/$id/bsha.json"

  #  缺陷名 C-102：旧版拿当前这趟构建当期望值（`_want_sha="${GITHUB_SHA:-}"`），于是恒红。
  #  第一档只适用「件包就是这一趟固化出来的那一件」；当前 HEAD 每次都在变，旧一轮固化的件包合法地不是它产的。
  #  后果（Lead 实读 job log 核过）：`::[error]那件自己的 buildSha 是 6ee61a0f6ed2、产出它的那一次构建是 fc80bf01e090`
  #  与 6aceba012cde 那条，九件都是这一句，「旧产物 9」恒红。
  #  判据：要核的 buildSha 跟产出它的那一趟比，不跟当前那趟比。
  #  处置：件包自带 buildRun 等于当前 GITHUB_RUN_ID 时才用 GITHUB_SHA；否则走 buildRun 反查；问不到时不判。
  _want_sha=""
  _want_src=""
  _cur_run="${GITHUB_RUN_ID:-}"
  if [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ] && [ -n "$_cur_run" ] && [ "$_pkg_run" = "$_cur_run" ]; then
    # 件包就是这一趟固化出来的那一件（自带的 buildRun 等于当前 run）。
    _want_sha="${GITHUB_SHA:-}"
    _want_src="GITHUB_SHA（=> 而那件包逐字就是这一趟产的那一件）"
  elif [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ]; then
    # 一律走 buildRun 反查（件包自带的 buildRun 就是产出它的那一趟 run）。
    _want_sha="$(run_head_sha_of "$_pkg_run")"
    _want_src="产物自带的 buildRun=$_pkg_run 反查（=> 而那产出它的那一趟）"
  fi
  if [ -n "$_want_sha" ]; then
    if [ "$_pkg_sha" != "$_want_sha" ]; then
      echo "::error title=$id 不是当前这份代码产的::那件自己的 buildSha 逐字是 ${_pkg_sha:0:12}、=> 而那产出它的那一次构建逐字是 ${_want_sha:0:12}"
      echo "::error:: （=> 而那**旧字节不能冒充新代码的产物**、=> 而那**不**许拿那旧的那一份凑）"
      echo "::error:: 处置：dispatch 那一件重新固化（=> 而那新的指纹逐字**才**是当前这份代码的）。"
      miss_stale=$((miss_stale+1)); missing=$((missing+1))
      rm -rf "${STAGE:?}/$id"
      continue
    fi
    echo "[ok] $id 那件自己的 buildSha 逐字与那产出它的那一次构建一致（$_want_src）"
  elif [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ]; then
    # 判据自洽（不依赖那陈旧的 pin 记录）。
    echo "[ok] $id 那件逐字**是**它自己带的那一次构建造出来的（buildRun=$_pkg_run）"
  else
    # 期望值读不到时不判（按 C-05 报缺、依 C-104 不并）。
    echo "::notice title=$id 那期望值读不到::那 GITHUB_SHA 逐字**没有**、=> 而那产物自带的 buildRun 逐字**也**没有"
    echo "::notice:: （=> 而那**判据**逐字**答不出**、=> 而那它逐字**不**判成「那不是当前这份」、=> 而那按 C-05 报缺）"
  fi

  # 格三（是不是我们要的那一代）：把这一件的两个标识读出来，供循环后那一格用。
  #   buildSha 同一 commit 上的多次构建相同，标「同一份代码」；buildRun 每次流水线都不同，标「哪一次流水线造了它」。
  #   01 裁：这一格保证不了同代（每件各自 dispatch 一条 run，八件按构造就不可能同代），
  #   而「同代还是不同代」才是有用的那一格——不保证，只说出来（取件是唯一一次看见全部八件的地方）。
  meta_json="$STAGE/$id/peek.json"
  rm -f "$meta_json"
  # meta_json 从件包取一次（$ASSETS/$id.zip 的根部，来源与内容两件事都在里面）。
  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$meta_json" 2>/dev/null && [ -s "$meta_json" ]; then
    g_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$meta_json")"
    g_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$meta_json")"
  else
    g_sha=""; g_run=""
  fi
  rm -f "$meta_json"
  GEN_ROWS="${GEN_ROWS:-}${id}|${g_sha}|${g_run}\n"

  #  缺陷名 C-102：旧版那个 ok 计数器恒是 0——`ok=0` 之后全文没有任何 `ok=$((ok+1))`，
  #  「[fetch] 已取到 $ok 个」恒印 0，于是「取到 6 件 · 缺 4 件」与「已取到 0 个」两行互相矛盾。
  #  处置：这一行加一次，只在这一件真的落到 $ASSETS 之后才加（上面那几处 continue 都是失败、不算取到）。
  ok=$((ok+1))

done

# 这一格：把这一批的标识列出来去重，并把「同代还是不同代」说出来（「同代」不是判据）。
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
