#!/usr/bin/env bash
# 编译单件的公共前置：定位 NDK、落位产物、**并把这一件自己的说明一起打进 jniLibs**。
#
# 为什么说明要打进 APK：
#   照抄 deb-control(5)——「Each Debian binary package contains a control file in its
#   control member」。每个包自带说明，安装器与内核只读落位，不预置任何一件的清单。
#   内核侧 PrefixProvisioner 扫 jniLibs 里的 *.meta.json 决定铺什么、铺成什么形状。
#
# 用法：source scripts/recipes/piece-env.sh <件名>，然后调用 land_piece
set -uo pipefail

PIECE_NAME="${1:?用法: source piece-env.sh <件名>}"

# 仓根（本文件在 scripts/recipes/）
PIECE_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PIECE_HERE/../.." && pwd)"
cd "$ROOT_DIR"

ABI="${ABI:-arm64-v8a}"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
JNI="$ROOT_DIR/container/app/src/main/jniLibs/$ABI"   # 绝对路径：调用方可能已 cd 走（busybox 就进了源码树）
# 件说明随 assets 走 —— **这是运行时唯一读得到的那一份**。
# jniLibs 里那份进不了 APK：AGP 的 jniLibs 打包只取 *.so（JniLibsPackaging
# 只有 excludes/pickFirsts/keepDebugSymbols，没有「非 .so 也打进去」的开关），
# 而 PrefixProvisioner.scanMeta() 正是在 nativeLibraryDir 里找 *.meta.json ——
# 找不到就整目录跳过（PrefixProvisioner.kt:103「目录里没有说明的 .so 不铺」）。
META_ASSETS="$ROOT_DIR/container/app/src/main/assets/supply/meta"
WORK="$ROOT_DIR/work/$PIECE_NAME"
mkdir -p "$OUT/bin" "$JNI" "$META_ASSETS" "$WORK"

# 说明文件名 —— jniLibs 里与件同名，PrefixProvisioner 按 *.meta.json 扫
META_SUFFIX=".meta.json"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")" >&2
  exit 1
}

# 定位 NDK：CC 由 CI 注入，本地没有就找 locate-ndk.sh
if [ -z "${CC:-}" ]; then
  [ -f "$ROOT_DIR/scripts/toolchain/locate-ndk.sh" ] \
    || die "缺 CC" "环境里没有 CC，也没有 scripts/toolchain/locate-ndk.sh"
  # ★ 用子进程跑，不 source ——
  #   locate-ndk.sh 里有 exit 1 与 set -euo pipefail；
  #   source 它会终止父进程并污染 set 选项（实测过）。
  #
  #   它把 CC/NDK 等写进 $GITHUB_ENV（不是 stdout），所以跑完从那个文件读回来。
  #   GitHub Actions 会把该文件的内容注入后续步骤的环境 —— 我们手动做同一件事。
  _GHE="$(mktemp)"
  GITHUB_ENV="$_GHE" bash "$ROOT_DIR/scripts/toolchain/locate-ndk.sh" \
    || die "定位 NDK 失败" "locate-ndk.sh 没跑通（见上面的输出）"
  # 只取我们需要的几个键
  local _k _v
  while IFS="=" read -r _k _v; do
    case "$_k" in
      CC|CXX|NDK|LLVM_AR|LLVM_RANLIB|LLVM_STRIP|LLVM_READELF|TRIPLE)
        printf -v "$_k" "%s" "$_v"
        export "$_k"
        ;;
    esac
  done < "$_GHE"
  rm -f "$_GHE"
fi
[ -n "${CC:-}" ] || die "定位不到 NDK 的 clang" "CC 为空"

TC_DIR="$(dirname "$CC")"
LLVM_AR="$TC_DIR/llvm-ar"
LLVM_RANLIB="$TC_DIR/llvm-ranlib"
LLVM_STRIP="$TC_DIR/llvm-strip"
LLVM_READELF="$TC_DIR/llvm-readelf"
# autotools 要的 AR/RANLIB（名字与 autotools 的约定一致）
AR_BIN="$LLVM_AR"
RANLIB_BIN="$LLVM_RANLIB"

# NDK 根 —— openssl 的 Configure 要它认android-arm64
NDK_ROOT="$(cd "$(dirname "$CC")/../../../../.." && pwd)"

# Android API 级别（NDK 的 clang 三元组里带着它）
API="${ANDROID_API:-35}"

# 并行度
JOBS="${JOBS:-2}"

# 校验产出的 .so：必须是 aarch64 的 ELF，且不太小（太小说明编坏了）
check_so() {
  local f="$1" min="${2:-1000}"
  [ -f "$f" ] || return 1
  local sz
  sz="$(wc -c < "$f")"
  [ "$sz" -ge "$min" ] || return 1
  # ★ file 要 -L（跟随软链）——
  #   共享库产物是一整条链：libz.so → libz.so.1 → libz.so.1.3.2，
  #   libz.so 与 libz.so.1 都是软链。不跟随时 file 报的是
  #   「symbolic link to libz.so.1.3.2」而不是 ELF 形态，
  #   于是这三层里唯二的两个软链都被判成「不是 aarch64 ELF」而报错。
  #   （wc -c 本来就跟随软链，所以只有 file 这步需要显式 -L。）
  if command -v file >/dev/null 2>&1; then
    file -bL "$f" 2>/dev/null | grep -q "ELF 64-bit.*ARM aarch64" || return 1
  fi
  return 0
}

