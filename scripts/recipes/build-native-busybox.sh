#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

API="${ANDROID_API:-35}"
TOOL="busybox"
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" busybox

BUSYBOX_VER="${BUSYBOX_VER:-1.36.1}"

note() { echo "[$TOOL] $*"; }

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang"
TC="$(dirname "$CC")"
LLVM_STRIP="${LLVM_STRIP:-$TC/llvm-strip}"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
for t in "$LLVM_STRIP" "$LLVM_READELF"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在"
done
NDK_ROOT="$(cd "$TC/../../../../.." && pwd)"
[ -d "$NDK_ROOT" ] || die "定位 NDK 失败" "从 clang 路径反推得到 '$NDK_ROOT'"

WORK="$ROOT_DIR/work/busybox"
mkdir -p "$WORK" "$OUT/bin"

SRC=""
BS_VER_TABLE="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version busybox 2>/dev/null || true)"
if [ -z "$BS_VER_TABLE" ]; then
  die "钉值表里没有 sources.busybox" \
    "没有 sha256 就不该下载底座件的源码 —— 那等于不校验。补上（scripts/registry/pin-github-release.js 不适用，busybox 不在 GitHub Releases）"
fi
if [ "$BS_VER_TABLE" != "$BUSYBOX_VER" ]; then
  die "busybox 版本不一致" \
    "构建脚本写的是 $BUSYBOX_VER，钉值表是 $BS_VER_TABLE。两处必须一致，否则钉的 sha256 与要编的版本对不上。"
fi
TGZ="$WORK/busybox.tar.bz2"
note "取源码 $BUSYBOX_VER（sha256 由钉值表逐字节校验）"
if ! bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin busybox "$TGZ"; then
  die "取源码失败或 sha256 不符" \
    "钉值与来源见 scripts/component-sources.json 的 sources.busybox。**不要**改成不校验的下载 —— 那样编出来的 busybox 错了没人知道。"
fi
if ! tar xjf "$TGZ" -C "$WORK"; then
  die "解包失败" "$TGZ —— sha256 是对的但 tar xjf 解不开，看上面 tar 的报错"
fi
SRC="$WORK/busybox-$BUSYBOX_VER"
if [ ! -d "$SRC" ]; then
  die "源码树目录名不符" \
    "期望 $SRC，实际解出：$(ls -d "$WORK"/busybox* 2>/dev/null | tr '\n' ' ')" \
    "—— 上游改了包内目录名？按实际改这里。"
fi
note "源码 $BUSYBOX_VER 就位（sha256 校验通过）"

for must in Makefile Config.in libbb; do
  [ -e "$SRC/$must" ] || die "源码树异常" "缺 $must —— 拿到的不是 busybox 源码"
done
[ -d "$SRC/applets" ] || die "源码树异常" "缺 applets/ 目录"
BB_VER="$(awk -F'= *' '
  /^VERSION *= */     {v=$2}
  /^PATCHLEVEL *= */ {p=$2}
  /^SUBLEVEL *= */   {s=$2}
  END {gsub(/[ \t]/,"",v); gsub(/[ \t]/,"",p); gsub(/[ \t]/,"",s);
       print v"."p"."s}
' "$SRC/Makefile")"
[ "$BB_VER" = "$BUSYBOX_VER" ] || die "版本不符" \
  "要 $BUSYBOX_VER，Makefile 三段拼出 $BB_VER（BUSYBOX_VER 写错，或源站给了别的版本）"
note "源码 $BB_VER 就位"

cd "$SRC"
make defconfig > "$WORK/defconfig.log" 2>&1 \
  || { echo "=== make defconfig 失败取证（末 30 行）==="; tail -30 "$WORK/defconfig.log"; exit 1; }
[ -f "$SRC/.config" ] || die "defconfig 没产出 .config" "看 $WORK/defconfig.log"

APPLETS="TAR GZIP GUNZIP GREP SED AWK LS CP MV CAT MKDIR RM LN VI DF PS TRUE FALSE"

set_conf() {
  local k="$1" v="$2"
  if [ "$v" = y ]; then
    grep -q "^CONFIG_${k}=y" "$SRC/.config" && return 0
    sed -i "s/^# CONFIG_${k} is not set/CONFIG_${k}=y/" "$SRC/.config"
    grep -q "^CONFIG_${k}=y" "$SRC/.config" || echo "CONFIG_${k}=y" >> "$SRC/.config"
  else
    sed -i "s/^CONFIG_${k}=y/# CONFIG_${k} is not set/" "$SRC/.config"
  fi
}

