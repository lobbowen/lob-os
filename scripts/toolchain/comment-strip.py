#!/usr/bin/env python3
"""把源码里的注释剥掉，供指纹计算用。

为什么要有这一份：件的指纹原先按文件原始字节算，于是改一处注释
（哪怕只删一个标记）都会让该件的资产名变、旧产物作废、必须重固化一轮。
这个项目已经因此重固化过四次。指纹要认的是「会改变构建结果的东西」，
注释不是 —— 所以先剥注释再算哈希。

硬要求：对不含注释的输入，输出必须与输入逐字节相同。
否则「剥注释」这件事本身就会改指纹，改造白做。
所以这里不在没注释的地方做任何规范化：不去行尾空白、不动缩进、
不改行尾符、不补不删空行。

用法：
    comment-strip.py <原文件名>  < 输入  > 输出
按文件后缀选语言；认不出的后缀原样输出。

语言支持：
    .sh                  shell（# 只在词首算注释；首行 #! 是 shebang 保留）
    .js .mjs .kt .java   // 与 /* */（Kotlin 的块注释按深度配对）
    .py                  # （三引号串保留，它是运行期对象）
    .c .h                // 与 /* */
    其它                  原样
"""
import sys


def _keep_newlines(chunk, left, right):
    """注释删掉后：换行数保留了，两侧都是正文时补一个空格。"""
    nl = chunk.count('\n')
    need = (not left.isspace()) and (not right.isspace())
    return (' ' if need else '') + '\n' * nl


def strip_c_like(src, nested_block=False):
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        # 三引号原始串（Kotlin/Java 的 \"\"\"）
        if src.startswith('"""', i):
            j = i + 3
            while j < n and not src.startswith('"""', j):
                j += 1
            j = min(j + 3, n)
            out.append(src[i:j])
            i = j
            continue
        if c in '"\'':
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                if src[j] == '\n':
                    break
                j += 1
            out.append(src[i:j])
            i = j
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            left = src[i - 1] if i > 0 else '\n'
            right = src[j] if j < n else '\n'
            out.append(_keep_newlines(src[i:j], left, right))
            i = j
            continue
        if src.startswith('/*', i):
            j = i + 2
            if nested_block:
                depth = 1
                while j < n and depth:
                    if src.startswith('/*', j):
                        depth += 1
                        j += 2
                    elif src.startswith('*/', j):
                        depth -= 1
                        j += 2
                    else:
                        j += 1
            else:
                k = src.find('*/', j)
                j = n if k < 0 else k + 2
            left = src[i - 1] if i > 0 else '\n'
            right = src[j] if j < n else '\n'
            out.append(_keep_newlines(src[i:j], left, right))
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def strip_js(src):
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c in '"\'':
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                if src[j] == '\n':
                    break
                j += 1
            out.append(src[i:j])
            i = j
            continue
        if c == '`':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '`':
                    j += 1
                    break
                j += 1
            out.append(src[i:j])
            i = j
            continue
        # 行注释；前面是反斜杠时是正则里的转义斜杠，不是注释
        if src.startswith('//', i) and (i == 0 or src[i - 1] != '\\'):
            j = src.find('\n', i)
            j = n if j < 0 else j
            left = src[i - 1] if i > 0 else '\n'
            right = src[j] if j < n else '\n'
            out.append(_keep_newlines(src[i:j], left, right))
            i = j
            continue
        if src.startswith('/*', i):
            k = src.find('*/', i + 2)
            j = n if k < 0 else k + 2
            left = src[i - 1] if i > 0 else '\n'
            right = src[j] if j < n else '\n'
            out.append(_keep_newlines(src[i:j], left, right))
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def strip_py(src):
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        hit = None
        for q3 in ('"""', "'''"):
            if src.startswith(q3, i):
                hit = q3
                break
        if hit:
            j = src.find(hit, i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
            continue
        if c in '"\'':
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                if src[j] == '\n':
                    break
                j += 1
            out.append(src[i:j])
            i = j
            continue
        if c == '#':
            j = src.find('\n', i)
            j = n if j < 0 else j
            left = src[i - 1] if i > 0 else '\n'
            right = src[j] if j < n else '\n'
            out.append(_keep_newlines(src[i:j], left, right))
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def strip_sh(src):
    lines = src.split('\n')
    out = []
    for idx, line in enumerate(lines):
        if idx == 0 and line.startswith('#!'):
            out.append(line)
            continue
        s = line.lstrip()
        if s.startswith('#'):
            out.append('')
            continue
        # 行内 # 注释：只认「引号外、前面是空白」的 #
        res = []
        i = 0
        n = len(line)
        q = None
        cut = -1
        while i < n:
            ch = line[i]
            if q:
                res.append(ch)
                if ch == '\\' and i + 1 < n:
                    res.append(line[i + 1])
                    i += 2
                    continue
                if ch == q:
                    q = None
                i += 1
                continue
            if ch in '"\'':
                q = ch
                res.append(ch)
                i += 1
                continue
            if ch == '#' and i > 0 and line[i - 1] in ' \t':
                cut = i
                break
            res.append(ch)
            i += 1
        if cut >= 0:
            out.append(''.join(res).rstrip())
        else:
            out.append(line)
    return '\n'.join(out)


def pick(name):
    low = name.lower()
    if low.endswith(('.sh', '.bash')):
        return strip_sh
    if low.endswith(('.js', '.mjs')):
        return strip_js
    if low.endswith(('.kt', '.java')):
        return lambda s: strip_c_like(s, nested_block=True)
    if low.endswith(('.c', '.h', '.cc', '.cpp')):
        return lambda s: strip_c_like(s, nested_block=False)
    if low.endswith('.py'):
        return strip_py
    return None


def main():
    if len(sys.argv) < 2:
        sys.stderr.write('用法: comment-strip.py <原文件名>  < 输入 > 输出\n')
        return 2
    fn = pick(sys.argv[1])
    data = sys.stdin.buffer.read()
    if fn is None:
        sys.stdout.buffer.write(data)
        return 0
    try:
        src = data.decode('utf-8')
    except UnicodeDecodeError:
        sys.stdout.buffer.write(data)
        return 0
    sys.stdout.buffer.write(fn(src).encode('utf-8'))
    return 0


if __name__ == '__main__':
    sys.exit(main())