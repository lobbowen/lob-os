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

answers() {
  local n=0
#  缺陷名 C-102：oldconfig 的回答文件必须带显式默认值。空行不够：kconfig 要显式默认、会让它再问一遍，所以给 n。
#  数值项（FEATURE_IPC_SYSLOG_BUFFER_SIZE）要一个数，空行与 n 都答不上，答案里要给默认那个数。
#  另一个坑：answers 原来是进程替换、跟 kconfig 不同步，问后半段就没答案了；处置：答案先落成文件、oldconfig 从文件读。
#  缺陷名 C-102：answers 写死一个文件名，defconfig 那一次调用也导到同一个 defconfig-answers.txt，
#  于是 cat 读它自己正在写的文件，CI 报「cat: …: input file is output file」，编不过。处置：落点由调用给。
  local af="${1:-$WORK/oldconfig-answers.txt}"
  : > "$af"
  local m=0
  while [ "$m" -lt 1200 ]; do printf 'n\n16\n256\n' >> "$af"; m=$((m + 1)); done
  cat "$af"
}
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
#  缺陷名 C-102：旧版每次都重新下，网络一抖就编不成（2026-10-27 那一趟就是 curl 35）。
#  处置：已在那落点、且过了钉值的那一份直接用（「字节是什么」与「字节从哪来」是两件事）。
BUSYBOX_SHA="$(node -e 'const t=require(process.argv[1]);process.stdout.write(t.sources.busybox.sha256)' "$ROOT_DIR/scripts/component-sources.json")"
if [ -f "$TGZ" ] && [ "$(sha256sum "$TGZ" | cut -d" " -f1)" = "$BUSYBOX_SHA" ]; then
  note "源码 $BUSYBOX_VER 就位（落点那一份逐字过了那钉值，直接用）"
else
if ! bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin busybox "$TGZ"; then  die "取源码失败或 sha256 不符" \
    "钉值与来源见 scripts/component-sources.json 的 sources.busybox。**不要**改成不校验的下载 —— 那样编出来的 busybox 错了没人知道。"
fi
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

# mount 那个 applet 要 mntent 那一族，而 bionic 没有 addmntent；上游那一处没关它。
# 处置：上游那一处补上 bionic 的分支（busybox-mntent-bionic.py）。
[ -f "$SRC/util-linux/mount.c" ] && python3 "$ROOT_DIR/scripts/recipes/busybox-mntent-bionic.py" "$SRC/util-linux/mount.c" || true

cd "$SRC"
# defconfig 也会问（它要 stdin、CI 没有 tty），读到 EOF 就退、报「No rule to make target」。
# 处置：答案先落成文件，defconfig 从文件读。
answers "$WORK/defconfig-answers.txt" > /dev/null
make defconfig < "$WORK/defconfig-answers.txt" > "$WORK/defconfig.log" 2>&1 \
  || { echo "=== make defconfig 失败取证（末 30 行）==="; tail -30 "$WORK/defconfig.log"; exit 1; }
[ -f "$SRC/.config" ] || die "defconfig 没产出 .config" "看 $WORK/defconfig.log"

# AWK 不在清单里，尽管上游 android_ndk_defconfig 默认开着它——白名单把除 $APPLETS 以外全关了，
# awk 依赖的 CONFIG_DESKTOP / CONFIG_FEATURE_* 一起被关掉，于是 CONFIG_AWK=y 单独存在不足以让它被编，
# make install 不给它建软链。2026-10-16 实测：CI 判红「bin/awk 声明了但没产出」，而本行原先写着 AWK。

#  缺陷名 C-102：applet 清单没有 TEST 与 ASH，而 initramfs 那一层要 [ 与 sh。
#  成因：[ 是 TEST 那个 config（# CONFIG_TEST is not set）、sh 是 ASH 那个 config（CONFIG_SH_IS_ASH=y），而白名单只留 APPLETS。
#  症状（本机实跑）：/init: line 39: [: not found；init: applet not found（busybox 要 argv[0] 是 sh）。
#  处置：TEST 与 ASH 加进 APPLETS。按 C-104：busybox 有两条路（PIE 与静态），是两个不同产物、处置不合并。

