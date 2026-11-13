#!/usr/bin/env bash

set -euo pipefail

GEN_ROWS=""

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

ASSETS="container/app/src/main/assets/pieces"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

PRINT_PIECES_ONLY=0
for _arg in "${@:-}"; do
  [ "$_arg" = "--print-pieces" ] && PRINT_PIECES_ONLY=1
done

GH_TOKEN_VALUE="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$GH_TOKEN_VALUE" ] && [ "$PRINT_PIECES_ONLY" -eq 0 ]; then
  echo "::error title=缺令牌::取件要读 GitHub 的 Release 清单，而那匿名读只有 60 次/小时"
  echo "::error:: 给 GH_TOKEN 或 GITHUB_TOKEN（build-apk.yml 那一格逐字是 secrets.GITHUB_TOKEN）"
  echo "::error:: 这一格不猜 —— 没有身份就不读，**不**静默匿名。"
  exit 1
fi
export GH_TOKEN="$GH_TOKEN_VALUE"

GH_HDR=(-H "Authorization: Bearer $GH_TOKEN")

GH_HTTP="none"
GH_FAIL_KIND="ok"
GH_READ_TAG=""
GH_READ_BODY=""
GH_READ_RC=1

gh_read_release() {
  local tag="$1"
  if [ "$GH_READ_TAG" = "$tag" ]; then
    return "$GH_READ_RC"
  fi
  GH_READ_TAG="$tag"

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

PIECES=(bash busybox crypto curl git jq openssl ripgrep zlib)

fetch_asset() {
  local tag="$1" asset="$2" dir="$3" url fa_err

  if ! gh_read_release "$tag"; then

    echo "__FA_READ_FAILED__ http=${GH_HTTP:-none} kind=${GH_FAIL_KIND}" >&2
    return 1
  fi

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

    cat "$fa_err" >&2
    return 1
  fi
  if [ -z "$url" ]; then

    echo "__FA_URL_NOT_FOUND__" >&2
    return 1
  fi
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset" "$url" 2>/dev/null || { echo "__FA_DOWNLOAD_FAILED__" >&2; return 1; }
  [ -s "$dir/$asset" ] || { echo "__FA_EMPTY_FILE__" >&2; return 1; }

  sha_url="${url}.sha256"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset.sha256" "$sha_url" 2>/dev/null || true
  return 0
}

list_release_assets() {
  local tag="$1"
  local body rc http kind

  if ! gh_read_release "$tag"; then
    rc=1
    http="${GH_HTTP:-none}"
    kind="${GH_FAIL_KIND:-http_none}"

    echo "__LRA_READ_FAILED__ rc=$rc http=${http:-none} kind=${kind}" >&2

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

if [ "$PRINT_PIECES_ONLY" -eq 1 ]; then

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

    lra_err="${STAGE}/lra.err"
    lra_out="$(list_release_assets "$tag" 2>"$lra_err")" && lra_rc=0 || lra_rc=$?
    others="$lra_out"
    lra_http="none"
    lra_kind="none"

    if [ -f "${STAGE}/.lra-readings" ]; then
      case "$(cat "$STAGE/.lra-readings")" in
        *"http="*) lra_http="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^http=//p' | head -1)" ;;
      esac
      case "$(cat "$STAGE/.lra-readings")" in
        *"kind="*) lra_kind="$(printf '%s' "$(cat "$STAGE/.lra-readings")" | tr ' ' '\n' | sed -n 's/^kind=//p' | head -1)" ;;
      esac
    fi
    if [ "$lra_rc" -ne 0 ]; then

      echo "::notice title=$id 清单读失败::Release $tag 那一份**没读到**（那 stdout 逐字是空、那**不是**『那筐一个 asset 都没有』）。"
      echo "::notice:: HTTP 那一档 = ${lra_http:-未知}、那**那一档** = ${lra_kind:-未知}、那 curl 逐字自己的码逐字**在**那标记里、那**两档**逐字**不是**同一档。*"
      echo "::notice:: 那一档逐字说人话：$(gh_fail_label "$lra_kind")。*"
      echo "::notice:: 处置：先查那一次读（token / 限流 / 端点），那**不是** dispatch 那一件能解决的。*"
      miss_read=$((miss_read+1))
    elif [ -z "$others" ]; then
      echo "::notice title=$id 缺货::Release $tag **一个 asset 都没有** —— 这一件从来没固化过，或固化被删了。"
      echo "::notice:: 处置：dispatch 那一件即可。*"
      miss_stock=$((miss_stock+1))
    elif printf "%s\n" "$others" | grep -qxF "$asset"; then

      echo "::notice title=$id 在筐里但这一次没取到::Release $tag 里**有** $asset，那**这一次**逐字**没取到**。"
      echo "::notice:: 处置：那是**取件**那一族的问题（限流 / 鉴权 / 传输 / 空文件），那**不是**『没固化过』、也**不是**『旧产物』。*"
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

  SRC="$STAGE/$id/$asset"
  cp -f "$SRC" "$ASSETS/$id.zip"

  FMT_SH="$(cd "$(dirname "$0")" && pwd)/fmt.sh"
  [ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在 —— 容器判别源必须唯一"; missing=$((missing+1)); continue; }

  . "$FMT_SH"
  MAGIC="$(fmt_magic "$SRC")"
  SRC_FMT="$(fmt_of "$SRC")"
  if [ "$SRC_FMT" = "gzip" ]; then

    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! tar xzf "$SRC" -C "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 gzip（那头四字节 $MAGIC），那 tar xzf 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi

    INNER=""
    if ! INNER="$(fmt_pick_piece "$SRC" "$STAGE/$id/unpacked" "$id" 2>"$STAGE/$id/pick.err")"; then
      echo "::error title=$id 挑不出那一颗件::$(tr '\n' ' ' < "$STAGE/$id/pick.err")"
      echo "::error:: 而那按内容挑逐字挑不出、按 C-05 不猜、不取第一个。"
      missing=$((missing+1)); continue
    fi
    cp -f "$INNER" "$ASSETS/$id.zip"
    printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 gzip，那内层取出来）\n" "$id" "$asset" "$id"
  elif [ "$SRC_FMT" = "zip" ] || [ "$SRC_FMT" = "zip64" ]; then

    rm -rf "$STAGE/$id/unpacked"
    mkdir -p "$STAGE/$id/unpacked"
    if ! unzip -q -o "$SRC" -d "$STAGE/$id/unpacked" 2>/dev/null; then
      echo "::error title=$id 解包失败::那资产逐字是那 zip（头四字节 $MAGIC），那 unzip 逐字**读不通**。"
      missing=$((missing+1)); continue
    fi

    INNER=""
    if ! INNER="$(fmt_pick_piece "$SRC" "$STAGE/$id/unpacked" "$id" 2>"$STAGE/$id/pick.err")"; then
      echo "::error title=$id 挑不出那一颗件::$(tr '\n' ' ' < "$STAGE/$id/pick.err")"
      echo "::error:: 而那按内容挑逐字挑不出、按 C-05 不猜、不取第一个。"
      missing=$((missing+1)); continue
    fi
    if [ "$INNER" = "$SRC" ]; then

      printf "  ✓ %-9s %s → %s.zip（那资产逐字**就是**那一颗件）\n" "$id" "$asset" "$id"
    else
      cp -f "$INNER" "$ASSETS/$id.zip"
      printf "  ✓ %-9s %s → %s.zip（那资产逐字是那 zip 套内层，那内层取出来）\n" "$id" "$asset" "$id"
    fi
  else

    echo "::error title=$id 容器形态不认::那资产逐字是那 $SRC_FMT（头四字节 $MAGIC），既不是 zip 也不是 gzip。"
    echo "::error:: 处置：先核那一份的容器形态（scripts/verify/probe-container-form.sh），那**不是** dispatch 能解决的。"
    missing=$((missing+1)); continue
  fi

  _pkg_sha=""
  _pkg_run=""
  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$STAGE/$id/bsha.json" 2>/dev/null \
      && [ -s "$STAGE/$id/bsha.json" ]; then
    _pkg_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$STAGE/$id/bsha.json")"
    _pkg_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$STAGE/$id/bsha.json")"
  fi
  rm -f "$STAGE/$id/bsha.json"

  _want_sha=""
  _want_src=""
  _cur_run="${GITHUB_RUN_ID:-}"
  if [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ] && [ -n "$_cur_run" ] && [ "$_pkg_run" = "$_cur_run" ]; then

    _want_sha="${GITHUB_SHA:-}"
    _want_src="GITHUB_SHA（件包逐字就是这一趟产的那一件）"
  elif [ -n "$_pkg_run" ] && [ "$_pkg_run" != "local" ]; then

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

    echo "[ok] $id 那件逐字**是**它自己带的那一次构建造出来的（buildRun=$_pkg_run）"
  else

    echo "::notice title=$id 那期望值读不到::那 GITHUB_SHA 逐字**没有**、产物自带的 buildRun 逐字**也**没有"
    echo "::notice:: （那**判据**逐字**答不出**、它逐字**不**判成「那不是当前这份」、按 C-05 报缺）"
  fi

  meta_json="$STAGE/$id/peek.json"
  rm -f "$meta_json"

  if unzip -p "$ASSETS/$id.zip" component-meta.json > "$meta_json" 2>/dev/null && [ -s "$meta_json" ]; then
    g_sha="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildSha||""))}catch(e){}})' < "$meta_json")"
    g_run="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).buildRun||""))}catch(e){}})' < "$meta_json")"
  else
    g_sha=""; g_run=""
  fi
  rm -f "$meta_json"
  GEN_ROWS="${GEN_ROWS:-}${id}|${g_sha}|${g_run}\n"

  ok=$((ok+1))

done

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
