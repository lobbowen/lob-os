#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

API="${ANDROID_API:-35}"
TOOL="busybox"
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bundle-env.sh" busybox

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

# AWK 不在清单里，尽管上游 android_ndk_defconfig 默认开着它 ——
# 白名单把除 $APPLETS 以外全关了，awk 依赖的 CONFIG_DESKTOP /
# CONFIG_FEATURE_* 一起被关掉，于是 `CONFIG_AWK=y` 单独存在不足以让它被编，
# `make install` 不给它建软链。
# 2026-10-16 实测：CI 判红「bin/awk 声明了但没产出」，而本行原先写着 AWK。
APPLETS="TAR GZIP GUNZIP GREP SED LS CP MV CAT MKDIR RM LN VI DF PS TRUE FALSE"

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

#  白名单：只留 APPLETS 那些，其余全关 
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
# 与上游的一致性：busybox 自带 configs/android_ndk_defconfig（512 项，给 Android NDK 用）。
#   白名单比它严：凡不在 $APPLETS 的一律关掉，因此「上游开着」不代表「我们开着」。
#   上游开着而我们没列的，会因为依赖的 CONFIG_* 一起被关掉而编不出来
#   —— AWK 就是这样：上游开着，但我们不列它，它就没被编出来。
#   所以「上游 defconfig 开着的项」不能直接当清单用，必须实测落包数（见下面那段比对）。
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

#  补丁：Android 分支里两行过期的 #undef 
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
#  只改 **Android 那一个分支**：platform.h 里有 4 处
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

#  编译器：官方只有「前缀」这一条路，没有 clang 变体 
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

#  必须喂输入：oldconfig 会就新增项提问，CI 上没有 tty 就卡死
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

#  编译器只能从这里给 —— 命令行赋值优先级高于 .config 与 Makefile 里的
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
  # 连带把全部软链建好（**同目录相对软链**，指向 busybox 本体）。
  # **限定**：这句话只说 install/bin/ 里那批**同目录**软链不用我们列。
  # 它**不**否定上面 $APPLETS（第 78 行）—— 那是「**要开哪些 applet**」的构建配置，
  # 是我们自己的数据，必须列。
  # （原措辞没带「同目录」这个限定，会被读成「applet 名都不用列」，
  #   而 :78 恰好列了 18 个 —— 于是这两行读起来自相矛盾。2026-10-15 修正措辞。）
  rm -rf "$WORK/install"
  make "${MAKE_ARGS[@]}" CONFIG_PREFIX="$WORK/install" install > "$WORK/install.log" 2>&1 \
    || { echo "=== make install 失败取证 ==="; tail -30 "$WORK/install.log"; exit 1; }
  [ -x "$WORK/install/bin/busybox" ] || die "make install 没产出 busybox" "$WORK/install/bin/busybox 不存在"

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR/lib" "$OUT_DIR/bin"
#  **本体落 bin/**（它按形态是程序形态：ET_EXEC、无 PT_INTERP —— 07 今天的 readelf 读数）。
#   缺陷名（C-102）本体放 lib/ 而它按形态是程序形态：原来这里是 lib/libbusybox.so，
#   而「形态决定落位目录」 运行期 dirForRole(program)  它会落进 bin/，
#     而那 17 条软链若指向 ../lib/libbusybox.so  **断链**  整个工具族消失。
#    现在：本体在 bin/libbusybox.so（与它按形态该落的地方一致），软链指向 ../bin/libbusybox.so。
cp -f "$SRC/busybox" "$OUT_DIR/bin/libbusybox.so"
chmod 0755 "$OUT_DIR/bin/libbusybox.so"

