#!/usr/bin/env bash
set -euo pipefail

NODE_VERSION="${1:-24.21.0}"
export ANDROID_NDK="${ANDROID_NDK:?请先设置 ANDROID_NDK 指向 NDK 根目录 (r27+)}"
ANDROID_API="${ANDROID_API:-24}"
ARCH="arm64"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/container/app/src/main/jniLibs/arm64-v8a"
OUT_NAME="libnode.so"
mkdir -p "$OUT_DIR"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 克隆 Node.js v${NODE_VERSION} 源码"
git clone --depth 1 --branch "v${NODE_VERSION}" https://github.com/nodejs/node "$WORK/node"
cd "$WORK/node"

STACK_TRACE="deps/v8/src/base/debug/stack_trace_posix.cc"
if [ -f "$STACK_TRACE" ]; then
  echo "==> 应用 bionic backtrace 补丁: $STACK_TRACE"
  python3 - "$STACK_TRACE" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8', errors='replace').read()
old = """#if V8_LIBC_GLIBC || V8_LIBC_BSD || V8_LIBC_UCLIBC || V8_OS_SOLARIS
#define HAVE_EXECINFO_H 1
#endif"""
new = """#if V8_LIBC_GLIBC || V8_LIBC_BSD || V8_LIBC_UCLIBC || V8_OS_SOLARIS
#define HAVE_EXECINFO_H 1
#endif
// [android-container patch] bionic libc has no <execinfo.h>/backtrace();
// force-disable the execinfo path to avoid 'use of undeclared identifier backtrace'.
#if defined(__ANDROID__)
#undef HAVE_EXECINFO_H
#define HAVE_EXECINFO_H 0
#endif"""
if old in s:
    s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print("patched:", p)
else:
    print("WARN: anchor not found, patch skipped (upstream may have changed)")
PY
  if ! grep -q "android-container patch" "$STACK_TRACE"; then
    echo "==> 锚点补丁未生效，改用文件头强制定义"
    sed -i '1i #if defined(__ANDROID__)\n#undef HAVE_EXECINFO_H\n#define HAVE_EXECINFO_H 0\n#endif' "$STACK_TRACE"
  fi
  grep -n "HAVE_EXECINFO_H" "$STACK_TRACE" | head
else
  echo "==> [warn] $STACK_TRACE 不存在，跳过补丁"
fi

TRAP_HDR="deps/v8/src/trap-handler/trap-handler.h"
if [ -f "$TRAP_HDR" ]; then
  echo "==> 应用 V8 trap-handler 补丁: $TRAP_HDR"
  python3 - "$TRAP_HDR" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8', errors='replace').read()
if 'android-container patch' in s:
    print("  already patched, skip")
    sys.exit(0)

START = "// X64 on Linux, Windows, MacOS, FreeBSD."
END = "// Everything else is unsupported."
if START not in s or END not in s:
    sys.exit("FATAL: trap-handler.h anchors not found; upstream layout changed, "
             "patch needs review")

i = s.index(START)
j = s.index(END)
block = s[i:j]

n_if = len(re.findall(r"^#if ", block, flags=re.M))
n_elif = len(re.findall(r"^#elif ", block, flags=re.M))
if n_if != 1 or n_elif < 1:
    sys.exit("FATAL: unexpected conditional structure in trap-handler ladder "
             "(#if=%d #elif=%d); patch needs review" % (n_if, n_elif))

new_block = ("// [android-container patch] Force every branch of the ladder below to be\n"
             "// false so control falls through to '#else -> V8_TRAP_HANDLER_SUPPORTED\n"
             "// false'. See https://github.com/nodejs/node/issues/36287 : cross-compiling\n"
             "// an arm64 target from an x64 host otherwise matches the 'Arm64 simulator\n"
             "// on x64' branch, setting V8_TRAP_HANDLER_VIA_SIMULATOR -- but the\n"
             "// simulator's ProbeMemory only exists in an arm64 translation unit, so the\n"
             "// x64 host tool mksnapshot cannot link.\n"
             "// We AND a false term into each condition rather than wrapping the block in\n"
             "// an extra '#if 0', because adding a directive would unbalance\n"
             "// #if/#endif and the compiler would fail with 'unterminated conditional\n"
             "// directive', swallowing the rest of this header.\n"
             + re.sub(r"^#if (?!0 &&)", "#if 0 && ", block, count=1, flags=re.M)
             )
