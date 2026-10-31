#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');

// C-102 层级错位：ROOT 原先解析成 scripts/ 自身，于是 ROOT-relative 路径全部少一层。
// C-103 回答（为什么有两边）：仓内 ROOT 一词有两种含义。
// 甲：仓根（repo root）。乙：scripts/ 自身。
// 正确含义是甲；d6e954c 时 ROOT 确是仓根，VERIFY 为 path.join(ROOT,'scripts','component-sources.json')，
// 到了 f87bcb2 把 ROOT 改成 '..'（变成乙）同时把 VERIFY 改成 path.join(ROOT,'component-sources.json')，
// 4105f16 又照着乙把 registry 路径改成 path.join(ROOT,'registry',...)。
// 所以这不是三处各写错一次，是一个词的含义被换掉、落在三个地方。
const ROOT = path.resolve(__dirname, '../..');
const POS = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const PROJECT_ONLY = process.argv.includes('--project');
//  => 而那 **--declared-only** 那一档（=> 而它逐字产出**声明侧那一份的形状**、=> 而**不**签名、
//     => 而而它逐字只供核「声明侧带缺件状态」那一格用、=> 而那它逐字**不能**当发布产物）。
const DECLARED_ONLY = process.argv.includes('--declared-only');
const DIST = POS[0] || 'dist';
const OUT = POS[1] || 'release';
const KEY = POS[2] || 'keys/ota-private.pem';
const CHANNEL = POS[3] || 'canary';
const BASE = (process.env.COMPONENT_BASE_URL || 'https://lobcdn.zll.ink').replace(/\/+$/, '');
const PUBKEY = process.env.COMPONENT_PUBKEY
  || path.join(ROOT, 'container', 'app', 'src', 'main', 'assets', 'supply/component-public.pem');
const VERIFY = path.join(ROOT, 'scripts', 'component-sources.json');
const TTL_MS = 30 * 86400_000;

function entryOf(name) {
  return cp.execFileSync('bash', [path.join(ROOT, 'scripts', 'registry', 'read-component-entry.sh'), name], { encoding: 'utf8' }).trim();
}

//  缺陷名（C-102）**那发布投影逐字按文件名后缀选工具** ——
//    而那旧版逐字是 `unzip -p` 与 `unzip -l`（=> 而那两处逐字**只**认 zip）。
//    => 而那**名字**与那**内容**逐字是**两件**事：
//      · Release 上的资产名沿用旧的 <id>-<deps>+…tar.gz，=> 而里面的内容**已经是 zip**；
//      · 而那 CI artifact 那一族逐字是「zip 套 zip」（=> 而那外层逐字装着那内层那一个件包）。
//    => 而按后缀选工具逐字**在这两种形状上都会解错**。
//  => 而按 C-994「判别源必须唯一」：=> 而那一格逐字**调** scripts/toolchain/fmt.sh
//    （=> 而那 fmt_of 逐字按头四字节与尾部哨兵判、=> 而它逐字有 zip / zip64 / gzip / tar / 未知 那五档）。
//  => 而那**真源**逐字不变：declaredEntry 仍来自 scripts/registry/read-component-entry.sh。

