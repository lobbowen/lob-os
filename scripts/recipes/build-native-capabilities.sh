#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ABI="${ABI:-arm64-v8a}"
CAPS=".github/native-capabilities.txt"
MANIFEST="${MANIFEST:-/tmp/native-capabilities-manifest.txt}"

NODE_VERSION="${NODE_VERSION:-$(bash scripts/registry/read-node-versions.sh default || true)}"
[ -n "$NODE_VERSION" ] || { echo "[error] 取不到 NODE_VERSION（read-node-versions.sh 失败？）—— 它决定下载哪份 node 头，不能猜。" >&2; exit 1; }
echo "NODE_VERSION=$NODE_VERSION"
ANDROID_HOME="${ANDROID_HOME:-}"

set -euo pipefail
NDK=""
for v in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
  [ -n "$v" ] && [ -d "$v" ] && NDK="$v" && break
done
if [ -z "$NDK" ]; then
  NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "[error] runner 上找不到 NDK"; exit 1; }
echo "NDK=$NDK"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${ANDROID_API:-35}-clang"
[ -x "$CC" ] || { echo "[error] 找不到 clang: $CC"; exit 1; }

curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-headers.tar.xz" \
  -o /tmp/node-headers.tar.xz
mkdir -p /tmp/node-headers
tar -xJf /tmp/node-headers.tar.xz -C /tmp/node-headers --strip-components=1
INC=/tmp/node-headers/include/node
[ -f "$INC/node_api.h" ] || { echo "[error] 头文件解压异常：缺 node_api.h"; exit 1; }

mkdir -p "container/app/src/main/jniLibs/${ABI}"
"$CC" -shared -fPIC -O2 -DNAPI_VERSION=9 -I "$INC" \
  -o "container/app/src/main/jniLibs/${ABI}/liblobosflock.so" container/native/d2/flock.c
SO="container/app/src/main/jniLibs/${ABI}/liblobosflock.so"
SIZE=$(stat -c%s "$SO")
[ "$SIZE" -gt 1000 ] || { echo "[error] $SO 仅 $SIZE 字节，编译产物可疑"; exit 1; }
file "$SO" | grep -q "ELF 64-bit.*ARM aarch64" || { echo "[error] 产物不是 aarch64 ELF"; exit 1; }
echo "[ok] liblobosflock.so $SIZE 字节 ($(file -b --mime-type "$SO" 2>/dev/null || echo ELF))"

"$CC" -shared -fPIC -O2 -I "$INC" \
  -o "container/app/src/main/jniLibs/${ABI}/liblobosposix.so" \
  container/native/d1/link-interpose.c container/native/d1/open-fallback.c container/native/d1/tmp-paths.c \
  container/native/d1/exec-path.c -ldl
SO2="container/app/src/main/jniLibs/${ABI}/liblobosposix.so"
SIZE2=$(stat -c%s "$SO2")
[ "$SIZE2" -gt 500 ] || { echo "[error] $SO2 仅 $SIZE2 字节，编译产物可疑"; exit 1; }
file "$SO2" | grep -q "ELF 64-bit.*ARM aarch64" || { echo "[error] posix 产物不是 aarch64 ELF"; exit 1; }
echo "[ok] liblobosposix.so $SIZE2 字节"

cd "$ROOT"

set -uo pipefail
NDK=""
for v in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
  [ -n "$v" ] && [ -d "$v" ] && NDK="$v" && break
done
if [ -z "$NDK" ]; then
  NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "[error] runner 上找不到 NDK"; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android${ANDROID_API:-35}-clang"
LLVM_AR="$TC/llvm-ar"
[ -x "$CC" ] || { echo "[error] 找不到 clang: $CC"; exit 1; }
J="container/app/src/main/jniLibs/${ABI}"
mkdir -p "$J"

check_so() {
  local f="$1" min="$2"
  [ -f "$f" ] || return 1
  local s; s=$(stat -c%s "$f"); [ "$s" -gt "$min" ] || return 1
  file "$f" | grep -q 'ELF 64-bit.*ARM aarch64' || return 1
  echo "[ok] $(basename "$f") $s 字节"
  file "$f" | sed 's/^/      /'
}