new_block = re.sub(r"^#elif (?!0 &&)", "#elif 0 && ", new_block, flags=re.M)

s = s[:i] + new_block + s[j:]
open(p, 'w', encoding='utf-8').write(s)
print("  patched: neutralised 1 #if + %d #elif" % n_elif)
PY
  python3 - "$TRAP_HDR" <<'PY'
import re, sys, os, subprocess, tempfile
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

depth = 0
for line in s.splitlines():
    if re.match(r"^#\s*(if|ifdef|ifndef)\b", line):
        depth += 1
    elif re.match(r"^#\s*endif\b", line):
        depth -= 1
    if depth < 0:
        sys.exit("FATAL: trap-handler.h has an extra #endif (depth went negative)")
if depth != 0:
    sys.exit("FATAL: trap-handler.h conditional directives are unbalanced "
             "(depth=%d) -- this is exactly the 'unterminated conditional "
             "directive' bug; refusing to build." % depth)
print("  [ok] conditional directives balanced (depth=0)")

START = "// X64 on Linux, Windows, MacOS, FreeBSD."
END = "// Everything else is unsupported."
if START not in s or END not in s:
    sys.exit("FATAL: cannot locate trap-handler ladder for semantic check")
ladder = s[s.index(START):s.index(END)]

defs = {"V8_HOST_ARCH_X64": 1, "V8_HOST_ARCH_ARM64": 0, "V8_HOST_ARCH_IA32": 0,
        "V8_HOST_ARCH_ARM": 0, "V8_HOST_ARCH_PPC64": 0, "V8_HOST_ARCH_S390X": 0,
        "V8_HOST_ARCH_RISCV64": 0, "V8_HOST_ARCH_LOONG64": 0,
        "V8_TARGET_ARCH_X64": 0, "V8_TARGET_ARCH_ARM64": 1, "V8_TARGET_ARCH_IA32": 0,
        "V8_TARGET_ARCH_ARM": 0, "V8_TARGET_ARCH_PPC64": 0, "V8_TARGET_ARCH_S390X": 0,
        "V8_TARGET_ARCH_RISCV64": 0, "V8_TARGET_ARCH_LOONG64": 0,
        "V8_OS_LINUX": 1, "V8_OS_ANDROID": 1, "V8_OS_WIN": 0, "V8_OS_DARWIN": 0,
        "V8_OS_FREEBSD": 0, "V8_OS_AIX": 0}
hdr = "\n".join("#define %s %d" % (k, v) for k, v in defs.items())
prog = (hdr + "\n" + ladder +
        "\n#else\n#define V8_TRAP_HANDLER_SUPPORTED 0\n#endif\n"
        "int probe_val = V8_TRAP_HANDLER_SUPPORTED;\n")