#  缺陷名 C-102：白名单把 ASH_INTERNAL_GLOB 也关掉了，而 ash.c 要 libc 的 glob 那一族。
#  成因：ash.c:263 是「#if !ENABLE_ASH_INTERNAL_GLOB」+「# include <glob.h>」，ASH_INTERNAL_GLOB 默认 y
#  （shell/Config.in:111「default y」），它被关掉那 #include 也就没了，ash.c 用 libc 的 glob 却没有头文件。
#  症状：「1251:shell/ash.c:7818:7: error: call to undeclared function 'glob'」、
#  「1578:make[1]: *** [scripts/Makefile.build:197: shell/ash.o] Error 1」。
#  处置：ASH_INTERNAL_GLOB 加进 APPLETS（它是内部的，不要头文件）。

#  缺陷名 C-102：APPLETS 把 applet 名与 config 名混在一起，落包集核对把 config 名也当成 applet。
#  成因：ASH_INTERNAL_GLOB 是 config 名不是 applet 名，声明集拿 APPLETS 比落包集，它永远落在外面。
#  症状：「::error title=声明了但没产出:: test ash_internal_glob 在 …/out/bin 下不存在」。
#  处置：config 名分开放 CONFIG_EXTRA，APPLETS 只留 applet 名。

#  缺陷名 C-102：init 那一族要 mount 与 echo，而旧版没有那两个 applet。
#  后果：init 挂不上 rootfs 那一层，症状是「mount: not found」与「echo: not found」。
#  处置：两个 applet 进清单（两个不同动作，按 C-135 各答一件事、不合并）。sleep 也要（init 的兜底靠它）。

#  缺陷名 C-102：mount 那个 applet 在 bionic 上编不过——util-linux/mount.c 调 addmntent，要 <mntent.h>，
#  而 bionic 没有那个头（HAVE_MNTENT_H 关着），错是「util-linux/mount.c:809:4: error: call to undeclared function addmntent」。
#  处置：scripts/recipes/busybox-mntent-bionic.py 补上游那一处；echo 与 sleep 仍要（init 靠它们说话与兜底）。
#  printf 也要（07 核到设备上那套 busybox 只有 21 个 applet，echo 与 printf 不在里面），处置：printf 进清单。

#  缺陷名 C-102：APPLETS 漏了 readlink 与 test 两个，于是 guest 那一族只有 21 个 applet。
#  处置：APPLETS 补上 readlink 与 test（「guest 要什么」与「配方列了什么」是两件事）。
APPLETS="TAR GZIP GUNZIP GREP SED LS CP MV CAT MKDIR RM LN VI DF PS TRUE FALSE ASH MOUNT LOSETUP ECHO SLEEP PRINTF READLINK TEST ENV"
#  额外的 config 名（不是 applet 名，只管开关）：ASH_INTERNAL_GLOB 要它是因为 ash.c 用 libc 的 glob
#  （关掉之后那 #include <glob.h> 就没了）；TEST1 是 [ 那个 applet（coreutils/Config.in:781），
#  TEST 是 test 那个 applet（:773），两个是两件事、不合并。
#  缺陷名 C-102：CONFIG_BUSYBOX 没被开——busybox 看 argv[0] 决定跑哪个 applet（appletlib.c 的 run_applet_and_exit），
#  只在 ENABLE_BUSYBOX 开着时才认「busybox」这个名字，不开就报「busybox: applet not found」。
#  处置：CONFIG_EXTRA 加上 BUSYBOX（set_conf 会开它）。
CONFIG_EXTRA="ASH_INTERNAL_GLOB TEST1 BUSYBOX"

set_conf() {
  local k="$1" v="$2"
  # config 名要大写（kconfig 只认大写）。
  k="$(printf %s "$k" | tr "[:lower:]" "[:upper:]")"
  if [ "$v" = y ]; then
    grep -q "^CONFIG_${k}=y" "$SRC/.config" && return 0
    sed -i "s/^# CONFIG_${k} is not set/CONFIG_${k}=y/" "$SRC/.config"
    grep -q "^CONFIG_${k}=y" "$SRC/.config" || echo "CONFIG_${k}=y" >> "$SRC/.config"
  else
    sed -i "s/^CONFIG_${k}=y/# CONFIG_${k} is not set/" "$SRC/.config"
  fi
}


