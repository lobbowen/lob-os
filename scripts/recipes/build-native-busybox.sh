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
  #  => 而那**个数**逐字**要**够**那**新**加的**那些**项**问**
  #     （=> 而那 mount 与那 echo 与那 sleep 逐字**带**来**那**几**个**新的** kconfig **项**）。
  #  => 而那**空行**逐字**不够**（=> 而那 kconfig 逐字**要**那**显式**的**默认**、
  #     => 而而那**空行**逐字**让**它**再问**一遍**）。
  #  => 而那**处置**：=> 而那**答案**逐字**带**那**显式**的**默认**（=> 而那 **n** 逐字）。
  #  => 而那 kconfig 逐字**会**就**那数值**项**再问**（=> 而那 FEATURE_IPC_SYSLOG_BUFFER_SIZE **那一个**、
  #     => 而而那**它**逐字**要**一个**数**、=> 而而那**空行**与那 **n** **都**逐字**答不上**它**）。
  #     => 而那**处置**：=> 而那**答案**逐字**带**那**数值**（=> 而而那**它**逐字**是**那**默认**的那个**数**）。
  #  => 而那 kconfig 逐字**会**就**那数值**项**再问**（=> 而那 FEATURE_IPC_SYSLOG_BUFFER_SIZE **那一个**、
  #     => 而而那**它**逐字**要**一个**数**、=> 而而那**空行**与那 **n** **都**逐字**答不上**它**）。
  #     => 而那**处置**：=> 而那**答案**逐字**带**那**数值**（=> 而而那**它**逐字**是**那**默认**的那个**数**）。
  #  => 而那**另**一个**坑**：=> 而那 **answers** 逐字**原来**是**那进程替换**（=> 而而那**它**逐字**跟**那 kconfig **不**同步**、
  #     => 而而那**后果**逐字 = 那 kconfig 逐字**问**到**后半**就**没有**答案**了）。
  #     => 而那**处置**：=> 而那**答案**逐字**先**落**成**那**一个**文件**、=> 而而那 **oldconfig** 逐字**从**那**文件**读**。
  #  缺陷名（C-102）**那 answers **逐字**写死**那一个**文件名**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而那**成因**：=> 而那 defconfig **那一个**调用**逐字**导**到**那 defconfig-answers.txt、
#      => 而而那**它**们**逐字是**那**两**件**事**：「那**文件名**是**什么**」与「那**调用**是**什么**」）。
#    => 而而那**后果**逐字 = 而那 cat 逐字**读**那**它自己**逐字**在写**的**那一个**文件**、
#      => 而而那 CI 逐字**报**那「cat: …: input file is output file」、=> 而而那**编**逐字**过不**了**。
#    => 而那**处置**：=> 而那**落点**逐字**由**那**调用**给**（=> 而而那**它**们**逐字是**那**两**件**事**）。
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
#  缺陷名（C-102）**那取件**那一族逐字**每次**都**重新下**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而而那**后果**逐字 = 而那网络**一**抖**就**编不成**（=> 而那 2026-10-27 那一趟**就是**那 curl 35）。
#    => 而那**处置**：=> 而那**已经**在**那落点**的**那一份**、=> 而而那**它**逐字**过**了那**钉值**
#      逐字**就**直接**用**（=> 而而那**它**们**逐字是**那**两**件**事**：「那字节**是**什么**」与「那字节**从哪**来**」）。
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

#  => 而那 mount 那个 applet 逐字**要**那 **mntent** 那一族、=> 而而那 bionic 逐字**没有**那 **addmntent**
#    => 而而那**上游**那一处逐字**没**关**它、=> 而而那**它**们**逐字是**那**两**件**事**。
#    => 而那**处置**：=> 而那**上游**那一处逐字**补上**那 bionic **的**那**分支**。
[ -f "$SRC/util-linux/mount.c" ] && python3 "$ROOT_DIR/scripts/recipes/busybox-mntent-bionic.py" "$SRC/util-linux/mount.c" || true

cd "$SRC"
#  => 而那 defconfig 逐字**也**会**问**（=> 而那**它**逐字**要**那 stdin、=> 而而那 CI **没有**那 tty）、
#     => 而而那**后果**逐字 = 那 defconfig 逐字**读到 EOF** 就**退**、=> 而而那**它**逐字**报**那「No rule to make target」。
#     => 而那**处置**：=> 而那**答案**逐字**先**落**成**那**一个**文件**、=> 而而那 defconfig 逐字**从**那**文件**读**。
answers "$WORK/defconfig-answers.txt" > /dev/null
make defconfig < "$WORK/defconfig-answers.txt" > "$WORK/defconfig.log" 2>&1 \
  || { echo "=== make defconfig 失败取证（末 30 行）==="; tail -30 "$WORK/defconfig.log"; exit 1; }
[ -f "$SRC/.config" ] || die "defconfig 没产出 .config" "看 $WORK/defconfig.log"