with tempfile.TemporaryDirectory() as d:
    f = os.path.join(d, "probe.cpp")
    open(f, "w").write(prog)
    r = subprocess.run(["g++", "-std=c++20", "-E", "-P", f],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("FATAL: trap-handler semantic probe failed to preprocess:\n"
                 + r.stderr[:1500])
    m = re.search(r"int probe_val = (\w+);", r.stdout)
    val = m.group(1) if m else None
if val == "0":
    print("  [ok] semantic check: x64-host/arm64-target -> "
          "V8_TRAP_HANDLER_SUPPORTED = false (trap handler disabled)")
elif val is None:
    sys.exit("FATAL: could not evaluate V8_TRAP_HANDLER_SUPPORTED "
             "(preprocessor output did not contain the probe line)")
else:
    sys.exit("FATAL: V8_TRAP_HANDLER_SUPPORTED evaluated to %r for "
             "x64-host/arm64-target; the arm64-simulator branch is still live "
             "and mksnapshot will fail to link." % val)
PY
  grep -c "^#if 0 && \|^#elif 0 && " "$TRAP_HDR" | sed 's/^/  neutralised branches: /'
else
  echo "==> [warn] $TRAP_HDR 不存在，跳过 trap-handler 补丁"
fi

CCTEST_HELLO="test/cctest/test_crypto_clienthello.cc"
if [ -f "$CCTEST_HELLO" ]; then
  echo "==> 应用 aligned_alloc 补丁（API ${ANDROID_API} < 28，bionic 无该函数）: $CCTEST_HELLO"
  python3 - "$CCTEST_HELLO" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8', errors='replace').read()
if 'android-container patch' in s:
    print("  already patched, skip")
    sys.exit(0)

old = "alloc_base = static_cast<uint8_t*>(aligned_alloc(page, 2 * page));"
new = ("// [android-container patch] aligned_alloc() only exists in bionic from API 28;\n"
       "    // this build targets API 24. memalign() has been available since API 1 and\n"
       "    // is equivalent here (alignment == page size, a power of two; size is a\n"
       "    // multiple of the alignment; result is free()-able).\n"
       "    alloc_base = static_cast<uint8_t*>(memalign(page, 2 * page));")
if old not in s:
    sys.exit("FATAL: aligned_alloc anchor not found in %s; upstream layout "
             "changed, patch needs review" % p)
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print("  patched: aligned_alloc -> memalign (1 occurrence)")
PY
  ALIGNED_CALLS="$(grep -n "aligned_alloc[[:space:]]*(" "$CCTEST_HELLO" \
                     | grep -v ":[[:space:]]*//" \
                     | grep -v ":[[:space:]]*\*" || true)"
  if [ -n "$ALIGNED_CALLS" ]; then
    echo "==> [error] $CCTEST_HELLO 里仍有 aligned_alloc 调用："
    echo "$ALIGNED_CALLS" | sed 's/^/        | /'
    echo "            API ${ANDROID_API} 下会再次报 'use of undeclared identifier'。"
    exit 1
  fi
  echo "    [ok] 已无 aligned_alloc 调用点（注释中的说明文字已排除）"
  NDKBIN_FOR_CHECK="$( { ls -d "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null || true; } | head -1)"
  if [ -n "$NDKBIN_FOR_CHECK" ] && [ -x "$NDKBIN_FOR_CHECK/aarch64-linux-android${ANDROID_API}-clang" ]; then
    cat > "$WORK/memalign_probe.c" <<'PROBE'
#include <malloc.h>
#include <stdlib.h>
int main(void) { void* p = memalign(4096, 8192); free(p); return 0; }
PROBE
    if "$NDKBIN_FOR_CHECK/aarch64-linux-android${ANDROID_API}-clang" -Wl,--no-undefined \
         "$WORK/memalign_probe.c" -o "$WORK/memalign_probe" >/dev/null 2>&1; then
      echo "    [ok] memalign 在 API ${ANDROID_API} 下可链接（--no-undefined 校验通过）"
    else
      echo "==> [warn] memalign 在 API ${ANDROID_API} 下 --no-undefined 校验未通过，"
      echo "            输出如下（若真失败，需改用其他对齐分配方案）："
      "$NDKBIN_FOR_CHECK/aarch64-linux-android${ANDROID_API}-clang" -Wl,--no-undefined \
        "$WORK/memalign_probe.c" -o "$WORK/memalign_probe" 2>&1 | head -5 | sed 's/^/        | /'
    fi
    rm -f "$WORK/memalign_probe.c" "$WORK/memalign_probe"
  fi
else
  echo "==> [warn] $CCTEST_HELLO 不存在，跳过 aligned_alloc 补丁"
fi

HOST_CC="${CC_host:-}"
HOST_CXX="${CXX_host:-}"
NDK_HOST_BIN="$( { ls -d "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null || true; } | head -1)"

