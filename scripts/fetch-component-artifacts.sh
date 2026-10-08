#!/usr/bin/env bash
# 把每一件最近一次「产物还在」的构建 run 的 zip 收进 dist/，供清单汇总投影。
#
# 为什么要逐件查 run：
#   各件已拆成独立构建链（build-<件>.yml），产物留在**各自 run 的 artifact** 里。
#   actions/download-artifact 不带 run-id 时只认同一个 run，拆链后取不到任何件。
#
# 为什么不用 Release 资产：
#   Release 步跑在 package-component.sh **之前**（tar 里装的是松散文件，不是 zip），
#   而清单按内容寻址命名 sha256，逐字节对不上就会把设备指向一批下不着的字节。
#   artifact 里是真 zip，且就是那次 run 实际发到七牛的同一批字节。
#
# 真相源是 GitHub（各件独立链的 run 与其 Release 预制品），七牛只在最后一步作为发布目标。
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/.." && pwd)"
cd "$ROOT_DIR"

REPO="${REPO:-${GITHUB_REPOSITORY:-}}"
[ -n "$REPO" ] || { echo "::error title=不知道仓库::设 REPO 或 GITHUB_REPOSITORY"; exit 1; }
[ -n "${GH_TOKEN:-}" ] || [ -n "${GITHUB_TOKEN:-}" ] || {
  echo "::error title=缺令牌::查 run / 下 artifact 都要令牌"; exit 1; }
export GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"

DIST="${DIST:-dist}"
if [ "$#" -gt 0 ]; then
  echo "::error title=不认的入参::这个脚本没有入参（收到: $*）—— 取件口径由 bucket_for 决定"
  exit 2
fi

mkdir -p "$DIST"

# 三筐的件名，以 scripts/cache-key.sh 的 bucket_for 为唯一真相
COMPONENTS="$(node scripts/list-bucket-components.js)"
[ -n "${COMPONENTS// /}" ] || { echo "::error title=件清单为空::bucket_for 没解析出任何件"; exit 1; }

# 随 APK 打包的原生件不走商店分发（产物在 build-apk 的 jniLibs 里）
NATIVE="bash rg busybox"

got=0; missed=0; skipped=0
for t in $COMPONENTS; do
  case " $NATIVE " in
    *" $t "*) echo -e "$t\tskipped-native\t随 APK 打包，不进商店清单"; skipped=$((skipped+1)); continue ;;
  esac

  WF="build-$t.yml"
  ART="component-$t"

  # 往回找几轮：最近那次成功 run 的 artifact 可能已过保留期（默认 30 天）
  picked=""
  for rid in $(gh run list --repo "$REPO" --workflow "$WF" --status success \
               --limit 5 --json databaseId --jq '.[].databaseId'); do
    # 该 run 里这个件的 artifact 还在吗（未过期）
    live="$(gh api "repos/$REPO/actions/runs/$rid/artifacts?per_page=100" \
            --jq "[.artifacts[] | select(.name == \"$ART\" and .expired_at == null)][0].name" 2>/dev/null || true)"
    if [ "$live" = "$ART" ]; then picked="$rid"; break; fi
  done

  if [ -z "$picked" ]; then
    echo "::warning title=$t 没有可取的件::$WF 还没有成功 run，或其 artifact 已过保留期 —— 本次清单不含这件"
    echo -e "$t\tmissing\t没有产物活着的成功 run（$WF / $ART）"
    missed=$((missed+1))
    continue
  fi

  if ! gh run download "$picked" --repo "$REPO" --name "$ART" --dir "$DIST" >/dev/null; then
    echo "::warning title=$t 取件失败::run $picked 的 $ART 没能下下来（可能被并发清理），本次清单不含这件"
    echo -e "$t\tmissing\trun $picked 下载失败"
    missed=$((missed+1))
    continue
  fi

  # 一次 run 只能给出这个件的一颗 zip。出现多颗说明那份 artifact 里混了别的东西
  # （旧缓存的 zip 没清干净是最常见的一种），下游会把它当成两颗件投进清单。
  mapfile -t zips < <(compgen -G "$DIST/component-$t-*-android-arm64.zip" || true)
  if [ "${#zips[@]}" -eq 0 ]; then
    echo "::warning title=$t 产物不合契约::run $picked 的 $ART 里没有 component-$t-<版>-<哈希>-android-arm64.zip"
    echo -e "$t\tmissing\tartifact 里没有符合命名契约的 zip"
    # 收下来的不合契约文件必须清掉：投影脚本会扫整个 dist/，
    # 留在这里就等于把一个会被当成件的脏文件递给下游。
    rm -f "$DIST/component-$t-"*.zip 2>/dev/null || true
    missed=$((missed+1))
    continue
  fi
  if [ "${#zips[@]}" -gt 1 ]; then
    echo "::error title=$t 一次 run 给出多颗件::$ART 里有 ${#zips[@]} 颗 ${t} 的 zip:"
    for z in "${zips[@]}"; do echo "::error::  $(basename "$z")"; done
    echo "::error::逐件判断它们哪颗是本轮编的 —— 那份 artifact 不该收进清单"
    exit 1
  fi

  echo -e "$t\trun\t$picked"
  got=$((got+1))
done

echo "---- 取件汇总：取到 $got 件 · 缺 $missed 件 · 原生跳过 $skipped 件"
if [ "$got" -eq 0 ]; then
  echo "::error title=一件也没取到::清单投影会把「没有件」和「清单为空」混为一谈，这里先停"
  exit 1
fi