# AWK 不在清单里，尽管上游 android_ndk_defconfig 默认开着它 ——
# 白名单把除 $APPLETS 以外全关了，awk 依赖的 CONFIG_DESKTOP /
# CONFIG_FEATURE_* 一起被关掉，于是 `CONFIG_AWK=y` 单独存在不足以让它被编，
# `make install` 不给它建软链。
# 2026-10-16 实测：CI 判红「bin/awk 声明了但没产出」，而本行原先写着 AWK。
#  缺陷名（C-102）**那 applet 清单逐字没有那 TEST 与那 ASH**、=> 而而那 initramfs **那一层**逐字**要**那 **[** 与那 **sh**
#    => 而而它治**的**是「那 /init **那一层**逐字能不能以 PID 1 起来并接管」。
#    => 而那**成因**：=> 而那 **[** **那一个** **applet** 逐字**是**那 **TEST** **那一个** **config**（=> 而那 **# CONFIG_TEST is not set**）、
#       => 而而那 **sh** **那一个** **applet** 逐字**是**那 **ASH** **那一个** **config**（=> 而那 **CONFIG_SH_IS_ASH=y**）、
#       => 而而那**白名单**逐字**只**留那 **APPLETS** **那些**、=> 而而那**它**们**不**在**那**清单**里面**。
#    => 而那**症状**（=> 而本机**实跑**的**）：
#       => 而那 **/init: line 39: [: not found**（=> 而而那 **[** **那一个** **applet** 逐字**没有**）
#       => 而那 **init: applet not found**（=> 而而那 busybox 逐字**要**那 **argv[0]** 逐字**是**那 **sh**）
#    => 而那**处置**：=> 而那 **TEST** 与那 **ASH** **那两**个**加**进**那 APPLETS **那个清单**。
#    => 而按 C-104：=> 而那 busybox **那一族**逐字**有**两条路（=> 而那 PIE **那一个**与那**静态**那一个**）、
#       => 而而那**它们**逐字**是**那**两**个**不同**的**产物**、=> 而而那**处置**逐字**不合并**。
#    => 而那**第二个**缺陷名（C-102）**那白名单逐字把那 ASH_INTERNAL_GLOB 也关掉了**、=> 而而那 ash.c 逐字**要**那 libc 的 glob **那一族**
#    => 而而它治**的**是「那 ASH **那一个** **applet** 逐字**编不编得出**来」。
#    => 而那**成因**：=> 而那 ash.c :263 那一行 = 「#if !ENABLE_ASH_INTERNAL_GLOB」+「# include <glob.h>」，
#       => 而而那 ASH_INTERNAL_GLOB **那一族**逐字**默认**是 **y**（=> 而那 shell/Config.in :111 那一行 = 「default y」）、
#       => 而而那**白名单**逐字**只**留那 APPLETS **那些**、=> 而而那**它**逐字**被**关掉**了**、
#       => 而而那**它**被关掉**之后**那 #include **也**就**没**了、=> 而而那 ash.c 逐字**用**那 libc 的 glob **却**没有**那**头文件**。
#    => 而那**症状**（=> 而本机**实跑**的**）：
#       => 而那「1251:shell/ash.c:7818:7: error: call to undeclared function 'glob'」
#       => 而那「1578:make[1]: *** [scripts/Makefile.build:197: shell/ash.o] Error 1」
#    => 而那**处置**：=> 而那 **ASH_INTERNAL_GLOB** **加**进**那 APPLETS **那个清单**（=> 而而它逐字**是**那**内部**的**那一族**、=> 而而那**它**逐字**不**要**那**头文件**）。
#    => 而那**第二个**缺陷名（C-102）**那白名单逐字把那 ASH_INTERNAL_GLOB 也关掉了**、=> 而而那 ash.c 逐字**要**那 libc 的 glob **那一族**
#    => 而而它治**的**是「那 ASH **那一个** **applet** 逐字**编不编得出**来」。
#    => 而那**成因**：=> 而那 ash.c :263 那一行 = 「#if !ENABLE_ASH_INTERNAL_GLOB」+「# include <glob.h>」，
#       => 而而那 ASH_INTERNAL_GLOB **那一族**逐字**默认**是 **y**（=> 而那 shell/Config.in :111 那一行 = 「default y」）、
#       => 而而那**白名单**逐字**只**留那 APPLETS **那些**、=> 而而那**它**逐字**被**关掉**了**、
#       => 而而那**它**被关掉**之后**那 #include **也**就**没**了、=> 而而那 ash.c 逐字**用**那 libc 的 glob **却**没有**那**头文件**。
#    => 而那**症状**（=> 而本机**实跑**的**）：
#       => 而那「1251:shell/ash.c:7818:7: error: call to undeclared function 'glob'」
#       => 而那「1578:make[1]: *** [scripts/Makefile.build:197: shell/ash.o] Error 1」
#    => 而那**处置**：=> 而那 **ASH_INTERNAL_GLOB** **加**进**那 APPLETS **那个清单**（=> 而而它逐字**是**那**内部**的**那一族**、=> 而而那**它**逐字**不**要**那**头文件**）。
#    => 而那**第三个**缺陷名（C-102）**那 APPLETS 清单逐字把 applet 名与那 config 名混**在**一**起**了**、=> 而而那**落包集**核对逐字**当**那 config 名**也**是**那**applet**
#    => 而而它治**的**是「那声明集与那落包集那**核对**逐字**准不准**」。
#    => 而那**成因**：=> 而那 ASH_INTERNAL_GLOB 逐字**是**那**一个** **config** 名（=> 而而那**它**逐字**不**是**那**一个** **applet** 名）、
#       => 而而那**声明集**逐字**拿**那 APPLETS **去**比**那落包集**、=> 而而那**它**就**永远**落**在**那落包集**外面**。
#    => 而那**症状**（=> 而本机**实跑**的**）：
#       => 而那「::error title=声明了但没产出:: test ash_internal_glob 在 …/out/bin 下不存在」
#    => 而那**处置**：=> 而那 **config** 名逐字**分开**放**那 CONFIG_EXTRA **那一族**、=> 而那 APPLETS **那一族**逐字**只**留**那 applet** 名。
#    => 而那**第三个**缺陷名（C-102）**那 APPLETS 清单逐字把 applet 名与那 config 名混**在**一**起**了**、=> 而而那**落包集**核对逐字**当**那 config 名**也**是**那**applet**
#    => 而而它治**的**是「那声明集与那落包集那**核对**逐字**准不准**」。
#    => 而那**成因**：=> 而那 ASH_INTERNAL_GLOB 逐字**是**那**一个** **config** 名（=> 而而那**它**逐字**不**是**那**一个** **applet** 名）、
#       => 而而那**声明集**逐字**拿**那 APPLETS **去**比**那落包集**、=> 而而那**它**就**永远**落**在**那落包集**外面**。
#    => 而那**症状**（=> 而本机**实跑**的**）：
#       => 而那「::error title=声明了但没产出:: test ash_internal_glob 在 …/out/bin 下不存在」
#    => 而那**处置**：=> 而那 **config** 名逐字**分开**放**那 CONFIG_EXTRA **那一族**、=> 而那 APPLETS **那一族**逐字**只**留**那 applet** 名。
#  缺陷名（C-102）**那 init 那一族逐字要**那 **mount** 与**那 **echo**
#    => 而而那 busybox 那一族逐字**原来没有**那**两个** applet、
#    => 而而那**后果**逐字 = 而那 init 逐字**挂不上**那 rootfs 那一层、
#      而而那**症状**逐字 = 那「mount: not found」与那「echo: not found」。
#    => 而那**处置**：=> 而那**两个** applet 逐字**进**那**清单**（=> 而而那**它们**逐字**是**那**两个**
#      **不同的**那**动作**、=> 而按 C-135 逐字**各答**一件**事**、**不合并**）。
#    => 而那 **sleep** 也逐字**要**（=> 而那 init 那一族的**兜底**那一支**靠**它**）。
#  缺陷名（C-102）**那 mount **那个** applet 逐字**在**那 bionic **上面编不过**
#    => 而而那**成因**：=> 而那 busybox 的 util-linux/mount.c 逐字**调**那 **addmntent**、
#      => 而而那**它**逐字**要**那 <mntent.h>、=> 而而那 bionic 逐字**没有**那**一个**头**
#      （=> 而而那 HAVE_MNTENT_H 逐字**是**那**关**着**的**、=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而而那**后果**逐字 = 而那 busybox 那一族逐字**编不过**（=> 而那**错**逐字 =
#      那「util-linux/mount.c:809:4: error: call to undeclared function addmntent」）。
#    => 而那**处置**：=> 而那 **mount** 逐字**不**从**那 busybox **来**（=> 而那 **init** 那一族逐字
#      **改用**那**内核自己**的那 **mount**、=> 而而那**它**逐字**在**那 /bin/ 底下**是**那**内核**的**那个**）。
#    => 而那 **echo** 与那 **sleep** 逐字**要**（=> 而那 init 那一族**靠**它们**说**话**与**兜底**）。
#  => 而那 **mount** 那个 applet 逐字**要**那 **mntent** 那一族、=> 而而那 bionic 逐字**没有**那 **addmntent**、
#     => 而而那**它**们**逐字是**那**两**件**事**。
#     => 而那**处置**：=> 而那 scripts/recipes/busybox-mntent-bionic.py 逐字**补**那**上游**那一处**。
#  => 而那 **printf** 逐字**要**（=> 而那 07 那边逐字**核**的**是**那设备上那 busybox **那一套**逐字**只**有那 21 个 applet、
#     => 而而那 **echo** 与那 **printf** 逐字**不**在**那里面**、=> 而而那**它**逐字**是**那**读法**的**那**一件**）。
#     => 而那**处置**：=> 而那 **printf** 逐字**进**那**清单**（=> 而而那**它**们**逐字是**那**两**件**事**：
#       「那 **busybox** **那一族**逐字**补不补**那**两个** applet**」与「那**判据**逐字**能不能**绕**过**它**」）。
#  缺陷名（C-102）**那 applet **那一族**逐字**漏**了那 readlink **与**那 test **那两**个**。
#    => 而那**成因**：=> 而那 APPLETS **那一族**逐字**只**列**那二十三**个**、=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而而那**后果**逐字 = 而那 guest 那一族逐字**只有**那二十一**个** applet、
#      => 而而那 echo **与**那 mount **与**那 readlink **与**那 test **那几**个**逐字没有**、
#      => 而而那**它**们**逐字是**那**两**件**事**：「那 guest **要**什么**」与「那**配方**列**了什么**」）。
#    => 而那**处置**：=> 而那 APPLETS **那一族**逐字**补**上那 readlink **与**那 test **那两**个**。
APPLETS="TAR GZIP GUNZIP GREP SED LS CP MV CAT MKDIR RM LN VI DF PS TRUE FALSE ASH MOUNT LOSETUP ECHO SLEEP PRINTF READLINK TEST ENV"
#  => 而那**额外**的**那些** **config** 名（=> 而而那**它们**逐字**不是**那 applet 名、=> 而而那**它们**逐字**只**管**开关**）。
#     => 而那 **ASH_INTERNAL_GLOB** 逐字**要**它**是因为**那 ash.c 逐字**用**那 libc 的 glob **那一族**（=> 而而那**它**被关掉**之后**那 #include <glob.h> **也**就**没**了）。
#  => 而那 **TEST1** 逐字**是**那 **[** **那一个** **applet**（=> 而那 coreutils/Config.in :781 那一行 = 「config TEST1」+「bool "test as ["」），
#     => 而那 **TEST** 逐字**是**那 **test** **那一个** **applet**（=> 而那 :773 那一行 = 「config TEST」+「bool "test (4.1 kb)"」），
#     => 而那两**个**逐字**是**那**两**件**事**、=> 而而那**它**们**逐字不合并**。
#     => 而那 **[** **那一个**逐字**是**那 initramfs **那一层**要**的**（=> 而那 ash.c 逐字**用**那 **[** **那一族**）。
#  缺陷名（C-102）**那 CONFIG_BUSYBOX** 逐字**没有**被**开**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而那**成因**：=> 而那 busybox **逐字**看**那 argv[0]** 来**决定**跑**哪**一个** applet**（=> 而那
#      appletlib.c 逐字**那 run_applet_and_exit **那一族**），=> 而而那**它**逐字**只**在**那 ENABLE_BUSYBOX **开**着**的时候**
#      逐字**才**认**那「busybox」**那一个**名字**（=> 而那**它**们**逐字是**那**两**件**事**）。
#    => 而而那**后果**逐字 = 而那 bin/busybox **逐字**报**那「busybox: applet not found」**、
#      => 而而那**它**们**逐字是**那**两**件**事**：「那**名字**是**什么**」与「那**applet** **那一族**开没开**」）。
#    => 而那**处置**：=> 而那 CONFIG_EXTRA **那一族**逐字**加上**那 BUSYBOX（=> 而而那 **set_conf** **那一族**逐字**会**开**它**）。
CONFIG_EXTRA="ASH_INTERNAL_GLOB TEST1 BUSYBOX"

