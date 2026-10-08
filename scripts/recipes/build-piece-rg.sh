#!/usr/bin/env bash
# 编 ripgrep —— glob/grep 工具，编成 liblobosrg.so 落 $PREFIX/bin/rg。
#
# 它走 cargo（Rust），不是 tarball —— 所以钉值表里那一项没有 urls，只有 version。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" rg

if ! command -v cargo >/dev/null 2>&1; then
  echo "[rg] runner 无 cargo，装最小 rustup"
  curl -fsSf https://sh.rustup.rs -o /tmp/rustup.sh && sh /tmp/rustup.sh -y --profile minimal >/dev/null 2>&1
  export PATH="$HOME/.cargo/bin:$PATH"
fi
rustup target add aarch64-linux-android >/dev/null 2>&1 \
  || echo "[warn] rustup target add 失败（可能已装）"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN"
export CC_aarch64_linux_android="$CC"
export CXX_aarch64_linux_android="$CC"
export AR_aarch64_linux_android="$LLVM_AR"

# 版本从钉值表读，不在脚本里写第二份
RG_VER="$(node -e '
  const t = require(process.argv[1]);
  const s = (t.sources || {}).ripgrep;
  if (!s) { console.error("钉值表里没有 ripgrep 这一项"); process.exit(1); }
  if (!s.version) { console.error("钉值表里的 ripgrep 没有 version"); process.exit(1); }
  if (s.urls) { console.error("ripgrep 不该有 urls —— 它走 cargo，没有 tarball"); process.exit(1); }
  process.stdout.write(String(s.version));
' "$ROOT_DIR/scripts/component-sources.json")" || die "取不到 ripgrep 版本" "看 scripts/component-sources.json 的 sources.ripgrep"

rm -rf /tmp/rgbin
cargo install --locked --version "$RG_VER" ripgrep --target aarch64-linux-android --root /tmp/rgbin \
  > /tmp/rg-build.log 2>&1 \
  || { tail -30 /tmp/rg-build.log >&2; die "ripgrep 编译失败" "日志 /tmp/rg-build.log"; }

SO="$JNI/liblobosrg.so"
cp -f /tmp/rgbin/bin/rg "$SO"
check_so "$SO" 300000 || die "必需件缺失" "liblobosrg.so 未产出 —— glob/grep 依赖 \$PREFIX/bin/rg，无回退路径"

# 字节身份与钉值表比对（钉表里记的是 crates.io 发布物的 sha256）
RG_GOT="$(sha256sum "$SO" | cut -d' ' -f1)"
RG_WANT="$(node -e '
  const t = require(process.argv[1]);
  process.stdout.write(String((((t.sources || {}).ripgrep) || {}).sha256 || ""));
' "$ROOT_DIR/scripts/component-sources.json")"
if [ -z "$RG_WANT" ]; then
  echo "[warn] 钉值表里没有 ripgrep 的 sha256 —— 首次构建时建立基线（$RG_GOT）"
else
  [ "$RG_GOT" = "$RG_WANT" ] || die "ripgrep 字节与钉值表不符" "表里 $RG_WANT / 实际 $RG_GOT"
  echo "[ok] ripgrep 字节与钉值表一致"
fi
echo "[ok] rg → $SO $(wc -c < "$SO") 字节"