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
  #  用子进程跑，不 source ——
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
#
# ★ 缺陷名（C-102）**这一格按文件长什么样定** —— 而 01 裁的 S117 那一件把形态那一族的取值域
#   按「**能不能被当入口调起**」那一问定，=> 而按文件长什么样不定域。
#   而这一格原来逐字是 `file -bL "$f" | grep -q "ELF 64-bit.*ARM aarch64"`
#   => 而那是**第四把尺**，=> 而它答的是「这个文件的字节长得像不像 aarch64 的 ELF」。
#   => 而 #! 脚本（=> 而 c_rehash / npm-cli.js / pnpm 那几件都是）**不是** ELF，
#   => 而而它们**是**可调起的入口形态（=> 而按那一把尺它们是 shebang + program），
#   => 而所以这一格**判它们红**，=> 而那一趟逐字是
#   「##[error]…/work/openssl/out/bin/c_rehash 不是 aarch64 ELF 或小于 1000 字节」。
#   => 而处置：这一格改用**形态那一把尺**（=> 而 elf_form 那一族），
#     而**字节大小**那一条**不变**（=> 而那一条答的是「编坏了没有」，=> 而那不是形态那一问）。
check_so() {
  local f="$1" min="${2:-1000}"
  [ -f "$f" ] || return 1
  local sz
  sz="$(wc -c < "$f")"
  [ "$sz" -ge "$min" ] || return 1
  # 形态那一把尺（=> 而 elf-form.sh 那一族，=> 而它现在认 shebang 那一档）：
  #   exec / dyn-interp  => 而那是 aarch64 的 ELF 程序
  #   dyn-plain          => 而那是 aarch64 的 ELF 库
  #   shebang            => 而那是 #! 脚本（=> 而它是可调起的入口形态）
  #   unknown            => 而那不是入口形态、=> 而也不是库形态（=> 而它不该进这一件）
  local form
  form="$(elf_form "$f" 2>/dev/null)"
  case "$form" in
    "$ELF_FORM_EXEC"|"$ELF_FORM_DYN_INTERP"|"$ELF_FORM_DYN_PLAIN"|"$ELF_FORM_SHEBANG") return 0 ;;
    *) return 1 ;;
  esac
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