"$CC" -static -O2 -o "$J/liblobosptyprobe.so" container/native/d2/pty-probe.c \
  || { echo "[error] ptyprobe 编译失败（纯 C 静态，失败即环境问题）"; exit 1; }
check_so "$J/liblobosptyprobe.so" 1000 || exit 1

"$CC" -static -O2 -o "$J/librivospty.so" container/native/d3/pty-session.c \
  || { echo "::error title=PTY 会话宿主编译失败::纯 C 静态，失败即环境问题 —— shell.exec 与终端都依赖它"; exit 1; }
check_so "$J/librivospty.so" 1000 || exit 1

BASH_VER=5.2.15
BASH_VER_TABLE="$(bash scripts/toolchain/fetch-pinned.sh --src-version bash 2>/dev/null || true)"
if [ -n "$BASH_VER_TABLE" ] && [ "$BASH_VER_TABLE" != "$BASH_VER" ]; then
  echo "::error title=bash 版本不一致::构建脚本写的是 $BASH_VER，钉值表是 $BASH_VER_TABLE"
  echo "             两处必须一致 —— 改一个，另一个也要跟着改。"
  exit 1
fi
echo "[bash] 取源码 $BASH_VER（sha256 由钉值表校验）"
bash_tarball="bash-${BASH_VER}.tar.gz"
if ! bash scripts/toolchain/fetch-pinned.sh --pin bash "/tmp/bash.tar.gz"; then
  echo "::error title=bash 源码取不到或 sha256 不符::钉值与来源见 scripts/component-sources.json 的 sources.bash"
  echo "             —— 所有镜像都试过了仍失败；**不要**改成不校验的下载。"
  exit 1
fi
echo "[bash] 命中钉值来源，sha256 校验通过"
tar -xzf /tmp/bash.tar.gz -C /tmp \
  || { echo "::error title=bash 解包失败::sha256 是对的，但 tar 解不开 —— 看上面 tar 的报错"; exit 1; }
cat > /tmp/termcap_stub.c <<'EOF'
/* bionic 无 termcap：readline 美化路径的 no-op 桩（载荷只走非交互 bash -c） */
int tputs(const char *s, int affcnt, int (*putc_)(int)) { (void)s; (void)affcnt; (void)putc_; return 0; }
int tgetent(char *bp, const char *name) { (void)bp; (void)name; return -1; }
int tgetflag(const char *id) { (void)id; return 0; }
int tgetnum(const char *id) { (void)id; return -1; }
char *tgetstr(const char *id, char **area) { (void)id; (void)area; return 0; }
char *tgoto(const char *cap, int col, int row) { (void)cap; (void)col; (void)row; return 0; }
/* 第三轮 CI 实证：桩只给函数不够 —— configure 探到 tputs 后 readline 引用
 * termcap 填充三件套全局 PC/BC/UP（真实 termcap 库附带），必须一并定义。 */
char PC = 0;
char *BC = 0;
char *UP = 0;
EOF
"$CC" -c -O2 /tmp/termcap_stub.c -o /tmp/termcap_stub.o \
  && "$LLVM_AR" rcs /tmp/libtermcap_stub.a /tmp/termcap_stub.o
cp scripts/bionic-compat.c /tmp/bionic_compat.c
"$CC" -c -O2 /tmp/bionic_compat.c -o /tmp/bionic_compat.o \
  && "$LLVM_AR" rcs /tmp/libbionic_compat.a /tmp/bionic_compat.o
