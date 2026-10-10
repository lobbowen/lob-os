#!/usr/bin/env bash
# 取件：把已固化的件取进 APK，**不编译**。
#
# APK 是取件模式：预装件是现成的，编一次固化到 Release，之后一直取用。
# 这里做两件事：
#   1. 从 Release 下载 <筐>-<件名> 的固化产物，解包进 jniLibs/arm64-v8a/
#   2. 把同名的 .meta.json 分流进 assets/supply/meta/
#      （PrefixProvisioner 扫的是 assets，不是 jniLibs —— 两处都要有）
#
# 取不到就报错退出。**不在这里回退去编译** —— 那正是「APK 又开始编件」的由来，
# 而且会把「某个件没固化」这个事实藏起来。缺件就明说缺哪个、去跑
# 「workflow_dispatch build-<id>」，见 RELEASE-POLICY.md。
set -euo pipefail

cd "$(cd "$(dirname "$0")/../.." && pwd)"

ABI="${ABI:-arm64-v8a}"
JNI="container/app/src/main/jniLibs/$ABI"
META="container/app/src/main/assets/supply/meta"
STAGE="dist/.fetch"
REPO="${GITHUB_REPOSITORY:-lobbowen/lob-os}"

mkdir -p "$JNI" "$META" "$STAGE"

# base 筐随 APK 内置的那些上游软件。全都是固化产物，这里只取不编。
#
# 为什么是这七个：openssl 连带 crypto（它只是 openssl 的一部分，脚本里一起产出）。
# posix / flock / ptyprobe / ptysession 不在其中 —— 它们是内核的兼容性垫片
# （container/native 下我们自己写的 C），由 build-kernel-compat.sh 编，随 APK 而来，
# 不是件、不走 Release。见 kernel/compat/Compat.kt。
PIECES=(zlib openssl crypto curl jq bash ripgrep busybox)

GH="${GH:-gh}"
missing=0
ok=0

for id in "${PIECES[@]}"; do
  tag="$(bash scripts/toolchain/cache-key.sh tag "$id")"
  asset="$(bash scripts/toolchain/cache-key.sh asset "$id")"

  rm -rf "${STAGE:?}/$id"; mkdir -p "$STAGE/$id"

  if ! $GH release download "$tag" --repo "$REPO" --pattern "$asset" --dir "$STAGE/$id" >/dev/null 2>&1; then
    echo "::error title=$id 没有固化产物::Release $tag 里取不到 $asset —— 先去构建这一件："
    echo "::error::  gh workflow run build-$id.yml   （或 API dispatch，见 RELEASE-POLICY.md）"
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

echo "[fetch] ${#PIECES[@]} 个件全部取自 Release，APK 不再编译任何件"
echo "[fetch] .so → $JNI"
echo "[fetch] meta → $META"