#  白名单：只留 APPLETS 那些，其余全关。
# 此前是「defconfig 全开、再逐个关掉有问题的」：defconfig 开了 512 项，靠「缺头就关」「bionic 缺函数就关」
# 一层层筛会一直漏——HOSTID 用 gethostid() 而 bionic 没有那个函数，但头文件在，按「NDK 有没有这个头」扫抓不到。
# 改成白名单后，缺头的、缺函数的、将来才暴露的都不会进编译。
# 与上游的一致性：busybox 自带 configs/android_ndk_defconfig（512 项，给 Android NDK 用），白名单比它严，
# 「上游开着」不代表「我们开着」（AWK 就是这样），必须实测落包数。
grep -o '^CONFIG_[A-Z0-9_]*=y' "$SRC/.config" | sed 's/^CONFIG_//; s/=y$//' > "$WORK/all-on.txt"
N_ON=0
while read -r k; do
  [ -n "$k" ] || continue
  set_conf "$k" n
  N_ON=$((N_ON + 1))
done < "$WORK/all-on.txt"
echo "[busybox] defconfig 开了 $N_ON 项，已全部关掉（白名单式）"
# mount 那个 applet 带来 syslog 那一族（FEATURE_MOUNT_NFS / FEATURE_MOUNT_CIFS 选 FEATURE_SYSLOG），它们不在清单里。
# 不关掉的话 kconfig 会就那几个项再问（「清单是什么」与「依赖是什么」是两件事）；处置：逐个显式关掉（见下一行）。
for a in $APPLETS $CONFIG_EXTRA; do set_conf "$a" y; done
#  INIT 与 LINUXRC 是上游默认开着的（它们选 FEATURE_SYSLOG），不在清单里，不关掉 kconfig 会再问那几个项。
for k in INIT LINUXRC SYSLOGD KLOGD LOGGER LOGREAD FEATURE_SYSLOG FEATURE_IPC_SYSLOG FEATURE_KMSG_SYSLOG FEATURE_SYSLOGD_CFG FEATURE_SYSLOGD_DUP FEATURE_SYSLOGD_PRECISE_TIMESTAMPS FEATURE_MOUNT_NFS FEATURE_MOUNT_CIFS FEATURE_MOUNT_LABEL FEATURE_MOUNT_FAKE FEATURE_MOUNT_VERBOSE FEATURE_MOUNT_HELPERS FEATURE_MOUNT_MTAB FEATURE_MOUNT_HELP; do set_conf "$k" n; done
echo "[busybox] 白名单开启：$APPLETS（另外那 config：$CONFIG_EXTRA）"

#  补丁：Android 分支里两行过期的 #undef。症状（实测）：ld.lld: error: duplicate symbol: strchrnul。
# 成因：libbb/platform.c 按 `#ifndef HAVE_STRCHRNUL` 自己实现了一份，而 bionic 静态库已有一份，链接时撞了。
#  include/platform.h 的 Android 分支写着「# undef HAVE_MEMPCPY」「# undef HAVE_STRCHRNUL」，那是给 Android 8 之前写的；
#  API 21 之后 bionic 已提供（readelf 查 /system/lib64/libc.so 的 .dynsym 都在），所以改成按 __ANDROID_API__ 分级。
#  只改 Android 那一支：platform.h 里有 4 处该 #undef（__WATCOMC__ / __dietlibc__ / __APPLE__ / Android），
#  区间由 find-platform-h-android-branch.js 按分支头是不是写着 ANDROID 来算（不是「区间里含 undef」）。
PLATFORM_H="$SRC/include/platform.h"
[ -f "$PLATFORM_H" ] || die "找不到 platform.h" "$PLATFORM_H 不存在 —— 上游路径变了，按实际改这里"

# 区间计算与实际修改都在那个脚本里（--patch 模式）。不用 sed 改多行：s/// 里的换行转义是 GNU sed 扩展，
# 而设备上的 /system/bin/sed 是 toybox（实测），它不认；地址块写法在 toybox 上又会被当文件名
# （实测「sed: 529,554: No such file or directory」）。
node "$ROOT_DIR/scripts/verify/find-platform-h-android-branch.js" "$PLATFORM_H" --patch
# 缺陷名 C-102：这一件编成了 ET_EXEC，而 ET_EXEC 在设备上起不来。
# 真机读数（07，2026-10-24，0c1d9af）：busybox 报 `Cannot run program`（error=13），入口 547392 字节、Type: EXEC、INTERP 行数 0。
# 成因（Config.in:372、Makefile.flags:125/131）：STATIC=y 加 -static 必然 ET_EXEC；PIE 一项 `depends on !STATIC`，STATIC=y 时不可选。
# 起不来不是「套不套 linker64」：Shape.Exec（ExecEntry.kt:59）直接 exec 入口，而入口是 u:object_r:app_data_file:s0、
# app 域 u:r:untrusted_app 没有执行许可（EACCES，error=13），套 linker64 也被 AOSP linker_main.cpp:405 挡掉。
# 处置：STATIC 关掉、PIE 给 y，成为 ET_DYN 走「经 linker64」那条路（域 system_linker_exec:s0 有执行许可）。