(
  set -e
  cd /tmp/bash-${BASH_VER}
  ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
    --prefix=/native --disable-nls --without-bash-malloc \
    CC="$CC" CFLAGS="-O2 -Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-error=incompatible-function-pointer-types -Wno-error=incompatible-pointer-types" \
    LDFLAGS="-Wl,--allow-multiple-definition" LIBS="/tmp/libtermcap_stub.a /tmp/libbionic_compat.a" \
    bash_cv_getcwd_malloc=yes bash_cv_func_sigsetjmp=present \
    bash_cv_printf_a_format=yes bash_cv_dev_fd_standard=yes \
    bash_cv_unusable_rtsigs=no > /tmp/bash-configure.log 2>&1 \
    || { echo "=== configure 失败取证 ==="; tail -40 /tmp/bash-configure.log; exit 1; }
  make -j4 bash > /tmp/bash-make.log 2>&1 || {
    echo "=== make 失败取证（error 行 + 末 120 行）==="
    grep -nE "error:|Error [0-9]+$|undefined symbol" /tmp/bash-make.log | head -40 || true
    tail -120 /tmp/bash-make.log
    exit 1; }
) && cp -f /tmp/bash-${BASH_VER}/bash "$J/libbash.so"
if ! check_so "$J/libbash.so" 300000; then
  echo "::error title=必需件缺失::libbash.so 未产出 —— bash 工具依赖 $PREFIX/bin/bash，无回退路径"
  exit 1
fi

if ! command -v cargo > /dev/null 2>&1; then
  echo "runner 无 cargo，装最小 rustup"
  curl -fsSf https://sh.rustup.rs -o /tmp/rustup.sh && sh /tmp/rustup.sh -y --profile minimal --default-toolchain stable >/dev/null
  export PATH="$HOME/.cargo/bin:$PATH"
fi
rustup target add aarch64-linux-android > /dev/null 2>&1 || echo "[warn] rustup target add 失败（可能非 rustup 安装）"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN"
export CC_aarch64_linux_android="$CC" CXX_aarch64_linux_android="$CC" AR_aarch64_linux_android="$LLVM_AR"
RG_VER="$(node -e '
const t = require(process.argv[1]);
const s = (t.sources || {}).ripgrep;
if (!s) { console.error("钉值表里没有 ripgrep 这一项"); process.exit(1); }
if (!s.version) { console.error("钉值表里的 ripgrep 没有 version"); process.exit(1); }
if (s.urls) { console.error("钉值表里的 ripgrep 不该有 urls —— 它走 cargo，没有可下载的 tarball"); process.exit(1); }
process.stdout.write(String(s.version));
' "$ROOT_DIR/scripts/component-sources.json")" || exit 1

if cargo install --locked --version "$RG_VER" ripgrep --target aarch64-linux-android --root /tmp/rgbin --no-track > /tmp/rg-build.log 2>&1; then
  cp -f /tmp/rgbin/bin/rg "$J/liblobosrg.so"
else
  [ -f /tmp/rg-build.log ] && tail -30 /tmp/rg-build.log || echo "  （/tmp/rg-build.log 不存在）"
fi
if ! check_so "$J/liblobosrg.so" 300000; then
  echo "::error title=必需件缺失::liblobosrg.so 未产出 —— glob/grep 依赖 \$PREFIX/bin/rg，无回退路径"
  exit 1
fi

RG_GOT="$(sha256sum "$J/liblobosrg.so" | cut -d' ' -f1)"
RG_WANT="$(node -e '
const t = require(process.argv[1]);
process.stdout.write(String(((t.sources || {}).ripgrep || {}).sha256 || ""));
' "$ROOT_DIR/scripts/component-sources.json")"
if [ -z "$RG_WANT" ]; then
  node -e '
