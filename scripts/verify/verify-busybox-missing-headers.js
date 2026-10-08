#!/usr/bin/env node
// 列出 busybox 源码树里**无条件**include 了「NDK sysroot 里没有的头」的 .c，
// 以及对应的配置项（用来把那些 applet 关掉）。
//
// 为什么需要它：busybox 有一批 applet 直接 include 内核 uapi 头
//（<sys/kd.h> <linux/fs.h> <linux/pkt_sched.h> …）。NDK 只提供 libc 头，
// 内核 uapi 头在 Linux 内核源码树的 include/uapi 里，NDK 不提供，于是编不过：
//   console-tools/loadfont.c:59:10: fatal error: 'sys/kd.h' file not found
//   networking/tc.c:…: fatal error: 'linux/pkt_sched.h' file not found
//
// ── 判据一：问 NDK sysroot，不按 sys//linux 前缀分 ──────────────
//   不能按前缀猜：NDK 的 libc 头本来就是 sys/*.h 布局（sys/socket.h…），
//   而 linux/* 才多为内核 uapi —— 但两边都有例外
//   （linux/foo.h 是 busybox 自己的头，linux/limits.h NDK 也有）。
//   逐个问 sysroot 最可靠，判据也跟着 NDK 版本走。
//
// ── 判据二：只看**无条件**的 include ──────────────────────────
//   busybox 把依赖 applet 的 include 包在条件编译里，例如
//     libbb/run_shell.c:31   #if ENABLE_SELINUX
//     libbb/run_shell.c:32   #include <selinux/selinux.h>
//     libbb/xconnect.c:14    #if ENABLE_IFPLUGD || ENABLE_UEVENT
//     libbb/xconnect.c:15    #include <linux/netlink.h>
//   关掉那个 applet，这段 include 根本不编 —— 缺的头不会被引用。
//   不看这一层就会误判：libbb 是**所有 applet 共享**的基础设施，
//   从它身上扒配置项会把一大票 applet 连带关掉（上一轮就这样
//   把 DF/PS 关了 —— 它们只是恰好在 libbb 的条件依赖里，不是自己缺头）。
//
// ── libbb/ 单列，不据此关 applet ────────────────────────────
//   libbb 与 libpwdgrp 是所有 applet 共享的基础设施，
//   它们的缺头不由 applet 开关决定（关 applet 也不改 libbb 的 .o）。
//   所以 --libbb 单独输出，让人看清那些是什么，而不是自动去关一堆 applet。
//
// 为什么用脚本算而不是手写名单：
//   名单会随 busybox 版本漂移 —— 漏一个就编不过，多关一个是我们白丢能力。
//   判据本身稳定，所以在构建时从源码树现算。
//   这与 busybox 官方 android_ndk_defconfig 的做法一致（它也是靠关 applet
//   避开这些头），只是我们让它跟着源码树与 NDK 自动算。
//
// 配置项名从 busybox 自己的 `//config:` 注释读，依据 scripts/gen_build_files.sh
// 第 117~120 行：各子目录的 Config.in 由 `//config:` 注释生成
//（`sed -n 's@^//config:@@p' "$srctree/$d"/*.c`），所以官方 tarball 里
// 那些子目录 Config.in 根本不存在 —— 配置项的真身就在 .c 的注释里。
//
// 用法：
//   verify-busybox-missing-headers.js <busybox 源码树> <NDK sysroot include 目录>
//                                [--list|--headers|--libbb]
//   默认输出配置项（空格分隔）
//   --list 输出命中的 .c（每行一个）· --headers 缺的头 · --libbb 共享基础设施里的缺头
// 退出码：有源文件却认不出配置项 → 1（判据与这版源码对不上，要人看）
'use strict';

const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const incDir = process.argv[3];
const mode = process.argv[4];
if (!root || !incDir) {
  console.error('用法: verify-busybox-missing-headers.js <busybox 源码树> <NDK include 目录> [--list|--headers|--libbb]');
  process.exit(2);
}
if (!fs.existsSync(root)) { console.error('源码树不存在: ' + root); process.exit(2); }
if (!fs.existsSync(incDir)) { console.error('NDK include 目录不存在: ' + incDir); process.exit(2); }

function walk(dir, acc) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === '.git') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (e.name.endsWith('.c')) acc.push(p);
  }
  return acc;
}

const haveHeader = h => fs.existsSync(path.join(incDir, h));