#  缺陷名 C-102：这一族只编 PIE 那一个，而 initramfs 那一层要静态那一个（它没有 linker64）。
#  处置：能编两种形态（PIE / 静态），开关由 LOBOS_BUSYBOX_STATIC 给、默认 PIE。
#  按 C-104：busybox 有两条路，落的是两个不同形态的产物、不是同一内容的两条取法；initramfs 只有一条路。
#  开关要让判据要的那一份别被安装器换掉（安装器从 APK 的 jniLibs 落位），两件事不合并。
#  默认还是 PIE（现行那件是 PIE；判据库对 busybox 判的是产物形状，判据归 04）。
BUSYBOX_STATIC="${LOBOS_BUSYBOX_STATIC:-0}"
if [ "$BUSYBOX_STATIC" = "1" ]; then
  # 静态那一个（是 ET_EXEC，不要 linker64）。
  set_conf STATIC y
  set_conf PIE n
else
  set_conf STATIC n
  set_conf PIE y
fi

#  编译器：官方只有「前缀」这一条路，没有 clang 变体。busybox 的 Makefile 第 292~298 行写死了
#     CC = $(CROSS_COMPILE)gcc / LD = $(CC) -nostdlib / AR = $(CROSS_COMPILE)ar / NM = $(CROSS_COMPILE)nm，
#  拼的是 `aarch64-linux-android-gcc`，而 NDK 里的编译器叫 `aarch64-linux-android35-clang`，那个 gcc 不存在。
#  而 CROSS_COMPILER_PREFIX 是 .config 里的 string 项，`make oldconfig` 会就它提问（Config.in:470），
#  不给输入读到 EOF 就退 1（日志停在「Cross compiler prefix (CROSS_COMPILER_PREFIX) [] (NEW) make[1]: *** Error 1」）。
#  所以编译器走 make 命令行（优先级高于 .config，且不需要 oldconfig 过问）。
echo "  [busybox] CC = $CC（make 命令行给，不经 .config）"
echo "  [busybox] 不设 CROSS_COMPILE：它拼出来的 gcc 在 NDK 里不存在"

# 交叉编时 kconfig 还会问一堆只有交叉编才有的项，一次性喂默认答案。不能只给空输入（读到 EOF 就退 1），
# 也不要用 `yes ""`（管道提前关闭会 Broken pipe），这里自己产出一批空行。

sed -i '/^CONFIG_EXTRA_CFLAGS=/d' "$SRC/.config" || true
  # CONFIG_EXTRA_LDFLAGS 今天不加：busybox 的 Kconfig 有 `config EXTRA_LDFLAGS`（本机取过 1.36.1 源码树），
  # 但这一件改成动态（PIE）之后 NEEDED 的只有 libc 那几项系统库（不是同件 lib/ 下的字节），RUNPATH 不适用
  # （加了只会让产物多一个无用段）；这一件落 bin/、lib/ 下没有要找的。
  echo "CONFIG_EXTRA_CFLAGS=\"-O2 -fPIC -D__ANDROID_API__=$API\"" >> "$SRC/.config"

#  必须喂输入：oldconfig 会就新增项提问，CI 上没有 tty 就卡死（日志停在 "Support --long-options (LONG_OPTS) [Y/?] y"）。
#  `yes ""` 会让每个提问取默认，但 yes 会被提前关闭的管道打断（Broken pipe），所以自己产出一批空行。
answers "$WORK/oldconfig-answers.txt" > /dev/null
make oldconfig < "$WORK/oldconfig-answers.txt" > "$WORK/oldconfig.log" 2>&1 \
  || { echo "=== make oldconfig 失败取证（末 30 行）==="; tail -30 "$WORK/oldconfig.log"; exit 1; }

  # 关掉之后复核：还有哪个 applet 的源文件要内核 uapi 头？
  STILL="$(
