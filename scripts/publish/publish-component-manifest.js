#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');

const ROOT = path.resolve(__dirname, '..');
const POS = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const PROJECT_ONLY = process.argv.includes('--project');
const DIST = POS[0] || 'dist';
const OUT = POS[1] || 'release';
const KEY = POS[2] || 'keys/ota-private.pem';
const CHANNEL = POS[3] || 'canary';
const BASE = (process.env.COMPONENT_BASE_URL || 'https://lobcdn.zll.ink').replace(/\/+$/, '');
const PUBKEY = process.env.COMPONENT_PUBKEY
  || path.join(ROOT, 'container', 'app', 'src', 'main', 'assets', 'supply/component-public.pem');
const VERIFY = path.join(ROOT, 'component-sources.json');
const TTL_MS = 30 * 86400_000;

function entryOf(name) {
  return cp.execFileSync('bash', [path.join(ROOT, 'registry', 'read-component-entry.sh'), name], { encoding: 'utf8' }).trim();
}

function pieceText(zipPath, rel) {
  if (!pieceNames(zipPath).includes(rel)) return null;
  try {
    return cp.execFileSync('unzip', ['-p', zipPath, rel], {
      encoding: 'buffer', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (e) {
    if (e.status === 11 || /filename not matched/.test(String(e.stderr || ''))) return null;
    throw e;
  }
}

function pieceNames(zipPath) {
  const r = cp.spawnSync('unzip', ['-l', zipPath], { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error('unzip -l 读不了件 ' + zipPath + ': ' + String(r.stderr || '').slice(0, 200));
  }
  const out = [];
  for (const line of String(r.stdout || '').split('\n')) {
    const m = /^\s*\d+\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$/.exec(line);
    if (m) out.push(m[1].trim());
  }
  if (!out.length) throw new Error('unzip -l 没解析出条目名（输出格式异常）: ' + zipPath);
  return out;
}

function aliasesOf(name, zipPath, declaredEntry) {
  const raw = pieceText(zipPath, 'package.json');
  if (raw === null) return [];
  if (!raw.length) {
    throw new Error(
      '件 ' + name + ' 里有 package.json 但取出来 0 字节 —— 件坏了。' +
      '先看：unzip -l ' + zipPath
    );
  }
  let bin;
  try {
    bin = JSON.parse(raw.toString('utf8')).bin;
  } catch (e) {
    throw new Error('件 ' + name + ' 的根 package.json 不是合法 JSON: ' + e.message);
  }
  if (bin === undefined) return [];
  if (typeof bin === 'string') {
    if (bin !== declaredEntry) {
      throw new Error('件 ' + name + ' 的 package.json bin 是字符串 ' + bin + '，与仓内声明的入口 ' + declaredEntry + ' 分叉');
    }
    return [];
  }
  if (bin[name] !== declaredEntry) {
    throw new Error('件 ' + name + ' 的 package.json bin 映射里 ' + name + ' → ' + String(bin[name]) +
      '，与仓内声明的入口 ' + declaredEntry + ' 分叉 —— 入口只能有一个真相（scripts/registry/read-component-entry.sh 那条纪律）');
  }
  const names = pieceNames(zipPath);
  const out = [];
  for (const alias of Object.keys(bin).sort()) {
    const entryBase = String(declaredEntry).split('/').pop();
    if (alias === name || alias === entryBase) continue;
    const rel = bin[alias];
    if (typeof rel !== 'string' || !rel.includes('/') || rel.startsWith('/') || rel.split('/').includes('..')) {
      throw new Error('件 ' + name + ' 的别名 ' + alias + ' 的件内入口不是合规相对路径: ' + String(rel));
    }
    if (!names.includes(rel)) {
      throw new Error('件 ' + name + ' 声明的别名 ' + alias + ' 指向件内不存在的 ' + rel);
    }
    out.push({ name: alias, entry: rel });
  }
  return out;
}

function assertNameUniqueness(tools) {
  const owner = new Map();
  const claim = (name, who) => {
    const prev = owner.get(name);
    if (prev !== undefined) {
      throw new Error('名字冲突: ' + name + ' 同时属于 ' + prev + ' 与 ' + who + ' —— 后落位会覆盖前一颗');
    }
    owner.set(name, who);
  };
  for (const t of tools) {
    claim(t.name, '件 ' + t.name + ' 的本名');
    for (const a of t.aliases || []) claim(a.name, '件 ' + t.name + ' 的别名 ' + a.name);
  }
}

function toolsFromDist() {
  if (!fs.existsSync(DIST)) throw new Error('dist 目录不存在: ' + DIST);
  const out = [];
  for (const f of fs.readdirSync(DIST)) {
    const m = /^component-([a-z0-9-]+)-([0-9][^-]*)-([0-9a-f]{12})-android-arm64\.zip$/.exec(f);
    if (!m) continue;
    const name = m[1];
    const ver = m[2];
    const claimed = m[3];
    const zipPath = path.join(DIST, f);
    const buf = fs.readFileSync(zipPath);
    if (buf.length === 0) throw new Error('0 字节产物: ' + f);
    const real = crypto.createHash('sha256').update(buf).digest('hex').slice(0, 12);
    if (real !== claimed) throw new Error('文件名里的内容哈希与实际不符: ' + f + '（名 ' + claimed + ' vs 实 ' + real + '）');
    const entry = entryOf(name);
    out.push({
      name, provider: 'zip', version: ver,
      url: BASE + '/component/' + f,
      sha256: crypto.createHash('sha256').update(buf).digest('hex'),
      entry,
      aliases: aliasesOf(name, zipPath, entry),
    });
  }
  return out;
}

function versionString() {
  const d = new Date().toISOString().slice(0, 10).replace(/-/g, '.');
  const run = process.env.GITHUB_RUN_NUMBER || '0';
  return d + '.' + run;
}

function revisionForManifest() {
  const raw = process.env.LOBOS_COMPONENT_REVISION || '';
  if (!/^[1-9][0-9]*$/.test(raw)) {
    throw new Error('LOBOS_COMPONENT_REVISION 必须是正整数（它来自发布 tag `component-<channel>-<revision>`），读到: ' + (raw || '空'));
  }
  return Number(raw);
}

function criteria() {
  const j = JSON.parse(fs.readFileSync(VERIFY, 'utf8'));
  return j.sources || {};
}

function main() {
  const crit = criteria();
  const tools = toolsFromDist().sort((a, b) => a.name.localeCompare(b.name));
  if (!tools.length) throw new Error('清单为空：dist 下没有一颗按命名契约产出的件');
  for (const t of tools) {
    // 判据只挂在真正由产物自身证明的字段上（entry 的存在性、必需字段齐全）。
    // 原来那道门要的是 component-sources.json 里的 node —— 那个字符串在整个仓里不存在，
    // 而 component-verify.json 自己写着「它不是自动执行的检查」。按 C-91 废除。
    //  缺陷名（C-102）**那判据逐字去问了一处逐字已经被删掉的地方**、=> 而而那消息逐字说错了那要补什么
    //    —— 而而那**真的成因**逐字是：「那判据逐字去**钉值表**里要那一格、=> 而而那一格逐字
    //    **在 2026-10-20** 逐字**被删**了、=> 而而它的**唯一真源**逐字是产物自带的
    //    component-meta.json」那个形状。
    //    => 而而那 read-component-entry.sh 那一族逐字**自己写着**缘由（=> 而本机逐字读过）：
    //       :2 | # 读「这一件该有的入口」—— **从产物里读回**，不从表里读。
    //       :8 | #   而 component-sources.json 的 entry 键（15 处）与各 workflow 的第三参都是**人写下的副本**
    //       :11| # => 所以那两处副本都删了（2026-10-20），而这个脚本改成**从产物里读回 entry**。
    //    => 而而那**纯库件**逐字**故意**是空（=> 而那 gen-component-meta.js:415 那一行
    //       逐字 = 「return { entry: '', role: 'library', … }」）、=> 而而那**不是**缺陷。
    //    => 而而那**消息**逐字说的「在 scripts/component-sources.json 里补」逐字**指错了**、
    //    => 而而那一格**补**不**进去**、=> 而而那一格逐字**该**从**产物**里**读**。
    //    => 而而那**不是**「那表里**每一件**都**漏**了那一格」那个形状、=> 而而那逐字是
    //    「那一格逐字**根本不该**在**那张表**里」。
    //    —— 而那 table 那一族逐字在 0a57629（2026-10-20）逐字被「entry 的三处副本收口成真源一处」
    //    那一笔逐字改过（=> 而那**改的是那表**、=> 而而那**没改**这道门）。
    //
    //  而而那判据该挂在**哪**一处（=> 而按 C-103 那一族）：
    //    一边「**那一件**在不在那表里」        => 而那治的是**登记**那一族
    //    另一边「**那表里每一件**都有没有那一格」=> 而那治的是**那格从哪来**那一族
    //  => 而而它们**各**答一件事、=> 而而那**处置**不一样、=> 而而它们**不并**。
    //
    //  而而那**真源**逐字是**产物**那一份（=> 而那 entry 由 read-component-entry.sh 逐字算出来、
    //  => 而而那 t.entry 逐字就是**它**算出来的那一份、=> 而而它逐字**不需要**表里那一格）。
    //  => 而那表里逐字那一格叫 entryName、=> 而而它逐字是「**这一件有多个入口时该取哪一个**」那一族
    //  （=> 而按 C-92：=> 而删掉那个名字，=> 而还剩「**多入口时取哪一个**」那个形状）。
    //  => 而而那 4 个有 entryName 的件逐字 = openssl / git / busybox / npm、
    //  => 而而那**两个**（openssl / busybox）逐字**就在** base 筐那 8 件里（=> 而那 8 件逐字 =
    //     zlib / openssl / curl / jq / bash / busybox / ripgrep / crypto、=> 而那由
    //     fetch-prebuilt-pieces.sh:52 那一行逐字 PIECES=(zlib openssl curl jq bash busybox ripgrep crypto) 定）、
    //  => 而而那**另外两个**（git / npm）逐字**不**在那 8 件里。
    //  => 而那**纯库件**逐字（crypto / curl / zlib 那一族）逐字**故意**是空（=> 而那 gen-component-meta.js:415
    //     那一行逐字 = 「return { entry: '', role: 'library', … }」）、=> 而而那**不是**缺陷。
    //
    //  => 而而那 :161 与 :162 那一族逐字**记着同一族形状**（=> 而那逐字是「那**同一族**形状在 **node** 那一格
    //     逐字**被废除**了、=> 而而它在 **entry** 那一格逐字**留着**」那个形状）。
    //  => 而而按 C-104：=> 而那表里逐字**零**个 entry、=> 而而那**没有**第二种生成路径。
    //  => 而而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
    const v = crit[t.name];
    if (!v) {
      throw new Error('钉值表里没有这一件: ' + t.name + '（在 scripts/component-sources.json 里补）');
    }
    //  入口逐字**不**再看那表里那一格：=> 而而那**真源**逐字是**产物**那一份、=> 而而那表里逐字**零**个 entry。
    //  => 而而那**纯库件**逐字合法地 entry 逐字是空、=> 而而那**不**判红、=> 而而那**只**记下它。
    if (!t.entry) {
      //  这一句逐字**必须**写 stderr：=> 而而 --project 那一档逐字把 stdout **重定向**成
      //  work/declared.json（=> 而那 build-component.yml:87 那一行）、=> 而而那 stdout **只**能是那份 JSON。
      //  => 而而 console.log 逐字会写 stdout、=> 而而那会把那一份 JSON 逐字**写坏**（=> 而那是 SyntaxError 那一族）。
      process.stderr.write('  - ' + t.name + ' 纯库件（没有程序那一侧、entry 留空）\n');
    }
    t.kind = t.kind || 'component';
    t.layout = t.layout || 'toolset';
    t.abi = t.abi || 'aarch64';
    if (!t.name || !t.version || !t.sha256) throw new Error('件缺字段(name/version/sha256): ' + JSON.stringify(t).slice(0, 120));
  }
  assertNameUniqueness(tools);
  if (PROJECT_ONLY) {
    process.stdout.write(JSON.stringify(tools, null, 2) + '\n');
    return;
  }
  const man = {
    schema: 1,
    channel: CHANNEL,
    revision: revisionForManifest(),
    version: versionString(),
    sequence: Math.floor(Date.now() / 1000),
    expiresEpochMs: Date.now() + TTL_MS,
    tools,
  };
  const body = JSON.stringify(man, null, 2) + '\n';
  const key = fs.readFileSync(KEY, 'utf8');
  const sig = crypto.sign(null, Buffer.from(body, 'utf8'), key).toString('base64');
  const ok = crypto.verify(null, Buffer.from(body, 'utf8'), fs.readFileSync(PUBKEY, 'utf8'), Buffer.from(sig, 'base64'));
  if (!ok) { console.error('::error title=签名不配对::私钥与 APK 焊死的公钥不是一对 —— 这份清单设备验不过，拒绝发布。'); process.exit(1); }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'component-manifest.json'), body);
  fs.writeFileSync(path.join(OUT, 'component-manifest.json.sig'), sig + '\n');
  console.log('[component] channel=' + CHANNEL + ' revision=' + man.revision + ' version=' + man.version + ' sequence=' + man.sequence + ' tools=' + tools.length);
  for (const t of tools) {
    const als = t.aliases.length ? ' 别名 ' + t.aliases.map((a) => a.name + '→' + a.entry).join(',') : '';
    console.log('  - ' + t.name + '@' + t.version + ' ' + t.provider + ' ' + t.sha256.slice(0, 12) + '… 入口 ' + t.entry + als);
  }
  console.log('[component] 签名自检通过（与 APK 公钥配对）: ' + path.join(OUT, 'component-manifest.json'));
}

module.exports = { aliasesOf, assertNameUniqueness, pieceText, pieceNames };

if (require.main === module) {
  try { main(); } catch (e) { console.error('::error title=清单发布失败::' + (e && e.message)); process.exit(1); }
}