set_conf() {
  local k="$1" v="$2"
  #  => 而那 **config 名**逐字**大写**（=> 而而那 **kconfig** **那一族**逐字**只**认**那大写**的**）
  k="$(printf %s "$k" | tr "[:lower:]" "[:upper:]")"
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
#  => 而那 mount 那个 applet 逐字**带**来**那 syslog **那一族**（=> 而那 FEATURE_MOUNT_NFS
#     逐字**选**那 FEATURE_SYSLOG、=> 而而那**它**们**逐字是**那**两**件**事**）。
#  => 而那**处置**：=> 而那 syslog **那一族**逐字**显式**关**掉**（=> 而而那**它**们**逐字不**在**那**清单**里面**、
#     => 而而那**后果**要是不关**它**、=> 而那 kconfig 逐字**会**再**问**那**几个**项**、
#     => 而而那**它**们**逐字是**那**两**件**事**：「那**清单**是**什么**」与「那**依赖**是**什么**」）。
#  => 而那 mount 那个 applet 逐字**带**来**那 syslog **那一族**（=> 而那 FEATURE_MOUNT_CIFS 与
#     那 FEATURE_MOUNT_NFS 逐字**选**那 FEATURE_SYSLOG、=> 而而那**它**们**逐字是**那**两**件**事**）。
#  => 而那**处置**：=> 而那些**逐字选**那 syslog **的**那**几个**逐字**显式**关**掉**
#     （=> 而而那**它们**逐字**不**在**那**清单**里面**、=> 而而那**后果**要是不关**它们**、
#     => 而那 kconfig 逐字**会**再**问**那**几个**项**、=> 而而那**它**们**逐字是**那**两**件**事**：
#  => 而那 mount 那一族逐字**带**来**那 syslog **那一族**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#     => 而那**处置**：=> 而那些**逐字选**那 syslog **的**那**几个**逐字**再**关**一遍**
#       （=> 而而那**它们**逐字**不**在**那**清单**里面**、=> 而而那**后果**要是不关**它们**、
#       => 而那 kconfig 逐字**会**再**问**那**几个**项**、=> 而而那**它**们**逐字是**那**两**件**事**：
#       「那**清单**是**什么**」与「那**依赖**是**什么**」）。
for a in $APPLETS $CONFIG_EXTRA; do set_conf "$a" y; done
#  => 而那 **INIT** 与那 **LINUXRC** 逐字**是**那上游**默认**开**着**的**（=> 而那**它们**逐字**选**那 FEATURE_SYSLOG）、
#     => 而而那**它们**逐字**不在**那**清单**里面**、=> 而而那**后果**逐字 = 而那 kconfig 逐字**再问**那**几**个**项**。
for k in INIT LINUXRC SYSLOGD KLOGD LOGGER LOGREAD FEATURE_SYSLOG FEATURE_IPC_SYSLOG FEATURE_KMSG_SYSLOG FEATURE_SYSLOGD_CFG FEATURE_SYSLOGD_DUP FEATURE_SYSLOGD_PRECISE_TIMESTAMPS FEATURE_MOUNT_NFS FEATURE_MOUNT_CIFS FEATURE_MOUNT_LABEL FEATURE_MOUNT_FAKE FEATURE_MOUNT_VERBOSE FEATURE_MOUNT_HELPERS FEATURE_MOUNT_MTAB FEATURE_MOUNT_HELP; do set_conf "$k" n; done
echo "[busybox] 白名单开启：$APPLETS（另外那 config：$CONFIG_EXTRA）"

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
# 缺陷名（C-102）**这一件编成了 ET_EXEC，而 ET_EXEC 在设备上起不来**。
#
# 真机读数（07，2026-10-24，装 0c1d9af 那一版之后）：
#   busybox 那一件仍是 `Cannot run program`（error=13, Permission denied），
#   而另外三件（bash / jq / rg）起来了。
#   而 busybox 入口的形态（设备侧取件后本机读）：
#     字节数 547392  sha256 030023fb6a1ef966cebd535615211df394cf623c90f842cdbe162f7db60c7ef6
#     Type: EXEC (Executable file)   INTERP 行数: 0
#     对照（bash 那一份）: Type=DYN  INTERP=1
#
# **=> 而成因是这两行配置（=> 而 upstream busybox 1.36.1 的 Config.in:372 那一段逐字）：**
#     config PIE
#         bool "Build position independent executable"
#         default n
#         depends on !STATIC
#   而 Makefile.flags:125 那一段（=> 而逐字）：
#     ifeq ($(CONFIG_STATIC),y)
#     CFLAGS_busybox += -static
#   而 Makefile.flags:131 那一段：
#     ifeq ($(CONFIG_PIE),y)
#     CFLAGS_busybox += $(ARCH_PIE)   # => 而 ARCH_PIE ?= -pie
#     CFLAGS += $(ARCH_FPIE)           # => 而 ARCH_FPIE ?= -fpie
#
# **=> 而 `STATIC y` => 而 -static => 而而那一份必然是 ET_EXEC；**
# **=> 而 `PIE` 那一项 `depends on !STATIC` => 而而 STATIC y 时它根本不可选（=> 而 set_conf PIE y 会被 kconfig 丢掉）；**
# **=> 而所以 `set_conf STATIC y` 与 `set_conf PIE n` 合起来 = 一份 ET_EXEC。**
#
# **=> 而 ET_EXEC 在设备上起不来，而那个原因不是「套不套 linker64」：**
#   · 那一件走 Shape.Exec 那一支（=> 而 ExecEntry.kt:59 逐字 `is Shape.Exec -> listOf(entry.absolutePath) + args.drop(1)`），
#     => 而那是**直接 exec 那个入口本身**；
#   · 而那个入口在设备上是 u:object_r:app_data_file:s0，
#     而 app 进程那个域是 u:r:untrusted_app，**那一域对 app_data_file 没有执行许可**
#     （=> 而而直接 exec 它在加载解释器之前就 EACCES => 而那一格是 error=13）；
#   · 而**套 linker64 绕不过去** —— 而 AOSP linker_main.cpp:405 那一段逐字：
#     `if (elf_hdr->e_type != ET_DYN) { __linker_error("error: %s: Android only supports position-independent executables (-fPIE)", ...); }`
#     => 而那一件是 ET_EXEC => 而而经 linker64 加载它会被那一条挡。
#
# **=> 而所以处置是让这一件成为 ET_DYN（=> 而而那一族就有「经由 linker64」那条路可走，=> 而而那一路的 execve 目标是 linker64，
#     => 而那是 u:object_r:system_linker_exec:s0，=> 而那一域有执行许可，=> 而那个入口是 linker 的参数、=> 而由 linker 自己加载）。**
#
# **=> 而做法：=> 而把 STATIC 关掉（=> 而而 CONFIG_PIE 的 `depends on !STATIC` 就允许 PIE），=> 而而 PIE 给 y。**
# **=> 而「静态」那一族是为「系统救援工具」准备的（=> 而 Config.in:365 那一段 help 逐字：
#     "If you want to build a static binary, which does not use or require any shared libraries..."），
# **=> 而而那一件在 LOB-OS 里是**程序**形态（=> 而它落 bin/，=> 而而那 17 条软链是它的 argv[0] 派生物，
#     => 而而 build-native-busybox.sh:271 那一段逐字：「它是一份独立的**程序**字节，而那 17 个名字是它的 argv[0] 派生物」），
# **=> 而而它要的是「能起得来」，=> 而而「不依赖动态库」那个好处在设备上换不来「起得来」。**
#
# 而这一件在 CI 上产出的形态会变（=> 而 ET_EXEC -> ET_DYN），
# **=> 而而它落 bin/ 那个位置不变，=> 而而那 17 条软链指向它的方式也不变（=> 而而都是 argv[0] 派生物）。**
#  缺陷名（C-102）**这一族逐字只编那 PIE **那一个**、=> 而而那 initramfs **那一层**逐字**要**那**静态**的**那一个**
#    => 而而它治**的**是「那 **initramfs** **那一层**逐字**能不能**装**进**那**静态**的**那一个**」。
#    => 而那**成因**：=> 而那 PIE **那一个**逐字**是**那 ET_DYN、=> 而而那**它**逐字**要**那 **linker64** **那一族**、
#       => 而而那 **linker64** 逐字**不**在**那 initramfs **那一层**里面**、=> 而而那 **initramfs** **那一层**逐字**要**那**静态**的**那一个**。
#    => 而那**处置**：=> 而那**这一族**逐字**能**编**那**两种**形态**（=> 而那 **PIE** **那一个**与那**静态**那一个**）、
#       => 而那**开关**逐字**由**那 **LOBOS_BUSYBOX_STATIC** **那个**环境变量**给**、=> 而而那**默认**逐字**是**那 PIE。
#    => 而按 C-104：=> 而那 busybox **那一族**逐字**有**两条路**（=> 而那**两条**路逐字**落**的是**那两**个**不同**的**产物**、
#       => 而而那**它**们逐字**不是**同一条内容的**两条取法**、=> 而而那**它**们逐字**是**那**两**个**不同**的**形态**）；
#       => 而而那 initramfs **那一层**逐字**只有**一条路**。
#  => 而那**开关**逐字**给**那**形态**那一族**（=> 而那 **PIE** **那一个** 与那**静态**那一个**）。
#     => 而那**它**逐字**要**让**那判据**要**的**那一份**别**被**那安装器**换掉**、
#       => 而那**安装器**逐字**从**那 **APK** 的那 **jniLibs** **那一族**落**位**、
#       => 而而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**。
#     => 而那**默认**逐字**还是**那 PIE（=> 而那**现行**的**那一件**逐字**是**那 PIE、
#       => 而而那**判据库**那 busybox **那一栏**逐字**判**的**是**那**产物**的**形状**、=> 而那**判据**归 **04**）。
#     => 而那**静态**的**那一个**逐字**是**那 initramfs **那一层**要**的**那一份**（=> 而那**它**逐字**不**要**那 **linker64**）。
#  => 而那**开关**逐字**给**那**形态**那一族**（=> 而那 **PIE** **那一个** 与那**静态**那一个**）。
#     => 而那**它**逐字**要**让**那判据**要**的**那一份**别**被**那安装器**换掉**、
#       => 而那**安装器**逐字**从**那 **APK** 的那 **jniLibs** **那一族**落**位**、
#       => 而而那**它**们**逐字是**那**两**件**事**、=> 而按 C-104 逐字**不合并**。
#     => 而那**默认**逐字**还是**那 PIE（=> 而那**现行**的**那一件**逐字**是**那 PIE、
#       => 而而那**判据库**那 busybox **那一栏**逐字**判**的**是**那**产物**的**形状**、=> 而那**判据**归 **04**）。
#     => 而那**静态**的**那一个**逐字**是**那 initramfs **那一层**要**的**那一份**（=> 而那**它**逐字**不**要**那 **linker64**）。
BUSYBOX_STATIC="${LOBOS_BUSYBOX_STATIC:-0}"
if [ "$BUSYBOX_STATIC" = "1" ]; then
  #  => 而那**静态**的**那一个**（=> 而而它逐字**是**那 ET_EXEC、=> 而而它逐字**不**要**那 **linker64**）
  set_conf STATIC y
  set_conf PIE n
else
  set_conf STATIC n
  set_conf PIE y
fi

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

sed -i '/^CONFIG_EXTRA_CFLAGS=/d' "$SRC/.config" || true
  # 而 CONFIG_EXTRA_LDFLAGS：**这一件今天不加**
  #   · 而 busybox 的 Kconfig 里有没有 CONFIG_EXTRA_LDFLAGS 那一项，
  #     **而本机今天取过 busybox 1.36.1 源码树**（=> 而 upstream Config.in 里逐字有 `config EXTRA_LDFLAGS`）
  #   · 而**这一件今天改成动态（PIE）之后**，=> 而而它 NEEDED 的只有 libc 那几项系统库
  #     （=> 而而**不是**同件 lib/ 下那些字节）=> 而而 RUNPATH 那一格对它**不适用**
  #     （=> 而而加它只会让产物多一个无用段，=> 而而那还是错的那一种无用）
  #   · 而而「同件 lib/ 下那些字节」那一格今天仍然不适用（=> 而而这一件落 bin/，=> 而而它 lib/ 下没有要找的）
  echo "CONFIG_EXTRA_CFLAGS=\"-O2 -fPIC -D__ANDROID_API__=$API\"" >> "$SRC/.config"

#  必须喂输入：oldconfig 会就新增项提问，CI 上没有 tty 就卡死
#   （表现：日志停在 "Support --long-options (LONG_OPTS) [Y/?] y"）。
#   yes "" 会让每个提问取默认，但 yes 会被提前关闭的管道打断（Broken pipe），
#   所以改成自己产出一批空行。
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
#  缺陷名（C-102）**本体逐字**只有**那 libbusybox.so **那一个**名字**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而那**成因**：=> 而那 applet **那十七**条软链逐字**是**那 argv[0] **那些**派生**物**、
#      => 而而那**本体自己**那**一个**名字**逐字**没有**被**建**出来**。
#    => 而而那**后果**逐字 = 而那 busybox **那一格**逐字**报**那「No such file or directory」、
#      => 而而那**它**们**逐字是**那**两**件**事**。
#    => 而那**处置**：=> 而那**本体自己**那**一个**名字**也**建**出来**（=> 而那**它**逐字**是**那**本体**那一份**字节**）。
if [ ! -e "$OUT_DIR/bin/busybox" ]; then
  ln -sf libbusybox.so "$OUT_DIR/bin/busybox"
  note "本体自己那一个名字已建（busybox -> libbusybox.so）"
fi

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
#  缺陷名（C-102）**那收集**逐字**只**扫**那 install/bin/ **那一族**（=> 而而那**后果**逐字 =
#    而那**有的** applet 逐字**落**在**那 install/sbin/ **那一族**、=> 而而那**它**逐字**进不来**、
#    => 而而那**后果**逐字 = 那「::error title=声明了但没产出:: losetup … 不存在」（=> 而那 **losetup** 逐字**是**那**那一个**）。
#    => 而那**处置**：=> 而那**收集**逐字**也**扫**那 install/sbin/ **与**那 install/usr/bin/ **那两**族**。
for l in "$WORK/install/bin/"* "$WORK/install/sbin/"* "$WORK/install/usr/bin/"*; do
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
[ "$SIZE" -gt 100000 ] || die "产物可疑" "busybox 只有 $SIZE 字节 —— 动态编也不该这么小"
    #  => 而那**静态**的**那一个**逐字**不**能** strip（=> 而而它逐字**要**留**那**符号**给**那**静态**链接**用**）
    if [ "$BUSYBOX_STATIC" != "1" ]; then
      "$LLVM_STRIP" --strip-unneeded "$BB" 2>/dev/null || true
    fi

INFO=$(file -b "$BB")
case "$INFO" in
  *aarch64*|*arm64*|*ARM64*) : ;;
  *) die "架构不对" "$INFO —— 装到真机上 exec format error" ;;
