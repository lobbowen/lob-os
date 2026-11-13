#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/../.."
ROOT_DIR="$(pwd)"

API="${ANDROID_API:-35}"
TOOL="busybox"

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

[ -f "$SRC/util-linux/mount.c" ] && python3 "$ROOT_DIR/scripts/recipes/busybox-mntent-bionic.py" "$SRC/util-linux/mount.c" || true

cd "$SRC"

answers "$WORK/defconfig-answers.txt" > /dev/null
make defconfig < "$WORK/defconfig-answers.txt" > "$WORK/defconfig.log" 2>&1 \
  || { echo "=== make defconfig 失败取证（末 30 行）==="; tail -30 "$WORK/defconfig.log"; exit 1; }
[ -f "$SRC/.config" ] || die "defconfig 没产出 .config" "看 $WORK/defconfig.log"

APPLETS="TAR GZIP GUNZIP GREP SED LS CP MV CAT MKDIR RM LN VI DF PS TRUE FALSE ASH MOUNT LOSETUP ECHO SLEEP PRINTF READLINK TEST ENV"

CONFIG_EXTRA="ASH_INTERNAL_GLOB TEST1 BUSYBOX"

set_conf() {
  local k="$1" v="$2"

  k="$(printf %s "$k" | tr "[:lower:]" "[:upper:]")"
  if [ "$v" = y ]; then
    grep -q "^CONFIG_${k}=y" "$SRC/.config" && return 0
    sed -i "s/^# CONFIG_${k} is not set/CONFIG_${k}=y/" "$SRC/.config"
    grep -q "^CONFIG_${k}=y" "$SRC/.config" || echo "CONFIG_${k}=y" >> "$SRC/.config"
  else
    sed -i "s/^CONFIG_${k}=y/# CONFIG_${k} is not set/" "$SRC/.config"
  fi
}

grep -o '^CONFIG_[A-Z0-9_]*=y' "$SRC/.config" | sed 's/^CONFIG_//; s/=y$//' > "$WORK/all-on.txt"
N_ON=0
while read -r k; do
  [ -n "$k" ] || continue
  set_conf "$k" n
  N_ON=$((N_ON + 1))
done < "$WORK/all-on.txt"
echo "[busybox] defconfig 开了 $N_ON 项，已全部关掉（白名单式）"

for a in $APPLETS $CONFIG_EXTRA; do set_conf "$a" y; done

for k in INIT LINUXRC SYSLOGD KLOGD LOGGER LOGREAD FEATURE_SYSLOG FEATURE_IPC_SYSLOG FEATURE_KMSG_SYSLOG FEATURE_SYSLOGD_CFG FEATURE_SYSLOGD_DUP FEATURE_SYSLOGD_PRECISE_TIMESTAMPS FEATURE_MOUNT_NFS FEATURE_MOUNT_CIFS FEATURE_MOUNT_LABEL FEATURE_MOUNT_FAKE FEATURE_MOUNT_VERBOSE FEATURE_MOUNT_HELPERS FEATURE_MOUNT_MTAB FEATURE_MOUNT_HELP; do set_conf "$k" n; done
echo "[busybox] 白名单开启：$APPLETS（另外那 config：$CONFIG_EXTRA）"

PLATFORM_H="$SRC/include/platform.h"
[ -f "$PLATFORM_H" ] || die "找不到 platform.h" "$PLATFORM_H 不存在 —— 上游路径变了，按实际改这里"

node "$ROOT_DIR/scripts/verify/find-platform-h-android-branch.js" "$PLATFORM_H" --patch

BUSYBOX_STATIC="${LOBOS_BUSYBOX_STATIC:-0}"
if [ "$BUSYBOX_STATIC" = "1" ]; then

  set_conf STATIC y
  set_conf PIE n
else
  set_conf STATIC n
  set_conf PIE y
fi

echo "  [busybox] CC = $CC（make 命令行给，不经 .config）"
echo "  [busybox] 不设 CROSS_COMPILE：它拼出来的 gcc 在 NDK 里不存在"

sed -i '/^CONFIG_EXTRA_CFLAGS=/d' "$SRC/.config" || true

  echo "CONFIG_EXTRA_CFLAGS=\"-O2 -fPIC -D__ANDROID_API__=$API\"" >> "$SRC/.config"

answers "$WORK/oldconfig-answers.txt" > /dev/null
make oldconfig < "$WORK/oldconfig-answers.txt" > "$WORK/oldconfig.log" 2>&1 \
  || { echo "=== make oldconfig 失败取证（末 30 行）==="; tail -30 "$WORK/oldconfig.log"; exit 1; }

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

  rm -rf "$WORK/install"
  make "${MAKE_ARGS[@]}" CONFIG_PREFIX="$WORK/install" install > "$WORK/install.log" 2>&1 \
    || { echo "=== make install 失败取证 ==="; tail -30 "$WORK/install.log"; exit 1; }
  [ -x "$WORK/install/bin/busybox" ] || die "make install 没产出 busybox" "$WORK/install/bin/busybox 不存在"

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR/lib" "$OUT_DIR/bin"

cp -f "$SRC/busybox" "$OUT_DIR/bin/libbusybox.so"
chmod 0755 "$OUT_DIR/bin/libbusybox.so"

if [ ! -e "$OUT_DIR/bin/busybox" ]; then
  ln -sf libbusybox.so "$OUT_DIR/bin/busybox"
  note "本体自己那一个名字已建（busybox -> libbusybox.so）"
fi

NAPP=0

for l in "$WORK/install/bin/"* "$WORK/install/sbin/"* "$WORK/install/usr/bin/"*; do
  [ -L "$l" ] || continue
  b="$(basename "$l")"
  [ "$b" = busybox ] && continue
  ln -sf ./libbusybox.so "$OUT_DIR/bin/$b"
  NAPP=$((NAPP+1))
done
echo "[$TOOL] 本体(bin/，按形态是程序形态) + $NAPP 个 applet 软链(bin/，指向它) 进包"

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

    if [ "$BUSYBOX_STATIC" != "1" ]; then
      "$LLVM_STRIP" --strip-unneeded "$BB" 2>/dev/null || true
    fi

INFO=$(file -b "$BB")
case "$INFO" in
  *aarch64*|*arm64*|*ARM64*) : ;;
  *) die "架构不对" "$INFO —— 装到真机上 exec format error" ;;
esac
note "$INFO"

if [ "$BUSYBOX_STATIC" = "1" ]; then

  ETYPE=$("$LLVM_READELF" -W -h "$BB" 2>/dev/null | awk '/^  Type:/ {print $2}')
  [ "$ETYPE" = "EXEC" ] || die "不是静态产物" "e_type=$ETYPE —— 而那**静态**的**那一个**必须**是**那 ET_EXEC"
  NEEDED=$("$LLVM_READELF" -W -d "$BB" 2>/dev/null | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' | wc -l)
  [ "$NEEDED" -eq 0 ] || die "静态产物却有依赖" "NEEDED=$NEEDED 项 —— 而那**静态**的**那一个**逐字**不**能**有**那**动态**依赖"

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

BB_BINS="$(cd "$OUT_DIR/bin" && for f in *; do [ -L "$f" ] && printf '%s ' "$f"; done)"

printf '%s' "$BB_VER" > "$OUT_DIR/$TOOL.version"
emit_piece "$TOOL" "$OUT_DIR" bin/libbusybox.so 100000 "$BB_BINS" "$TOOL.version"