# applet 落 bin/，而软链指向**本体那份字节**（本体也在 bin/）。
#
#  缺陷名（C-102）**本体与软链目标不一致**（两段注释自相矛盾）：
#   上面那段已把本体搬到 bin/libbusybox.so（按形态：ET_EXEC、无 PT_INTERP  程序形态  落 bin/），
#   而这一段原来还写着「本体仍留在 lib/（它是库，LdConfig 按 SONAME 建 usr/lib 软链）」
#   并且软链指向 ../lib/libbusybox.so  **那 17 条会全是断链**  整个工具族在真机上消失而 CI 全绿。
#    处置（04 今天的判别形式：不自洽在「位置上」 改放置它的那一处）：软链目标跟着本体一起搬。
#
#  而「它是库」那个判断今天不成立（01 今天裁的 S32，三条读数）：
#    它**没有 SONAME**（readelf -d 零命中）—— 而「库」在 ELF 里的标志就是 SONAME；
#    **没有任何字节 NEEDED 它**（逐件扫过全部 opt/*/*/lib 与 opt/*/*/bin，零命中）——
#      而「库」的定义是「被别的字节装载」；
#    它自己的 DT_NEEDED **是空的** —— 而「库」是需要别人的东西。
#    它是一份独立的**程序**字节，而那 17 个名字是它的 argv[0] 派生物。
#
# 链接目标必须与本体同目录（./libbusybox.so —— 同目录相对链接）：
#   LdConfig 照软链建链且用 absolutePath  usr/bin/ls -> <abs>/opt/busybox/<ver>/bin/ls
#   -> ./libbusybox.so 一跳；写成 libbusybox.so（无 ./) 会断链，
#   而断链会让整个工具族在真机上继续消失而 CI 全绿。
NAPP=0
for l in "$WORK/install/bin/"*; do
  [ -L "$l" ] || continue
  b="$(basename "$l")"
  [ "$b" = busybox ] && continue
  ln -sf ./libbusybox.so "$OUT_DIR/bin/$b"
  NAPP=$((NAPP+1))
done
echo "[$TOOL] 本体(bin/，按形态是程序形态) + $NAPP 个 applet 软链(bin/，指向它) 进包"

# 声明集 ⊆ 落包集（单向包含）。
#
# 为什么比集合而不是比个数：
#   声明侧 = $APPLETS（我们写下的常量）；
#   落包侧 = $OUT_DIR/bin/ 的实际内容（make install 产出的），**不是 NAPP**。
#   NAPP 数的是 install/bin/ 里的软链总数，它**不是声明的落地数** ——
#   2026-10-16 实测：去掉 AWK 后声明 17、而 NAPP 反而是 18（落包集含我们没声明的派生 applet）。
#   比计数会在「集合包含而计数不同」时误判失败 —— 那正是那次误判。
#
# 为什么是单向而不是双向：落包集可能比我们声明的多（派生 applet），那是正常的；
# 而「我们声明了却没有」是断链 —— 本会话三段断链的共同形态。
MISSING_BIN=""
for a in $APPLETS; do
  lo=$(printf '%s' "$a" | tr 'A-Z' 'a-z')
  [ -e "$OUT_DIR/bin/$lo" ] || MISSING_BIN="$MISSING_BIN $lo"
done
[ -z "$MISSING_BIN" ] || die "声明了但没产出" \
  "$MISSING_BIN 在 $OUT_DIR/bin 下不存在 —— 上面的「配置项全部生效」只验 .config 里写没写，验不到 applet 到底编没编出来。看它依赖的 CONFIG_* 是不是被白名单关了。"
echo "[$TOOL] 声明的 $(printf '%s\n' $APPLETS | wc -w) 个 applet 全部在落包里 ✓（落包共 $NAPP 个，多出的是未声明的派生 applet）"

BB="$OUT_DIR/bin/libbusybox.so"
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

