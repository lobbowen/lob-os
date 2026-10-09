// 算出 platform.h 里 Android 分支的准确区间（含 HAVE_STRCHRNUL 的那个）。
// 输出 "begin,end"，供 shell 里的 sed -i 用。
//
// 为什么不能在 awk 里现算：Android 分支**有两处**
// （第 392 行那个 4 行就结束；第 529 行那个才是要改的），
// 而「找到目标行再回溯它所属的 #if」需要栈 —— awk 单遍做这个别扭，
// node 一遍就清楚。
'use strict';
const fs = require('fs');

const H = process.argv[2];
if (!H || !fs.existsSync(H)) { console.error('用法: find-android-branch.js <platform.h>'); process.exit(2); }

const lines = fs.readFileSync(H, 'utf8').split('\n');

// 找出所有 #if…#endif 的区间（栈）
const stack = [];
const ranges = [];
lines.forEach((l, i) => {
  const t = l.trim();
  if (/^#\s*if/.test(t)) { stack.push(i + 1); return; }
  if (/^#\s*endif/.test(t)) {
    const b = stack.pop();
    if (b) ranges.push({ b, e: i + 1 });
  }
});
// 半开：先出现先闭
ranges.sort((x, y) => (x.e - x.b) - (y.e - y.b));   // 小的（更内层）优先

// ── 判据：分支头写着 ANDROID，而不是「区间里含 undef」──
//   platform.h 里有 4 处 "# undef HAVE_STRCHRNUL"，分属
//   __WATCOMC__ / __dietlibc__ / __APPLE__ / Android 四个分支。
//   只按「区间里含 undef」去找，会抓到最内层的 __dietlibc__ 那个
//   （我踩过：输出是 493,495 而不是 Android 的 529,554）。
//   我们要改的是 **Android 那个** —— 判据必须是分支头写着
//   `#if defined(ANDROID) || defined(__ANDROID__)`。
const isAndroidHead = (n) =>
  /^#if\s+defined\(ANDROID\)\s*\|\|\s*defined\(__ANDROID__\)/.test((lines[n - 1] || '').trim());
const androidBranches = ranges.filter(r => isAndroidHead(r.b));
if (!androidBranches.length) {
  console.error('::warning title=补丁没打上::platform.h 里找不到 ' +
    '"#if defined(ANDROID) || defined(__ANDROID__)" 分支 —— 上游改了结构，按实际改这里。');
  process.exit(1);
}
if (androidBranches.length > 1) {
  console.error('[busybox] 注意：Android 分支有 ' + androidBranches.length +
    ' 处（起始行 ' + androidBranches.map(r => r.b).join(' / ') +
    '），取**含 HAVE_STRCHRNUL 的那一处**');
}

// ── Android 分支有两处（一个只设两个 SYS_* 宏就结束，另一个才是要改的）──
// 取含目标 undef 的那一处；若那一处已经没有裸 undef 行，说明**已经打过补丁**
// （幂等的第二次运行），那就取「含我们写的那行注释」的那一处。
const targets = ['# undef HAVE_STRCHRNUL', '# undef HAVE_MEMPCPY'];
const TIERED_MARK = /HAVE_STRCHRNUL.*bionic 在 API 21/;
let chosen = null;
for (const r of androidBranches) {
  for (let i = r.b; i <= r.e; i++) {
    if (targets.includes((lines[i - 1] || '').trim())) { chosen = r; break; }
  }
  if (chosen) break;
}
let alreadyPatched = false;
if (!chosen) {
  // 找含我们写的分级注释的那一处 —— 那是「已经打过」的证据
  for (const r of androidBranches) {
    for (let i = r.b; i <= r.e; i++) {
      if (TIERED_MARK.test(lines[i - 1] || '')) { chosen = r; alreadyPatched = true; break; }
    }
    if (chosen) break;
  }
}

if (!chosen) {
  console.error('::notice title=补丁没打上::Android 分支里既没有 ' +
    '"# undef HAVE_STRCHRNUL"，也没有我们的分级注释 —— 上游可能已自行修好。文件未改动。');
  process.exit(0);
}
if (alreadyPatched) {
  console.error('[busybox] 补丁已在位（幂等跳过，不重复施加）—— ' +
    'platform.h 第 ' + chosen.b + '~' + chosen.e + ' 行');
  process.exit(0);
}

const inRange = [];
for (let i = chosen.b; i <= chosen.e; i++) inRange.push(lines[i - 1]);
console.error('[busybox] Android 条件块：第 ' + chosen.b + '~' + chosen.e + ' 行（' +
  (chosen.e - chosen.b + 1) + ' 行）');

// ── --patch：直接改文件，不走 sed ─────────────────────────────
// 为什么不用 sed 改：s/// 里的 \n 是 GNU sed 扩展，而 Android 上
// /system/bin/sed 是 toybox（实测），它不认 —— 会静默不改或报错。
// 而 `{a,b}` 地址块写法在 toybox 上也要当文件名解析（实测
// 「sed: 529,554: No such file or directory」）。
// 区间已经在 node 里算好了，直接改数组最省事也最可靠。
const PATCH = process.argv.includes('--patch');
if (!PATCH) {
  process.stdout.write(chosen.b + ',' + chosen.e);
  process.exit(0);
}

// 要把这两行包进 __ANDROID_API__ 分级 —— bionic 在 API 21 之后才提供它们
// （依据：本机 readelf 直查 /system/lib64/libc.so 的 .dynsym）。
// 上游那两行是给 Android 8 之前写的，那时 bionic 确实没有。
// 分级写法与这个分支里已有的写法一致（它自己就用
// `# if __ANDROID_API__ < 8` / `< 21` / `>= 21` 处理 dprintf）。
const NEEDS_TIERING = {
  '# undef HAVE_STRCHRNUL':
    '# if __ANDROID_API__ < 21\n#  undef HAVE_STRCHRNUL /* bionic 在 API 21 之后才提供它 */\n# endif',
  '# undef HAVE_MEMPCPY':
    '# if __ANDROID_API__ < 21\n#  undef HAVE_MEMPCPY /* 同上 */\n# endif',
};
// 这两个 bionic 确实没有，那两行 #undef 是**对的**，必须保留：
//   strverscmp · wait3（实测 .dynsym 里没有）
const KEEP_AS_IS = new Set(['# undef HAVE_STRVERSCMP', '# undef HAVE_WAIT3']);

const out = [];
let nStr = 0, nMem = 0;
for (let i = 1; i <= lines.length; i++) {
  const t = (lines[i - 1] || '').trim();
  if (i >= chosen.b && i <= chosen.e) {
    if (NEEDS_TIERING[t]) {
      out.push(...NEEDS_TIERING[t].split('\n'));
      if (t.includes('STRCHRNUL')) nStr++; else nMem++;
      continue;
    }
    if (KEEP_AS_IS.has(t)) {
      out.push(lines[i - 1]);
      continue;
    }
  }
  out.push(lines[i - 1]);
}

// 走到这里说明分支里有**裸的** undef 行（已打过的那种在上面就退出了）
if (!nStr) {
  // 同理：分支找到了却没匹配上 —— 那行可能被上游改了写法
  console.error('::notice title=补丁没打上::Android 分支第 ' + chosen.b + '~' + chosen.e +
    ' 行里没有可替换的 "# undef HAVE_STRCHRNUL" —— 上游可能改了写法。文件未改动。');
  process.exit(0);
}

fs.writeFileSync(H, out.join('\n'));
console.error('[busybox] 已打补丁：' + H + ' 第 ' + chosen.b + '~' + chosen.e + ' 行（Android 条件块）');
console.error('[busybox]   HAVE_STRCHRNUL' + (nStr ? ' / HAVE_MEMPCPY' : '') +
  ' 按 __ANDROID_API__ 分级');
console.error('[busybox]   不修就 duplicate symbol —— libbb/platform.c 会自己实现一份，');
console.error('[busybox]   而 bionic 静态库里已有一份（实测本机 libc.so 的 .dynsym 里两个都有）');
console.error('[busybox]   保留 HAVE_STRVERSCMP / HAVE_WAIT3 的 undef —— bionic 确实没有那两个');