# 件的**作者声明的入口名** —— 取自 component-sources.json 的 sources.<件>.entryName。
#
# ★ 缺陷名（C-102）**「按形态挑」挑的是「第一个程序形态的项」**，=> 而那**不是**「这一件的入口」。
#   而 bin/ 下有多个程序形态的项时（=> 而 openssl 那一件有 bin/c_rehash 与 bin/openssl，
#   => 而 c_rehash 是 #! 脚本、=> 而它是入口形态、=> 而它按 LC_ALL=C 排序**在前**），
#   => 而那一支挑到的是**排序靠前**的那一个，=> 而那**不一定是**作者声明的那一个。
#   => 而而作者声明的那一格**本来就存在**（=> 而 component-sources.json 的 sources.<件>.entryName），
#   => 而而那一格是「**这一件有多个入口时该取哪一个**」那一问的答案（=> 而按 C-135 它与「多调用那一族」是两问）。
#   => 而处置：先取作者声明的那一个；=> 而它**不在** bin/ 下或它不是入口形态时，
#     才退回「按形态挑第一个」那一支（=> 而那**保持**原来那一支的行为）。
author_entry_name_of() {
  node -e '
    const s = require(process.argv[1]).sources || {};
    const v = (s[process.argv[2]] || {}).entryName;
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
  # 而「那一件该有的那个文件」这个声明，从**产物那一份**读（01 今天裁的候选 (a)，
  #   而 03 今天补的那一句把机制说全了：
  #   **声明由产出方给**，而打包那一支**手上没有那份信息**（而 (b) 按形态算已被读数排除）
  # => 而它落在 component-sources.json 的 sources 表里（与 installName 同一族）
  # => **而这里读它，那个声明就由产出方给、而不由调用方传**
  # 而**没声明就不查**（而那不是「缺」，C-05）
  # 缺陷名（C-102）**这一处用的是相对路径，而 cwd 不一定是仓根**。
  #
  # 真机读数（build-busybox run 40106308483，sha=3e89949）：
  #   Error: Cannot find module '/home/runner/work/lob-os/lob-os/work/busybox/busybox-1.36.1/scripts/toolchain/read-marker.js'
  # 而那一趟的前一句逐字是
  #   ##[error]…/work/busybox/out/bin/cat 不是 aarch64 ELF 或小于 500000 字节
  #
  # 而成因：这一份 bundle-env.sh 在 source 的时候（=> 而第 15 行）cd 回了仓根，
  # **=> 而那时的 cwd 是对的**；
  # 而 busybox 那一件在 build-native-busybox.sh:73 那一处 `cd "$SRC"` 进了源码树，
  # **=> 而 emit_piece 是第 462 行才被调的，=> 而那时的 cwd 还在源码树里**
  # => 而那个相对路径就解析到源码树底下去了，=> 而源码树里没有 scripts/toolchain/。
  #
  # 而对照：bash 那个 recipe 从不 cd（=> 而 build-bundle-bash.sh:71 直接调 emit_piece），=> 而所以它没事。
  # 而本文件里其余那几处调仓内脚本的（=> 而 :39、:48、:115、:220）**逐字都是 "$ROOT_DIR/..."**，
  # **=> 而只有这一处是相对路径**。
  #
  # => 而处置：这一处改成绝对路径（=> 而与本文件其余那几处同一口径）。
  local marker="$(node "$ROOT_DIR/scripts/toolchain/read-marker.js" "$id")"
  #  **不再与「声明」比** —— 声明那一份已删（component-sources.json 的 entry 键 15 处）。
  #   缺陷名（C-102）entry 散在几处、而那几处是副本：
  #     原来这一格是「declared（表里那份）!= $entry（recipe 传的那份） 判红」，
  #     而今天 entry **按形态算**（下面那段 pick），recipe 传的那份只是声明  两者本就不同。
  #    现在：entry 只有**真源**一处（按形态算，写进产物那份 meta 与 piece-shape.json）。
  #     而「那一份该长什么样」由 verify-piece-shape.sh 那两路对账核（产物内部自洽）。
  local so base dir f b ver
  ver="$(version_of "$id")"
  [ -n "$ver" ] || die "${id} 没有版本" "scripts/component-sources.json 里查不到"
  #  **entry 按形态挑**（与 package-bundle-zip.sh 同一把尺）：
  #   这一件在磁盘上有没有程序那一侧（bin 下有程序形态字节）？
  #     有  入口是**那一侧**（按形态挑，不按名字顺序）；
  #     没有  入口是 lib/ 下那一份**库**（纯库件，entry 那一格在清单里留空）。
  #   缺陷名（C-102）调用方声明的 entry 被当真相：原来这一行是 so="$out/$entry" ——
  #   而 openssl 那一件声明的是 lib/libssl.so（库形态） 按形态决定执行位与链的那一段
  #   作用在**库**上  程序那一份（bin/openssl）没被判型、没带执行位。
  local pick=""
  #  ★ 缺陷名（C-102）**「按形态挑」挑的是「第一个程序形态的项」** —— 而那不是「这一件的入口」。
  #   而 bin/ 下有**多个**程序形态的项时（=> 而 openssl 那一件有 bin/c_rehash 与 bin/openssl，
  #   => 而 c_rehash 是 #! 脚本、=> 而按 01 裁的 S117 那一件它**是**入口形态、
  #   => 而它按 LC_ALL=C 排序**在前**），=> 而那一支挑到的是**排序靠前**的那一个。
  #   => 而而作者声明的那一格**本来就存在**（=> 而 component-sources.json 的 sources.<件>.entryName），
  #   => 而而那一格是「这一件有多个入口时该取哪一个」那一问的答案（=> 而按 C-135 它与「多调用那一族」是两问）。
  #   => 而处置：**先**取作者声明的那一个；
  #     而它**不在** bin/ 下、或它不是入口形态（=> 而按形态那一把尺）时，
  #     **才**退回「按形态挑第一个」那一支（=> 而那**保持**原来那一支的行为）。
  local want_entry="$(author_entry_name_of "$id")"
  if [ -n "$want_entry" ] && [ -f "$out/bin/$want_entry" ]; then
    local wf="$(elf_form "$out/bin/$want_entry" 2>/dev/null)"
    if elf_form_is_program "$wf"; then pick="bin/$want_entry"; fi
  fi
  if [ -z "$pick" ] && [ -d "$out/bin" ]; then
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
  #  形态：构建期算一次 
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
  bash "$ROOT_DIR/scripts/recipes/package-bundle-zip.sh" "$id" "$out" "$ver" "$entry" "$bins" "$marker"
}