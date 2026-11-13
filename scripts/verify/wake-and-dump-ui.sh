#!/usr/bin/env bash

set -uo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DEVICE=""
PACKAGE="lobos.os"
ACTIVITY="lobos.os/lobos.services.app.HostControlActivity"
OUT=""
DO_WAKE=0

die() { echo "[error] $1" >&2; shift; for l in "$@"; do echo "        $l" >&2; done; exit 2; }

usage() {
  echo "用法: bash $0 [--device <序列号>] [--package <包名>] [--activity <包名/活动>] [--out <文件>] [--wake]"
  echo "  例: bash $0 --out /tmp/ui.xml"
  echo "  退出码: 0=取到树并打印目标节点；2=参数/环境错误；3=未取到（屏幕黑 / 树里没有那个节点）"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --device)   [ $# -ge 2 ] || die "--device 缺值"; DEVICE="$2"; shift 2 ;;
    --package)  [ $# -ge 2 ] || die "--package 缺值"; PACKAGE="$2"; shift 2 ;;
    --activity) [ $# -ge 2 ] || die "--activity 缺值"; ACTIVITY="$2"; shift 2 ;;
    --out)      [ $# -ge 2 ] || die "--out 缺值"; OUT="$2"; shift 2 ;;
    --wake)     DO_WAKE=1; shift ;;
    -h|--help)  usage; exit 0 ;;
    -*)
      case "$1" in
        --tap|--input|--text|--pull|--cat|--get|--write|--push|--put|--rm|--delete|--install|--uninstall)
          echo "[error] 本工具不做该操作：$1" >&2
          echo "        本工具只做一件事：唤醒屏幕、确认窗口可见、dump 出控件树（--package/--activity/--out）。" >&2
          echo "        不做 tap、不做输入、不做设备写入。新增用途请另提认可。" >&2
          exit 2 ;;
        *) echo "[error] 不认识的选项：$1" >&2; usage >&2; exit 2 ;;
      esac ;;
    *)
      echo "[error] 本工具不接受位置参数：$1" >&2
      echo "        只唤醒 + dump；不做 tap、不做 pull 任意文件、不做设备写入。" >&2
      exit 2 ;;
  esac
done

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  for c in /home/bowen/runtimes/platform-tools/adb "$(command -v adb 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { ADB="$c"; break; }
  done
fi
[ -n "$ADB" ] || die "找不到 adb" "设置 ADB=/path/to/adb 或把它放进 PATH"

DEVS="$($ADB devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}')"
[ -n "$DEVS" ] || die "没有在线设备" "adb devices 里没有 status=device 的行"

if [ -n "$DEVICE" ]; then
  printf '%s\n' "$DEVS" | grep -qx -- "$DEVICE" \
    || die "指定的设备不在线" "$DEVICE 不在 adb devices 的在线列表里"
  DEV="$DEVICE"
else
  DEV="$(printf '%s\n' "$DEVS" | head -1)"
  DEV_COUNT="$(printf '%s\n' "$DEVS" | wc -l | tr -d ' ')"
  if [ "$DEV_COUNT" -gt 1 ]; then
    echo "[warn] 多台设备在线，取第一台：$DEV" >&2
  fi
fi

echo "[dev]  $DEV"
echo "[pkg]  $PACKAGE"
echo "[act]  $ACTIVITY"
echo "[time] $(date -Is)"

WAKE_LINE="$($ADB -s "$DEV" shell "dumpsys power 2>/dev/null | grep -i mWakefulness" 2>/dev/null | tr -d '\r' | head -1)"
echo "[wake] ${WAKE_LINE:-(读不到 mWakefulness)}"

DOZING=0
case "$WAKE_LINE" in
  *Dozing*|*Asleep*|*Standby*) DOZING=1 ;;
esac

