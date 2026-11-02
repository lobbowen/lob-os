#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

TOOL="${1:?需要工具名}"
# 版本号读不到就报缺，不编一个值（那个值会一路进文件名与 meta，
# 让「本轮编的」与「上一次留下的」分不出来）。范式照 :13 与 :21。
VER=$(cat "dist/${TOOL}.version" 2>/dev/null || true)
#  缺陷名（C-102）**那一格逐字把那版本号逐字**吊**在那编译产出上、=> 而而那那编译那一档逐字被跳过了它逐字就**没有**了** ——
#    => 而那旧版逐字是 `[ -n "$VER" ] || { echo "::error title=缺版本号::…"; exit 1; }`
#      => 而那 `dist/${TOOL}.version` 逐字**只有**那编译那一个 recipe 逐字**写**
#        （=> 而那 python 那一族逐字是 `build-component-python3.sh:200` 逐字
#          `printf '%s' "$SRC_VER" > "$OUT/python.version"`、
#          => 而那 sqlite 那一族逐字是 `build-component-sqlite3.sh:18` 逐字
#          `--version-file "$ROOT_DIR/$OUT/sqlite.version"`、
#          => 而那 pkgconf 那一族逐字是 `build-component-pkg-config.sh:107` 逐字
#          `printf '%s' "$SRC_VER" > "$OUT/pkgconf.version"`）
#      => 而那**编译**那一档逐字带 `if: steps.reuse.outputs.hit != 'yes'`
#        （=> 而那 python 那一族逐字是 `build-python3.yml:122`、=> 而那 sqlite 那一族逐字是
#          `build-sqlite3.yml:109`、=> 而那 pkgconf 那一族逐字是 `build-pkg-config.yml:109`）
#        => 而那**复用命中**了（=> 而那预制品逐字**已经**逐字在、=> 而那编译逐字**跳过**）
#        => 而那 `dist/${TOOL}.version` 逐字**从来没被写出来**
#      => 而那**后果**（06 本机实读那三份 job log 逐字顺序、Lead 本机实读仓内逐字复核成立）：
#        · 那 python 那一趟逐字 `##[error]dist/python.version 不在或为空 —— 不编值 …`
#          （=> 而那逐字时刻 07:45:03.743、=> 而那二进制逐字**早**就做出来了
#            （=> 而那逐字 `07:45:03.705` 逐字 `[ok] python 入口 25672408 字节 形状=elf 入口=bin/python3`））
#        · 那 sqlite 那一趟逐字 `dist/sqlite.version 不在或为空`、那 pkgconf 那一趟逐字
#          `dist/pkgconf.version 不在或为空`
#      => 而那**缓存**那一句逐字**不是**那成因（=> 而那逐字 `07:45:05.505` 逐字晚 1.8 秒、
#        => 而那**真正**的红逐字在 `:03.743`）=> 而那两族逐字**不并**、依 **C-104**
#    => 而那**判据**逐字是「那版本号逐字是不是那件自己的版本」那一个事实上
#      （=> 而那**版本号**逐字**不**是「本轮编的」那个事实、=> 而它是那**件**自己的版本）
#    => 而那**处置**：=> 而那版本号逐字**不靠**那编译产出
#      => 而那它逐字**从**那钉值表逐字**取**（=> 而那 `fetch-pinned.sh --src-version` 逐字**读**的
#        就是那 `scripts/component-sources.json` 那一族）
#      => 而那**钉值表**逐字与那本产物那份逐字**本来就**要**对上**（=> 而和那下面那一格逐字**已经在**核它）
#        => 而那**两处一致**逐字**是**那一个事实、=> 而那它逐字**不**是那「本轮编的」那一族
#      => 而那**钉值表**逐字**没有**时逐字**照旧**逐字报缺（=> 而按 C-05 不猜填）
if [ -z "$VER" ]; then
  echo "::notice:: 那 dist/${TOOL}.version 不在（=> 而那**复用**命中了、=> 而那编译逐字被跳过了）"
  echo "::notice:: => 而那版本号逐字**改从**那钉值表逐字取（=> 而它是那件自己的版本、=> 而**不**是「本轮编的」那个事实）"
  VER="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --src-version "$TOOL" 2>/dev/null || true)"
  if [ -n "$VER" ]; then
    printf '%s' "$VER" > "dist/${TOOL}.version"
    echo "::notice:: => 而那版本号逐字 = $VER（=> 而那从那钉值表逐字取到、=> 而那它逐字**写回**了那一份）"
  fi
