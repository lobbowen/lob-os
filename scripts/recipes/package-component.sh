#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

TOOL="${1:?需要工具名}"
# 版本号读不到就报缺，不编一个值（那个值会一路进文件名与 meta，
# 让「本轮编的」与「上一次留下的」分不出来）。范式照 :13 与 :21。
VER=$(cat "dist/${TOOL}.version" 2>/dev/null || true)
[ -n "$VER" ] || { echo "::error title=缺版本号::dist/${TOOL}.version 不在或为空 —— 不编值（这一版没有版本号就无法判断哪个包是本轮编的）；先跑产出它的 recipe"; exit 1; }
RAW="work/.component-${TOOL}-raw.zip"
mkdir -p "$(dirname "$RAW")"
ENTRY=$(bash "$ROOT_DIR/scripts/registry/read-component-entry.sh" "$TOOL")
# 两处版本号来源：钉值表（声明）与本产物那份（事实）。不一致就是发布出了问题。
PINNED=$(node -e 'const t=require(process.argv[1]);const s=(t.sources||{})[process.argv[2]]||{};process.stdout.write(String(s.version||""))' "$ROOT_DIR/scripts/component-sources.json" "$TOOL")
[ -z "$PINNED" ] || [ "$PINNED" = "$VER" ] || { echo "::error title=版本号两处不一致::钉值表 component-sources.json 写的是 $PINNED，而本产物那份写的是 $VER"; exit 1; }
[ -f "dist/$ENTRY" ] || { echo "::error title=缺件::dist/$ENTRY 不在（清单入口声明=$ENTRY），先构建"; exit 1; }
STAGE=$(mktemp -d)
cp -a dist/. "$STAGE/"
rm -f "$STAGE"/*.version "$STAGE"/SHA256SUMS $(find "$STAGE" -maxdepth 1 -name '*.zip') 2>/dev/null || true

# 件自描述：包里带全部信息，安装器只读包，不需要外部清单。
# 字段真源是 scripts/component-sources.json（gen-component-meta.js 自己读），不手写。
#
# 缺陷名（C-102）**生成器与它的调用方不配套** ——
#   而具体形态是「生成器从四参改成三参加 --payload 并**拒收**旧形态，
#   而其中一条调用方没有跟着改」。
#
# 真机读数（02，2026-10-25，同一份 payload）：
#   · 三参形态给出「npm 算不出 entry::bin/ 下 3 个文件但没有一个是程序形态」而 exit 1；
#   · 四参形态给出「entry 不再由命令行传入」而 exit 1。
#   ⇒ 而那是**两问**（按 C-135）：读数二在「门」那一格（参数个数），
#     而读数一在「形态」那一格（entry 能不能从落位结果算出来），
#     而门先响、形态那一格要把门打开才走得到。
#
# 而成因三件叠在一起（逐件有出处）：
#   · bcfdc21（2026-10-20 16:43:39）把生成器定成三位置参数加 --payload 并明确拒收四参
#     （=> 而那一份的提交正文照录，=> 而代码是 gen-component-meta.js:61 到 66）；
#   · package-bundle-zip.sh 那一路在 57d769e（16:11:04，早 32 分钟）就已改成三参加 --payload；
#   · 而**这一行至今仍是四参**，=> 而那一行的 blame 是 4fe8289（2026-10-19 17:09:03），
#     而 bcfdc21 的提交里没有 package-component.sh（=> 而 git show bcfdc21 --name-only 命中 0）。
#
# ⇒ 而它治「参数个数」那一层，⇒ 而所以那 11 个走这一条路的 workflow
#   （build-npm.yml:109、build-pnpm.yml:109、build-node.yml:131、build-git.yml:137、
#     build-python3.yml:122、build-sqlite3.yml:109、build-make.yml:109、build-cmake.yml:120、
#     build-pkg-config.yml:109、build-llvmtoolchain.yml:126、build-component.yml:87）
#   今天在打包步就红。
#
# ⇒ 而处置：这一处与 package-bundle-zip.sh 那一路**对齐**（=> 而那一路逐字是）：
#   node "$ROOT/scripts/recipes/gen-component-meta.js" \
#     "$ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
#     --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN"
# 而 **entry 不再由命令行传入**（=> 而它由生成器从落位结果算出，=> 而而那正是那一格该有的形态）。
# 而构建标识那两格照 package-bundle-zip.sh 那一路取（=> 而 GITHUB_SHA / GITHUB_RUN_ID，
#   => 而本地没有时是 "local"，=> 而而那正是「占位不能是空」那一格）。
BUILD_SHA="${GITHUB_SHA:-local}"
BUILD_RUN="${GITHUB_RUN_ID:-local}"
node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" \
  "$TOOL" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
  --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN" \
  || { echo "::error title=$TOOL 说明生成失败"; exit 1; }

find "$STAGE" -exec touch -h -t 198001010000.00 {} +
echo "[package] 打包（zip）：component-meta.json + bin + 其它 prefix 目录"
( cd "$STAGE" && zip -q -r -X -y "$ROOT_DIR/$RAW" . )
rm -rf "$STAGE"
SHA=$(sha256sum "$RAW" | cut -c1-12)
ZIP="dist/component-${TOOL}-${VER}-${SHA}-android-arm64.zip"
mv "$RAW" "$ZIP"
NAME=$(basename "$ZIP")
cd dist
sha256sum "$NAME" >> SHA256SUMS
cd ..
echo "[ok] $ZIP"
sha256sum "$ZIP"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  SUM=$(sha256sum "$ZIP")
  { echo '### component 产物 sha256（C 的清单就钉这个值）'; echo '```'; echo "$SUM"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