probe_host_compiler() {
  local cxx="$1" tag="$2"
  [ -n "$cxx" ] || return 1
  command -v "$cxx" >/dev/null 2>&1 || [ -x "$cxx" ] || return 1
  cat > "$WORK/host_probe.cpp" <<'PROBE'
#include <atomic>
#include <cstdio>
#include <string>
#include <memory>
int main() {
    int v = 0, exp = 0;
    __atomic_compare_exchange_n(&v, &exp, 1, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    std::string s = "ok";
    auto p = std::make_shared<int>(42);
    std::printf("%s %d %d\n", s.c_str(), v, *p);
    return 0;
}
PROBE
  if "$cxx" -m64 -std=gnu++20 "$WORK/host_probe.cpp" -o "$WORK/host_probe.out" -latomic >/dev/null 2>&1; then
    local info; info="$(file -b "$WORK/host_probe.out" 2>/dev/null || echo unknown)"
    case "$info" in
      *x86-64*)
        echo "    [ok] $tag -> $cxx (x86-64, C++20 + stdlib + atomics 全部可用)"
        return 0 ;;
      *)
        echo "    [skip] $tag -> $cxx 产出非 x86-64: $info"
        return 1 ;;
    esac
  else
    echo "    [skip] $tag -> $cxx 探针编译/链接失败，真实原因:"
    "$cxx" -m64 -std=gnu++20 "$WORK/host_probe.cpp" -o "$WORK/host_probe.out" -latomic 2>&1 \
      | head -3 | sed 's/^/        | /'
    return 1
  fi
}

echo "==> 挑选宿主编译器（逐个实测）"
HOST_CXX_PICKED=""
for cand in \
  "${CXX_host:-}" \
  "$(command -v clang++ 2>/dev/null)" \
  "/usr/bin/clang++" \
  "$(command -v g++ 2>/dev/null)" \
  "/usr/bin/g++" \
  "${NDK_HOST_BIN:+$NDK_HOST_BIN/clang++}"
do
  [ -n "$cand" ] || continue
  if probe_host_compiler "$cand" "host-cxx"; then
    HOST_CXX_PICKED="$cand"
    break
  fi
done
if [ -z "$HOST_CXX_PICKED" ]; then
  echo "==> [error] 找不到能用的宿主编译器。宿主构建（icupkg/mksnapshot 等）无法完成。"
  echo "    请安装 clang++ 或 g++ 以及 libstdc++/libatomic 开发包后重试。"
  exit 1
fi
HOST_CXX="$HOST_CXX_PICKED"
case "$HOST_CXX" in
  */clang++|clang++) HOST_CC="$(dirname "$HOST_CXX")/clang"; [ -x "$HOST_CC" ] || HOST_CC="$(command -v clang || echo "$HOST_CXX")" ;;
  */g++|g++)         HOST_CC="$(dirname "$HOST_CXX")/gcc";   [ -x "$HOST_CC" ] || HOST_CC="$(command -v gcc || echo "$HOST_CXX")" ;;
  *)                 HOST_CC="${CC_host:-$(command -v clang || command -v gcc)}" ;;
esac
echo "==> 宿主编译器确定: CXX_host=$HOST_CXX  CC_host=$HOST_CC"
export CC_host="$HOST_CC"
export CXX_host="$HOST_CXX"
export LINK_host="$HOST_CXX"
export AR_host="${AR_host:-$(command -v ar || echo ar)}"

export LDFLAGS_host="${LDFLAGS_host:-} -latomic"
echo "==> 宿主工具链: CC_host=$CC_host  CXX_host=$CXX_host  AR_host=$AR_host  LDFLAGS_host=$LDFLAGS_host"

DOLLAR='$'
LDFLAGS_TARGET_OVERRIDE="LDFLAGS.target=-Wl,--enable-new-dtags -Wl,-rpath,'${DOLLAR}${DOLLAR}ORIGIN'"
echo "==> 目标侧链接标志（make 命令行变量）: $LDFLAGS_TARGET_OVERRIDE"

echo "==> 运行官方 android-configure (NDK ${ANDROID_NDK} + API ${ANDROID_API} + arch ${ARCH})"
./android-configure "$ANDROID_NDK" "$ANDROID_API" "$ARCH"

if [ ! -f out/Makefile ]; then
  echo "==> [error] android-configure 之后没有 out/Makefile，配置未生成。"
  exit 1