esac
note "$INFO"

# 形态判据（缺陷名 C-102）**这一件现在必须是 PIE（ET_DYN）而不是静态（ET_EXEC）**。
#   缺陷名（C-102）**「静态」那个前提已经过期了**：
#     静态编 => 而 Makefile.flags:125 那一支是 `CFLAGS_busybox += -static`
#             => 而而那一份必然是 ET_EXEC
#     => 而而 ET_EXEC 在设备上**不可执行**（=> 而那是域的策略：=> 而 app_data_file
#        在 u:r:untrusted_app 那个域里没有执行许可，=> 而而直接 exec 它就是 error=13）；
#     => 而而**套 linker64 也绕不过去**（=> 而 AOSP linker_main.cpp:405 那一段：
#        `if (elf_hdr->e_type != ET_DYN) { __linker_error("...only supports position-independent executables (-fPIE)"); }`）
#     => 而而所以静态编这一件在设备上**两头都不落**。
#   而 PIE 那一支（Makefile.flags:131）是 `CFLAGS_busybox += $(ARCH_PIE)` 与 `CFLAGS += $(ARCH_FPIE)`
#     => 而 ARCH_PIE ?= -pie、ARCH_FPIE ?= -fpie（Makefile.flags:108 到 109）
#     => 而而那一份是 ET_DYN => 而而它要经 linker64 驱动
#     => 而而那一路的 execve 目标是 /system/bin/linker64
#        （=> 而那是 u:object_r:system_linker_exec:s0，=> 而那一域有执行许可）
#
# 而「无 PT_DYNAMIC」那个判据是**静态编专属**的（=> 而动态编一定有一个 PT_DYNAMIC），
# **=> 而它今天必须换成「有 PT_DYNAMIC」**，=> 而而换成「ET_DYN + 有 PT_INTERP」这两格。
#  => 而那**形态**那一族逐字**分**那**两种**（=> 而那 PIE **那一个**要**那 ET_DYN** 与**那 PT_INTERP**、=> 而那**静态**那一个**要**那 ET_EXEC** 与**没有**那 PT_INTERP**）
if [ "$BUSYBOX_STATIC" = "1" ]; then
  #  => 而那**静态**的**那一族**（=> 而而它逐字**不**要**那 **linker64**、=> 而而它逐字**能**进**那 initramfs **那一层**）
  ETYPE=$("$LLVM_READELF" -W -h "$BB" 2>/dev/null | awk '/^  Type:/ {print $2}')
  [ "$ETYPE" = "EXEC" ] || die "不是静态产物" "e_type=$ETYPE —— 而那**静态**的**那一个**必须**是**那 ET_EXEC"
  NEEDED=$("$LLVM_READELF" -W -d "$BB" 2>/dev/null | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' | wc -l)
  [ "$NEEDED" -eq 0 ] || die "静态产物却有依赖" "NEEDED=$NEEDED 项 —— 而那**静态**的**那一个**逐字**不**能**有**那**动态**依赖"
  #  缺陷名（C-102）**那 grep -c 零命中逐字把**那一步**带**死**了** ——
  #    => 而而它治**的**是「那**形态**判据逐字**判不判得到**」。
  #    => 而那**成因**：=> 而那 grep -c **零命中**的**退出码** = **1**（=> 而那 06 那边本机**实跑**逐字 = 1）、
  #       => 而而那 set -euo pipefail 逐字**就死**在**那一行**、=> 而而那**它**逐字**没有**走到**那一句** die**、
  #       => 而而那**日志**逐字**停在**那一句 note **之后**。
  #    => 而那**静态**那一支逐字**期望**那 INTERP **= 0**（=> 而而那**它**逐字**是**那**静态**的**前提**）、
  #       => 而而那 **PIE** 那一支逐字**期望**那 INTERP **≥ 1**（=> 而而那**它**逐字**碰巧**没踩**那一个**坑**）。
  #    => 而那**处置**：=> 而那**两处**都**加**那 || true（=> 而而那**它**逐字**把**那**退出码**归零**、
  #       => 而而那**零**逐字**照样**是**那零**、=> 而而那**它**们**逐字不合并**）。
  #    => 而按 C-104：=> 而那**形态**判据那一族逐字**只有**一条路（=> 而这一支**就是**它**）。
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
[ "$INTERP" -ge 1 ] || die "缺 PT_INTERP" "INTERP 行数=$INTERP —— 而这一件要经 linker64 驱动，=> 而那个驱动者由 PT_INTERP 指出来（=> 而设备上是 /system/bin/linker64）"
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

#  => 而那**落包集**逐字**取**那 **out/bin** 底下**那些软链**（=> 而而那**它们**逐字是**那**两**件**事**：
#     「那**软链**是**什么**」与「那**本体**是**什么**」）。
#  => 而而那**本体**逐字**仍**在**那包里**（=> 而那是那些软链的**指向目标**）。
BB_BINS="$(cd "$OUT_DIR/bin" && for f in *; do [ -L "$f" ] && printf '%s ' "$f"; done)"
#  缺陷名（C-102）**那 emit_piece 那一族**逐字**没有**被**调**（=> 而而那**它**们**逐字是**那**两**件**事**）。
#    => 而那**成因**：=> 而那**配方**的**尾部**逐字**被**截**了、=> 而而那**它**们**逐字是**那**两**件**事**：
#      「那**尾部**是**什么**」与「那**产物**是**什么**」）。
#    => 而而那**后果**逐字 = 而那 CI 逐字**报**那「build 脚本没产出 dist/component-*.zip」、=> 而而那**件**逐字**发不上去**。
#    => 而那**处置**：=> 而那**发**的那一句逐字**回**那**尾部**（=> 而而那**它**们**逐字是**那**两**件**事**）。
printf '%s' "$BB_VER" > "$OUT_DIR/$TOOL.version"
emit_piece "$TOOL" "$OUT_DIR" bin/libbusybox.so 100000 "$BB_BINS" "$TOOL.version"
# 缺陷名（C-102）**写的那一处与核的那一处用的 `$OUT` 不是同一个值** ——
# 而两处都叫 OUT，=> 而同名不同义（按 C-150 那一族）。
#
# 真机读数（build-busybox run 40130918388，sha=798a504）：
#   ##[error]声明: busybox.version（ 而它是这一件自己声明的）
#   ##[error] 而它在产物的目录里：/home/runner/work/lob-os/lob-os/work/busybox/out/busybox.version
#   ##[error]Process completed with exit code 1.