fi
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
#   => 而那是**两问**（按 C-135）：读数二在「门」那一格（参数个数），
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
# => 而它治「参数个数」那一层，=> 而所以那 11 个走这一条路的 workflow
#   （build-npm.yml:109、build-pnpm.yml:109、build-node.yml:131、build-git.yml:137、
#     build-python3.yml:122、build-sqlite3.yml:109、build-make.yml:109、build-cmake.yml:120、
#     build-pkg-config.yml:109、build-llvmtoolchain.yml:126、build-component.yml:87）
#   今天在打包步就红。
#
# => 而处置：这一处与 package-bundle-zip.sh 那一路**对齐**（=> 而那一路逐字是）：
#   node "$ROOT/scripts/recipes/gen-component-meta.js" \
#     "$ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
#     --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN"
# 而 **entry 不再由命令行传入**（=> 而它由生成器从落位结果算出，=> 而那正是那一格该有的形态）。
# 而构建标识那两格照 package-bundle-zip.sh 那一路取（=> 而 GITHUB_SHA / GITHUB_RUN_ID，
#   => 而本地没有时是 "local"，=> 而那正是「占位不能是空」那一格）。
BUILD_SHA="${GITHUB_SHA:-local}"
BUILD_RUN="${GITHUB_RUN_ID:-local}"
node "$ROOT_DIR/scripts/recipes/gen-component-meta.js" \
  "$TOOL" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
  --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN" \
  || { echo "::error title=$TOOL 说明生成失败"; exit 1; }

#  缺陷名（C-102）**那形态**逐字**只**在**那 bundle** **那一路**写**、=> 而那**组件** **这一路**没**写**。
#    => 而那**成因**：=> 而那 elf_write_shape 逐字**只**被**那 package-bundle-zip.sh **调**（=> 而那一行逐字 = 那
#      「elf_write_shape "$ID" "$ENTRY" "$STAGE" "$STAGE/piece-shape.json" "$VER" bin lib」）、=> 而那**组件** **这一路**
#      （=> 而那 git / npm / node / python3 / sqlite3 / make / cmake / pkg-config / llvmtoolchain 那**几**件**走**它**）
#      逐字**没有**那**一处**、=> 而那**它**们**逐字**是**那**两**件**事**：「那**形态**是**什么**」与「那**它**在**哪**一条**路**上**写**」）。
#    => 而那**后果**逐字 = 而那**组件** **那些**件**逐字**包里没有 piece-shape.json**、=> 而那**它**们**逐字**是**那**两**件**事**：
#      「那**安装**逐字**报缺**」与「那**它**们**逐字**装不上**」）、=> 而那 **git** **那一件**逐字**就**是**那**败**的**那一个**。
#    => 而那**处置**：=> 而这**这一路**逐字**也**写**那**同一**份**形态**（=> 而那**它**们**逐字是**那**两**件**事**：「那**写**的**是**什么**」
#      与「那**它**在**哪**一处**」）、=> 而那 entry 逐字**用**那**已经**算**好**的**那一个**（=> 而那 :15 那一行）。
source "$ROOT_DIR/scripts/toolchain/elf-form.sh"
elf_write_shape "$TOOL" "$ENTRY" "$STAGE" "$STAGE/piece-shape.json" "$VER" bin lib \
  || { echo "::error title=$TOOL 形态算不出来::构建期必须能判形态（readelf 缺失）"; exit 1; }

find "$STAGE" -exec touch -h -t 198001010000.00 {} +
echo "[package] 打包（zip）：component-meta.json + bin + 其它 prefix 目录"
( cd "$STAGE" && zip -q -r -X -y "$ROOT_DIR/$RAW" . )
rm -rf "$STAGE"
SHA=$(sha256sum "$RAW" | cut -c1-12)
#  缺陷名（C-102）**那产物名里那一个目标段逐字是写死的那一档、=> 而而那它逐字**没有**那声明侧** ——
#    => 而那旧版逐字是 `ZIP="dist/component-${TOOL}-${VER}-${SHA}-android-arm64.zip"`
#      （=> 而那目标段逐字是 `android-arm64`、=> 而那 PC 侧那一个逐字**还没有**）
#    => 而那**那格乙**逐字要机械判「那产物名那目标段与那声明**相等**」
#      => 而那声明逐字是那「PC 侧那一个目标三元组」那一份（=> 而那它是那**声明侧**）
#    => 而那**默认**逐字**不变**（=> 而那**现有**那几件逐字**照**那 `android-arm64` 走）
#      => 而那**PC 侧**逐字**要**换时逐字**显式**给那 `TARGET_SEGMENT`
TARGET_SEGMENT="${TARGET_SEGMENT:-android-arm64}"
#    => 而那**声明侧**逐字**给**了时逐字**核**那产物名那目标段与那声明**相等**
#      （=> 而那不等判红并报出**哪一档**不等、=> 而那按 C-05 报缺、=> 而那**不**猜）
if [ -n "${PC_TARGET_SEGMENT:-}" ]; then
  _decl_seg="$(bash "$ROOT_DIR/scripts/toolchain/pc-target.sh" product-segment 2>/dev/null || true)"
  if [ -n "$_decl_seg" ] && [ "$_decl_seg" != "$TARGET_SEGMENT" ]; then
    echo "::error title=那产物名那目标段与那声明不等::产物名那目标段=$TARGET_SEGMENT、=> 而那声明=$_decl_seg"
    echo "::error::  => 而那一档逐字是「承载形态」、=> 而那按 C-05 报缺、=> 而那**不**猜"
    exit 1
  fi
fi
ZIP="dist/component-${TOOL}-${VER}-${SHA}-${TARGET_SEGMENT}.zip"
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
