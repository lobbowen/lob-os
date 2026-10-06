#!/usr/bin/env node
'use strict';

// 全仓死代码扫描。
//
// 这个工具最容易出错的地方是误判：Android 有大量"不用写调用点"的入口
// （Manifest 声明的组件、反射拿到的类、序列化读的字段、布局里的 id）。
// 把这些当死码删掉，编译能过、运行就炸。所以这里宁可漏报也不误报：
// 白名单里的东西一律不报，并且把白名单理由打印出来，让人能核对。
//
// 输出分两类：
//   · 确认死：声明处 + 全仓零引用，且不在任何白名单里
//   · 可疑：只在白名单场景被引用（反射/Manifest/序列化），删前必须人工确认

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'container/app/src/main/java');
const RES = path.join(ROOT, 'container/app/src/main/res');
const ASSETS = path.join(ROOT, 'container/app/src/main/assets');
const MANIFEST = path.join(ROOT, 'container/app/src/main/AndroidManifest.xml');

function walk(dir, ext, out) {
  out = out || [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, ext, out);
    else if (!ext || p.endsWith(ext)) out.push(p);
  }
  return out;
}

const ktFiles = walk(JAVA, '.kt');
const allKtText = ktFiles.map((f) => fs.readFileSync(f, 'utf8')).join('\n');
const nonKtFiles = [
  ...walk(RES, null), ...walk(ASSETS, null),
  ...walk(path.join(ROOT, 'container/engine/src'), null),
  ...walk(path.join(ROOT, 'tools'), '.js'),
  ...walk(path.join(ROOT, 'docs'), '.md'),
  ...walk(path.join(ROOT, 'scripts'), null),
].filter((f) => fs.existsSync(f) && fs.statSync(f).isFile());
const nonKtText = nonKtFiles
  .map((f) => { try { return fs.readFileSync(f, 'utf8'); } catch (e) { return ''; } })
  .join('\n');
const manifestText = fs.existsSync(MANIFEST) ? fs.readFileSync(MANIFEST, 'utf8') : '';

// ── 白名单：Android 隐式入口 ──────────────────────────────────────────
// 判据：这些名字可能通过 Manifest / 反射 / 布局 / 序列化被用到，
// 静态引用计数为 0 不等于死码。

// Manifest 的属性是跨行排的（<service\n  android:name=".X"\n  …/>），
// 所以要按整块标签解析，不能假设 name 与标签名同行。
const MANIFEST_COMPONENTS = new Set();
for (const tag of manifestText.matchAll(/<(activity|service|receiver|provider|application)\b([^>]*?)\/?>/gs)) {
  const nm = /android:name="([^"]+)"/.exec(tag[2]);
  if (!nm) continue;
  const v = nm[1];
  // ".MainActivity" → MainActivity；"lobos.os.X" 或 "lobos.os.X.Y" → X（取首字母大写段）
  if (v.startsWith('.')) MANIFEST_COMPONENTS.add(v.slice(1));
  else {
    const seg = v.split('.').find((s) => /^[A-Z]/.test(s));
    if (seg) MANIFEST_COMPONENTS.add(seg);
  }
}

const REFLECTIVE_HINTS = [
  'getDeclaredMethod', 'getDeclaredField', 'Class.forName', '::class.java',
  'registerExtModule', 'newInstance', 'getMethod(',
];

// 布局 id：R.id.xxx 被 Kotlin 以 R.id 形式引用，但代码里也可能是 findViewById(R.id.x)
const layoutIds = new Set();
for (const f of walk(RES, '.xml')) {
  const t = fs.readFileSync(f, 'utf8');
  for (const m of t.matchAll(/android:id="@\+id\/([a-zA-Z0-9_]+)"/g)) layoutIds.add(m[1]);
}

function isWhitelisted(name) {
  if (MANIFEST_COMPONENTS.has(name)) return 'AndroidManifest 声明的组件';
  if (layoutIds.has(name)) return '布局文件里的 id';
  if (/^(onCreate|onStart|onResume|onPause|onStop|onDestroy|onBind|onUnbind|onReceive|onCommand|onStartCommand|onTaskRemoved|onTrimMemory|onConfigurationChanged|onLowMemory|onUpgrade|onClick|onTouch|onDraw|onLayout|onMeasure|onAttach|onDetach|onActivityResult|onRequestPermissionsResult|onNewIntent)$/.test(name)) {
    return 'Android 生命周期/回调覆写（无显式调用点）';
  }
  if (/^on[A-Z]/.test(name)) return '疑似生命周期/事件回调覆写';
  if (/^get[A-Z]|^set[A-Z]/.test(name) && /^(get|set)(Value|OrDefault|OrElse|OrNull|IfPresent)$/.test(name)) {
    return '值访问器';
  }
  return null;
}

// ── 声明抽取 ─────────────────────────────────────────────────────────