fi
HOST_CC_IN_MK="$(sed -n 's/^CC\.host[[:space:]]*?*=[[:space:]]*//p' out/Makefile | head -1)"
HOST_CXX_IN_MK="$(sed -n 's/^CXX\.host[[:space:]]*?*=[[:space:]]*//p' out/Makefile | head -1)"
echo "==> 校验 out/Makefile 中的宿主工具链:"
echo "    CC.host  = ${HOST_CC_IN_MK:-<空>}"
echo "    CXX.host = ${HOST_CXX_IN_MK:-<空>}"
if [ -z "$HOST_CXX_IN_MK" ]; then
  echo "==> [error] out/Makefile 里没有 CXX.host，gyp 未采用我们的宿主工具链。"
  echo "            宿主工具会被编成 ARM64，随后在构建机上 Exec format error。"
  exit 1
fi
case "$HOST_CXX_IN_MK" in
  *android*)
    echo "==> [error] 宿主编译器落到了 NDK 的 android 工具链（$HOST_CXX_IN_MK）。"
    echo "            宿主侧（mksnapshot/icupkg 等）必须用系统编译器，否则会报"
    echo "            \"fatal error: 'atomic' file not found\"。"
    echo "            期望: $HOST_CXX"
    exit 1 ;;
esac
if ! "$HOST_CXX_IN_MK" -m64 -std=gnu++20 -x c++ -c /dev/null -o /dev/null >/dev/null 2>&1; then
  echo "==> [error] out/Makefile 记录的宿主编译器无法编译 C++ 头：$HOST_CXX_IN_MK"
  "$HOST_CXX_IN_MK" -m64 -std=gnu++20 -x c++ -c /dev/null -o /dev/null 2>&1 | head -3 | sed 's/^/            | /'
  echo "            这就是 abseil.host.mk 报 'atomic' file not found 的直接原因。"
  echo "            可在环境变量里显式指定 CXX_host/CC_host 后重跑本脚本。"
  exit 1
fi
echo "    [ok] 宿主编译器校验通过（非 android 工具链，且实测能编 C++ 头）"

DRY_LOG="${TMPDIR:-/tmp}/lobos-node-make-n.log"
MAKE_N_RC=0
make -n "$LDFLAGS_TARGET_OVERRIDE" > "$DRY_LOG" 2>&1 || MAKE_N_RC=$?
EXPECTED_RPATH="-Wl,-rpath,'${DOLLAR}ORIGIN'"
RPATH_SEEN="$( { grep -o -- '-Wl,-rpath,[^ ]*' "$DRY_LOG" || true; } | sort -u | tr '\n' ' ')"
RPATH_LINES="$(grep -c -- '-rpath' "$DRY_LOG" || true)"
NODE_LINK_PATTERN='-o [^ ]*/Release/node($|[^_a-zA-Z0-9])'
NODE_LINK_CNT="$(grep -cE -- "$NODE_LINK_PATTERN" "$DRY_LOG" || true)"
LDFLAGS_TARGET_IN_MK="$(sed -n 's/^\(LDFLAGS\.target[[:space:]]*[*?]*=[[:space:]]*\)/\1/p' out/Makefile | head -1)"

