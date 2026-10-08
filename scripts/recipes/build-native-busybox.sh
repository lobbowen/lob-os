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
for a in $APPLETS; do set_conf "$a" y; done

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
# ── 关掉「include 了 NDK 没有的头」的 applet ──────────────────────
# busybox 有一批 applet 直接 include 内核 uapi 头（<sys/kd.h> <linux/fs.h>
# <linux/pkt_sched.h> …）。NDK 只提供 libc 头，内核 uapi 头在 Linux 内核
# 源码树的 include/uapi 里，NDK 不提供，于是编不过：
#   console-tools/loadfont.c:59:10: fatal error: 'sys/kd.h' file not found
#   networking/tc.c:…: fatal error: 'linux/pkt_sched.h' file not found
#
# 判据是「**NDK sysroot 里到底有没有这个头**」，不是按 sys//linux 前缀分 ——
# NDK 的 libc 头本来就是 sys/*.h 布局，两者都有例外。逐个问 sysroot 最可靠，
# 判据也跟着 NDK 版本走。
#
# 为什么用脚本算而不是手写名单：
#   名单会随 busybox 版本漂移 —— 漏一个就编不过，多关一个是我们白丢能力。
#   判据本身稳定，所以在构建时从源码树现算。
#   这与 busybox 官方 android_ndk_defconfig 的做法一致（它也是靠关 applet
#   避开这些头），只是我们让它跟着源码树与 NDK 自动算。
#
# 配置项名读 busybox 自己的 `//config:` 注释 —— 依据 scripts/gen_build_files.sh
# 第 117~120 行：各子目录的 Config.in 由它生成，所以官方 tarball 里那些
# Config.in 根本不存在，配置项的真身就在 .c 注释里。
SCAN="node $ROOT_DIR/scripts/verify/verify-busybox-missing-headers.js"
# 引用之前先确认它在 —— 路径写错时报的是 node 的 MODULE_NOT_FOUND，
# 那句堆栈完全看不出是「脚本被移走了」，很难一眼定位。
SCAN_JS="${SCAN#node }"
[ -f "$SCAN_JS" ] || die "扫描脚本不存在" "$SCAN_JS"
# NDK 的 sysroot 按目标架构命名：
#   <ndk>/sysroot/usr/include/<架构>-linux-android/   ← 目标平台的头（linux/*.h 等）
#   <ndk>/sysroot/usr/include/                        ← libc 头（sys/*.h、stdio.h 等）
#
# 目录名**不带 API 级别** —— clang 的文件名带（aarch64-linux-android35-clang），
# 但 sysroot 里的目录是 aarch64-linux-android。上一轮实测取证：
#   sysroot/usr/include/aarch64-linux-android    ← 是这个
#   sysroot/usr/include/aarch64-linux-android35  ← 我推出来的那个，不存在
# 所以拼之前去掉结尾的 API 号。
TARGET_TRIPLE="$(basename "$CC")"
TARGET_TRIPLE="${TARGET_TRIPLE%-clang}"
TARGET_TRIPLE="${TARGET_TRIPLE%-clang++}"
TARGET_TRIPLE="$(printf '%s' "$TARGET_TRIPLE" | sed 's/-linux-android[0-9]*$/-linux-android/')"
NDK_INC="$(cd "$TC_DIR/.." && pwd)/sysroot/usr/include/$TARGET_TRIPLE"
[ -d "$NDK_INC" ] || {
  echo "=== NDK sysroot include 目录取证 ==="
  echo "从 $CC 推出的目录名 = $TARGET_TRIPLE"
  echo "--- sysroot/usr/include 下有什么 ---"
  ls -d "$(cd "$TC_DIR/.." && pwd)/sysroot/usr/include/"* 2>/dev/null | sed -n '1,20p'
  die "找不到 NDK 的 sysroot include" "要找的是 $NDK_INC（按 clang 名去掉 API 号推的）"
}

MISSING_APPLES="$($SCAN "$SRC" "$NDK_INC")" || {
  echo "=== 缺头的 .c 有，但认不出对应的配置项（取证）==="
  $SCAN "$SRC" "$NDK_INC" --list | sed -n '1,40p'
  die "认不出该关哪个 applet" "上面这些 .c include 了 NDK 没有的头，但源码里没有 //config: / applet 标记 —— 判据与这版源码对不上，按取证补"
}
MISSING_FILES="$($SCAN "$SRC" "$NDK_INC" --list | wc -l)"
MISSING_HDRS="$($SCAN "$SRC" "$NDK_INC" --headers)"
echo "[busybox] $MISSING_FILES 个源文件 include 了 NDK 没有的头"
echo "[busybox] 缺的头：$MISSING_HDRS"
echo "[busybox] 对应配置项：$MISSING_APPLES"
for k in $MISSING_APPLES; do set_conf "$k" n; done

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