const DECL = [
  // object / class / interface / enum class
  { kind: '类型', re: /^[ \t]*(?:internal\s+|private\s+|public\s+|abstract\s+|open\s+|sealed\s+|data\s+|value\s+)*(?:object|class|interface|enum\s+class)\s+([A-Z][A-Za-z0-9_]*)/gm },
  // fun（顶层与成员都收；成员靠"可疑/死"两档区分，不靠这里区分）
  { kind: '函数', re: /^[ \t]*(?:internal\s+|private\s+|public\s+|override\s+|inline\s+|suspend\s+|operator\s+|infix\s+|tailrec\s+|open\s+|abstract\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_.<>]+\.)?([a-z][A-Za-z0-9_]*)\s*\(/gm },
  // val / var（含 companion object 成员）
  { kind: '属性', re: /^[ \t]*(?:internal\s+|private\s+|public\s+|override\s+|const\s+|lateinit\s+|open\s+|abstract\s+|final\s+)*(?:val|var)\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_.<>]+\.)?([A-Za-z_][A-Za-z0-9_]*)/gm },
];

const decls = [];
for (const f of ktFiles) {
  const rel = f.replace(JAVA + '/', '');
  const text = fs.readFileSync(f, 'utf8');
  // 行号索引：offset → 行号，避免每命中一次就重扫全文
  const lineStarts = [0];
  for (let i = 0; i < text.length; i += 1) if (text[i] === '\n') lineStarts.push(i + 1);
  const lineAt = (off) => {
    let lo = 0, hi = lineStarts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (lineStarts[mid] <= off) lo = mid; else hi = mid - 1;
    }
    return lo + 1;
  };
  for (const { kind, re } of DECL) {
    // 每个文件每个模式都用独立实例：共享带 g 的正则会让 lastIndex 跨文件泄漏
    const rx = new RegExp(re.source, re.flags);
    let m;
    while ((m = rx.exec(text)) !== null) {
      if (m[0].length === 0) { rx.lastIndex += 1; continue; }
      decls.push({
        kind, name: m[1], rel,
        line: lineAt(m.index),
        indented: /^\s/.test(m[0]),
      });
    }
  }
}

// ── 引用计数 ─────────────────────────────────────────────────────────
// 声明行本身要排除，否则"声明即一次引用"

function countRefs(name, selfRel, selfLine) {
  let n = 0;
  const inKt = (text, rel) => {
    const re = new RegExp('\\b' + name + '\\b', 'g');
    let mm;
    while ((mm = re.exec(text)) !== null) {
      if (rel === selfRel) {
        const upto = text.slice(0, mm.index);
        const ln = upto.split('\n').length;
        if (ln === selfLine) continue;
      }
      n += 1;
    }
  };
  for (const f of ktFiles) inKt(fs.readFileSync(f, 'utf8'), f.replace(JAVA + '/', ''));
  // 非 Kotlin 来源：布局 id、assets、脚本、文档、Manifest
  const re = new RegExp('\\b' + name + '\\b', 'g');
  let mm;
  while ((mm = re.exec(nonKtText)) !== null) n += 1;
  while ((mm = re.exec(manifestText)) !== null) n += 1;
  return n;
}

const dead = [];
const suspicious = [];
for (const d of decls) {
  const refs = countRefs(d.name, d.rel, d.line);
  const why = isWhitelisted(d.name);
  if (refs === 0) {
    if (why) suspicious.push({ ...d, why });
    else dead.push(d);
  } else if (refs <= 1 && why && d.kind === '类型') {
    suspicious.push({ ...d, why, refs });
  }
}

// ── 输出 ─────────────────────────────────────────────────────────────

console.log('');
console.log('扫描范围：' + ktFiles.length + ' 个 Kotlin 文件');
console.log('');
console.log('确认死（零引用且不在白名单，可删）：' + dead.length);
const byKind = {};
for (const d of dead) (byKind[d.kind] = byKind[d.kind] || []).push(d);
for (const [k, list] of Object.entries(byKind)) {
  console.log('  ' + k + '（' + list.length + '）');
  for (const d of list.slice(0, 40)) {
    console.log('    ' + d.rel + ':' + d.line + '  ' + d.name + (d.indented ? '  [成员]' : ''));
  }
  if (list.length > 40) console.log('    … 还有 ' + (list.length - 40) + ' 个');
}

console.log('');
console.log('可疑（零引用但命中白名单，删前必须人工确认）：' + suspicious.length);
const byWhy = {};
for (const d of suspicious) (byWhy[d.why] = byWhy[d.why] || []).push(d);
for (const [w, list] of Object.entries(byWhy)) {
  console.log('  ' + w + '（' + list.length + '）');
  for (const d of list.slice(0, 12)) console.log('    ' + d.rel + ':' + d.line + '  ' + d.name);
  if (list.length > 12) console.log('    … 还有 ' + (list.length - 12) + ' 个');
}

console.log('');
console.log('白名单规模：Manifest 组件 ' + MANIFEST_COMPONENTS.size
  + ' 个 / 布局 id ' + layoutIds.size + ' 个');
console.log('反射提示词命中：' + REFLECTIVE_HINTS.filter((h) => allKtText.includes(h)).length + ' 类');

process.exit(dead.length > 0 ? 1 : 0);
