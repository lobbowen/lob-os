#!/usr/bin/env bash
# 判一个包的实际格式 —— **看内容，不看文件名**。
#
# 为什么不能看文件名：
#   Release 上的资产名沿用旧的 <id>-<deps>+…tar.gz，
#   而里面的内容已经是 zip（package-piece-zip.sh 改成了 zip）。
#   按扩展名分派会对 zip 内容走 tar 分支，解不开，判据一律拒绝。
#   —— 这个问题我踩过一次：资产名与内容格式不一致时，扩展名是骗人的。
#
# 用法：. fmt.sh <文件>   → 打印 zip | tar | gzip | tar.gz | 未知
fmt_of() {
  local f="$1" m
  [ -s "$f" ] || { echo "空文件"; return 1; }
  m="$(head -c 4 "$f" 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')"
  case "$m" in
    504b*)                                            echo "zip"; return 0 ;;
    1f8b*)                                            echo "gzip"; return 0 ;;
    *) ;;
  esac
  # 不是 zip/gzip 魔数 —— 试着当 tar 读
  if tar tf "$f" >/dev/null 2>&1; then echo "tar"; return 0; fi
  echo "未知"; return 1
}