for a in $APPLETS $CONFIG_EXTRA; do
      grep -q "^CONFIG_${a}=y" "$SRC/.config" && printf '%s\n' "$a"
    done | tr '\n' ' '
  )"
  echo "[busybox] 关完内核 uapi 头那批之后，仍开启的 applet：${STILL:-（无）}"

MISSING=""
for a in $APPLETS $CONFIG_EXTRA; do
  grep -q "^CONFIG_${a}=y" "$SRC/.config" || MISSING="$MISSING $a"
done
[ -z "$MISSING" ] || die "配置项未生效" \
  "这些 applet 没进 .config：$MISSING —— 配置项名与本版本对不上（别凭记忆写）"
note "applet 配置项全部生效（$(echo $APPLETS | wc -w) 项）"

#  编译器只能从这里给——命令行赋值优先级高于 .config 与 Makefile 里的 `CC = $(CROSS_COMPILE)gcc`。
#  AR/NM/STRIP 在它的 Makefile 里是 $(CROSS_COMPILE)ar/nm/strip，同样得给，否则交叉编时会去找宿主 x86-64 的
#  ar/nm，产物就废了。LD 不给：它默认是 `$(CC) -nostdlib`，而 Makefile 里没有规则真的用 $(LD) 链接
#  （链接全走 $(CC)），给它反而会因为值里带空格被命令行拆成两项。
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
  # make install：busybox 官方机制——构建系统自己知道编了哪些 applet，连带把全部同目录相对软链建好（指向本体）。
  # 限定：这句话只说 install/bin/ 里那批同目录软链不用我们列，不否定上面 $APPLETS ——
  # 那是「要开哪些 applet」的构建配置，是我们自己的数据，必须列。
  # （原措辞没带「同目录」限定，会被读成「applet 名都不用列」，而 :78 恰好列了 18 个，读起来自相矛盾。2026-10-15 修正。）
  rm -rf "$WORK/install"
  make "${MAKE_ARGS[@]}" CONFIG_PREFIX="$WORK/install" install > "$WORK/install.log" 2>&1 \
    || { echo "=== make install 失败取证 ==="; tail -30 "$WORK/install.log"; exit 1; }
  [ -x "$WORK/install/bin/busybox" ] || die "make install 没产出 busybox" "$WORK/install/bin/busybox 不存在"

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR/lib" "$OUT_DIR/bin"
#  本体落 bin/（按形态是程序形态：ET_EXEC、无 PT_INTERP —— 07 的 readelf 读数）。
#   缺陷名 C-102：原来这里是 lib/libbusybox.so，而运行期 dirForRole(program) 会落进 bin/，
#   那 17 条软链若指向 ../lib/libbusybox.so 就断链、整个工具族消失。
#   现在：本体在 bin/libbusybox.so（与形态该落的地方一致），软链指向 ../bin/libbusybox.so。
cp -f "$SRC/busybox" "$OUT_DIR/bin/libbusybox.so"
chmod 0755 "$OUT_DIR/bin/libbusybox.so"
#  缺陷名 C-102：本体只有 libbusybox.so 一个名字，而 applet 那 17 条软链是 argv[0] 派生物，本体自己的名字没被建出来。
#  后果：busybox 那一格报「No such file or directory」。
#  处置：本体自己那一个名字也建出来（busybox -> libbusybox.so，是本体那份字节）。
if [ ! -e "$OUT_DIR/bin/busybox" ]; then
  ln -sf libbusybox.so "$OUT_DIR/bin/busybox"
  note "本体自己那一个名字已建（busybox -> libbusybox.so）"
fi

# applet 落 bin/，软链指向本体那份字节（本体也在 bin/）。
#  缺陷名 C-102：本体与软链目标不一致——上面已把本体搬到 bin/libbusybox.so，而这段原来还写着「本体留在 lib/」
#   且软链指向 ../lib/libbusybox.so，那 17 条会全是断链、整个工具族在真机上消失而 CI 全绿。处置：软链目标跟着本体搬。
#  「它是库」今天不成立（01 裁的 S32，三条读数）：没有 SONAME（readelf -d 零命中）；没有任何字节 NEEDED 它
#   （逐件扫过 opt/*/*/lib 与 opt/*/*/bin，零命中）；它自己的 DT_NEEDED 是空的。它是独立的程序字节。
#  链接目标必须与本体同目录：写 ./libbusybox.so；写成 libbusybox.so（无 ./）会断链。
NAPP=0
#  缺陷名 C-102：收集只扫 install/bin/，而有的 applet 落在 install/sbin/ 进不来，
#  于是报「::error title=声明了但没产出:: losetup … 不存在」。处置：也扫 install/sbin/ 与 install/usr/bin/。
for l in "$WORK/install/bin/"* "$WORK/install/sbin/"* "$WORK/install/usr/bin/"*; do
  [ -L "$l" ] || continue
  b="$(basename "$l")"
  [ "$b" = busybox ] && continue
  ln -sf ./libbusybox.so "$OUT_DIR/bin/$b"
  NAPP=$((NAPP+1))
