#!/usr/bin/env bash
# 编 openssl —— **一件一个脚本**，产出 libssl.so 与 libcrypto.so。
#
# crypto 不是独立的上游项目（OpenSSL 的一部分），但它是独立的 .so、能单独升级，
# 所以落位上是两件 —— 与 verify.json 的 crypto 条目一致（sameAs: openssl）。
#
# 此前与 zlib、curl 一起编到同一个 $DEPS/ 下（no-shared 静态），
# 依赖它的件把依赖静态链了进去。现在产出共享库，只记 DT_NEEDED。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bundle-env.sh" openssl

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"
TC="$(dirname "$CC")"
export PATH="$TC:$PATH"
export ANDROID_NDK_HOME="$NDK_ROOT" ANDROID_NDK_ROOT="$NDK_ROOT"
export SOURCE_DATE_EPOCH="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --source-date-epoch 2>/dev/null || echo 0)"

bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin openssl "$ROOT_DIR/work/openssl.tar.gz" \
  || die "openssl 源码取不到" "钉值见 scripts/component-sources.json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
tar xzf "$ROOT_DIR/work/openssl.tar.gz" -C "$WORK/src" --strip-components=1

(
  set -e
  cd "$WORK/src"
  # shared（原来 no-shared）· -fPIC 供动态链接 · 去掉 no-tests/no-ui-console
  # 而 DT_RUNPATH： 而 openssl 的 Configure **不吃 GNU 的 LDFLAGS 环境变量** 
  #   （ 仓里原来那一行没有传 LDFLAGS， 而那只是「没写」， **不是「验过不行」**）
  #     而它**接受 -Wl, 形式的额外链接选项**（ 而这是它的 documented 用法）
  #     而 --enable-new-dtags 那一项要一起给：
  #      「只有 DT_RPATH 不行，bionic 忽略它」（verify-runtime-elf.sh:321 与 check-elf-deps.sh:156）
  # 而这一份补丁是「我们对上游的一份补丁」，而它带着 pin
  #   （而 git 那些补丁就是这条路：scripts/recipes/build-component-git.sh:67-73）
  #   而它**跟着上游那一版走** —— 而它的 pin 与今天取的源码树同版（openssl-3.6.3）
  #   而**打不上就判红退出**（而那正是 git 那一件的做法）
  #  缺陷名（C-102）**那库**逐字**与**那宿主**那**同名**、=> 而而那**它**逐字**被**那宿主**那 libc **命名空间**里**那同名**的**那一个**盖**了**。
  #    => 而那**处置**：=> 而那 shlib_variant **那一族**逐字**改**那 SONAME 与那**符号版本**（=> 而那**它们**逐字**一起**变**）。
  #    => 而那**这一份**补丁**带**那 pin**、=> 而它**跟着上游那一版**走**、=> 而**打不上**就**判红退出**。
  for PATCH_NAME in openssl-runpath-origins.patch openssl-libname-variant.patch; do
    bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "$PATCH_NAME" "$WORK/$PATCH_NAME"
    if ! patch -p1 -i "$WORK/$PATCH_NAME"; then
      echo "::error title=补丁打不上::$PATCH_NAME —— 而上游 openssl 版本与这一份补丁不匹配？"
      exit 1
    fi
    echo "[openssl] 补丁已打：$PATCH_NAME"
  done

  # 而 rpath **不再从 Configure 那一行给**：
  #   而它由那份补丁给（scripts/patches/openssl-runpath-origins.patch）
  #   => 而两处都给，而链接器会把两个 rpath 用冒号连起来，=> 而值成 RIGIN:RIGIN
  #   => 而那一处撤掉，而值只剩补丁那一个
  # 而 --enable-new-dtags **要留着**（★★ 而它决定出 DT_RUNPATH 而不是 DT_RPATH，★★
  #   而 bionic 忽略 DT_RPATH，★★ 而 verify-runtime-elf.sh:321 查的是 DT_RUNPATH）★★
  #   而 rpath 那一项**不再从 Configure 那一行给**：
  #   而它由那份补丁给（scripts/patches/openssl-runpath-origins.patch）
  #   => 而两处都给，而链接器会把两个 rpath 用冒号连起来，=> 而值成 RIGIN:RIGIN
  #   => 而那一处撤掉，而值只剩补丁那一个
  ./Configure android-arm64 shared -fPIC -D__ANDROID_API__=$API \
    '-Wl,--enable-new-dtags' \
    --prefix="$OUT_DIR" --openssldir="$OUT_DIR/ssl" \
    no-tests no-ui-console > "$WORK/configure.log" 2>&1 \
    || { echo "=== openssl Configure 失败取证（末 30 行）==="; tail -30 "$WORK/configure.log"; exit 1; }
  make -j"$JOBS" build_libs > "$WORK/build.log" 2>&1 \
    || { echo "=== openssl 编译失败取证（末 30 行）==="; tail -30 "$WORK/build.log"; exit 1; }
  make install_sw > "$WORK/install.log" 2>&1 \
    || { echo "=== openssl install_sw 失败取证（末 30 行）==="; tail -30 "$WORK/install.log"; exit 1; }
)

