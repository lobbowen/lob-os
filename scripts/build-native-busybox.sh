#!/usr/bin/env bash
# busybox —— 底座工具件（多调用二进制 + 软链）。
#
# ── 为什么进底座 ──
# 对齐 Linux：几乎每个程序都要用到的基础命令（ls/cat/cp/mv/tar/gzip/grep/sed）
# 不该要求用户先装。按「有真实消费者才补」判据，它们的消费者是**每一个程序**，
# 所以属于基础设施而不是小众能力。
#
# ── 形状（照 Linux 的 busybox 惯例）──
#   一个多调用二进制 $PREFIX/bin/busybox，
#   加上 tar/ls/cat/gzip/... 的软链（由 PrefixProvisioner 建）。
#   判据：`busybox tar --help` 能跑，而不是 `tar --help`（后者证明不了软链对了）。
#
# ── 静态编，不链底座 libz ──
# gzip/gunzip 的 zlib 代码编进去，代价 ~100-200KB，换来 **busybox 完全自足**。
# 底座件之间不互相依赖到「少一件就起不来」的程度 —— 这是底座稳定性的底线。
# （阶段0 的 libz.so 是给**商店件**改动态链用的，不是给底座件用的。）
#
# ── 配置：不要用 configs/android_ndk_defconfig ──
# 核实过它是 **BusyBox 1.24.0 (2015)**，而源码是 1.36.1 —— 跨了十几个版本，
# 配置项名已变。而且它的 EXTRA_CFLAGS 硬编码了 `-march=armv7-a`（32 位 ARM）
# 与 `-nostdlib`（Bionic 下链接必失败）。照抄会编出错误架构。
#
# 正确做法：`make defconfig` 取**本版本自己的**基线，再按需改配置项。
# 所有配置项名从源码的 Config.in 与 defconfig 输出里核实，不凭记忆写。
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

ABI="${ABI:-arm64-v8a}"
API="${ANDROID_API:-23}"
TOOL="busybox"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
JOBS="${JOBS:-4}"

BUSYBOX_VER="${BUSYBOX_VER:-1.36.1}"
# 两个源的**压缩格式与目录名都不同**（都实测过，不是照抄 URL）：
#   busybox.net      busybox-1.36.1.tar.bz2   → tar.bz2，解出 busybox-1.36.1/
#   github mirror    1_36_1.tar.gz            → tar.gz ，解出 busybox-1_36_1/
# 早先写成「所有源都用 tar xjf + 同一个目录名」，那在第二个源上会失败 ——
# 而且失败信息是「解包失败」，看不出是格式不对。
# 所以每个源带自己的解包方式与期望目录名。
# 三元组：<说明> <URL> <解包flag> <期望目录名>

die() {
  # 第二个及之后的参数都并进同一条 ::error。只取 ${2:-} 的话，
  # 调用点传的第 3 句往后会被**静默丢掉** —— 写上去像是说了，其实没输出。
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}
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

# ── 取源码 ──
#
# 走 fetch-pinned：它按 userland-sources.json 的 sha256 **逐字节校验**。
#
# 早先这里是裸 curl +「拿到就算」，而且注释写「上游 sha256 查不到可靠官方值时
# 不钉它 —— 那比钉错更坏」。但那个前提是错的：**下下来算一次就是可靠值**
# （实测 busybox.net 的 busybox-1.36.1.tar.bz2 = b8cc24c9…）。
# 不钉的真实后果更坏：镜像被替换或传输损坏都会静默通过，然后编出错的 busybox，
# 而它是 upstream 档、缺件硬红的底座件。
#
# **只钉 busybox.net 那一个源**：github mirror 给的是 .tar.gz，与 .tar.bz2
# 是不同字节（实测 ea549484… ≠ b8cc24c9…）。一个 sha256 配两种压缩会让
# 命中 github 时必然校验失败 —— 那正是 python 那次踩过的坑。
# 所以 SOURCES 里 github 那条**不参与下载**，只留注释说明它为什么不能用。
SRC=""
BS_VER_TABLE="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --src-version busybox 2>/dev/null || true)"
if [ -z "$BS_VER_TABLE" ]; then
  die "钉值表里没有 sources.busybox" \
    "没有 sha256 就不该下载底座件的源码 —— 那等于不校验。补上（scripts/pin-github-release.js 不适用，busybox 不在 GitHub Releases）"
