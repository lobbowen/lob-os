#!/usr/bin/env bash
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="$(cd "$REPO/.." && pwd)"
PKG="${LOBE_ISO_PKG:-lobos.app.verify}"
DIR=/data/user/0/$PKG/files
APK="${LOBO_APK:-$WORK/ss-apk/app-debug.apk}"

echo "══ 0. 用法 ══"
echo "  bash run-verify.sh              装 + 启 + 等供给 + 打结果"
echo "  bash run-verify.sh --no-install  只看结果（已经装过了）"
echo "  前置：供给服务在设备本机跑着（ppid=1 脱离会话），包在 \$LOBO_APK。"
echo "  用法与判据见 components/SELF-SIGNED-VERIFY.md 与 VERIFY-ON-DEVICE.md。"
echo

echo "══ 1. 供给服务在吗 ══"
BASEURL=$(node -e '
  const fs=require("fs");
  try {
    const j=JSON.parse(fs.readFileSync((process.env.LOBO_BUNDLE_MANIFEST || "/data/user/0/lobos.app/files/work/ss-bundle/dist/component-manifest-2.json"),"utf8"));
    process.stdout.write(((j.tools||[])[0]||{}).url||"" );
  } catch (e) { process.stdout.write(""); }
' 2>/dev/null)
HOSTPORT=$(printf '%s' "$BASEURL" | sed -n 's|^[a-z]*://\([^/]*\)/.*$|\1|p')
[ -n "$HOSTPORT" ] || { echo "  [fail] 读不到自签清单的 baseUrl"; exit 1; }
if curl -s -o /dev/null --max-time 8 "http://$HOSTPORT/component-canary/component-manifest-2.json"; then
  echo "  [ok] http://$HOSTPORT 回 200"
else
  echo "  [fail] http://$HOSTPORT 不通 —— 供给服务没起？"
  echo "         node tools/serve-supply.js ../ss-bundle/dist 8120 --channel canary --presigned ../ss-bundle/dist/component-manifest-2.json"
  exit 1
fi

HAVE_ADB=0
command -v adb >/dev/null 2>&1 && HAVE_ADB=1

if [ "$HAVE_ADB" = "1" ] && [ "${1:-}" != "--no-install" ]; then
  echo "══ 2. 装包 ══"
  [ -f "$APK" ] || { echo "  [fail] 没有 $APK"; exit 1; }
  adb install -r "$APK" || { echo "  [fail] adb install 失败"; exit 1; }
  adb shell pm list packages | grep -q "^package:$PKG$" \
    && echo "  [ok] $PKG 已装（与开发包 lobos.app 并存）" \
    || echo "  [warn] 没在 pm list 里看到 $PKG"
  echo "  装着的包："
  adb shell pm list packages 2>/dev/null | grep -i lobos | sed 's/^/    /'
fi

if [ "$HAVE_ADB" = "1" ]; then
  echo "══ 3. 启动（走 LAUNCHER，别写组件名）══"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "  [ok] 已发起启动"
  echo "══ 4. 等供给（88MB 走 loopback；瓶颈是解包落位，给 1~2 分钟）══"
  SEEN=0
  for i in $(seq 1 30); do
    sleep 10
    N=$(adb shell "cat $DIR/os/diag.jsonl 2>/dev/null | grep -c '\"stage\":\"supply\"'" 2>/dev/null | tr -d '\r\n ')
    case "${N:-0}" in ''|*[!0-9]*) N=0 ;; esac
    if [ "$N" -gt 0 ]; then
      echo "  供给链已跑（$((i*10)) 秒，共 $N 条 supply 记录）"
      SEEN=1
      break
    fi
    printf '.'
  done
  [ "$SEEN" = 1 ] || echo "  [warn] 5 分钟内没等到 supply 记录 —— 查应用是否真的起来了（pm list packages | grep $PKG）"
  echo
else
  echo "══ 2/3/4. 这台机器上没有 adb ══"
  echo "  装包与启动请在有 adb 的机器上做："
  echo "    adb install -r $APK"
  echo "    adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1"
  echo "  下面的读取不需要 adb（shell 与 app 同 uid，直接读绝对路径）——"
  echo "  如果包已经装上并启动过，现在就能看到结果。"
fi

echo "══ 5. 结果（直接读，不经 adb）══"
if [ ! -d "$DIR" ]; then
  echo "  $DIR 还不存在 —— 包没装上。"
  exit 1
fi
DIAG=$DIR/os/diag.jsonl
JOURNAL=$DIR/os/journal/events.jsonl
TOOLCHAIN=$DIR/usr/lib/toolchain

echo "-- supply（商店供给对账）--"
grep supply "$DIAG" 2>/dev/null | tail -3 || echo "  （还没有 —— 应用刚启动或供给还在跑）"
echo "-- prefix / version --"
grep -E '"stage":"(prefix|version)"' "$DIAG" 2>/dev/null | tail -3
echo "-- 目录刷新（在 journal 里；老 APK 不写它）--"
grep catalog "$JOURNAL" 2>/dev/null | tail -2
echo "-- 装出来的件 --"
ls "$TOOLCHAIN" 2>/dev/null | tr '\n' ' '
echo
echo "-- node 能不能跑（最终判据）--"
"$DIR/usr/bin/node" -v 2>/dev/null || echo "  （还没装好）"