echo "==> 进编译前取证（红也要红得能自证）"
echo "    make -n: 退出码=$MAKE_N_RC  展开行数=$(wc -l < "$DRY_LOG")  日志=$DRY_LOG"
echo "    本次注入: $LDFLAGS_TARGET_OVERRIDE"
echo "    out/Makefile 里 LDFLAGS* 原文行:"
{ grep -n '^LDFLAGS' out/Makefile || true; } | sed -n '1,10p' | cut -c1-300 | sed 's/^/      /'
echo "    out/Makefile 中含 -rpath 的行数: $(grep -c -- '-rpath' out/Makefile || true)"
echo "    make -n 展开中含 -rpath 的行数: ${RPATH_LINES:-0}"
echo "    make -n 展开里的 -rpath 实文: ${RPATH_SEEN:-<无>}"
echo "    node 本体链接行条数: ${NODE_LINK_CNT:-0}"
{ grep -E -- "$NODE_LINK_PATTERN" "$DRY_LOG" || true; } | sed -n '1,2p' | cut -c1-400 | sed 's/^/      链接行: /'
echo "    make -n 日志末尾 15 行:"
tail -n 15 "$DRY_LOG" | cut -c1-300 | sed 's/^/      /'
echo "==> 校验展开后的链接参数: ${LDFLAGS_TARGET_IN_MK:-<out/Makefile 里没有 LDFLAGS.target 赋值>}"
case " $RPATH_SEEN " in
  *" $EXPECTED_RPATH "*)
    echo "    [ok] 链接行含 $EXPECTED_RPATH"
    grep -q -- '--enable-new-dtags' "$DRY_LOG" || {
      echo "==> [error] 有 -rpath 但缺 --enable-new-dtags：bionic 忽略 DT_RPATH，产物会白编。"
      exit 1
    }
    if [ "${NODE_LINK_CNT:-0}" -eq 0 ]; then
      echo "==> [error] make -n 展开里找不到 node 的链接命令（-o .../Release/node），"
      echo "            无法证明链接标志落进了产物那次链接，不放行。make -n 退出码=$MAKE_N_RC。"
      echo "            按上面「本次注入」与「out/Makefile 里 LDFLAGS* 原文行」核对生成器用的变量名。"
      exit 1
    fi
    if { grep -E -- "$NODE_LINK_PATTERN" "$DRY_LOG" || true; } | grep -q -- '-rpath'; then
      echo "    [ok] node 本体那次链接就带 $EXPECTED_RPATH（命中 ${NODE_LINK_CNT} 行配方）"
    else
      echo "==> [error] -rpath 出现在展开里，但 node 本体那次链接没有它 —— 产物仍会 CANNOT LINK。"
      echo "            说明标志落到了别的工具集/目标；上面「链接行」原文就是实际配方行。"
      exit 1
    fi
    ;;
  *RIGIN*)
    echo "==> [error] -rpath 的参数没有展开成期望的 $EXPECTED_RPATH（出现 RIGIN 字样）。"
    echo "            多半是 \$\$ 转义在 gyp → Makefile → sh 三层展开中某一层错位。"
    echo "            实际链接行: $RPATH_SEEN"
    exit 1
    ;;
  *)
    echo "==> [error] make -n 展开结果里根本没有 -rpath —— 链接标志未被 gyp 采纳。"
    echo "            期望: $EXPECTED_RPATH    本次注入: $LDFLAGS_TARGET_OVERRIDE"
    echo "            make -n 退出码=$MAKE_N_RC（非零则先按上面的日志末尾判断展开本身有没有失败）"
    echo "            取证段已列出 out/Makefile 的 LDFLAGS* 原文。若 LDFLAGS.target 在那里"
    echo "            是 \`?=\` 且被命令行覆盖后仍无 -rpath，说明链接配方引的是另一个变量"
    echo "            （如裸 \$(LDFLAGS)），按原文改注入点，不要放宽判据。"
    exit 1
    ;;
esac

echo "==> 使用 make 生成器（android-configure 的默认配置，上游唯一验证过的路径）"
echo "    注意: 构建生成器固定为 make；不要改成 --ninja（见上方注释）。"
unset GYP_DEFINES GYP_GENERATORS 2>/dev/null || true
USE_NINJA=0
if command -v ninja >/dev/null 2>&1; then
  echo "    [info] 系统里装了 ninja，但本构建刻意不使用它。"
fi
if [ ! -f out/Makefile ]; then
  echo "==> [error] 未找到 out/Makefile，说明配置没有落到 make 生成器。"
  echo "            out/ 可能被之前的 --ninja 配置污染，请清理后重跑。"
  exit 1
fi
echo "    [ok] out/Makefile 存在，确认使用 make 生成器"

ZMK_DIR="out/deps/zlib"
ZMK_FILES=""
for f in "$ZMK_DIR"/zlib.target.mk \
         "$ZMK_DIR"/zlib_arm_crc32.target.mk \
         "$ZMK_DIR"/zlib_adler32_simd.target.mk \
         "$ZMK_DIR"/zlib_data_chunk_simd.target.mk; do
  [ -f "$f" ] && ZMK_FILES="$ZMK_FILES $f"