# ── 白名单：只留 APPLETS 那些，其余全关 ────────────────────────
# 此前是「defconfig 全开，再逐个关掉有问题的」——
#   defconfig 开了 512 项（含 HOSTID 这种我们要的 18 个之外的）。
#   靠「缺头就关」「bionic 缺函数就关」一层层筛，问题是**会一直漏**：
#   HOSTID 用 gethostid()，而 bionic 没有那个函数 ——
#   头文件在，所以按「NDK 有没有这个头」扫**抓不到**；
#   又是个 286 字节的小 applet，不值得为它加一类新判据。
#
# 改成白名单：defconfig 之后把 .config 里所有 CONFIG_XXX=y 关掉，
#   再只开 APPLETS 里的那些。缺头的 applet、bionic 缺函数的 applet、
#   将来才暴露的别的 —— 它们**根本不会进编译**，不必一个个去筛。
#
# 与上游的一致性：busybox 自带 configs/android_ndk_defconfig（也是 512 项，
#   给 Android NDK 用），我们要的 18 个里 17 个它也开着 —— 唯一例外是 DF
#   （它要 sys/statvfs.h，那轮实测 NDK 其实有，但我们自己的判据把它算成了缺）。
#   所以白名单与官方配置不冲突，只是更严。
grep -o '^CONFIG_[A-Z0-9_]*=y' "$SRC/.config" | sed 's/^CONFIG_//; s/=y$//' > "$WORK/all-on.txt"
N_ON=0
while read -r k; do
  [ -n "$k" ] || continue
  set_conf "$k" n
  N_ON=$((N_ON + 1))
done < "$WORK/all-on.txt"
echo "[busybox] defconfig 开了 $N_ON 项，已全部关掉（白名单式）"
for a in $APPLETS; do set_conf "$a" y; done
echo "[busybox] 白名单开启：$APPLETS"

# ── 补丁：Android 分支里两行过期的 #undef ──────────────────────
# 症状（实测）：
#     ld.lld: error: duplicate symbol: strchrnul
#     clang: error: linker command failed with exit code 1
# —— libbb/platform.c 按 `#ifndef HAVE_STRCHRNUL` 自己实现了一份，
#    而 bionic 静态库里已有一份。链接时撞了。
#
# 成因：busybox 的 include/platform.h 在 Android 分支里写着
#     # undef HAVE_MEMPCPY
#     # undef HAVE_STRCHRNUL
# 那是给 **Android 8 之前**的 bionic 写的（那时它没有这两个函数）。
# API 21 之后 bionic 已经提供 —— 依据：本机 readelf 直查
# /system/lib64/libc.so 的 .dynsym，strchrnul / mempcpy 都在。
# 所以改成按 __ANDROID_API__ 分级 —— 与这个分支里已有的写法一致
# （它自己就用 `__ANDROID_API__ < 8` / `< 21` / `>= 21` 处理 dprintf）。
#
# ★ 只改 **Android 那一个分支**：platform.h 里有 4 处
#   "# undef HAVE_STRCHRNUL"，分属 __WATCOMC__ / __dietlibc__ /
#   __APPLE__ / Android。别人的平台不该动（改了等于替他们做决定）。
#   而且 Android 分支本身有两处（一处只设两个 SYS_* 宏就结束），
#   所以区间由 scripts/verify/find-platform-h-android-branch.js 算
#   ——它按分支头是不是写着 ANDROID 来判，而不是「区间里含 undef」
#   （后者会抓到最内层的 __dietlibc__ 那个）。
PLATFORM_H="$SRC/include/platform.h"
[ -f "$PLATFORM_H" ] || die "找不到 platform.h" "$PLATFORM_H 不存在 —— 上游路径变了，按实际改这里"

# 区间计算与实际修改都在那个脚本里（--patch 模式）。
# 不用 sed 改多行：s/// 里的换行转义是 GNU sed 扩展，而 Android 上
# /system/bin/sed 是 toybox（实测），它不认；地址块写法在 toybox 上
# 又会被当文件名（实测「sed: 529,554: No such file or directory」）。
node "$ROOT_DIR/scripts/verify/find-platform-h-android-branch.js" "$PLATFORM_H" --patch
set_conf STATIC y
set_conf PIE n