WAKE_CMDS="adb -s $DEV shell input keyevent KEYCODE_WAKEUP
adb -s $DEV shell wm dismiss-keyguard"
if [ "$DOZING" = "1" ]; then
  if [ "$DO_WAKE" = "1" ]; then
    echo "[wake] 屏幕逐字是灭的、按 --wake 逐字执行那两条唤醒命令："
    printf '%s\n' "$WAKE_CMDS" | sed 's/^/       /'

    $ADB -s "$DEV" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    $ADB -s "$DEV" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    sleep 1
    WAKE_LINE2="$($ADB -s "$DEV" shell "dumpsys power 2>/dev/null | grep -i mWakefulness" 2>/dev/null | tr -d '\r' | head -1)"
    echo "[wake] 唤醒后：${WAKE_LINE2:-(读不到 mWakefulness)}"
    case "$WAKE_LINE2" in
      *Dozing*|*Asleep*|*Standby*) DOZING=1 ;;
      *) DOZING=0 ;;
    esac
  else
    echo "[wake] 屏幕逐字是灭的、唤醒那两条命令逐字由 07 执行（设备写入只归 07）："
    printf '%s\n' "$WAKE_CMDS" | sed 's/^/       /'
    echo "[wake] 唤醒之后重跑本工具、时它逐字会拿到树。"
    exit 3
  fi
fi

VIS_LINE="$($ADB -s "$DEV" shell "dumpsys window windows 2>/dev/null | grep -i 'mCurrentFocus\|mFocusedApp'" 2>/dev/null | tr -d '\r' | head -2)"
echo "[focus] ${VIS_LINE:-(读不到焦点)}"

DUMP_OUT="$(mktemp)"
trap 'rm -f "$DUMP_OUT"' EXIT
DUMP_ERR="$($ADB -s "$DEV" shell "uiautomator dump /sdcard/uidump.xml 2>&1; echo '---'; cat /sdcard/uidump.xml 2>/dev/null" 2>/dev/null | tr -d '\r')"

if printf '%s\n' "$DUMP_ERR" | grep -q 'null root node returned by UiTestAutomationBridge'; then
  echo "[error] 设备回了这一句原文：ERROR: null root node returned by UiTestAutomationBridge" >&2
  echo "屏幕逐字是灭的、唤醒那一格**没满足**。" >&2
  echo "唤醒是**设备写入**、只归 07；两条命令逐字是：" >&2
  printf '%s\n' "$WAKE_CMDS" | sed 's/^/          /' >&2
  echo "唤醒之后**重跑**本工具、时它逐字会拿到树。" >&2
  exit 3
fi

XML="$(printf '%s\n' "$DUMP_ERR" | sed -n '/^---$/,$p' | tail -n +2)"
if [ -z "$XML" ]; then
  echo "[error] uiautomator dump 没给出控件树。" >&2
  printf '%s\n' "$DUMP_ERR" | head -5 | sed 's/^/        /' >&2
  exit 3
fi

printf '%s\n' "$XML" > "$DUMP_OUT"
echo "[dump] 控件树字节数=$(stat -c%s "$DUMP_OUT")"

if printf '%s\n' "$XML" | grep -q "$ACTIVITY"; then
  echo "[node] 那一格逐字**在**树里：$ACTIVITY"

  BOUNDS="$(printf '%s\n' "$XML" | grep -o 'resource-id="[^"]*"[^>]*bounds="\[[0-9,]*\]\[[0-9,]*\]"' | head -1)"
  if [ -n "$BOUNDS" ]; then
    echo "[bounds] $BOUNDS"
  else

    BOUNDS2="$(printf '%s\n' "$XML" | grep -o "$ACTIVITY\"[^>]*bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" | head -1)"
    echo "[bounds] ${BOUNDS2:-(树里逐字没取到 bounds —— 而那要人从那 XML 里看)}"
  fi
  if [ -n "$OUT" ]; then
    cp "$DUMP_OUT" "$OUT"
    echo "[out]  $OUT"
  fi
  exit 0
fi

echo "[error] 树取到了，但那一格逐字**不在**树里：$ACTIVITY" >&2
echo "这与「屏幕黑」是**两件**事、要分开确认（依 I-32）。" >&2
echo "先看 [focus] 那一行：焦点逐字在别处、要先把那个 Activity 叫起来。" >&2
if [ -n "$OUT" ]; then
  cp "$DUMP_OUT" "$OUT"
  echo "[out]  $OUT（树逐字留下来了、目标逐字不在里面）" >&2
fi
exit 3
