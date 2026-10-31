#!/usr/bin/env bash
# 族七 · 容器层取证脚本。
#
# 它**只取证**，不判别「哪一层才算那一颗件」
# （=> 而那是 01 裁的 S607-01-CONTAINER-FORM-RULING.md 那一格、=> 而本脚本**不**拍板）。
#
# 判别源**唯一**：scripts/toolchain/fmt.sh（=> 而按 C-994「判别源必须唯一」、
# => 而其余各处改成调它、=> 而本脚本**不**另写第二套 MAGIC= 判别）。
#
# 逐件打：
#   头四字节（十六进制）
#   外层中央目录条目数
#   内层条目名
#   内层 usize
#
# 一脚本两用：既能读本地件包，也能读 CI 下的件包（=> 而那 CI 下的那一份
# 逐字是一个**外层容器**（=> 而那里面逐字装着那**内层**那一个件包）。
#
# 用法：
#   bash scripts/verify/probe-container-form.sh <件包路径> [更多路径…]
#   bash scripts/verify/probe-container-form.sh --dir <目录>   # 扫那目录下全部件包
#
# 而「整读」逐字**不做**（=> 而那 8.4 GB 那一颗整读逐字 ERR_FS_FILE_TOO_LARGE、
# => 而那 Release 直链逐字约 3 KB/s 且必断）=> 而它逐字**只读头四字节与尾部**。
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
FMT_SH="$ROOT_DIR/scripts/toolchain/fmt.sh"
[ -f "$FMT_SH" ] || { echo "::error title=没有判别源::$FMT_SH 不在"; exit 1; }
# shellcheck source=../toolchain/fmt.sh
. "$FMT_SH"

WORK=""
cleanup() { [ -n "$WORK" ] && rm -rf "$WORK"; }
trap cleanup EXIT

probe_one() {
  local f="$1"
  local magic fmt entries inner n_entries
  echo "== $f =="
  [ -s "$f" ] || { echo "  空文件（或读不到）"; return 1; }
  magic="$(fmt_magic "$f")"
  fmt="$(fmt_of "$f")"
  echo "  头四字节=$magic"
  echo "  判别源给的形态=$fmt"
  case "$fmt" in
    zip|zip64)
      entries="$(fmt_zip_entries "$f" 2>/dev/null)"
      if [ -z "$entries" ]; then
        echo "  外层中央目录条目数=读不到"
      else
        n_entries="$(printf '%s\n' "$entries" | grep -c . || true)"
        echo "  外层中央目录条目数=$n_entries"
        printf '%s\n' "$entries" | while IFS="$(printf '\t')" read -r nm us cs lo; do
          [ -n "$nm" ] || continue
          echo "    外层条目=$nm usize=$us csize=$cs localOffset=$lo"
        done
      fi ;;
    gzip)
      #  => 而那 gzip 逐字是「外层是 tar.gz、而那里面逐字装着那内层那一个件包」那个形状
      #     => 而而那内层逐字是**什么**逐字是取证要答的那一格、=> 而不是判据。
      WORK="$(mktemp -d)"
      if tar tzf "$f" >/dev/null 2>&1; then
        echo "  外层=tar.gz"
        tar tzf "$f" 2>/dev/null | while IFS= read -r nm; do
          [ -n "$nm" ] || continue
          echo "    外层条目=$nm"
        done
      else
        echo "  外层=gzip（但 tar tzf 读不通）"
      fi
      inner="$(fmt_inner_zip "$f" "$WORK/unpacked")"
      if [ -n "$inner" ]; then
        echo "  内层路径=$inner"
        echo "  内层头四字节=$(fmt_magic "$inner")"
        echo "  内层形态=$(fmt_of "$inner")"
        fmt_zip_entries "$inner" 2>/dev/null | while IFS="$(printf '\t')" read -r nm us cs lo; do
          [ -n "$nm" ] || continue
          echo "    内层条目=$nm 内层usize=$us"
        done
      else
        echo "  内层=没找到件包"
      fi ;;
    tar)
      echo "  外层=tar"
      tar tf "$f" 2>/dev/null | while IFS= read -r nm; do
        [ -n "$nm" ] || continue
        echo "    外层条目=$nm"
      done
      ;;
    *)
      echo "  外层=不是容器（那这一份逐字就是那件包本身）" ;;
  esac
  echo ""
  return 0
}

MODE="files"
if [ "${1:-}" = "--dir" ]; then
  MODE="dir"
  DIR="${2:?--dir 要给一个目录}"
  shift 2
  while IFS= read -r f; do probe_one "$f"; done < <(find "$DIR" -maxdepth 2 -type f \( -name '*.zip' -o -name '*.tar.gz' -o -name '*.tgz' \) | sort)
  exit 0
fi

[ "$#" -ge 1 ] || { echo "用法: $0 <件包路径> [更多路径…] | --dir <目录>"; exit 2; }
for f in "$@"; do probe_one "$f"; done
