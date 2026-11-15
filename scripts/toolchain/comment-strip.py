#!/usr/bin/env python3
"""把源码里的注释与纯提示文字剥掉，供指纹计算用。用法: comment-strip.py <原文件名> < 输入 > 输出"""
import re
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


def strip_message_lines(src):
    HINT = ('echo', 'printf', 'die', 'say', 'warn', 'err', 'fail', 'note', 'trace')
    out = []
    for line in src.split('\n'):
        t = line.strip()
        t2 = t
        moved = True
        while moved:
            moved = False
            for pf in ('||', '&&'):
                if t2.startswith(pf):
                    t2 = t2[2:].lstrip(); moved = True
            for pf in ('(', '!', '{'):
                if t2.startswith(pf):
                    t2 = t2[1:].lstrip(); moved = True
        w = (t2.split(None, 1)[0] if t2 else '').rstrip(';|&')
        if w in HINT and '>' not in t2 and '|' not in t2 and '<' not in t2 \
                and '$' not in t2 and not t2.startswith(':'):
            continue
        out.append(line)
    return '\n'.join(out)

def pick(name):
    low = name.lower()
    if low.endswith(('.sh', '.bash')):
        return lambda s: strip_message_lines(strip_sh(s))
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