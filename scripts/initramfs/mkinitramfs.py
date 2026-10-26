#!/usr/bin/env python3
# 把 initramfs 那一层装成内核认的那一个格式。
#
# 缺陷名（C-102）**那仓内逐字没有那 initramfs 那一层的装配脚本**
#    => 而而它治**的**是「那 initramfs 那一层逐字**装配**得**成不成**」。
#    => 而那**成因**：=> 而那仓内逐字**没有**那 cpio **那一层**与那 /init **那一层**的**装配**。
#    => 而那**处置**：=> 而这一族逐字**只**认**那 newc**（=> 而那内核认**那一个**）。
#      newc 的头逐字是那 "070701"、=> 而那归档**末尾**逐字**要**那 "TRAILER!!!"。
#    => 而那 cpio.h 那一件逐字**写着**那 MAGIC = "070707"（=> 而那**旧**的**那一种**），
#       而那**旧**的**那一种**逐字**不**带**那**目录**项、=> 而那内核**不**收**它**。
#
# 判别形式：
#   - 那 newc 那一族逐字**带**那 "070701" **那一个**头、=> 而每一项**带**那 ino 与那 nlink。
#   - 那 "070707" **那一个**逐字**不**带**那**目录**项、=> 而那内核**不**收**它**。
#
# 按 C-104：而那 initramfs **这一族**逐字**只有**一条**路**（=> 而这一支**就是**它**）。
import os
import struct
import sys

MAGIC = b"070701"
TRAILER = b"TRAILER!!!"


def _pad4(n: int) -> int:
    return (4 - (n % 4)) % 4


def _entry(name: bytes, data, ino: int, mode: int, nlink: int, mtime: int = 0) -> bytes:
    #  那 newc 那一族逐字**是**那 110 字节**那一个**头（=> 而那名字**不**算**在**那**头**里面**）。
    if data is None:
        data = b""
    #  那 newc 那一族逐字**是**那 **13** 个**字段**（=> 而那 6 + 8*13 = **110** 字节、=> 而那名字**从**那 110 **开始**）。
    #    而那**字段**逐字**是**：c_ino c_mode c_uid c_gid c_nlink c_mtime c_filesize
    #      c_devmajor c_devminor c_rdevmajor c_rdevminor c_namesize c_check。
    hdr = (MAGIC
           + b"%08X" % ino        # c_ino
           + b"%08X" % mode       # c_mode
           + b"%08X" % 0           # c_uid
           + b"%08X" % 0           # c_gid
           + b"%08X" % nlink      # c_nlink
           + b"%08X" % mtime      # c_mtime
           + b"%08X" % len(data)   # c_filesize（那**目录**项逐字**是**那空字节**的长度**）
           + b"%08X" % 0           # c_devmajor
           + b"%08X" % 0           # c_devminor
           + b"%08X" % 0           # c_rdevmajor
           + b"%08X" % 0           # c_rdevminor
           + b"%08X" % (len(name) + 1)  # c_namesize（含那结尾的 NUL）
           + b"%08X" % 0)          # c_check（那 newc 那一族逐字**不**用**它**）
    out = hdr + name + b"\0"
    out += b"\0" * _pad4(len(out))
    out += data
    out += b"\0" * _pad4(len(data))
    return out


def build(root: str, out_path: str) -> None:
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        rel = os.path.relpath(dirpath, root)
        if rel != ".":
            files.append((rel.replace(os.sep, "/"), None, 0o040755))
        for fn in sorted(filenames):
            p = os.path.join(dirpath, fn)
            r = os.path.relpath(p, root).replace(os.sep, "/")
            files.append((r, p, 0o100755))
    #  那**根**目录**要**在**最**前面**（=> 而那内核逐字**从**那**根**开始**解**）。
    files.sort(key=lambda t: (t[0] != "", t[0]))
    blob = bytearray()
    ino = 1
    #  而那**根**那一项逐字**要**那**名字**（=> 而那 newc **那一族**逐字**用**那**没有**那**前导**斜杠**的**相对**路径**）。
    blob += _entry(b".", None, ino, 0o040755, 2)
    ino += 1
    for name, path, mode in files:
        if path is None:
            blob += _entry(name.encode(), b"", ino, mode, 2)
            ino += 1
            continue
        with open(path, "rb") as f:
            data = f.read()
        blob += _entry(name.encode(), data, ino, mode, 1)
        ino += 1
    blob += _entry(TRAILER, b"", 0, 0, 1)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(bytes(blob))
    print("[initramfs] 写了 %s：%d 字节、%d 项、头 %s" % (
        out_path, len(blob), len(files), MAGIC.decode()))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("用法: %s <那要装的**那一层**> <那产物>" % sys.argv[0], file=sys.stderr)
        sys.exit(2)
    build(sys.argv[1], sys.argv[2])