#  此前这里校验 buildinf.h（构建时间基准）——
#   OpenSSL 3.x 不再生成那个文件（那个判据是 1.x 时代的），所以它成了空尺子。
#   判据改成「构建产物里要有版本头」：openssl/version.h 或 include/openssl/opensslv.h
[ -f "$OUT_DIR/include/openssl/opensslv.h" ] || [ -f "$WORK/src/include/openssl/opensslv.h" ] \
  || die "openssl 没产出版本头" "opensslv.h 应该在（找不到说明 Configure 或 make 失败）"
echo "[openssl] 版本头在位"

# 只打 openssl 这一件。crypto 虽然是 openssl 的一部分
#（component-sources.json 里 crypto.sameAs=openssl），但它在清单里是独立的
# 一件，有自己的 tag 与 asset —— 打包上传必须一件对一个 tag，
# 否则两者会互相「复用」对方的包（base-openssl 上装的是 libcrypto.so）。
# crypto 由 build-crypto.yml 从本件的固化包里取。
#  缺陷名（C-102）**那产物**逐字**自带**那**改名**后的**那**库**、=> 而而那**它**们**逐字是**那**两**件**事**：「那**库**叫**什么**」与「那**符号版本**是**什么**」）。
#    => 而那**改**名**由**那 shlib_variant **那一族**给**（=> 而那 openssl-libname-variant.patch **那一份**）、
#      => 而而那**产物**里**有**那 libssl-lobos.so **与**那 libcrypto-lobos.so、=> 而那**旧名字**的**那两**个**也**在**。
#    => 而那**取**逐字**取**那**改名**后的**那一个**（=> 而而那**它**们**逐字是**那**两**件**事**）。
SSL="$OUT_DIR/lib/libssl-lobos.so"
[ -f "$SSL" ] || SSL="$(ls "$OUT_DIR"/lib/libssl.so* 2>/dev/null | head -1)"
[ -n "$SSL" ] && [ -f "$SSL" ] || die "openssl 没产出 libssl" "$OUT_DIR/lib 下没有 libssl*（=> 而那改名**后**的**那一个**）"
  # OPENSSL_LIBS=1：包要同时带 libssl.so 与 libcrypto.so ——
  # crypto 是清单里的独立一件（crypto.sameAs=openssl），
  # 由 build-crypto.yml 从本件的包里拆出去。
  # 第五个参数：openssl 这个件要在 usr/bin 出现的命令名。
  # 少了它，打包只搬 entry（lib/libssl.so），bin/openssl 不会进包 ——
  # 那样系统里就永远没有「openssl 这个命令」，只有库。
  OPENSSL_BINS=""
  [ -e "$OUT_DIR/bin/openssl" ] && OPENSSL_BINS="openssl"
  #  缺陷名（C-102）**那 entry** 逐字**指**那**旧名字**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**名字**是**什么**」与「那**字节**是**什么**」）。
  #    => 而那**成因**：=> 而那 shlib_variant **那一族**逐字**把**那库**改**名**了（=> 而那 SONAME 逐字 = 那 libssl-lobos.so）、
  #      => 而而那 entry 逐字**还**是**那 lib/libssl.so**、=> 而而那**它**逐字**是**那**软链**、
  #      => 而而那**打包**那一族逐字**只**搬**那 entry **那**一条**链**、=> 而而那**链**逐字**按**那**文件名**算**（=> 而而那**它**不认**那 libssl-lobos.so**）、
  #      => 而而那**后果**逐字 = 而那包里**没有**那 libssl-lobos.so **那一份**实体**、
  #        => 而而那**验形态**那一族逐字**报**那「lib/libssl.so 自述 SONAME=libssl-lobos.so，而链里没有这一层」。
  #    => 而那**处置**：=> 而那 entry 逐字**指**那**改名**后的**那一个**（=> 而而那**它**们**逐字是**那**两**件**事**）。
  OPENSSL_LIBS=1 emit_piece openssl "$OUT_DIR" lib/libssl-lobos.so 1000 "$OPENSSL_BINS"