done
if [ -n "$ZMK_FILES" ]; then
  echo "==> 修补 gyp 生成的 zlib 目标: ARMV8_OS_ANDROID -> ARMV8_OS_LINUX"
  python3 - $ZMK_FILES <<'PY'
import os, sys
total_files = 0
total = 0
for f in sys.argv[1:]:
    s = open(f, encoding="utf-8", errors="replace").read()
    n = s.count("-DARMV8_OS_ANDROID")
    if n:
        s = s.replace("-DARMV8_OS_ANDROID", "-DARMV8_OS_LINUX")
        open(f, "w", encoding="utf-8").write(s)
        print("  patched %s (%d occurrence(s))" % (f, n))
        total += n
        total_files += 1
print("  total: %d occurrence(s) in %d file(s)" % (total, total_files))
if total == 0:
    sys.exit("FATAL: no -DARMV8_OS_ANDROID found in zlib gyp outputs "
             "(searched out/deps/zlib/*.target.mk); upstream layout changed, "
             "patch needs review")
PY
  echo "==> 补丁后核对（应只剩 ARMV8_OS_LINUX）:"
  grep -h -o "ARMV8_OS_[A-Z]*" $ZMK_FILES 2>/dev/null | sort -u | sed 's/^/    /'
else
  echo "==> [warn] 未找到 zlib gyp 产物（$ZMK_DIR），跳过补丁"
fi

echo "==> 编译 (NDK r27+ 链接器默认 max-page-size=16384 → 16KB 页对齐)"

detect_mem_mb() {
  local lim
  lim="$(cat /sys/fs/cgroup/memory.max 2>/dev/null || true)"
  if [ -z "$lim" ] || [ "$lim" = "max" ]; then
    lim="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || true)"
  fi
  case "$lim" in
    ''|max|*[!0-9]*) lim="" ;;
  esac
  if [ -n "$lim" ] && [ "$lim" -gt 1000000000000 ] 2>/dev/null; then lim=""; fi
  if [ -n "$lim" ]; then
    echo $((lim / 1024 / 1024))
    return
  fi
  awk '/^MemAvailable:/{print int($2/1024); exit}' /proc/meminfo 2>/dev/null || echo 0
}

CPU_JOBS="$(nproc)"
MEM_MB="$(detect_mem_mb)"
MEM_JOBS=2
if [ -n "$MEM_MB" ] && [ "$MEM_MB" -gt 0 ] 2>/dev/null; then
  MEM_JOBS=$(( (MEM_MB - 2048) / 3584 ))
  [ "$MEM_JOBS" -lt 2 ] && MEM_JOBS=2
else
  MEM_JOBS="$CPU_JOBS"
fi
JOBS="${NODE_BUILD_JOBS:-$CPU_JOBS}"
if [ "$MEM_JOBS" -lt "$JOBS" ]; then
  JOBS="$MEM_JOBS"
fi
[ "$JOBS" -lt 1 ] && JOBS=1

echo "==> 并行度决策（按内存而非核数）"
echo "    检测到可用内存: ${MEM_MB:-未知} MB   CPU: ${CPU_JOBS} 核"
echo "    按 3.5GB/编译进程 + 2GB 余量 → 内存上限 -j${MEM_JOBS}"
echo "    最终使用: make $LDFLAGS_TARGET_OVERRIDE -j${JOBS}（并行度可用 NODE_BUILD_JOBS 覆盖）"
echo "    预期: host+target 合计约 6800 个编译单元，耗时以小时计。"
echo "    注: 这里刻意不用满 CPU —— 编译 V8 是内存瓶颈而非 CPU 瓶颈，"
echo "        并发放大后峰值内存会撞穿 runner 限额，导致进程被 OOM 杀掉，"
echo "        表现是「任务在远早于超时的时刻突然消失、连收尾步骤都没记录」。"
(
  while true; do
    sleep 120
    t="$({ find out/Release/obj.target -name '*.o' 2>/dev/null || true; } | wc -l)"
    h="$({ find out/Release/obj.host -name '*.o' 2>/dev/null || true; } | wc -l)"
    printf '[progress %s] host=%s target=%s\n' "$(date -u +%H:%M:%S)" "$h" "$t"
  done
) &
PROGRESS_PID=$!
trap 'kill "$PROGRESS_PID" 2>/dev/null || true' EXIT

