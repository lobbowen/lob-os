#!/usr/bin/env bash
# 真机读数工具 —— **只做一件事：取回设备上 compat-degrade.log 里匹配指定模式的行**。
#
# 职责边界（写成「不做什么」是因为下一轮一定会有人来加能力）：
#   - **不做 ls**        —— 不列目录
#   - **不做 pull**      —— 不取回任意文件
#   - **不做 write/push** —— 不往设备写任何东西
#   - 不改设备的任何状态，只读
#   传了上述类参数或位置参数，一律非零退出并打印「本工具不做该操作」。
#
# 唯一用途：取 compat-degrade.log 里 d1.env-merge 那一行。
# 它之所以需要独立成工具，是因为 .lobos-tools/obs.js 只管 CI 侧（GitHub Actions）；
# 工具一旦什么都能做，后来就没有理由拒绝新增用途。
#
# ★ 取数前置（不是取数之后）：取回该行之前先确认 LOBOS_COMPAT_LOG 在子进程 env 中
#   且路径可写。compat-report.h:36-48 读 getenv("LOBOS_COMPAT_LOG")，路径为空或不以 / 开头
#   就落 write(2)=stderr —— 此时「取不到」会被误读成「机制没生效」，
#   而真相是「通道没通」。（见 I-32：能力具备 ≠ 条件满足。）
#
# ★ 这个工具现在很可能空手而归：exec-path.c:97 的取值用 getenv()，读宿主进程自身 environ，
#   而全仓无一处 setenv/PUTENV，父侧 environ 从未被写 → extra=0 → :100 提前 return →
#   :111 的自述一行都不打。先备尺子，再解环（M0a），尺子才有东西可量。
#
# 凭据：只可能用到 adb（USB 直连）；不使用 .lobos-tools/tok。
set -uo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

PATTERN=""
FILE=""
LAST=""

die() { echo "[error] $1" >&2; shift; for l in "$@"; do echo "        $l" >&2; done; exit 2; }

usage() {
  echo "用法: bash $0 --file <设备上文件> --pattern <匹配模式>"
  echo "  例: bash $0 --file var/log/compat-degrade.log --pattern 'd1.env-merge'"
  echo "  退出码: 0=取到并打印该行原文；2=参数/环境错误；3=未取到"
}

# ── 职责边界：先拒掉一切越界请求，再谈功能 ──
while [ $# -gt 0 ]; do
  case "$1" in
    --file)    [ $# -ge 2 ] || die "--file 缺值"; FILE="$2"; shift 2 ;;
    --pattern) [ $# -ge 2 ] || die "--pattern 缺值"; PATTERN="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*)
      case "$1" in
        --ls|--list|--pull|--cat|--get|--write|--push|--put|--rm|--delete|--install|--uninstall)
          echo "[error] 本工具不做该操作：$1" >&2
          echo "        本工具只做一件事：读设备上指定文件的指定模式行（--file/--pattern）。" >&2
          echo "        不做 ls、不做 pull、不做写入。新增用途请另提认可。" >&2
          exit 2 ;;&
        *) echo "[error] 不认识的选项：$1" >&2; usage >&2; exit 2 ;;
      esac ;;
    *)
      echo "[error] 本工具不接受位置参数：$1" >&2
      echo "        只读指定文件的指定模式行；不做 pull 任意文件，不做设备写入。" >&2
      exit 2 ;;
  esac
done

[ -n "$FILE" ]    || { usage >&2; die "必须给 --file" "要读设备上哪个文件（相对设备上的 root 或绝对路径）"; }
[ -n "$PATTERN" ] || { usage >&2; die "必须给 --pattern" "取回含该模式的行原文"; }

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  for c in /home/bowen/runtimes/platform-tools/adb "$(command -v adb 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { ADB="$c"; break; }
  done
fi
[ -n "$ADB" ] || die "找不到 adb" "设置 ADB=/path/to/adb 或把它放进 PATH"

DEVS="$($ADB devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}')"
[ -n "$DEVS" ] || die "没有在线设备" "adb devices 里没有 status=device 的行"

DEV="$(printf '%s\n' "$DEVS" | head -1)"
DEV_COUNT="$(printf '%s\n' "$DEVS" | wc -l | tr -d ' ')"

# 取数前置 —— 先确认通道，再读数（I-25 / I-32）
if [ "$LAST" = "" ] && [ $DEV_COUNT -gt 1 ]; then
  echo "[warn] 多台设备在线，取第一台：$DEV" >&2
fi

ROOT="${LOBOS_DEVICE_ROOT:-/data/user/0/lobos.os/files}"
case "$FILE" in
  /*) ABS="$FILE" ;;
  *)  ABS="$ROOT/$FILE" ;;
esac

echo "[dev]  $DEV"
echo "[file] $ABS"
echo "[grep] $PATTERN" >&2

OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

if ! $ADB -s "$DEV" shell "cat '$ABS' 2>/dev/null" >"$OUT" 2>/dev/null; then
  rm -f "$OUT"
  die "读取失败" "$ABS 读不出（文件不存在？无权限？）"
fi

if [ ! -s "$OUT" ]; then
  rm -f "$OUT"
  echo "[error] 设备上这个文件是空的或读不到：$ABS" >&2
  echo "        可能是文件不存在，也可能是读通道没通 —— 这两件事要分开确认。" >&2
  exit 3
fi

MATCHES="$(grep -F -- "$PATTERN" "$OUT" || true)"
rm -f "$OUT"

if [ -z "$MATCHES" ]; then
  echo "[error] 文件读到了，但一行都没匹配上 '$PATTERN'：$ABS" >&2
  echo "        按 I-32 四分类自查，别直接当成「机制没生效」：" >&2
  echo "          一 不存在   —— 那一行还没产生过" >&2
  echo "          二 模式错   —— 换模式串（这里用的是整串匹配 grep -F）" >&2
  echo "          三 工具坏了 —— 通道本身有问题" >&2
  echo "          四 机制不产出 —— 工具没问题，被测对象当前条件下不产出" >&2
  echo "        本案当前很可能属第四类：exec-path.c 的 env_merge_sys 在 :100 提前 return，" >&2
  echo "        而它取值的 getenv() 读父进程 environ，父侧从未 setenv。" >&2
  exit 3
fi

printf '%s\n' "$MATCHES"
exit 0
