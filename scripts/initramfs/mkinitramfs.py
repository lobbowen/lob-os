
import os
import struct
import sys

MAGIC = b"070701"
TRAILER = b"TRAILER!!!"

def _pad4(n: int) -> int:
    return (4 - (n % 4)) % 4

def _entry(name: bytes, data, ino: int, mode: int, nlink: int, mtime: int = 0) -> bytes:

    if data is None:
        data = b""

    hdr = (MAGIC
           + b"%08X" % ino
           + b"%08X" % mode
           + b"%08X" % 0
           + b"%08X" % 0
           + b"%08X" % nlink
           + b"%08X" % mtime
           + b"%08X" % len(data)
           + b"%08X" % 0
           + b"%08X" % 0
           + b"%08X" % 0
           + b"%08X" % 0
           + b"%08X" % (len(name) + 1)
           + b"%08X" % 0)
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

            if os.path.islink(p):
                files.append((r, p, 0o120777))
            else:
                files.append((r, p, 0o100755))

    files.sort(key=lambda t: (t[0] != "", t[0]))
    blob = bytearray()
    ino = 1

    blob += _entry(b".", None, ino, 0o040755, 2)
    ino += 1
    for name, path, mode in files:
        if path is None:
            blob += _entry(name.encode(), b"", ino, mode, 2)
            ino += 1
            continue
        if os.path.islink(path):

            data = os.readlink(path).encode()
        else:
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