// 那一件**实际上是什么容器**（=> 而那判别源唯一、=> 而那它逐字就是 fmt.sh 那一个）。
function containerFormOf(file) {
  const FMT_SH = path.join(ROOT, 'scripts', 'toolchain', 'fmt.sh');
  if (!fs.existsSync(FMT_SH)) {
    throw new Error('判别源不在: ' + FMT_SH + ' —— 容器判别源必须唯一');
  }
  const r = cp.spawnSync('bash', ['-c', '. "$1"; fmt_of "$2"', 'bash', FMT_SH, file], { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error('判别源读不了 ' + file + ': ' + String(r.stderr || '').slice(0, 200));
  }
  return String(r.stdout || '').trim();
}

// 那一件**里层**那一个 zip（=> 而那挑选规则逐字只有一条）。
//  => 而按 01 裁的 S607-01-CONTAINER-FORM-RULING.md §3 逐字
//     「**按内容挑** —— 内层容器的中央目录里**逐字含 `component-meta.json` 且其 `id` 逐字等于要取的件名**；
//      => 而**名字 glob 只当第一道粗筛**」、
//     => 而「**零个候选与多过一个候选都判红、不取第一个、不猜、不许退回外层凑数**」。
//  => 而那**判据必须落在内容上**（=> 而不按名字）。
//
// 缺陷名（C-102）**「按内容挑内层」那一格只覆盖了「外层套内层」那一档，
//   而「外层即件包」那一档被一并套进了同一个挑法** ——
//   => 而那旧版逐字**只有**「解开外层、=> 而在解开处找内层」那一步，
//     => 而那八件（bash / busybox / crypto / curl / jq / openssl / ripgrep / zlib）
//       逐字是**外层自己就是那一颗件**（=> 而那 Release 资产逐字**就是**那件包本身），
//       => 而它们**没有内层**、=> 而按内容挑逐字**挑不出**任何候选。
//   => 而 01 裁的 S607-01 §3.1 逐字那一句**本来就含**这一档：
//     「**内层那一颗件包才算**。**外层不是那一颗件**（=> 而那**除非外层自己逐字含 component-meta.json**）」
//     => 而那**后半句**逐字就是这一档、=> 而那实现里逐字**没有实现那后半句**。
//
// => 而那**三步**（逐字）：
//   第 1 步：那**外层自己**是不是那一颗件（=> 而那外层根目录逐字有没有 component-meta.json、
//            => 而有 => 而那它的 `id` 逐字要等于要取的件名、=> 而不对 => 判红）；
//   第 2 步：外层**没有** component-meta.json => 而那走「外层套内层」那一档（=> 而按内容挑内层、
//            => 而那零个或多个都判红、不取第一个）；
//   第 3 步：gzip / tar 那一档 => 而那**先解开 tar**、=> 而再对解开处逐字走第 1 步与第 2 步。
function pieceIdOf(zipPath) {
  try {
    const raw = cp.execFileSync('unzip', ['-p', zipPath, 'component-meta.json'], {
      encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (!raw) return '';
    return String(JSON.parse(raw).id || '');
  } catch (e) {
    return '';
  }
}

function innerZipCandidates(file) {
  const work = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'lob-inner-'));
  try {
    const form = containerFormOf(file);
    if (form === 'gzip') {
      cp.execFileSync('tar', ['xzf', file, '-C', work], { stdio: ['ignore', 'pipe', 'pipe'] });
    } else if (form === 'zip' || form === 'zip64') {
      cp.execFileSync('unzip', ['-q', '-o', file, '-d', work], { stdio: ['ignore', 'pipe', 'pipe'] });
    } else {
      return [];
    }
    const out = [];
    const walk = (dir, depth) => {
      if (depth > 2) return;
      let kids = [];
      try { kids = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
      for (const k of kids) {
        const p = path.join(dir, k.name);
        if (k.isDirectory()) walk(p, depth + 1);
        else if (k.isFile() && /\.zip$/.test(k.name)) out.push(p);
      }
    };
    walk(work, 0);
    //  => 而那**按字节**挑（=> 而不按名字）：=> 而那候选逐字**头四字节**是 504b* 的那一个才算 zip 容器。
    const zips = [];
    for (const p of out) {
      const fd = fs.openSync(p, 'r');
      try {
        const b = Buffer.alloc(4);
        const n = fs.readSync(fd, b, 0, 4, 0);
        if (n >= 4 && b[0] === 0x50 && b[1] === 0x4b) zips.push(p);
      } finally {
        fs.closeSync(fd);
      }
    }
    //  => 而那**按内容**挑（=> 而 01 裁的规则）：=> 而那候选逐字**含 component-meta.json**、
    //     => 而那一份里的 `id` 逐字**等于**要取的那一件。
    const picked = [];
    for (const p of zips) {
      let meta = null;
      try {
        meta = cp.execFileSync('unzip', ['-p', p, 'component-meta.json'], {
          encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
        });
      } catch (e) {
        meta = null;
      }
      if (!meta) continue;
      let id = '';
      try { id = String(JSON.parse(meta).id || ''); } catch (e) { id = ''; }
      if (!id) continue;
      picked.push({ path: p, id });
    }
    return picked;
  } catch (e) {
    return [];
  } finally {
    try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) { /* 清理失败不影响判别 */ }
  }
}

// 那一件**里层**那一个 zip（=> 而那按内容挑、=> 而那零个或多个都判红）。
function innerZipOf(file, wantPiece) {
  //  => 而那第 1 步：那**外层自己**是不是那一颗件
  //     （=> 而那 zip 那一档逐字**不**解开、=> 而那 gzip 那一档逐字**先解开**）。
  const form = containerFormOf(file);
  const outerId = (form === 'gzip')
    ? (() => {
        const work = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'lob-outer-'));
        try {
          cp.execFileSync('tar', ['xzf', file, '-C', work], { stdio: ['ignore', 'pipe', 'pipe'] });
          const p = path.join(work, 'component-meta.json');
          if (!fs.existsSync(p)) return '';
          return String(JSON.parse(fs.readFileSync(p, 'utf8')).id || '');
        } catch (e) {
          return '';
        } finally {
          try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) { /* 清理失败不影响判别 */ }
        }
      })()
    : pieceIdOf(file);
  if (outerId) {
    if (outerId === wantPiece) return file;   //  => 而那**外层自己**就是那一颗件
    throw new Error(
      '件 ' + file + ' 的**外层自己**就是那一颗件、=> 而它的 id 逐字是 ' + outerId +
      '（要的是 ' + wantPiece + '）—— 判红'
    );
  }
  const picked = innerZipCandidates(file);
  if (!picked.length) {
    throw new Error(
      '件 ' + file + ' 的外层解开之后，=> 而那里面逐字**没有**一个含 component-meta.json 的件包' +
      '（=> 而那按内容挑逐字挑不出、=> 而按 C-05 不猜、=> 而不取第一个）。'
    );
  }
  const hit = wantPiece ? picked.filter((c) => c.id === wantPiece) : picked;
  if (hit.length === 0) {
    throw new Error(
      '件 ' + file + ' 的外层解开之后，=> 而那里面逐字**没有** id=' + wantPiece + ' 的那一个件包' +
      '（=> 而那候选逐字是 ' + picked.map((c) => c.id).join(', ') + '）。'
    );
  }
  if (hit.length > 1) {
    throw new Error(
      '件 ' + file + ' 的外层解开之后，=> 而那里面逐字有 **' + hit.length + '** 个 id=' + wantPiece +
      ' 的件包（=> 而 01 裁的规则逐字「多过一个候选都判红、不取第一个」）。'
    );
  }
  return hit[0].path;
}

