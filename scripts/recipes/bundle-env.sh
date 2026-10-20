#!/usr/bin/env bash
#
# 为什么说明要打进 APK：
#   照抄 deb-control(5)——「Each Debian binary package contains a control file in its
#   control member」。每个包自带说明，安装器与内核只读落位，不预置任何一件的清单。
#
# 用法：source scripts/recipes/bundle-env.sh <件名>，然后调用 emit_piece
set -uo pipefail

PIECE_NAME="${1:?用法: source bundle-env.sh <件名>}"

# 仓根（本文件在 scripts/recipes/）
PIECE_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PIECE_HERE/../.." && pwd)"
cd "$ROOT_DIR"

# 形态判据（ET_EXEC / ET_DYN+PT_INTERP / ET_DYN 无 PT_INTERP）——
# 照 container/app/src/main/java/lobos/kernel/elf/ExecBits.kt:26-30 的形状在 shell 里实现一份。
# 那一层在构建期算，写进产物；运行期只读产物，不判断、不探测。
source "$ROOT_DIR/scripts/toolchain/elf-form.sh"

ABI="${ABI:-arm64-v8a}"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac
# 件说明随 assets 走 —— **这是运行时唯一读得到的那一份**。
# 只有 excludes/pickFirsts/keepDebugSymbols，没有「非 .so 也打进去」的开关），
WORK="$ROOT_DIR/work/$PIECE_NAME"
OUT_DIR="$WORK/out"
mkdir -p "$OUT/bin" "$WORK"

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

# 生成这一件的说明 —— 数据全来自构建期表（component-sources.json），
# 这里不硬编码任何一件的信息；数据不齐就报错，不产出半截的说明。
# 件的版本 —— 取自钉值表（构建期唯一真相源）
version_of() {
  node -e '
    const s = require(process.argv[1]).sources || {};
    const v = (s[process.argv[2]] || {}).version;
    if (v) process.stdout.write(String(v));
  ' "$ROOT_DIR/scripts/component-sources.json" "$1"
}

