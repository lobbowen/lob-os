#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

TOOL=""
KIND="key"
for a in "$@"; do
  case "$a" in
    tag) KIND="tag" ;;
    key) KIND="key" ;;
    asset)      KIND="asset" ;;
    deps-list)  KIND="deps-list" ;;
    deps)       KIND="deps" ;;
    *)
      if [ -n "$TOOL" ]; then
        echo "只能给一个件名（收到 $a 与 $TOOL）" >&2
        exit 2
      fi
      TOOL="$a"
      ;;
  esac
done
[ -n "$TOOL" ] || {
  echo "用法: $0 <子命令> <件名>" >&2
  echo "  子命令: key · tag · deps · deps-list · asset" >&2
  exit 2
}
TABLE="$ROOT_DIR/scripts/component-sources.json"

deps_for() {
      case "$1" in
      # ── 依赖哪些件（照 dpkg 的 Depends=）──────────────────────
      # 只有「真的缺了它就编不出来/跑不起来」的才算
      curl)       echo "openssl zlib" ;;
      openssl)    echo "zlib" ;;
      crypto)     echo "openssl" ;;
      git)        echo "curl openssl zlib" ;;
      python)     echo "openssl zlib" ;;
      node)       echo "" ;;
      # ── 自己就是自己（编它要它自己的源码）────────────────────
      bash|ripgrep|busybox|jq|sqlite|npm|pnpm|llvm|make|cmake|pkgconf) echo "" ;;
      zlib)      echo "" ;;
      # ── 不是件
      libcxx|sysroot)                                               echo "" ;;
      *) die ;;
      esac
    }


# 四个筐：
#   base 基础环境件 —— 系统运转离不开的
#   rt   运行时—— 程序靠它跑
#   tool 工具       —— 人用的命令行工具
#   lib  库         —— 静态/动态库，给上面那些件编的时候链进去
#
# 库这一筐是新加的：zlib / openssl(crypto) / curl / libcxx 以前只是
#「编某件时的中间产物」，每次编git 都要重编一遍 zlib+openssl+curl。
# 它们本身就是基础件，且未来会不断增加 —— 与环境件不同类，单独立筐。
bucket_for() {
      case "$1" in
      # ── base 基础环境件：随 APK 走 ──────────────────────────────
      # 命令与库都在这里 —— Linux 不区分它们（ldconfig 扫的是目录，
      # libz.so 与 libcurl.so 是同一类东西：/usr/lib 下的共享库）
      bash|ripgrep|busybox|jq)                             echo "base" ;;
      curl|zlib|openssl|crypto)                            echo "base" ;;
      # ── rt 运行时：zip 件，用户自己装 ──────────────────────────
      node|python)echo "rt" ;;
      # ── tool 工具：zip 件，用户自己装 ─────────────────────────
      git|sqlite|npm|pnpm|llvm|make|cmake|pkgconf)        echo "tool" ;;
      # ── 不是件，但随 APK 走 ──────────────────────────────────
      # libcxx  NDK 给的共享库，直接进 jniLibs（不是我们编的件）
      # sysroot 编译期的头文件与库目录（Linux 里对应 gcc 包的 include/）
      libcxx|sysroot)                                   echo "apkonly" ;;
      *) die ;;
      esac
    }

# 本脚本认的件名 —— 从 deps_for 与 bucket_for 的 case 里抄出来的，
# 免得「不在已知列表」这句提示不给出可抄的清单。
KNOWN_TOOLS="bash ripgrep busybox jq curl zlib openssl crypto"

die() {
  # 走 stderr：die 是在命令替换里被调的（DEPS="$(deps_for …)"），
  # 报错若走 stdout 会被那个变量吞掉，终端上什么都看不到 ——
  # 表现为「件名写错了，但脚本一声不响」。
  echo "::error title=未知件名::$TOOL 不在已知列表里 —— 加新件时要在这里补 deps_for 与 bucket_for" >&2
  echo "         本脚本认的件名（注意是 python 不是 python3、是 ripgrep 不是 rg）：" >&2
  echo "         $KNOWN_TOOLS" >&2
  exit 1
}

DEPS="$(deps_for "$TOOL")"
VER=""
for k in $DEPS; do
  v="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$k" 2>/dev/null || true)"
  [ -n "$v" ] || v="none"
  VER="$VER$k-$v"
done
[ -n "$VER" ] || VER="nodeps"

if [ "$TOOL" = "git" ]; then
  P=""
  for k in $(node -e '
    const t=require(process.argv[1]);
    process.stdout.write(Object.keys(t.sources).filter(x=>/^git-.*\.patch$/.test(x)).join(" "));
  ' "$TABLE"); do
    v="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$k" 2>/dev/null || true)"
    P="$P$(echo "$k" | head -c6)-${v:0:8}; "
  done
  VER="$VER|patch:$P"
fi

if [ "$TOOL" = "node" ]; then
  NVER="$(bash "$ROOT_DIR/scripts/registry/read-node-versions.sh" default)"
  NABI="$(bash "$ROOT_DIR/scripts/registry/read-node-versions.sh" abi | tr -c 'A-Za-z0-9._-' '+')"
  VER="${NVER}+${NABI}"
fi

NDK="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --ndk 2>/dev/null || echo unknown)"
API=35
RUN_OS="${RUN_OS:-Linux}"
RUN_ARCH="${RUN_ARCH:-X64}"