# ── 编译器：官方只有「前缀」这一条路，没有 clang 变体 ──────────
# busybox 的 Makefile 第 292~298 行写死了：
#     CC = $(CROSS_COMPILE)gcc      LD = $(CC) -nostdlib
#     AR = $(CROSS_COMPILE)ar      NM = $(CROSS_COMPILE)nm ...
# 它拼的是 `aarch64-linux-android-gcc`，而 NDK 里的编译器叫
# `aarch64-linux-android35-clang` —— 那个 `gcc` 不存在。
#
# 而 CROSS_COMPILER_PREFIX 是 .config 里的一个 **string** 项，
# `make oldconfig` 会就它提问（Config.in 第 470 行）；不给输入它读到 EOF
# 就退 1（此前那轮就是这样失败的，日志停在
# 「Cross compiler prefix (CROSS_COMPILER_PREFIX) [] (NEW) make[1]: *** Error 1」）。
#
# 所以编译器不走 .config，走 make 命令行 —— 它优先级高于 .config，
# 且不需要 oldconfig 过问。
echo "  [busybox] CC = $CC（make 命令行给，不经 .config）"
echo "  [busybox] 不设 CROSS_COMPILE：它拼出来的 gcc 在 NDK 里不存在"

# 交叉编时 kconfig 还会问一堆只有交叉编才有的项；一次性喂默认答案。
# 不能只给空输入（读到 EOF 就退 1），也不要用 `yes ""`（管道提前关闭会 Broken pipe）。
# 这里自己产出一批空行 —— kconfig 逐个取走，剩下的正常收尾。
answers() {
  local n=0
  while [ "$n" -lt 400 ]; do printf '\n'; n=$((n + 1)); done
}

sed -i '/^CONFIG_EXTRA_CFLAGS=/d' "$SRC/.config" || true
  echo "CONFIG_EXTRA_CFLAGS=\"-O2 -fPIC -D__ANDROID_API__=$API\"" >> "$SRC/.config"

# ★ 必须喂输入：oldconfig 会就新增项提问，CI 上没有 tty 就卡死
#   （表现：日志停在 "Support --long-options (LONG_OPTS) [Y/?] y"）。
#   yes "" 会让每个提问取默认，但 yes 会被提前关闭的管道打断（Broken pipe），
#   所以改成自己产出一批空行。
make oldconfig < <(answers) > "$WORK/oldconfig.log" 2>&1 \
  || { echo "=== make oldconfig 失败取证（末 30 行）==="; tail -30 "$WORK/oldconfig.log"; exit 1; }

  # 关掉之后复核：还有哪个 applet 的源文件要内核 uapi 头？
  STILL="$(
    for a in $APPLETS; do
      grep -q "^CONFIG_${a}=y" "$SRC/.config" && printf '%s\n' "$a"
    done | tr '\n' ' '
  )"
  echo "[busybox] 关完内核 uapi 头那批之后，仍开启的 applet：${STILL:-（无）}"

MISSING=""
for a in $APPLETS; do
  grep -q "^CONFIG_${a}=y" "$SRC/.config" || MISSING="$MISSING $a"
done
[ -z "$MISSING" ] || die "配置项未生效" \
  "这些 applet 没进 .config：$MISSING —— 配置项名与本版本对不上（别凭记忆写）"
note "applet 配置项全部生效（$(echo $APPLETS | wc -w) 项）"

# ★ 编译器只能从这里给 —— 命令行赋值优先级高于 .config 与 Makefile 里的
#   `CC = $(CROSS_COMPILE)gcc`（命令行赋值不会被 Makefile 里的赋值覆盖）。
#   AR/NM/STRIP 在它的 Makefile 里是 $(CROSS_COMPILE)ar/nm/strip，同样得给，
#   否则交叉编时会去找宿主 x86-64 的 ar/nm —— 产物就废了。
#   LD 不给：它默认是 `$(CC) -nostdlib`，而 Makefile 里没有任何规则真的用 $(LD)
#   链接（链接全走 $(CC)），给它反而会因为值里带空格被命令行拆成两项。
MAKE_ARGS=(CC="$CC"
           AR="$AR_BIN"
           NM="$TC/llvm-nm"
           STRIP="$LLVM_STRIP"
           OBJCOPY="$TC/llvm-objcopy")
for p in "$CC" "$AR_BIN" "$TC/llvm-nm" "$LLVM_STRIP" "$TC/llvm-objcopy"; do
  [ -x "$p" ] || die "缺工具" "$p 不存在（应从 $CC 所在目录 $TC 里找）"
done