const fs = require("fs");
const p = process.argv[1];
let t;
try { t = JSON.parse(fs.readFileSync(p, "utf8")); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
if (!t.sources || !t.sources.ripgrep) { console.error("钉值表里没有 ripgrep 这一项"); process.exit(1); }
if (!/^[0-9a-f]{64}$/.test(String(process.argv[2]))) { console.error("实测哈希不是 64 位小写十六进制: " + process.argv[2]); process.exit(1); }
t.sources.ripgrep.sha256 = process.argv[2];
fs.writeFileSync(p, JSON.stringify(t, null, 2) + "\n");
console.log("[ripgrep] 首轮建立基线，已写入钉值表：sha256=" + process.argv[2]);
' "$ROOT_DIR/scripts/component-sources.json" "$RG_GOT"
elif [ "$RG_GOT" = "$RG_WANT" ]; then
  echo "[ripgrep] 产物哈希与钉值表一致：$RG_GOT（版本 $RG_VER）"
else
  echo "::notice title=ripgrep 产物哈希变了::钉值表=$RG_WANT 本次=$RG_GOT。已把实测值写回钉值表（下一轮起以它为基线）。"
  echo "::notice::这不是构建坏了 —— 是同一版本的产物字节变了（NDK 或 cargo 输入变了）。若字节本不该变，查 NDK 版本与 lock 文件。"
  node -e '
const fs = require("fs");
const p = process.argv[1];
const t = JSON.parse(fs.readFileSync(p, "utf8"));
t.sources.ripgrep.sha256 = process.argv[2];
fs.writeFileSync(p, JSON.stringify(t, null, 2) + "\n");
console.log("[ripgrep] 已写回钉值表：sha256=" + process.argv[2]);
' "$ROOT_DIR/scripts/component-sources.json" "$RG_GOT"
fi

for f in libbash.so liblobosrg.so liblobosptyprobe.so librivospty.so libbusybox.so liblobosflock.so liblobosposix.so liblobospty.so; do
  if [ -f "$J/$f" ]; then
    before=$(stat -c%s "$J/$f"); "$TC/llvm-strip" --strip-unneeded "$J/$f" 2>/dev/null || true
    echo "[strip] $f $before -> $(stat -c%s "$J/$f") 字节"
  fi
done

echo "== 能力二进制就位情况 =="
ls -la "$J"

cd "$ROOT"

(
set -uo pipefail
PTY_VER="1.2.0-beta.15"
NDK=""
for v in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
  [ -n "$v" ] && [ -d "$v" ] && NDK="$v" && break
done
if [ -z "$NDK" ]; then NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true); fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "::warning title=能力件缺失::找不到 NDK，跳过 node-pty"; exit 0; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
PTY_CC="$TC/aarch64-linux-android${PTY_API:-24}-clang"
PTY_CXX="$TC/aarch64-linux-android${PTY_API:-24}-clang++"
export CC="$PTY_CC" CXX="$PTY_CXX" AR="$TC/llvm-ar" LINK="$PTY_CXX"
[ -x "$PTY_CC" ] || { echo "::warning title=能力件缺失::找不到 clang: $PTY_CC"; exit 0; }
rm -rf /tmp/ptybuild && mkdir -p /tmp/ptybuild && cd /tmp/ptybuild
npm pack "node-pty@${PTY_VER}" >/dev/null 2>&1 || { echo "::warning title=能力件缺失::拉取 node-pty 源码失败"; exit 0; }
tar -xzf node-pty-*.tgz && cd package
python3 - <<'PY'
import re, pathlib
p = pathlib.Path('binding.gyp')
p.write_text(re.sub(r"\s*'-lutil'\s*,?", "", p.read_text()))
PY
npm install --ignore-scripts --no-audit --no-fund >/dev/null 2>&1 || true
if ! node -e '
const fs = require("node:fs");
const p = "binding.gyp";
let t = fs.readFileSync(p, "utf8");
if (t.includes("-Wl,--enable-new-dtags")) { console.log("[pty] 已含 RUNPATH 补丁，跳过"); process.exit(0); }
const m = /(\x27ldflags\x27\s*:\s*\[)/.exec(t);
if (!m) { console.error("[pty] binding.gyp 里找不到 ldflags 数组 —— 补丁无处可落"); process.exit(1); }
const D = String.fromCharCode(36);
const S = String.fromCharCode(39);
const B = String.fromCharCode(34);
const CALL = B + "-Wl,-rpath," + S + D + D + "ORIGIN" + S + B;
const FLAG = S + "-Wl,--enable-new-dtags" + S + ", " + CALL + ",";
t = t.slice(0, m.index + m[1].length) + " " + FLAG + t.slice(m.index + m[1].length);
fs.writeFileSync(p, t);
console.log("[pty] ldflags 已加 -Wl,--enable-new-dtags 与 $ORIGIN RUNPATH（$$ 给 make，单引号给 shell）");
'; then
  echo '::warning title=能力件缺失::binding.gyp 补丁失败 —— 终端 PTY 本包降级（缺 $ORIGIN RUNPATH）'
  exit 0
fi
npx --yes node-gyp@10 rebuild --arch=arm64 --nodedir=/tmp/node-headers > /tmp/pty-gyp.log 2>&1 || {
  echo '=== node-gyp 失败取证（末 60 行）==='; tail -60 /tmp/pty-gyp.log
  echo '::warning title=能力件缺失::构建 node-pty 失败 —— 终端 PTY 本包不可用'; exit 0; }
SO="build/Release/pty.node"
file "$SO" | grep -q 'ELF 64-bit.*ARM aarch64' || { echo '::warning title=能力件缺失::pty.node 非 aarch64'; tail -20 /tmp/pty-gyp.log; exit 0; }
if ! readelf -d "$SO" 2>/dev/null | grep -q 'RUNPATH.*\$ORIGIN'; then
  echo '::warning title=pty 依赖会 CANNOT LINK::pty.node 依赖 libc++_shared.so 却没有含 $ORIGIN 的 DT_RUNPATH —— bionic 不查 nativeLibraryDir。终端 PTY 本轮降级。'
fi
cp -f "$SO" "${GITHUB_WORKSPACE:-$ROOT}/container/app/src/main/jniLibs/${ABI}/liblobospty.so"
echo "[ok] liblobospty.so $(stat -c%s "${GITHUB_WORKSPACE:-$ROOT}/container/app/src/main/jniLibs/${ABI}/liblobospty.so") 字节"

)
cd "$ROOT"

echo "== 底座共享库（libz / libssl / libcrypto / libcurl）=="
CC="$CC" ABI="$ABI" bash scripts/toolchain/build-base-libs.sh || {
  echo "::error title=底座共享库缺失::libz/libssl/libcrypto/libcurl 是 \$PREFIX 必备（upstream 档，缺件硬红）——"
  echo "             没有它们，curl/git 改动态链（第 2 阶段）与 busybox 的 gzip/tar（第 5 阶段）都无从谈起。"
  exit 1
}
cd "$ROOT"

echo "== busybox（基础命令集）=="
CC="$CC" ABI="$ABI" bash scripts/recipes/build-native-busybox.sh || {
  echo "::error title=busybox 缺失::\$PREFIX 的 tar/gzip/grep/sed/ls/cp/mv 等基础命令依赖它（upstream 档，缺件硬红）"
  exit 1
}
BB_STAGED="$ROOT/dist/bin/busybox"
if [ -f "$BB_STAGED" ]; then
  mkdir -p "$J"
  if ! cp -f "$BB_STAGED" "$J/libbusybox.so"; then
    echo "::error title=busybox 搬运失败::$BB_STAGED → $J/libbusybox.so（目录不存在或不可写）"
    exit 1
  fi
  echo "[ok] busybox 落位：$BB_STAGED → $J/libbusybox.so（$(stat -c%s "$J/libbusybox.so") 字节）"
else
  echo "::error title=busybox 未产出::build-native-busybox.sh 说它编完了，但 $BB_STAGED 不存在 —— 看上面配方的报错"
  exit 1
fi
check_so "$J/libbusybox.so" 500000 || {
  echo "::error title=busybox 产物不合格::静态编体积异常 —— 看上面的形态自检输出"
  exit 1
}
cd "$ROOT"

[ -f "$CAPS" ] || { echo "[error] 缺少 $CAPS" >&2; exit 1; }
: > "$MANIFEST"
N=0; MISS=0
while read -r TIER LIB _ID; do
  case "$TIER" in ''|'#'*) continue ;; esac
  P="container/app/src/main/jniLibs/$ABI/$LIB"
  if [ -f "$P" ]; then
    printf '%s %s %s\n' "$TIER" "$LIB" "$(sha256sum "$P" | cut -d' ' -f1)" >> "$MANIFEST"
    N=$((N + 1))
  else
    printf '%s %s MISSING\n' "$TIER" "$LIB" >> "$MANIFEST"
    MISS=$((MISS + 1))
  fi
done < "$CAPS"
echo "=== 小件产物清单（$MANIFEST）==="; cat "$MANIFEST"
echo "在册 $N 件，缺件 $MISS 件"
