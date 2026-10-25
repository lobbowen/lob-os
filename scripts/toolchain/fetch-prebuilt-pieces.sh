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

cd "$(cd "$(dirname "$0")/../.." && pwd)"

ASSETS="container/app/src/main/assets/pieces"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

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
PIECES=(zlib openssl curl jq bash busybox ripgrep crypto)

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
  local tag="$1" asset="$2" dir="$3" url
  local hdr=()
  [ -n "${GITHUB_TOKEN:-}" ] && hdr=(-H "Authorization: Bearer $GITHUB_TOKEN")
  # curl 到 api.github.com 偶发 TLS 断（unexpected eof while reading）—— 重试三次。
  url="$(curl -fsSL --retry 3 --retry-delay 2 "${hdr[@]}" -H "Accept: application/vnd.github+json" \
          "https://api.github.com/repos/$REPO/releases/tags/$tag" 2>/dev/null \
        | node -e '
            let d = "";
            process.stdin.on("data", c => d += c).on("end", () => {
              let j;
              try {
                j = JSON.parse(d);
              } catch (e) {
                process.stderr.write("__FA_PARSE_FAILED__\\n");
                process.exit(3);
              }
              const a = (j.assets || []).find(x => x.name === process.argv[1]);
              if (a) process.stdout.write(a.browser_download_url);
            });
          ' -- "$asset" 2>/dev/null)"
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
  local tag="$1" hdr=()
  [ -n "${GITHUB_TOKEN:-}" ] && hdr=(-H "Authorization: Bearer $GITHUB_TOKEN")
  local body rc http
  #  缺陷名（C-102）**那读失败那一族逐字只带一个 curl 逐字自己的码、=> 而而那 HTTP 那一档逐字要另取**
  #    —— 而实际上那 rc 逐字**只是** curl 那一族逐字**自己**的退出码（=> 而那 22 逐字是 curl 的「HTTP >= 400」那个码），
  #    => 而而那 401 与那 404 与那 429 那**几档**逐字**共用**同一个 22、=> 而而它们逐字**分不出来**。
  #    => 而而那 HTTP 那一档逐字**能**另取（=> 而那 curl 逐字可以用 -w %{http_code} 逐字**带**出来），
  #    => 而而那**改法**：=> 而那 body 逐字走 stdout、=> 而那 HTTP 那一档逐字走 stderr、=> 而而那 rc 逐字**也**留着。
  #    => 而而那**调用方**逐字**能**分出那 401 与那 404 那**两**档。
  #    => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
  http="$(curl -sS -o /dev/null -w '%{http_code}' --retry 3 --retry-delay 2 "${hdr[@]}" \
          -H "Accept: application/vnd.github+json" \
          "https://api.github.com/repos/$REPO/releases/tags/$tag" 2>/dev/null || true)"
  body="$(curl -fsSL --retry 3 --retry-delay 2 "${hdr[@]}" -H "Accept: application/vnd.github+json" \
          "https://api.github.com/repos/$REPO/releases/tags/$tag" 2>/dev/null)"
  rc=$?
  if [ "$rc" -ne 0 ] || [ -z "$body" ]; then
    #  那一读逐字**失败**了：=> 而而那**不是**「那筐真的一件 asset 都没有」。
    #  => 而而那逐字**带**一个标记、=> 而而那**调用方**逐字能**分出**那两种。
    #  => 而而那 HTTP 那一档逐字**也**在标记里、=> 而而那**调用方**逐字**能**分出那 401 与那 404。
    LRA_HTTP="${http:-none}"
    LRA_RC="$rc"
    echo "__LRA_READ_FAILED__ rc=$rc http=${http:-none}" >&2
    return 1
  fi
  LRA_HTTP="${http:-none}"
  LRA_RC="$rc"
  printf '%s' "$body" \
    | node -e '
        let d = "";
        process.stdin.on("data", c => d += c);
        process.stdin.on("end", () => {
          let j;
          try { j = JSON.parse(d); } catch (e) { process.stderr.write("__LRA_PARSE_FAILED__\\n"); process.exit(3); }
          (j.assets || []).map(x => x.name).filter(n => !/\.sha256$/.test(n)).forEach(n => process.stdout.write(n + "\n"));
        });
      ' 2>/dev/null
}
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
    if [ -f "$lra_err" ]; then
      case "$(cat "$lra_err")" in
        *"http="*) lra_http="$(printf '%s' "$(cat "$lra_err")" | tr ' ' '\n' | sed -n 's/^http=//p' | head -1)" ;;
      esac
    fi
    if [ "$lra_rc" -ne 0 ]; then
      #  那一读逐字**失败**了（=> 而而那限流 / 鉴权过期 / 端点变了 / 网络 那一族）：
      #  => 而而那**不是**「那筐真的一件 asset 都没有」、=> 而而那**不**能按「缺货」处置。
      #  => 而而那 HTTP 那一档逐字**已经**被带出来（=> 而而那 401 与那 404 与那 429 那**几档**逐字**分得开**了）。
      echo "::notice title=$id 清单读失败::Release $tag 那一份**没读到**（=> 而而那 stdout 逐字是空、=> 而而那**不是**『那筐一个 asset 都没有』）。"
      echo "::notice:: HTTP 那一档 = ${lra_http:-未知}、=> 而而那 curl 逐字自己的码逐字**在**那标记里、=> 而而那**两档**逐字**不是**同一档。*"
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
  cp -f "$STAGE/$id/$asset" "$ASSETS/$id.zip"
  printf "  ✓ %-9s %s → %s.zip\n" "$id" "$asset" "$id"

  # 格三（是不是我们要的那一代）：把这一件的两个标识读出来，供循环后那一格用。
  #   buildSha  同一 commit 上的多次构建相同  => 它标「同一份代码」
  #   buildRun 每次流水线都不同            => 它标「哪一次流水线造了它」
  #    而 01 今天裁：这一格保证不了同代（ 每件各自 dispatch 一条 run  
  #    八件按构造就不可能同代），  而「同代还是不同代」才是有用的那一格 
  #    不保证，只说出来（取件那一支是唯一一次看见全部八件的地方）。
  meta_json="$STAGE/$id/peek.json"
  rm -f "$meta_json"
  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$meta_json" 2>/dev/null; then
    g_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$meta_json")"
    g_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$meta_json")"
  else
    g_sha=""; g_run=""
  fi
  rm -f "$meta_json"
  GEN_ROWS="${GEN_ROWS:-}${id}|${g_sha}|${g_run}\n"

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