// 去掉注释与字符串字面量，只留下真正的代码。
// （不全：够用了 —— 我们要找的是 #include 指令本身。）
function stripCommentsAndStrings(s) {
  let out = '', i = 0;
  const n = s.length;
  while (i < n) {
    const two = s.slice(i, i + 2);
    if (two === '//') { while (i < n && s[i] !== '\n') i++; continue; }
    if (two === '/*') { i += 2; while (i < n && s.slice(i, i + 2) !== '*/') i++; i += 2; continue; }
    const c = s[i];
    if (c === '"' || c === "'") { const q = c; i++; while (i < n && s[i] !== q) { if (s[i] === '\\') i++; i++; } i++; continue; }
    out += c; i++;
  }
  return out;
}

// 每个字符所处的条件层数 —— 0 表示无条件。
// 逐行扫 #if/#ifdef/#ifndef 与 #endif 的配对即可；
// #else/#elif 分支里的深度算 1（它们仍是条件代码）。
// 判据不追求完备：busybox 里有 #ifdef __KERNEL__ 之类我们认不出的宏，
// 但那类分支几乎都是「有则用、无则退回」的选择逻辑，
// 不会让原本可编的头变成不可编。宁可多关也不多编。
function unconditionalSpans(code) {
  const depth = new Array(code.length).fill(0);
  let d = 0;
  const lines = code.split('\n');
  let pos = 0;
  for (const line of lines) {
    const m = /^[ \t]*#[ \t]*(if|ifdef|ifndef|endif|else|elif)\b/.exec(line);
    const start = pos;
    const end = pos + line.length;
    if (m) {
      const kw = m[1];
      if (kw === 'if' || kw === 'ifdef' || kw === 'ifndef') {
        d++; for (let i = start; i < end; i++) depth[i] = d;
      } else if (kw === 'endif') {
        d = Math.max(0, d - 1); for (let i = start; i < end; i++) depth[i] = d;
      } else {
        for (let i = start; i < end; i++) depth[i] = Math.max(1, d);
      }
    } else {
      for (let i = start; i < end; i++) depth[i] = d;
    }
    pos = end + 1;
  }
  return depth;
}

const opts = new Set();
const files = [];
const missingHdrs = new Set();
const libbb = [];

for (const f of walk(root, [])) {
  const raw = fs.readFileSync(f, 'utf8');
  const code = stripCommentsAndStrings(raw);
  const depth = unconditionalSpans(code);

  // 逐个 include 看它在第几层条件里；只有第 0 层（无条件）才算数
  const re = /#\s*include\s*[<"]([^>"\n]+)[>"]/g;
  const miss = [];
  let m;
  while ((m = re.exec(code))) {
    const h = m[1];
    if (depth[m.index] !== 0) continue;            // 条件编译里的不算
    if (!h.includes('/')) continue;
    if (haveHeader(h)) continue;
    if (fs.existsSync(path.join(root, h))) continue;
    if (fs.existsSync(path.join(root, 'include', h))) continue;
    miss.push(h);
  }
  if (!miss.length) continue;
  files.push(f);
  miss.forEach(h => missingHdrs.add(h));

  const rel = f.replace(root + path.sep, '');
  if (rel.startsWith('libbb' + path.sep) || rel.startsWith('libpwdgrp' + path.sep)) {
    libbb.push(rel + ' ← ' + miss.join(' '));
    continue;
  }

  // //config:config XXX —— 这才是配置项的真名（Config.in 由它生成）
  const c = /^\/\/config:config[ \t]+([A-Z0-9_]+)[ \t]*$/gm;
  let x, any = false;
  while ((x = c.exec(raw))) { opts.add(x[1]); any = true; }
  if (any) continue;

  // 没有 //config: 块的，退回 applet/kbuild 标记里的名字。
  const head = raw.split('\n').slice(0, 25).join('\n');
  const a = /^\/\/applet:IF_([A-Z0-9_]+)/gm;
  while ((x = a.exec(head))) opts.add(x[1]);
  const k = /^\/\/kbuild:lib-\$\(CONFIG_([A-Z0-9_]+)\)/gm;
  while ((x = k.exec(head))) opts.add(x[1]);
}

if (mode === '--list') {
  process.stdout.write(files.join('\n') + (files.length ? '\n' : ''));
  process.exit(files.length && opts.size === 0 ? 1 : 0);
}
if (mode === '--headers') {
  process.stdout.write([...missingHdrs].sort().join(' ') + (missingHdrs.size ? '\n' : ''));
  process.exit(0);
}
if (mode === '--libbb') {
  process.stdout.write(libbb.join('\n') + (libbb.length ? '\n' : ''));
  process.exit(0);
}
process.stdout.write([...opts].sort().join(' ') + (opts.size ? '\n' : ''));
process.exit(0);