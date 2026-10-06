#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ABI="${ABI:-arm64-v8a}"
CAPS=".github/native-capabilities.txt"
MANIFEST="${MANIFEST:-/tmp/native-capabilities-manifest.txt}"

NODE_VERSION="${NODE_VERSION:-$(bash scripts/read-node-versions.sh default || true)}"
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
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
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
CC="$TC/aarch64-linux-android21-clang"
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

# PTY 会话宿主：静态编，理由同 ptyprobe —— 它是**常驻可执行件**不是共享库，
# 静态让它不依赖 $PREFIX/lib 的任何一件（会话是底座能力，不能因为缺库而起不来）。
# 它要 fork/execve 别的程序，所以**不能** -pie（PIE + fork/exec 有坑），
# 也不要 -shared（那是共享库的形态，会被误当库 dlopen）。
"$CC" -static -O2 -o "$J/librivospty.so" container/native/d3/pty-session.c \
  || { echo "::error title=PTY 会话宿主编译失败::纯 C 静态，失败即环境问题 —— shell.exec 与终端都依赖它"; exit 1; }
check_so "$J/librivospty.so" 1000 || exit 1

BASH_VER=5.2.15
# ftp.gnu.org 从 GitHub runner 稳定不可达（实测 connect 134s 超时，本机同样 000），
# 故按镜像顺序回退；任一源拿到即止。
# 走 fetch-pinned：它按 userland-sources.json 的 sha256 **逐字节校验**。
#
# 早先这里是裸 curl + 「拿到就算」—— 那行 `sha256: $(sha256sum …)` 只打印不比对，
# 于是四个镜像任一被替换/传输损坏都会静默通过，然后编出错的 bash。
# 而 bash 是 upstream 档、缺件硬红的底座件，它错了整个 $PREFIX 都跟着错。
#
# 版本号改从钉值表读（BASH_VER 保留作兜底）：两者不一致时判红，
# 免得「脚本说 5.2.15、表里是别的版本」而无人察觉。
BASH_VER_TABLE="$(bash scripts/fetch-pinned.sh --src-version bash 2>/dev/null || true)"
if [ -n "$BASH_VER_TABLE" ] && [ "$BASH_VER_TABLE" != "$BASH_VER" ]; then
  echo "::error title=bash 版本不一致::构建脚本写的是 $BASH_VER，钉值表是 $BASH_VER_TABLE"
  echo "             两处必须一致 —— 改一个，另一个也要跟着改。"
  exit 1
fi
echo "[bash] 取源码 $BASH_VER（sha256 由钉值表校验）"
bash_tarball="bash-${BASH_VER}.tar.gz"
if ! bash scripts/fetch-pinned.sh --pin bash "/tmp/bash.tar.gz"; then
  echo "::error title=bash 源码取不到或 sha256 不符::钉值与来源见 scripts/userland-sources.json 的 sources.bash"
  echo "             —— 所有镜像都试过了仍失败；**不要**改成不校验的下载。"
  exit 1
fi
echo "[bash] 命中钉值来源，sha256 校验通过"
# 解包单独判红：sha256 对但 tar 解不开是另一类故障（磁盘满、解压中断），
# 混在一个 if 里会让报错指错方向。
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
# 早先这里还有一组 else 分支报「bash 源码下载/解包失败」。现在下载与解包各自在
# 上游就 exit 1 了，那些分支**永不可达** —— 而文案会把排查引向「下载失败」，
# 实际故障可能在这之后。留着它比删掉更坏。

if ! command -v cargo > /dev/null 2>&1; then
  echo "runner 无 cargo，装最小 rustup"
  curl -fsSf https://sh.rustup.rs -o /tmp/rustup.sh && sh /tmp/rustup.sh -y --profile minimal --default-toolchain stable >/dev/null
  export PATH="$HOME/.cargo/bin:$PATH"
fi
rustup target add aarch64-linux-android > /dev/null 2>&1 || echo "[warn] rustup target add 失败（可能非 rustup 安装）"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN"
export CC_aarch64_linux_android="$CC" CXX_aarch64_linux_android="$CC" AR_aarch64_linux_android="$LLVM_AR"
if cargo install --locked --version 14.1.1 ripgrep --target aarch64-linux-android --root /tmp/rgbin --no-track > /tmp/rg-build.log 2>&1; then
  cp -f /tmp/rgbin/bin/rg "$J/liblobosrg.so"
else
  tail -30 /tmp/rg-build.log
fi
if ! check_so "$J/liblobosrg.so" 300000; then
  echo "::error title=必需件缺失::liblobosrg.so 未产出 —— glob/grep 依赖 $PREFIX/bin/rg，无回退路径"
  exit 1
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

set -uo pipefail
PTY_VER="1.2.0-beta.15"
NDK=""
for v in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
  [ -n "$v" ] && [ -d "$v" ] && NDK="$v" && break
done
if [ -z "$NDK" ]; then NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true); fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "::warning title=能力件缺失::找不到 NDK，跳过 node-pty"; exit 0; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TC/aarch64-linux-android24-clang"
export CXX="$TC/aarch64-linux-android24-clang++"
export AR="$TC/llvm-ar" LINK="$CXX"
[ -x "$CC" ] || { echo "::warning title=能力件缺失::找不到 clang: $CC"; exit 0; }
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
cp -f "$SO" "${GITHUB_WORKSPACE}/container/app/src/main/jniLibs/${ABI}/liblobospty.so"
echo "[ok] liblobospty.so $(stat -c%s "${GITHUB_WORKSPACE}/container/app/src/main/jniLibs/${ABI}/liblobospty.so") 字节"

cd "$ROOT"

echo "== 底座共享库（libz / libssl / libcrypto / libcurl）=="
# 放在小件 strip 之后：小件 strip 段硬编码了六个文件名，新库不在其中。
# 库自己的 strip 在 build-base-libs.sh 内做（每件 strip 完立刻自检形态）。
# 这一段在脚本末尾（清单生成之前）执行，这样：
#   · 上游任何 exec 到本脚本的回退路径都自动带上它 —— 不用在
#     ensure-native-capabilities.sh 的五处 exec 里各加一行（那五处迟早漏一处）。
CC="$CC" ABI="$ABI" bash scripts/build-base-libs.sh || {
  echo "::error title=底座共享库缺失::libz/libssl/libcrypto/libcurl 是 \$PREFIX 必备（upstream 档，缺件硬红）——"
  echo "             没有它们，curl/git 改动态链（第 2 阶段）与 busybox 的 gzip/tar（第 5 阶段）都无从谈起。"
  exit 1
}
cd "$ROOT"

echo "== busybox（基础命令集）=="
# 静态编，不链底座 libz（理由见 build-native-busybox.sh 文件头）。
# 命名 libbusybox.so 只为进 jniLibs（APK 只打包 .so）；落位名是 busybox。
CC="$CC" ABI="$ABI" bash scripts/build-native-busybox.sh || {
  echo "::error title=busybox 缺失::\$PREFIX 的 tar/gzip/grep/sed/ls/cp/mv 等基础命令依赖它（upstream 档，缺件硬红）"
  exit 1
}
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
