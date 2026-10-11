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
  [ -s "$dir/$asset" ]
}

mkdir -p "$ASSETS" "$STAGE"

missing=0
ok=0

for id in "${PIECES[@]}"; do
  tag="$(bash scripts/toolchain/cache-key.sh tag "$id")"
  asset="$(bash scripts/toolchain/cache-key.sh asset "$id")"

  rm -rf "${STAGE:?}/$id"; mkdir -p "$STAGE/$id"

  if ! fetch_asset "$tag" "$asset" "$STAGE/$id"; then
    echo "::error title=$id 没有固化产物::Release $tag 里取不到 $asset —— 先去构建这一件："
    echo "::error::  gh workflow run $(bash scripts/toolchain/piece-workflow-name.sh "$id")   （见 RELEASE-POLICY.md）"
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
  echo "::error title=取件不全::$missing / ${#PIECES[@]} 个件没有固化产物"
  echo "[fetch] 已取到 $ok 个。缺的那几个要单独构建，别在 APK 里编 —— 见 RELEASE-POLICY.md"
  exit 1
fi

echo ""
echo "[fetch] ${#PIECES[@]} 个件包全部取自 Release → $ASSETS/<id>.zip"
echo "[fetch] 启动时装它们（与 OTA 装件同一条路），APK 不再编译任何件"