# 校验它是可执行文件（比 .so 多一步：要真能跑起来）
check_exe() {
  local f="$1" min="${2:-1000}"
  check_so "$f" "$min" || return 1
  [ -x "$f" ]
}

# 生成这一件的说明 —— 数据全来自构建期表（component-sources.json / component-verify.json），
# 这里不硬编码任何一件的信息；数据不齐就报错，不产出半截的说明。
gen_meta() {
  node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" "$1" "$WORK/component-meta.json"
}

# 落位一件：拷进 jniLibs + 说明同落。
#   land_piece <件名> <.so 文件名> [最低字节数]
# 产物与说明同名成对 —— 内核扫到 *.meta.json 就知道该铺什么。
# land_piece <件名> <产物文件名|完整路径> [最小字节]
  # 产物可以是 $WORK 下的（自写 C 那几件直接编在那儿），
  # 也可以给完整路径（autotools 那几件 make install 装到 $OUT_DIR/lib/ 下）。
land_piece() {
  local id="$1" so="$2" min="${3:-1000}"
  local built="$so"
  [ -f "$built" ] || built="$WORK/$so"
  [ -f "$built" ] || die "产物不存在" "$so（也不在 $WORK/ 下）"
  check_so "$built" "$min" || die "产物不可用" "$built（不是 aarch64 ELF，或小于 $min 字节）"

  # ★ 共享库有三层链：libz.so → libz.so.1 → libz.so.1.3.2
  #   linker 运行时找的是**带 SONAME 的那个**（libz.so.1），
  #   而 ls | head -1 只会拿到无版本的那个（libz.so）。
  #   所以要把**同目录下整条链**都拷过去，只拷一个会运行时找不到。
  #   依据：ldconfig(8) 对 libfoo.so → .so.1 → .so.1.12 建链。
  #
  # ★ 每一层都要落一份 .meta.json —— 这是运行时能不能铺它的前提。
  #   PrefixProvisioner.provision() 只处理「jniLibs 里带说明的条目」
  #   （container/…/PrefixProvisioner.kt 第 130 行 for (m in scanMeta(nativeDir))），
  #   没有说明的文件它根本不看。
  #   而 libcurl.so 的 DT_NEEDED 写的是 **libz.so.1**（SONAME），
  #   不是 libz.so —— 加载器找的是带版本的那一层。
  #   只给无版本的那个落说明 ⇒ usr/lib 里只有 libz.so ⇒ 运行时找不到 libz.so.1
  #   ⇒ 实测报「libcurl.so 无 DT_RUNPATH，却依赖同目录随包库: libz.so.1」。
  #
  # 说明内容相同 —— 整条链是**同一件**的三个文件（不同名字，不是三件）。
  # 照 deb-control(5)：一份 control 随包走，落地时几处同名。
  local dir base
  dir="$(dirname "$built")"; base="$(basename "$built")"
  gen_meta "$id" > /dev/null
  [ -f "$WORK/component-meta.json" ] \
    || die "说明没生成" "gen-component-meta.js 没产出 $WORK/component-meta.json"
  local landed=0
  if [ -L "$built" ] || ls "$dir/$base".* >/dev/null 2>&1; then
    local f
    for f in "$dir/$base" "$dir/$base".*; do
      [ -e "$f" ] || continue
      cp -f "$f" "$JNI/$(basename "$f")" 2>/dev/null \
        || cp -Pf "$f" "$JNI/$(basename "$f")" 2>/dev/null || continue
      # 每一层都落说明 —— 少了它，运行时就不铺这一层。
      # 两处都写：jniLibs 那份给人看（本机构建产物），assets 那份才进 APK。
      cp -f "$WORK/component-meta.json" "$JNI/$(basename "$f")$META_SUFFIX" \
        || die "说明没落位" "$(basename "$f")$META_SUFFIX"
      cp -f "$WORK/component-meta.json" "$META_ASSETS/$(basename "$f")$META_SUFFIX" \
        || die "说明没落到 assets" "$(basename "$f")$META_SUFFIX"
      landed=$((landed + 1))
    done
    echo "  （共享库整条链：$landed 个文件各带说明 → $JNI/）"
  else
    cp -f "$built" "$JNI/$base"
    cp -f "$WORK/component-meta.json" "$JNI/$base$META_SUFFIX" \
      || die "说明没落位" "gen-component-meta.js 没产出 $WORK/component-meta.json"
    cp -f "$WORK/component-meta.json" "$META_ASSETS/$base$META_SUFFIX" \
      || die "说明没落到 assets" "gen-component-meta.js 没产出 $WORK/component-meta.json"
  fi
  echo "[ok] $id → $JNI/$base（+ 说明）"
}
