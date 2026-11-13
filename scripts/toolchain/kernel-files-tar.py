#!/usr/bin/env python3
# 把那**逐文件**那一族合成**那一个**确定性 tar —— 而那**它**逐字**两次**做**一样**。
#
# 缺陷名（C-102）**那 urls 逐字有**几个**的**时候**逐字拿**一个**钉值**去**比**每一个**
# 它治**的**是「那**逐文件**那一族**取不取得到**」。
# **成因**： kernel 那一格逐字**是**那**逐文件**那一族（ urls 逐字**有**那**四**条**）、
# **它们**逐字**各**是**那**一个**文件**、 **每**一个**的**字节**都**不**是**那钉值**，
# **它**们**永远**对不上**、 **钉值**逐字**对不上**任何**一条**。
# **处置**： **逐文件**那一族逐字**合成****那一个**确定性 tar **再**跟**那钉值**比。
# 按 C-104： 取件那一族逐字**只有**一条路（ **合成**不是**那**第二种生成路径**、
# **它**逐字**是**那**同一个**产物的**一种**取法**）。
#
# 用法：kernel-files-tar.py <那**逐文件**装好的**那一层**> [那**清单**文件**（ **次序**）]
#   -> stdout 逐字**是**那 tar 的**字节**
import io
import os
import sys
import tarfile


def main() -> int:
    if len(sys.argv) < 2:
        sys.stderr.write("用法: %s <那**逐文件**装好的**那一层**> [那**清单**文件**（=> 而那**次序**）]\n" % sys.argv[0])
        return 2
    root = sys.argv[1]
    # **次序**逐字**是**那**清单**给的**次序**（ **它**逐字**不**自己**排**）。
    # **理由**： **钉值**逐字**是**按**那**次序**合成**的**、 **它**们**不**互相顶替**。
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
