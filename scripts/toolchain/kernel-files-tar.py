
import io
import os
import sys
import tarfile

def main() -> int:
    if len(sys.argv) < 2:
        sys.stderr.write("用法: %s <那**逐文件**装好的**那一层**> [那**清单**文件**（那**次序**）]\n" % sys.argv[0])
        return 2
    root = sys.argv[1]

    paths = []
    if len(sys.argv) > 2:
        paths = [l.strip() for l in io.open(sys.argv[2], encoding="utf-8") if l.strip()]
    else:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames.sort()
            for fn in sorted(filenames):
                p = os.path.join(dirpath, fn)
                paths.append(os.path.relpath(p, root).replace(os.sep, "/"))
        paths.sort()
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tf:
        for rel in paths:
            with open(os.path.join(root, rel), "rb") as fh:
                data = fh.read()
            ti = tarfile.TarInfo(rel)
            ti.size = len(data)
            ti.mtime = 0
            ti.mode = 0o644
            ti.uid = 0
            ti.gid = 0
            ti.uname = ""
            ti.gname = ""
            tf.addfile(ti, io.BytesIO(data))
    sys.stdout.buffer.write(buf.getvalue())
    return 0

if __name__ == "__main__":
    sys.exit(main())