done
echo "[$TOOL] 本体(bin/，按形态是程序形态) + $NAPP 个 applet 软链(bin/，指向它) 进包"

# 声明集 ⊆ 落包集（单向包含）。
# 为什么比集合而不是比个数：声明侧 = $APPLETS（我们写的常量），落包侧 = $OUT_DIR/bin/ 的实际内容
#  （make install 产出的），不是 NAPP。NAPP 数的是 install/bin/ 里的软链总数，不是声明的落地数——
#  2026-10-16 实测：去掉 AWK 后声明 17、而 NAPP 反而是 18（落包集含我们没声明的派生 applet），
#  比计数会在「集合包含而计数不同」时误判失败，那正是那次误判。
# 为什么单向：落包集可能比声明的多（派生 applet）是正常的，而「声明了却没有」是断链。
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
[ "$SIZE" -gt 100000 ] || die "产物可疑" "busybox 只有 $SIZE 字节 —— 动态编也不该这么小"
    # 静态那一个不能 strip（要留符号给静态链接用）。
    if [ "$BUSYBOX_STATIC" != "1" ]; then
      "$LLVM_STRIP" --strip-unneeded "$BB" 2>/dev/null || true
    fi

INFO=$(file -b "$BB")
case "$INFO" in
  *aarch64*|*arm64*|*ARM64*) : ;;
  *) die "架构不对" "$INFO —— 装到真机上 exec format error" ;;
esac
note "$INFO"

