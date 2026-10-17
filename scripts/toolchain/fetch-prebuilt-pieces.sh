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

cd "$(cd "$(dirname "$0")/../.." && pwd)"

ASSETS="container/app/src/main/assets/pieces"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

PIECES=(zlib openssl crypto curl jq bash ripgrep busybox hello)

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
  [ -s "$dir/$asset" ]
}

# 列出某 tag 下现有的 asset 名（每个一行）。取不到时输出空串。
#
# 为什么需要它：取件失败有两种完全不同的性质，而在 CI 输出上都是「取不到」——
#   缺货   ：该 tag 一个 asset 都没有（从没固化 / 被删了）⇒ 处置是 dispatch
#   旧产物 ：有 asset 但名字对不上（改过 recipe，指纹变了）⇒ **旧产物不能冒充新代码的产物**，
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

  if [ -f "$STAGE/$id/$asset.sha256" ]; then
    ( cd "$STAGE/$id" && sha256sum -c "$asset.sha256" >/dev/null 2>&1 ) || {
      echo "::error title=$id 校验失败::$asset 的 sha256 对不上 —— Release 里的产物被改过"
      missing=$((missing+1)); continue
    }
  fi

  # 整包放进 assets/pieces/ —— 启动时由装配器解包安装，与 OTA 装件同路
  # 整包放进 assets/pieces/ —— 启动时由装配器解包安装，与 OTA 装件同路。
  # 落名 .zip：件包就用 zip（照抄 .deb 的 ar 换成 Android 原生就有的 zip），
  # 装配器 java.util.zip 直接解，不需要任何第三方库。
  # 别用 .tar.gz —— aapt2 会把 assets 下的 .tar.gz 解开并剥掉 .gz 后缀。
  cp -f "$STAGE/$id/$asset" "$ASSETS/$id.zip"
  rm -f "$STAGE/$id/$asset" "$STAGE/$id/$asset.sha256"
  printf "  ✓ %-9s %s → %s.zip\n" "$id" "$asset" "$id"

done
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