if [ "$DEPS" = "build-apk-only" ]; then
  echo "::error title=该件不在这条链上::$TOOL 由 build-apk.yml 编（APK 内置 + OTA 补丁），"
  echo "::error::不在 build-component 矩阵里 —— 要给它算预制品 key 得先在 build-apk 那边接上。"
  exit 1
fi

# VER 里已经含各依赖的版本（第 94-100 行那个循环），所以它变了名字就变 ——
# 依赖一变就该重新固化。
DEPS_STR="$VER-ndk$NDK-api$API"

# 源码指纹：这一格是 2026-10-16 加的。
# 起因（实测，run 38837094692）：改 busybox 的落位逻辑后，asset 名不变
#（DEPS_STR 只含依赖版本/NDK/API），而 reusable-asset.sh 的内容闸只验
#「meta 在 + entry 一致 + entry 路径存在」—— 对「改 flag / 改链接顺序」
# 这类 entry 与包结构都不变的改动完全看不见，于是 dispatch 会复用旧包、
# 源码改了却什么都没编。
#
# 为什么用「本件相关的源码文件哈希」而不是整仓 sha：
#   整仓 sha 会让「改了一个无关件」也触发全部件重新固化；
#   而只算本件的 recipe + 钉值条目，改别的件不会影响它。
SRC_HASH=""
case "$TOOL" in
  busybox) SRC_FILES="scripts/recipes/build-native-busybox.sh" ;;
  bash)    SRC_FILES="scripts/recipes/build-bundle-bash.sh" ;;
  ripgrep) SRC_FILES="scripts/recipes/build-bundle-rg.sh" ;;
  jq)      SRC_FILES="scripts/recipes/build-bundle-jq.sh" ;;
  curl)    SRC_FILES="scripts/recipes/build-bundle-curl.sh" ;;
  zlib)    SRC_FILES="scripts/recipes/build-bundle-zlib.sh" ;;
  openssl) SRC_FILES="scripts/recipes/build-bundle-openssl.sh" ;;
  crypto)  SRC_FILES="scripts/recipes/build-bundle-openssl.sh" ;;
  git)     SRC_FILES="scripts/recipes/build-component-git.sh scripts/patches" ;;
  # node 不在本轮范围（用户 2026-10-16：「node 不要修，node 是不能修的，是后期要用来验证的」）
  # ⇒ 不给它算指纹：它那条流水线今天跑不通，而本轮不碰它。
  node)    SRC_FILES="" ;;
  python)  SRC_FILES="scripts/recipes/build-component-python3.sh" ;;
  sqlite)  SRC_FILES="scripts/recipes/build-component-sqlite3.sh" ;;
  *)       SRC_FILES="" ;;
esac

# 决定「包里有什么」的两个机制，不产字节但改产物内容：
#   gen-component-meta.js  决定 meta 的字段（roles 就是它加的）
#   package-bundle-zip.sh   决定哪些文件进包、怎么命名
# 2026-10-16：改 gen-component-meta.js 加了 roles，而八件的指纹没变 ⇒
# 方案甲本该拦住却没拦住。判别形式不是「它是不是源码」，而是
# 「我改了这个文件，包里会不会变」。
# 2026-10-20：形态判定与写形态的那份脚本也进了包里（piece-shape.json），
#   改它同样会改产物内容 ⇒ 它也要进指纹。
PKG_MECH="scripts/recipes/gen-component-meta.js scripts/recipes/package-bundle-zip.sh scripts/toolchain/elf-form.sh"
SRC_FILES="$SRC_FILES $PKG_MECH"

# 必须只用 commit 里的内容（git show HEAD:<path>），不能用 sha256sum 读工作区文件。
# 那样会让「同一 commit + 不同未提交改动」算出不同值，于是 dispatch 时算出的名字与
# CI 取件时算出的不一致，重固化完了还是取不到。
# 2026-10-16 实测（修之前）：本地 e43e07288faf、CI a1f1d75df276、Release 里 85bd6ce121b3。
if [ -n "$SRC_FILES" ] && [ -d "$ROOT_DIR/.git" ]; then
  SRC_HASH="$(cd "$ROOT_DIR" && git ls-files $SRC_FILES 2>/dev/null | while read -r f; do git show "HEAD:$f" 2>/dev/null | sha256sum; done | sha256sum | cut -c1-12)"
  [ -n "$SRC_HASH" ] || SRC_HASH="nosrc"
fi
[ -n "$SRC_HASH" ] || SRC_HASH="nosrc"
DEPS_STR="$DEPS_STR-src$SRC_HASH"

BUCKET="$(bucket_for "$TOOL")"

case "$KIND" in
  tag)
    echo "$BUCKET-$TOOL"
    ;;
  deps-list)
    # 依赖的**件名**（不是缓存键）—— 打件包时要写进 component-meta.json 的 requires
    deps_for "$TOOL"
    ;;
  asset)
    SAFE="$(printf '%s' "$DEPS_STR" | tr -c 'A-Za-z0-9._' '+' | tr -s '+' '+')"
    # 扩展名跟内容走：件包已经是 zip（package-bundle-zip.sh 打的是 zip），
    # 名字叫 .tar.gz 而内容是 zip 会让「看名字判断版本」的人被骗 —— 判据按 magic 字节认，名字也该一致。
    echo "$TOOL-$SAFE.zip"
    ;;
  *)
    echo "uw-$TOOL-$DEPS_STR-$RUN_OS-$RUN_ARCH"
    ;;
esac