fi
if [ "$BS_VER_TABLE" != "$BUSYBOX_VER" ]; then
  die "busybox 版本不一致" \
    "构建脚本写的是 $BUSYBOX_VER，钉值表是 $BS_VER_TABLE。两处必须一致，否则钉的 sha256 与要编的版本对不上。"
fi
TGZ="$WORK/busybox.tar.bz2"
note "取源码 $BUSYBOX_VER（sha256 由钉值表逐字节校验）"
if ! bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin busybox "$TGZ"; then
  die "取源码失败或 sha256 不符" \
    "钉值与来源见 scripts/userland-sources.json 的 sources.busybox。**不要**改成不校验的下载 —— 那样编出来的 busybox 错了没人知道。"
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

# 源码树形状的自检：不是「解包成功就算」
for must in Makefile Config.in libbb; do
  [ -e "$SRC/$must" ] || die "源码树异常" "缺 $must —— 拿到的不是 busybox 源码"
done
[ -d "$SRC/applets" ] || die "源码树异常" "缺 applets/ 目录"
# 版本自检：Makefile 里版本是**三段分开写**的（VERSION=1 / PATCHLEVEL=36 /
# SUBLEVEL=1，核实过），不是一行 `VERSION = 1.36.1`。
# 早先写成单行 sed，取到的是 `1` 而不是 `1.36.1`，于是「版本不符」判死 ——
# 那是判据本身写错，不是源码有问题。
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

# ── 配置 ──
# make defconfig 生成**本版本**的基线（不用 configs/android_ndk_defconfig，
# 那份是 1.24.0 的，配置项名跨代）。
cd "$SRC"
make defconfig > "$WORK/defconfig.log" 2>&1 \
  || { echo "=== make defconfig 失败取证（末 30 行）==="; tail -30 "$WORK/defconfig.log"; exit 1; }
[ -f "$SRC/.config" ] || die "defconfig 没产出 .config" "看 $WORK/defconfig.log"

# 下面每个 applet 名与编译选项都**从 1.36.1 源码逐个核实过**（核实方法见下），
# 不是凭记忆写的 —— 写错的后果是 busybox 静默不编那个 applet，
# 编译照样「成功」，装上去才发现少东西。
#
# 核实方法（本轮实际用过的，别再用错的方式查）：
#   applet      grep '^//config:config NAME$' --include='*.c' .
#               —— applet 的 config 不是写在 Config.in 里，而是由 busybox 自己
#                  的一个生成步骤从 .c 文件里的 `//config:` 注释拼接生成到
#                  <dir>/Config.in。所以在 Config.in 里 grep 是找不到的。
#   编译选项    grep '^config NAME$' Config.in  （顶层 Config.in 有，如 STATIC/PIE）
APPLETS="TAR GZIP GUNZIP GREP SED AWK LS CP MV CAT MKDIR RM LN VI DF PS TRUE FALSE"

set_conf() { # set_conf <KEY> <y|n>
  local k="$1" v="$2"
  if [ "$v" = y ]; then
    grep -q "^CONFIG_${k}=y" "$SRC/.config" && return 0
    sed -i "s/^# CONFIG_${k} is not set/CONFIG_${k}=y/" "$SRC/.config"
    grep -q "^CONFIG_${k}=y" "$SRC/.config" || echo "CONFIG_${k}=y" >> "$SRC/.config"
  else
    sed -i "s/^CONFIG_${k}=y/# CONFIG_${k} is not set/" "$SRC/.config"
  fi
}
for a in $APPLETS; do set_conf "$a" y; done

# 静态编 —— 见文件头「静态编，不链底座 libz」
set_conf STATIC y
# 不要 PIE：我们要的是普通可执行件（多调用二进制 + 软链），PIE 没有好处
set_conf PIE n
# DESKTOP 关掉：它只影响「桌面兼容相关的一堆功能开关」，我们要的是小而全的基础命令
set_conf DESKTOP n
# 交叉编译前缀留空：ndk 的 clang 直接调用，不需要 arm-linux-androideabi- 这种前缀
set_conf CROSS_COMPILER_PREFIX n