function pieceText(zipPath, rel) {
  const form = containerFormOf(zipPath);
  if (form !== 'zip' && form !== 'zip64') {
    throw new Error(
      '件 ' + zipPath + ' 的容器形态逐字是 ' + form + ' —— 而这一格逐字要读件内那一个 package.json，' +
      '=> 而那形态逐字不对（=> 而那不是 zip 容器、=> 而那解不开）。'
    );
  }
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
  //  缺陷名（C-102）**拿件名当 package.json 的 bin 映射的必有键，而件名不是件内任一可执行名**
  //    —— 而原来那一判别式逐字是 `if (bin[name] !== declaredEntry)`：它要求「件名那一键存在、且值逐字等于入口」。
  //    => 而 llvm 那一件逐字**没有** llvm 这个可执行名（recipe 的 NEED_BIN 八项里逐字没有 llvm），
  //    => 而 :89 逐字又把件名从别名集合里剔掉、=> 而而那**同一段代码**里件名那一格既被要求存在、又被要求不参与。
  //  => 而那**处置**（按 01 裁的形态）：bin 是对象时，只要求「存在一个键，其值逐字等于 declaredEntry」，
  //    而**不**要求任何键 == 件名；那一键**不作为别名**发出去（:89 按键名跳过、下面按值再兜一次）。
  //  => 而那**真源**逐字不变：declaredEntry 来自 scripts/registry/read-component-entry.sh
  //    （=> 而那 entry 由 gen-component-meta.js:269 逐字算出来）、=> 而这一格**不引入第二处真相**。
  const entryBase = String(declaredEntry).split('/').pop();
  const entryKeys = Object.keys(bin).filter((k) => bin[k] === declaredEntry);
  if (!entryKeys.length) {
    throw new Error('件 ' + name + ' 的 package.json bin 映射里没有任何一项逐字指向仓内声明的入口 ' + declaredEntry +
      '（这一件声明过的键: ' + Object.keys(bin).sort().join(', ') + '）—— 入口只能有一个真相（scripts/registry/read-component-entry.sh 那条纪律）');
  }
  const names = pieceNames(zipPath);
  const out = [];
  for (const alias of Object.keys(bin).sort()) {
    if (alias === name || alias === entryBase) continue;
    const rel = bin[alias];
    //  那一键（值逐字等于仓内声明的入口）不是别名、=> 而它是**入口本身**、=> 而它不进别名集合。
    //  => 而 :89 按**键名**跳过 entryBase、=> 而这里按**值**再兜一次
    //  （=> 而当那一键的名字**不**等于 entryBase 时，:89 那一条逐字拦不住它）。
    if (rel === declaredEntry) continue;
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

// 缺陷名（C-102）**清单投影把整包读进内存** ——
// 而这一族它为什么有两边：一边是「算内容哈希要那一份字节」，
// 另一边是「那份字节**要多大**才拿得动」。
// Node 24 对单次 readFileSync 超过 2 GiB 直接抛 ERR_FS_FILE_TOO_LARGE，
// 而件（llvmtoolchain 那一颗 8.4 GB）就是那种大小 ——
// 读数：CI run_id=41114232489（head=650052b）step 4「清单投影」，
// `File size (8414536031) is greater than 2 GiB`。
// 而处置不是「换台机器」也不是「跳过那一颗」：哈希**流式**算，
// 内存里只留一块 1 MiB 的缓冲；字节数取 stat 而不是量数组长度。
// 而「取首 12 位」与「取全串」这一次只算一遍（=> 而那一遍就是同一份字节）。
function sha256File(file) {
  const h = crypto.createHash('sha256');
  const fd = fs.openSync(file, 'r');
  try {
    const chunk = Buffer.allocUnsafe(1024 * 1024);
    for (;;) {
      const n = fs.readSync(fd, chunk, 0, chunk.length, null);
      if (n <= 0) break;
      h.update(chunk.subarray(0, n));
    }
  } finally {
    fs.closeSync(fd);
  }
  return h.digest('hex');
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
    const bytes = fs.statSync(zipPath).size;
    if (bytes === 0) throw new Error('0 字节产物: ' + f);
    const digest = sha256File(zipPath);
    const real = digest.slice(0, 12);
    if (real !== claimed) throw new Error('文件名里的内容哈希与实际不符: ' + f + '（名 ' + claimed + ' vs 实 ' + real + '）');
    const entry = entryOf(name);
    out.push({
      name, provider: 'zip', version: ver,
      url: BASE + '/component/' + f,
      sha256: digest,
      entry,
      aliases: aliasesOf(name, zipPath, entry),
    });
  }
  return out;
}

// 那一趟的**缺件表**（=> 而那它逐字是那四列那一档：件名 / 那一档 / 那一档的原话 / 容器形态）。
//   => 而那它逐字是「**声明侧**那一份」的缺件状态来源
//     （=> 而按 01 裁的 S606-02-MISSING-PIECE-FORM-RULING.md 逐字「**声明侧那一份逐字要带缺件状态**」、
//     => 而而那**投影侧**那一份逐字**不留占位**）。
function missingPieces() {
  const out = [];
  const p = path.join(ROOT, 'dist', '.fetch-missing.tsv');
  if (!fs.existsSync(p)) return out;
  let raw = '';
  try { raw = fs.readFileSync(p, 'utf8'); } catch (e) { return out; }
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    const parts = line.split('\t');
    const name = String(parts[0] || '').trim();
    if (!name) continue;
    out.push({
      name,
      reason: String(parts[1] || '').trim() || 'unknown',
      detail: String(parts[2] || '').trim(),
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
    //  => 而那 **--declared-only** 那一档逐字**不要求**那 revision
    //     （=> 而那它逐字只是**核那形状**用、=> 而那一趟逐字**不是**发布轮、
    //     => 而而那一趟逐字**没有**发布 tag、=> 而而那个 revision 逐字**不存在**）。
    //     => 而而那它逐字报**缺**（=> 而按 C-05「缺字段就报缺、不许猜填」）
    //       ——=> 而而那它逐字**不**编一个值（=> 而那编一个就逐字把那形状**验错**了）。
    if (DECLARED_ONLY) {
      console.error('::notice title=缺 revision::--declared-only 那一档逐字不要求 revision（=> 而那一趟逐字不是发布轮）');
      return 0;
    }
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
    //    => 而而那**不是**「那表里**每一件**都**漏**了那一格」那个形状、
    //    => 而而那也**不是**「那一格逐字**根本不该**在**那张表**里」那个形状
    //  （=> 而那逐字是「那一格逐字**曾经**在那张表里、=> 而而它逐字**因为**是**副本**逐字**被删**了」）。
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
    //  => 而那**投影侧**那一份逐字**不留占位**（=> 而按 01 裁的 S606-02-MISSING-PIECE-FORM-RULING.md
    //     逐字「**投影侧 `work/declared.json` 逐字不留占位**（=> 而它答『取到了什么』）」）
    //  => 而而那**缺件状态**逐字落在**声明侧**那一份（=> 而那一格逐字是「声明侧那一份逐字要带缺件状态」）。
    process.stdout.write(JSON.stringify(tools, null, 2) + '\n');
    return;
  }
  //  => 而那**声明侧**那一份逐字带那缺件状态（=> 而 01 裁的 S606-02 逐字
  //     「**声明侧那一份逐字要带缺件状态**」、=> 而而那**缺件占位落在显式缺件表」）。
  const missing = missingPieces();
  if (missing.length) {
    for (const m of missing) {
      process.stderr.write('  - ' + m.name + ' 缺件（' + m.reason + '）：' + m.detail + '\n');
    }
  }
  const man = {
    schema: 1,
    channel: CHANNEL,
    revision: revisionForManifest(),
    version: versionString(),
    sequence: Math.floor(Date.now() / 1000),
    expiresEpochMs: Date.now() + TTL_MS,
    tools,
    //  => 而那**缺件**逐字在这一格（=> 而它逐字是**声明侧**那一份的、=> 而而那**投影侧**那一份逐字没有它）。
    missing,
  };
  const body = JSON.stringify(man, null, 2) + '\n';
  //  => 而那 **--declared-only** 那一档（=> 而它逐字产出**声明侧那一份的形状**、=> 而**不**签名）。
  //     => 而那**为什么**要有它（=> 而按 01 裁的 S606-02 逐字「**声明侧那一份逐字要带缺件状态**」）：
  //       · 而那声明侧那一份逐字**要私钥**才能签（=> 而那把私钥逐字**不在仓内**）
  //         => 而而那「声明侧带缺件状态」那一格在 CI 的**校验轮**逐字**产不出来**
  //         （=> 而那 step 8 到 10 逐字 skipped）=> 而而那一格逐字**在 CI 上未触发**
  //         （=> 而按 C-113 / C-116 逐字标明「未触发」）。
  //       · 而这一档逐字**不签名、=> 而只产出那形状**、=> 而而它逐字让那一格逐字**能被核**
  //         （=> 而那 CI 上逐字能跑、=> 而而那一格逐字**从「未触发」变成「已核」**）。
  //       · 而那它逐字**不是**发布那一支（=> 而那一支逐字仍然签名、=> 而那它逐字**只**多一档投影）。
  //       · 而那它逐字**不能**被当成发布产物（=> 而它逐字**没有**签名、=> 而那设备验不过、
  //         => 而而那一档逐字**只**供核那一格用）。
  if (DECLARED_ONLY) {
    process.stdout.write(body);
    return;
  }
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
