#!/usr/bin/env bash
# 取件：把已固化的件取进 APK，**不编译**。
#
# APK 是取件模式：预装件是现成的，编一次固化到 Release，之后一直取用。
# 这里做三件事：
#   1. 从 Release 下载 <筐>-<件名> 的固化产物
#   2. 校验 sha256（对不上说明 Release 被改过）
#   3. 解包分流：.so → jniLibs/arm64-v8a/    *.meta.json → assets/supply/meta/
#      两处都要有 —— PrefixProvisioner 扫的是 assets，不是 jniLibs。
#
# 取不到就报错退出。**不在这里回退去编译** —— 那正是「APK 又开始编件」的
# 由来，而且会把「某个件没固化」这个事实藏起来。缺件就明说缺哪个、去跑
# 「workflow_dispatch build-<id>」，见 RELEASE-POLICY.md。
#
# 不用 gh：少一个依赖少一个变数，而且 gh 的 `release download --pattern` 在
# tag 含 `+` 时会把它当通配符。走 API 拿 browser_download_url 更直接。
set -euo pipefail

cd "$(cd "$(dirname "$0")/../.." && pwd)"

ABI="${ABI:-arm64-v8a}"
JNI="container/app/src/main/jniLibs/$ABI"
META="container/app/src/main/assets/supply/meta"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

# base 筐随 APK 内置的那些上游软件。全都是固化产物，这里只取不编。
#
# crypto 在列表里是因为它虽是 openssl 的一部分（component-sources.json 的
# crypto.sameAs=openssl），但在 24 项清单里是独立的一件，控制面板按清单取。
#
# posix / flock / ptyprobe / ptysession 不在其中 —— 它们是内核的兼容性垫片
# （container/native 下我们自己写的 C），由 build-kernel-compat.sh 编，
# 随 APK 而来，不是件、不走 Release。见 kernel/compat/Compat.kt。
PIECES=(zlib openssl crypto curl jq bash ripgrep busybox)

# 取一个 release 资产。公开仓库不需要令牌；私有仓库带 GITHUB_TOKEN。
fetch_asset() {
  local tag="$1" asset="$2" dir="$3" url
  local hdr=()
  [ -n "${GITHUB_TOKEN:-}" ] && hdr=(-H "Authorization: Bearer $GITHUB_TOKEN")
  url="$(curl -fsSL "${hdr[@]}" -H "Accept: application/vnd.github+json" \
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
  curl -fsSL -o "$dir/$asset" "$url" 2>/dev/null || return 1
  [ -s "$dir/$asset" ]
}

mkdir -p "$JNI" "$META" "$STAGE"

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

  # 校验 sha256（固化时一起传的；sha 对不上说明 Release 被改过）
  if [ -f "$STAGE/$id/$asset.sha256" ]; then
    ( cd "$STAGE/$id" && sha256sum -c "$asset.sha256" >/dev/null 2>&1 ) || {
      echo "::error title=$id 校验失败::$asset 的 sha256 对不上 —— Release 里的产物被改过"
      missing=$((missing+1)); continue;
    }
  fi

  tar xzf "$STAGE/$id/$asset" -C "$STAGE/$id" 2>/dev/null || {
    echo "::error title=$id 解包失败::$asset 不是可解的 tar.gz"
    missing=$((missing+1)); continue
  }

  # .so → jniLibs；*.meta.json → assets/supply/meta/
  nso=0
  while IFS= read -r f; do
    b="$(basename "$f")"
    case "$b" in
      *.meta.json) cp -f "$f" "$META/$b" ;;
      *.so|*.so.*) cp -f "$f" "$JNI/$b"; nso=$((nso+1)) ;;
      *.a)         : ;;                      # 静态库不随包带
      *.tar.gz)    : ;;                      # 下载下来的原包，解完就没用了
      *)           cp -f "$f" "$JNI/$b" ;;
    esac
  done < <(find "$STAGE/$id" -maxdepth 1 -type f)

  [ "$nso" -gt 0 ] || { echo "::error title=$id 里没有 .so::$asset 解开后找不到共享库"; missing=$((missing+1)); continue; }

  printf "  ✓ %-9s %-58s %s 个 .so\n" "$id" "$asset" "$nso"
  ok=$((ok+1))
done

rm -rf "$STAGE"

echo ""
if [ "$missing" -gt 0 ]; then
  echo "::error title=取件不全::$missing / ${#PIECES[@]} 个件没有固化产物"
  echo "[fetch] 已取到 $ok 个。缺的那几个要单独构建，别在 APK 里编 —— 见 RELEASE-POLICY.md"
  exit 1
fi

# AGP 会把 jniLibs 下的东西原样打进 APK，权限得自己定 —— 不能指望 runner 的 umask。
find "$JNI" -maxdepth 1 -type f -exec chmod 644 {} + 2>/dev/null || true
find "$META" -maxdepth 1 -type f -exec chmod 644 {} + 2>/dev/null || true

echo "[fetch] ${#PIECES[@]} 个件全部取自 Release，APK 不再编译任何件"
echo "[fetch] .so → $JNI"
echo "[fetch] meta → $META"