# EXTRA_CFLAGS —— 1.36.1 的 defconfig 默认是空的（核实过 Config.in:498），
# 所以不用覆盖掉「别人的老参数」，只需给出自己要的。
sed -i '/^CONFIG_EXTRA_CFLAGS=/d' "$SRC/.config" || true
echo "CONFIG_EXTRA_CFLAGS=\"-O2 -fPIC -D__ANDROID_API__=$API\"" >> "$SRC/.config"

# oldconfig 让配置生效并检查一致性
make oldconfig > "$WORK/oldconfig.log" 2>&1 \
  || { echo "=== make oldconfig 失败取证（末 30 行）==="; tail -30 "$WORK/oldconfig.log"; exit 1; }

# 配置项真的生效了吗 —— 配置项名写错时 busybox 会**静默**不编那个 applet，
# 所以必须逐项核实产出物，而不是相信 .config 里那行 y。
MISSING=""
for a in $APPLETS; do
  grep -q "^CONFIG_${a}=y" "$SRC/.config" || MISSING="$MISSING $a"
done
[ -z "$MISSING" ] || die "配置项未生效" \
  "这些 applet 没进 .config：$MISSING —— 配置项名与本版本对不上（别凭记忆写）"
note "applet 配置项全部生效（$(echo $APPLETS | wc -w) 项）"

# ── 编 ──
make -j"$JOBS" > "$WORK/build.log" 2>&1 \
  || { echo "=== busybox 编译失败取证（error 行 + 末 40 行）==="; \
       grep -nE "error:|Error [0-9]+$|undefined (symbol|reference)" "$WORK/build.log" | head -25 || true; \
       tail -40 "$WORK/build.log"; exit 1; }

[ -f "$SRC/busybox" ] || die "没产出 busybox" "$SRC/busybox 不存在"
cp -f "$SRC/busybox" "$OUT/bin/$TOOL"
chmod 0755 "$OUT/bin/$TOOL"

# ── 形态自检 ──
BB="$OUT/bin/$TOOL"
SIZE=$(stat -c%s "$BB")
[ "$SIZE" -gt 500000 ] || die "产物可疑" "busybox 只有 $SIZE 字节 —— 静态编不该这么小"
"$LLVM_STRIP" --strip-unneeded "$BB" 2>/dev/null || true

INFO=$(file -b "$BB")
case "$INFO" in
  *aarch64*|*arm64*|*ARM64*) : ;;
  *) die "架构不对" "$INFO —— 装到真机上 exec format error" ;;
esac
note "$INFO"

# 静态编的判据：不依赖任何共享库。真机上动态链失败是「起不来」的最常见形态。
NEEDED=$("$LLVM_READELF" -W -d "$BB" 2>/dev/null | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' | wc -l)
DYN=$(printf '%s\n' "$("$LLVM_READELF" -W -l "$BB" 2>/dev/null || true)" | awk '/^[[:space:]]*DYNAMIC/{print "y"}')
if [ -n "$DYN" ]; then
  die "不是静态产物" "有 PT_DYNAMIC（NEEDED=$NEEDED 项）—— 底座件不该依赖任何共享库"
fi

# 16KB 对齐（Android 15+）
BAD=$("$LLVM_READELF" -W -l "$BB" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$(( a )); [ "$d" -eq 0 ] && continue
          [ $(( d % 16384 )) -ne 0 ] && printf ' %s' "$a"
        done)
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

echo "[ok] $BB $(stat -c%s "$BB") 字节（静态，无 PT_DYNAMIC，16KB 对齐合格）"
echo "[$TOOL] 软链由 PrefixProvisioner 建（$APPLETS 逐个软到 \$PREFIX/bin/）"
echo "[$TOOL] 判据用 'busybox <applet> --help'，不用 '<applet> --help'"
printf '%s' "$BB_VER" > "$OUT/$TOOL.version"