make -j"$JOBS" "${MAKE_ARGS[@]}" > "$WORK/build.log" 2>&1 \
  || { echo "=== busybox 编译失败取证（error 行 + 末 40 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -40 "$WORK/build.log"; exit 1; }

[ -f "$SRC/busybox" ] || die "没产出 busybox" "$SRC/busybox 不存在"
  # make install：busybox 官方机制 —— 构建系统自己知道编了哪些 applet，
  # 连带把全部软链建好（同目录相对软链，指向 busybox 本体）。
  # 我们不列applet 名：那是它的编译结果，不是我们的数据。
  rm -rf "$WORK/install"
  make "${MAKE_ARGS[@]}" CONFIG_PREFIX="$WORK/install" install > "$WORK/install.log" 2>&1 \
    || { echo "=== make install 失败取证 ==="; tail -30 "$WORK/install.log"; exit 1; }
  [ -x "$WORK/install/bin/busybox" ] || die "make install 没产出 busybox" "$WORK/install/bin/busybox 不存在"

  # 产物与说明成对落进 jniLibs —— 内核扫 *.meta.json 决定铺什么。
  mkdir -p "$JNI"
  cp -f "$SRC/busybox" "$JNI/libbusybox.so"
  chmod 0755 "$JNI/libbusybox.so"
  # ★ 用绝对路径 —— 这一段已经 cd 进 busybox 源码树了，
  #   相对路径 scripts/recipes/… 在那里不存在
  #   （报「Cannot find module .../busybox-1.36.1/scripts/recipes/…」就是这个）
  node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" busybox "$JNI/libbusybox.so.meta.json"
  # ★ 这件不走 land_piece（它自己管落位），所以 assets 那份得自己写 ——
  #   jniLibs 里的 .meta.json 进不了 APK（AGP 只打包 *.so），
  #   运行时 scanMeta 读的是 assets/supply/meta/，不写这份就等于没说明。
  mkdir -p "$META_ASSETS"
  cp -f "$JNI/libbusybox.so.meta.json" "$META_ASSETS/libbusybox.so.meta.json" \
    || die "busybox 的说明没落到 assets" "assets/supply/meta/libbusybox.so.meta.json"

  # applet 软链随件一起落：busybox 官方机制 —— make install 自己知道编了哪些
  # applet，那些软链是它建的。applet 名不在我们任何表里。
  NAPP=0
  for l in "$WORK/install/bin/"*; do
    [ -L "$l" ] || continue
    ln -sf busybox "$JNI/$(basename "$l")"
    NAPP=$((NAPP+1))
  done
  echo "[$TOOL] 本体 + $NAPP 个 applet 软链进 jniLibs（applet 名由 busybox 自己报）"
  BB="$JNI/libbusybox.so"
SIZE=$(stat -c%s "$BB")
[ "$SIZE" -gt 500000 ] || die "产物可疑" "busybox 只有 $SIZE 字节 —— 静态编不该这么小"
"$LLVM_STRIP" --strip-unneeded "$BB" 2>/dev/null || true

INFO=$(file -b "$BB")
case "$INFO" in
  *aarch64*|*arm64*|*ARM64*) : ;;
  *) die "架构不对" "$INFO —— 装到真机上 exec format error" ;;
esac
note "$INFO"

NEEDED=$("$LLVM_READELF" -W -d "$BB" 2>/dev/null | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' | wc -l)
DYN=$(printf '%s\n' "$("$LLVM_READELF" -W -l "$BB" 2>/dev/null || true)" | awk '/^[[:space:]]*DYNAMIC/{print "y"}')
if [ -n "$DYN" ]; then
  die "不是静态产物" "有 PT_DYNAMIC（NEEDED=$NEEDED 项）—— 底座件不该依赖任何共享库"
fi

BAD=$("$LLVM_READELF" -W -l "$BB" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$((a))
          if [ "$d" -eq 0 ]; then continue; fi
          if [ $(( d % 16384 )) -ne 0 ]; then printf ' %s' "$a"; fi
        done)
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

echo "[ok] $BB $(stat -c%s "$BB") 字节（静态，无 PT_DYNAMIC，16KB 对齐合格）"
echo "[$TOOL] 软链随 make install 一起建（busybox 官方机制：构建系统自己知道有哪些 applet）"
echo "[$TOOL] 判据用 'busybox <applet> --help'，不用 '<applet> --help'"
printf '%s' "$BB_VER" > "$OUT/$TOOL.version"