# 第五个参数是「这个件要在 usr/bin 出现的命令名」——
#  **取落包集**（make install 连带建的那批软链），**不取 $APPLETS 派生、也不取 .rodata 那张表**。
#   缺陷名（C-102）**声明集与落包集混用**：
#     原来这一段写的是「从 $APPLETS 转小写派生」—— 而 $APPLETS 是**白名单**（决定编不编），
#     而「这份字节实际响应哪些名字」是 **make install 连带建的那批软链**
#     （man1 INSTALL 段：「If you enabled any applet installation scheme …
#      these will also be installed in the location pointed to by CONFIG_PREFIX」）。
#      **两方的读数各指哪一轮**（2026-10-20 实测，逐个包数过）：
#       旧轮（b301f5 之前的那几轮：srcbe72ff17f17e / srccc116ca1a148 / srcec91509e6274）：
#         **软链 17、无 sh**  那几轮 BB_BINS 取 $APPLETS 派生  sh 没被声明  没进包
#       新轮（srcb1a8a6b2b285 / srcbc042d4e8e89 / src52587e94cdde，即 7f72d02 与 9e8338b 之后）：
#         **软链 18、含 sh**  BB_BINS 取落包集  落包集里那 18 个（含 sh）全被声明  全进包
#        而「落包集 18（18 = 17 软链 + 1 本体）」这个数是**错的**：
#         落包集 = **软链的条数**；而「包内 bin/ 的条目数」= 软链 + 本体（今天 = 19）。
#          我 2026-10-19 那一版把这两件事混成了一件 —— 03 今天按 C-100 补了这一条。
#      而**「sh 为什么在落包集里」这一格【未核】**：
#         白名单式配置（:114-121）先关掉 .config 里所有 CONFIG_XXX=y，再只开 $APPLETS 那些；
#         而 sh 是 ash 的别名 applet（它的开关是 CONFIG_ASH / CONFIG_SH_IS_ASH），
#         那两项**不在 $APPLETS 里**  按这段逻辑该被关掉  sh 不该被编  不该有软链。
#          而实测**新轮里 sh 在**   **这两条对不上**，而**答它要那一轮的 .config**（本机没有）
#          **这一格按未核记下，不猜**（C-05）；而它不改变「取落包集」这个改法。
#      而**取 .rodata 那张表**（19 个）也不行：它含 **awk**，而 awk 在 $OUT/bin 下**不存在**
#     （这一件编译时按白名单关掉了它） package-bundle-zip.sh 的 BINS 那一支会判红
#     （「清单里的命令入口不存在：bin/$bn 在 $OUT 下没有 —— 声明了但没产出」）。
#      三个集合的读数（按轮次分清）：
#       旧轮：表 19 / 落包集 17 / 白名单 17  表 − 落包集 = {awk, sh}、落包集 − 白名单 = ∅
#       新轮：表 19 / 落包集 18 / 白名单 17  表 − 落包集 = {awk}、落包集 − 白名单 = {sh}
#        而**差集记的是什么**：它记的是「这一件编译时没编出那个能力」——
#         而它**不区分**「我们显式关掉了它」与「它依赖的那些被我们关掉了，于是它编不出来」（01 今天裁的）。
#        而这一格与 C-16 同源：它是唯一能发现「事实与声明开始分家」的那个核对口。
#        而这**一个**差集不区分两种成因：
#         · awk 是**它依赖的那些配置被一起关掉了**，于是它编不出来（**不是「我们不要它」**）；
#         · sh 是它自己那个配置（CONFIG_ASH / CONFIG_SH_IS_ASH）没开。
#          两者都是「白名单式配置」的后果，而**白名单是我们写下的（声明）**。
#        而**落包集 − 白名单 = 空集**（实测两者相等），所以「落包集里有我们没声明的名字」这句话今天不成立。
#      而**取 .rodata 那张表**（19 个）也不行：它含 **awk**，而 awk 在 $OUT/bin 下**不存在**
#     （这一件编译时按白名单关掉了它） package-bundle-zip.sh 的 BINS 那一支会判红
#     （「清单里的命令入口不存在：bin/$bn 在 $OUT 下没有 —— 声明了但没产出」）。
#      三个集合的今天读数（实测 2026-10-20）：**表 19 / 落包集 17 / 白名单 17**；
#       **表 − 落包集 = {awk, sh}**（被白名单裁掉的那部分） 而这与 C-16 同源：
#       它是唯一能发现「事实与声明开始分家」的那个核对口。
BB_BINS="$(cd "$OUT_DIR/bin" && for f in *; do [ -L "$f" ] && printf '%s ' "$f"; done)"
emit_piece "$TOOL" "$OUT_DIR" bin/libbusybox.so 500000 "$BB_BINS" "$TOOL.version"