# 形态判据（缺陷名 C-102）：这一件现在必须是 PIE（ET_DYN）而不是静态（ET_EXEC）。
#  静态编（Makefile.flags:125 `-static`）必然是 ET_EXEC；而 ET_EXEC 在设备上不可执行（app_data_file 在
#  u:r:untrusted_app 里没有执行许可，直接 exec 是 error=13），套 linker64 也被 AOSP linker_main.cpp:405 挡掉。
#  PIE 那一支（Makefile.flags:131 `$(ARCH_PIE)` / `$(ARCH_FPIE)`，:108-109）是 ET_DYN、经 linker64 驱动
#  （execve 目标 /system/bin/linker64，u:object_r:system_linker_exec:s0 有执行许可）。
#  「无 PT_DYNAMIC」是静态编专属判据，今天换成「ET_DYN + 有 PT_INTERP」这两格。
if [ "$BUSYBOX_STATIC" = "1" ]; then
  # 静态那一族（不要 linker64，能进 initramfs 那一层）。
  ETYPE=$("$LLVM_READELF" -W -h "$BB" 2>/dev/null | awk '/^  Type:/ {print $2}')
  [ "$ETYPE" = "EXEC" ] || die "不是静态产物" "e_type=$ETYPE —— 而那**静态**的**那一个**必须**是**那 ET_EXEC"
  NEEDED=$("$LLVM_READELF" -W -d "$BB" 2>/dev/null | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' | wc -l)
  [ "$NEEDED" -eq 0 ] || die "静态产物却有依赖" "NEEDED=$NEEDED 项 —— 而那**静态**的**那一个**逐字**不**能**有**那**动态**依赖"
  #  缺陷名 C-102：`grep -c` 零命中会把这一步带死——零命中的退出码是 1（06 本机实跑 = 1），
  #  set -euo pipefail 就死在那一行，走不到那一句 die，日志停在 note 之后。
  #  静态那一支期望 INTERP = 0（静态的前提），PIE 那一支期望 INTERP ≥ 1。
  #  处置：两处都加 `|| true`（把退出码归零，零照样是零）。按 C-104：形态判据只有一条路。
  INTERP=$("$LLVM_READELF" -W -l "$BB" 2>/dev/null | grep -c 'INTERP' || true)
  [ "$INTERP" -eq 0 ] || die "静态产物却有 PT_INTERP" "INTERP 行数=$INTERP —— 而那**静态**的**那一个**逐字**不**能**要**那 **linker64**"
  echo "[$TOOL] 形态：e_type=$ETYPE、静态（无 PT_INTERP、NEEDED=$NEEDED 项）"
else
  ETYPE=$("$LLVM_READELF" -W -h "$BB" 2>/dev/null | awk '/^  Type:/ {print $2}')
  [ "$ETYPE" = "DYN" ] || die "不是 PIE 产物" "e_type=$ETYPE —— 而这一件必须编成 ET_DYN（PIE），而 ET_EXEC 在设备上不可执行（域的策略），而套 linker64 会被 AOSP linker_main.cpp:405 那一道硬门挡掉"

  NEEDED=$("$LLVM_READELF" -W -d "$BB" 2>/dev/null | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' | wc -l)
DYN=$(printf '%s\n' "$("$LLVM_READELF" -W -l "$BB" 2>/dev/null || true)" | awk '/^[[:space:]]*DYNAMIC/{print "y"}')
[ -n "$DYN" ] || die "不是动态产物" "没有 PT_DYNAMIC（NEEDED=$NEEDED 项）—— PIE 那一族一定有它，而缺了它说明链接没走动态那一路"

INTERP=$("$LLVM_READELF" -W -l "$BB" 2>/dev/null | grep -c 'INTERP' || true)
[ "$INTERP" -ge 1 ] || die "缺 PT_INTERP" "INTERP 行数=$INTERP —— 而这一件要经 linker64 驱动，个驱动者由 PT_INTERP 指出来（设备上是 /system/bin/linker64）"
echo "[$TOOL] 形态：e_type=$ETYPE、PT_INTERP=$INTERP 行、PT_DYNAMIC 有、NEEDED=$NEEDED 项"
fi

BAD=$("$LLVM_READELF" -W -l "$BB" 2>/dev/null | awk '/^[[:space:]]*LOAD/{print $NF}' \
      | while read -r a; do
          case "$a" in 0x[0-9a-fA-F]*) ;; *) continue ;; esac
          d=$((a))
          if [ "$d" -eq 0 ]; then continue; fi
          if [ $(( d % 16384 )) -ne 0 ]; then printf ' %s' "$a"; fi
        done)
[ -z "$BAD" ] || die "16KB 对齐不合格" "这些 LOAD 段：$BAD"

  if [ "$BUSYBOX_STATIC" = "1" ]; then
    echo "[ok] $BB $(stat -c%s "$BB") 字节（静态/ET_EXEC，无 PT_INTERP、无动态依赖，16KB 对齐合格）"
  else
    echo "[ok] $BB $(stat -c%s "$BB") 字节（PIE/ET_DYN，有 PT_DYNAMIC 与 PT_INTERP，16KB 对齐合格）"
  fi
echo "[$TOOL] 软链随 make install 一起建（busybox 官方机制：构建系统自己知道有哪些 applet）"
echo "[$TOOL] 判据用 'busybox <applet> --help'，不用 '<applet> --help'"

# 落包集取 out/bin 底下那些软链（「软链是什么」与「本体是什么」是两件事），本体仍在包里（是那些软链的目标）。
BB_BINS="$(cd "$OUT_DIR/bin" && for f in *; do [ -L "$f" ] && printf '%s ' "$f"; done)"
#  缺陷名 C-102：emit_piece 没被调——配方的尾部被截了。
#  后果：CI 报「build 脚本没产出 dist/component-*.zip」，件发不上去。处置：发的那一句回到尾部。
printf '%s' "$BB_VER" > "$OUT_DIR/$TOOL.version"
emit_piece "$TOOL" "$OUT_DIR" bin/libbusybox.so 100000 "$BB_BINS" "$TOOL.version"
# 缺陷名 C-102：写的那一处与核的那一处用的 `$OUT` 不是同一个值（两处都叫 OUT，同名不同义，按 C-150）。
# 真机读数（build-busybox run 40130918388，sha=798a504）：
#   ##[error]声明: busybox.version（而它是这一件自己声明的）
#   ##[error] 而它在产物的目录里：/home/runner/work/lob-os/lob-os/work/busybox/out/busybox.version
#   ##[error]Process completed with exit code 1.