# 打标准件包 —— 校验产物 + 产出装配器只读的那个 tar.gz。
#
# assets/supply/meta —— 那是预装件的私有通道，装配器读不了，也与 OTA 装件
#（走 ProgramInstallPipeline，读包里的 component-meta.json）不同构。
# 现在统一产出标准包，两条路同形。
#
# 用法：emit_piece <件名> <OUT_DIR> <入口相对路径> [最小字节] [命令名 空格分隔]
#
# 第 5 个参数是「这个件要在 usr/bin 出现的命令名」清单 —— 空格分隔。
# 它**必须由调用方从自己既有的变量派生**（如 build-native-busybox.sh 从 $APPLETS 转小写），
# **不要在这里或那里再写一份字面量** —— 两份名单必然漂移，而漂移的后果是
# 「声明了但没带上」（2026-10-16 实测：busybox 的 18 个 applet 一个都没进包）。
#
# 为什么需要它：entry 只能指一个文件，而一个件可能有多个命令入口
#（busybox 有 18 个 applet）。package-bundle-zip.sh 只搬 entry 与其同名版本链，
# 所以 bin/ 下其余的可执行件必须由这个清单显式带进去。
emit_piece() {
  local id="$1" out="$2" entry="$3" min="${4:-1000}" bins="${5:-}"
  # ★ **不再与「声明」比** —— 声明那一份已删（component-sources.json 的 entry 键 15 处）。
  #   缺陷名（C-102）entry 散在几处、而那几处是副本：
  #     原来这一格是「declared（表里那份）!= $entry（recipe 传的那份）⇒ 判红」，
  #     而今天 entry **按形态算**（下面那段 pick），recipe 传的那份只是声明 ⇒ 两者本就不同。
  #   ⇒ 现在：entry 只有**真源**一处（按形态算，写进产物那份 meta 与 piece-shape.json）。
  #     而「那一份该长什么样」由 verify-piece-shape.sh 那两路对账核（产物内部自洽）。
  local so base dir f b ver
  ver="$(version_of "$id")"
  [ -n "$ver" ] || die "${id} 没有版本" "scripts/component-sources.json 里查不到"
  # ★ **entry 按形态挑**（与 package-bundle-zip.sh 同一把尺）：
  #   这一件在磁盘上有没有程序那一侧（bin 下有程序形态字节）？
  #     有 ⇒ 入口是**那一侧**（按形态挑，不按名字顺序）；
  #     没有 ⇒ 入口是 lib/ 下那一份**库**（纯库件，entry 那一格在清单里留空）。
  #   缺陷名（C-102）调用方声明的 entry 被当真相：原来这一行是 so="$out/$entry" ——
  #   而 openssl 那一件声明的是 lib/libssl.so（库形态）⇒ 按形态决定执行位与链的那一段
  #   作用在**库**上 ⇒ 程序那一份（bin/openssl）没被判型、没带执行位。
  local pick=""
  if [ -d "$out/bin" ]; then
    for f in $(ls -A "$out/bin" 2>/dev/null | LC_ALL=C sort); do
      p="$out/bin/$f"
      [ -f "$p" ] || continue
      ff="$(elf_form "$p" 2>/dev/null)"
      if elf_form_is_program "$ff"; then pick="bin/$f"; break; fi
    done
  fi
  if [ -z "$pick" ]; then
    for f in $(ls -A "$out/lib" 2>/dev/null | LC_ALL=C sort); do
      case "$f" in *.so) pick="lib/$f"; break ;; esac
    done
  fi
  [ -n "$pick" ] || die "${id} 没产出" "bin/ 下无程序形态字节、lib/ 下无主名 .so —— 这一件没有可判形态的字节"
  if [ -n "$entry" ] && [ "$entry" != "$pick" ]; then
    echo "  （这一件按形态算出的入口是 $pick，而调用方声明的是 $entry —— 以形态为准）"
  fi
  entry="$pick"
  so="$out/$entry"
  [ -f "$so" ] || die "${id} 没产出" "$so 不存在"
  base="$(basename "$so")"
  dir="$(dirname "$so")"
  check_so "$so" "$min" || die "${id} 产物不可用" "$so 不是 aarch64 ELF 或小于 $min 字节"
  # ── 形态：构建期算一次 ─────────────────────────────────────
  #
  # 缺陷名（C-102）落位只搬字节，不定形态：
  #   原来「这份字节是库还是程序」这件事在构建期从来没有人算过，
  #   于是它只能由运行期去猜（看目录、看文件名）或去探测（读 ELF 头）。
  #   而规则是「构建时就知道」「运行期只读，不判断、不探测」。
  #   所以形态在**这一环**算：判型、按型定权限、按型补链。
  #
  # 判型用的是 ELF 头（readelf -h 的 Type 与 readelf -lW 的 PT_INTERP），
  # 判据的形状照 container/app/src/main/java/lobos/kernel/elf/ExecBits.kt:26-30。
  local form role
  form="$(elf_form "$so")"
  role="$(elf_role_of_form "$form")"
  echo "  （形态：$form → $role）"
  case "$role" in
    program)
      # 程序那一件带单入口，且那份字节在构建期就带 owner 执行位。
      chmod 0755 "$so" ;;
    library)
      # 库那一件带三段链（ldconfig(8)）：libfoo.so -> libfoo.so.1 -> libfoo.so.1.12
      chmod 0644 "$so"
      local derived
      derived="$(elf_ensure_chain "$dir" "$ver")"
      [ -n "$derived" ] && echo "  （补齐三段链：$(echo $derived | tr ' ' ' ')）" ;;
    unknown)
      die "${id} 形态判不出来" "$so 不是 ELF —— 形态必须在构建期定下来" ;;
  esac
  # 共享库整条链（libz.so → .so.1 → .so.1.3.2）一起进包 ——
  # 它们是同一份字节的三个名字，linker 按 DT_NEEDED 找的是带版本的那一层
  if ls "$dir/$base".* >/dev/null 2>&1; then
    for f in "$dir/$base" "$dir/$base".*; do
      [ -e "$f" ] || continue
      b="$(basename "$f")"
      case "$b" in *.so|*.so.*) cp -Pf "$f" "$out/" 2>/dev/null || cp -f "$f" "$out/" ;; esac
    done
    echo "  （共享库整条链一起进包）"
  fi
  bash "$ROOT_DIR/scripts/recipes/package-bundle-zip.sh" "$id" "$out" "$ver" "$entry" "$bins"
}