make "$LDFLAGS_TARGET_OVERRIDE" -j"${JOBS}"

NODE_OUT_DIR="${NODE_OUT_DIR:-$ROOT/dist}"
mkdir -p "$NODE_OUT_DIR"
echo "==> 拷贝 node 本体到 $NODE_OUT_DIR/$OUT_NAME（不进 jniLibs：走商店通道）"
cp out/Release/node "$NODE_OUT_DIR/$OUT_NAME"
chmod +x "$NODE_OUT_DIR/$OUT_NAME"
echo "==> 核对本地产物 sha256（发布时用同一份值）"
sha256sum "$NODE_OUT_DIR/$OUT_NAME"

echo "==> 打包 libc++_shared.so（node 运行时的动态依赖，系统不提供）"
LIBCXX_SRC="$( { ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so 2>/dev/null || true; } | head -1)"
if [ -z "$LIBCXX_SRC" ] || [ ! -f "$LIBCXX_SRC" ]; then
  echo "==> [error] 在 NDK 里找不到 libc++_shared.so，无法连带打包。"
  echo "           查找路径: $ANDROID_NDK/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/"
  exit 1
fi
cp -f "$LIBCXX_SRC" "$OUT_DIR/libc++_shared.so"
chmod +x "$OUT_DIR/libc++_shared.so"
echo "    源: $LIBCXX_SRC"
echo "    目标: $OUT_DIR/libc++_shared.so ($(stat -c%s "$OUT_DIR/libc++_shared.so") 字节)"

echo "==> 清单一致性自检（.github/native-assets.txt）"
MANIFEST="$ROOT/.github/native-assets.txt"
if [ ! -f "$MANIFEST" ]; then
  echo "==> [error] 找不到资产清单 $MANIFEST"
  exit 1
fi
MISMATCH=0
for f in "$OUT_DIR"/*.so; do
  [ -f "$f" ] || continue
  base="$(basename "$f")"
  if ! grep -qxF "$base" <(grep -v '^[[:space:]]*#' "$MANIFEST" | sed 's/[[:space:]]*$//' | grep -v '^$'); then
    echo "    [FAIL] $base 已产出，但不在 $MANIFEST 里（CI 不会下载/审计它）"
    MISMATCH=1
  fi
done
while IFS= read -r a; do
  case "$a" in ''|'#'*) continue ;; esac
  a="$(echo "$a" | tr -d '[:space:]')"
  if [ ! -f "$OUT_DIR/$a" ]; then
    echo "    [FAIL] 清单要求 $a，但 $OUT_DIR 里没有它（CI 下载会 404）"
    MISMATCH=1
  fi
done < "$MANIFEST"
if [ "$MISMATCH" -ne 0 ]; then
  echo "==> [error] 产物与 .github/native-assets.txt 不一致。"
  echo "           该清单是 NativeAssetRegistry 的投影，二者必须同步。"
  exit 1
fi
echo "    [ok] 产物与清单一致（$(ls "$OUT_DIR"/*.so | wc -l) 项）"

echo "==> 产物形态门禁（scripts/verify/verify-runtime-elf.sh）"
bash "$ROOT/scripts/verify/verify-runtime-elf.sh" "$OUT_DIR"
bash "$ROOT/scripts/verify/verify-runtime-elf.sh" "$NODE_OUT_DIR" 2>/dev/null \
  || echo "==> [note] dist/ 单独过一次形态门禁未通过（该脚本按 jniLibs 形态写的，不覆盖组件）"

echo "==> 完成。"
echo "    node 本体: $NODE_OUT_DIR/$OUT_NAME（编译工作区产物，不进 APK）"
echo "    libc++_shared.so: $OUT_DIR/libc++_shared.so（APK 原生件，随 APK 交付）"
echo "    下一步: bash scripts/recipes/build-component-node.sh 落成组件 →"
echo "            bash scripts/publish/make-release.sh ${NODE_VERSION} 发布。"
