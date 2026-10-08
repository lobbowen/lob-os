#!/usr/bin/env node
// 列出 busybox 源码树里 include 的、而 **NDK sysroot 里没有**的头文件，
// 以及对应的配置项（用来把那些 applet 关掉）。
//
// 为什么需要它：busybox 有一批 applet 直接 include 内核 uapi 头
//（<sys/kd.h> <linux/fs.h> <linux/pkt_sched.h> …）。NDK 只提供 libc 头，
// 内核 uapi 头在 Linux 内核源码树的 include/uapi 里，NDK 不提供，于是编不过：
//   console-tools/loadfont.c:59:10: fatal error: 'sys/kd.h' file not found
//   networking/tc.c:…: error: 'linux/pkt_sched.h' file not found
//
// ── 判据是「NDK sysroot 里到底有没有这个头」，不是前缀 ──────────
//   不能按 `sys/` vs `linux/` 前缀分：NDK 的 libc 头本来就是 sys/*.h 布局
//   （sys/socket.h、sys/stat.h…），而 linux/* 才多为内核 uapi —— 但 NDK
//   确实带了一批精简的 linux/*.h，且两者都有例外（linux/foo.h 是 busybox
//   自己的头、include/libbb.h 是 busybox 的）。
//   所以唯一可靠的办法是逐个问 sysroot。这也让判据跟着 NDK 版本走。
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
//   busybox-missing-headers.js <busybox 源码树> <NDK sysroot include 目录>
//                            [--list] [--headers]
//   默认输出配置项（空格分隔）；--list 输出命中的 .c；--headers 输出缺的头
// 退出码：有源文件却认不出配置项 → 1（判据与这版源码对不上，要人看）
'use strict';

const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const incDir = process.argv[3];
const mode = process.argv[4];
if (!root || !incDir) {
  console.error('用法: busybox-missing-headers.js <busybox 源码树> <NDK include 目录> [--list|--headers]');
  process.exit(2);
}
if (!fs.existsSync(root)) { console.error('源码树不存在: ' + root); process.exit(2); }
if (!fs.existsSync(incDir)) { console.error('NDK include 目录不存在: ' + incDir); process.exit(2); }

function walk(dir, acc) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === '.git') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (e.name.endsWith('.c') || e.name.endsWith('.h')) acc.push(p);
  }
  return acc;
}

// NDK 里查一个头：usr/include 下的相对路径就是它的 include 写法。
// 尖括号 include 也走 -isystem 的同一批目录，所以直接查 include 目录即可。
const haveHeader = h => fs.existsSync(path.join(incDir, h));

// ── 判据的边界：只认「内核 uapi 头」与「系统头」，不认 busybox 自带的可选依赖 ──
// 扫描源码里的 #include 时会误收三类东西，都不能算：
//   1. 注释里提到的路径（/* #include <wolfssl/ssl.h> */ 之类）—— 正则会跨行吃到。
//      所以先剥掉注释与字符串字面量，再扫 include。
//   2. busybox 自带的第三方源码目录（libarchive/、libiproute/、unxz/…）——
//      那些是源码树内的相对路径，不是系统头，找不到是正常的。
//   3. 依赖外部 GUI/加密库的 applet（gtk/、glade/、wolfssl/、matrixssl/）——
//      我们编的是无 X11 的 Android，那些 applet 本来也不该开。
const KEEP_PREFIX = /^(?:sys|linux|asm|net|netinet|arpa|machine|netpacket|rpc|mtd|scsi|security|sepol|selinux)\//;
const BUILTIN_DIRS = new Set([
  'libarchive', 'libiproute', 'unxz', 'libbb', 'include', 'applets', 'scripts',
  'libcoreutils', 'klibc-utils', 'modutils', 'qemu_multiarch_testing',
]);

// 去掉注释与字符串字面量，只留下真正的代码。
// （不全：够用了 —— 我们要找的是 #include 指令本身。）
function stripCommentsAndStrings(s) {
  let out = '';
  let i = 0;
  const n = s.length;
  while (i < n) {
    const two = s.slice(i, i + 2);
    if (two === '//') {                       // 行注释
      while (i < n && s[i] !== '\n') i++;
      continue;
    }
    if (two === '/*') {                       // 块注释
      i += 2;
      while (i < n && s.slice(i, i + 2) !== '*/') i++;
      i += 2;
      continue;
    }
    const c = s[i];
    if (c === '"' || c === "'") {              // 字面量
      const q = c; i++;
      while (i < n && s[i] !== q) {
        if (s[i] === '\\') i++;
        i++;
      }
      i++;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

const opts = new Set();
const files = [];
const missingHdrs = new Set();

for (const f of walk(root, [])) {
  const raw = fs.readFileSync(f, 'utf8');
  const code = stripCommentsAndStrings(raw);
  const re = /#\s*include\s*[<"]([^>"\n]+)[>"]/g;
  let m, miss = false;
  while ((m = re.exec(code))) {
    const h = m[1];
    // 只关心带目录前缀的头；"libbb.h"、"common_bufsiz.h" 这类同目录裸名不算
    if (!h.includes('/')) continue;
    // 不属于系统头的路径（busybox 自带目录、GUI/加密库）不算
    if (!KEEP_PREFIX.test(h)) continue;
    if (haveHeader(h)) continue;
    // busybox 自带头：源码树内能找到的就不是缺的系统头
    if (fs.existsSync(path.join(root, h))) continue;
    if (fs.existsSync(path.join(root, 'include', h))) continue;
    miss = true;
    missingHdrs.add(h);
  }
  if (!miss) continue;
  files.push(f);

  // //config:config XXX —— 这才是配置项的真名（Config.in 由它生成）
  const c = /^\/\/config:config[ \t]+([A-Z0-9_]+)[ \t]*$/gm;
  let x;
  let any = false;
  while ((x = c.exec(raw))) { opts.add(x[1]); any = true; }
  if (any) continue;

  // 有些文件没有 //config: 块，退回 applet/kbuild 标记里的名字。
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
process.stdout.write([...opts].sort().join(' ') + (opts.size ? '\n' : ''));
process.exit(0);