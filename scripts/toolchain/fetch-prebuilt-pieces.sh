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

PIECES=(zlib openssl crypto curl jq bash ripgrep busybox)

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
              try {
                const j = JSON.parse(d);
                const a = (j.assets || []).find(x => x.name === process.argv[1]);
                if (a) process.stdout.write(a.browser_download_url);
              } catch (e) {}
            });
          ' -- "$asset" 2>/dev/null)"
  [ -n "$url" ] || return 1
  curl -fsSL --retry 3 --retry-delay 2 -o "$dir/$asset" "$url" 2>/dev/null || return 1
  [ -s "$dir/$asset" ] || return 1
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
list_release_assets() {
  local tag="$1" hdr=()
  [ -n "${GITHUB_TOKEN:-}" ] && hdr=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fsSL --retry 3 --retry-delay 2 "${hdr[@]}" -H "Accept: application/vnd.github+json" \
          "https://api.github.com/repos/$REPO/releases/tags/$tag" 2>/dev/null \
    | node -e '
        let d = "";
        process.stdin.on("data", c => d += c);
        process.stdin.on("end", () => {
          try {
            const j = JSON.parse(d);
            (j.assets || []).map(x => x.name).filter(n => !/\.sha256$/.test(n)).forEach(n => process.stdout.write(n + "\n"));
          } catch (e) {}
        });
      ' 2>/dev/null
}
mkdir -p "$ASSETS" "$STAGE"

missing=0
miss_stock=0
miss_stale=0
ok=0

for id in "${PIECES[@]}"; do
  tag="$(bash scripts/toolchain/cache-key.sh tag "$id")"
  asset="$(bash scripts/toolchain/cache-key.sh asset "$id")"

  rm -rf "${STAGE:?}/$id"; mkdir -p "$STAGE/$id"

  if ! fetch_asset "$tag" "$asset" "$STAGE/$id"; then
    echo "::error title=$id 没有固化产物::Release $tag 里取不到 $asset —— 先去构建这一件："
    echo "::error::  gh workflow run $(bash scripts/toolchain/piece-workflow-name.sh "$id")   （见 RELEASE-POLICY.md）"
    others="$(list_release_assets "$tag")"
    if [ -z "$others" ]; then
      echo "::notice title=$id 缺货::Release $tag **一个 asset 都没有** —— 这一件从来没固化过，或固化被删了。"
      echo "::notice:: 处置：dispatch 那一件即可。*"
      miss_stock=$((miss_stock+1))
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
  echo "::error title=取件不全::$missing / ${#PIECES[@]} 个件取不到（缺货 $miss_stock · 旧产物 $miss_stale）"
  if [ "$miss_stock" -gt 0 ]; then
    echo "::error::  **缺货**（该 tag 一个 asset 都没有）→ dispatch 那一件即可"
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
