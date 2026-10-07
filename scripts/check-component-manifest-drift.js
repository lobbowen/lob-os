#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');

const ROOT = path.resolve(__dirname, '..');
const argv = process.argv.slice(2);
const IMMUTABLE = argv[0] === '--immutable';
if (IMMUTABLE) argv.shift();
const LOCAL = argv[0];
const CHANNEL = argv[1] || 'canary';

const REPRODUCIBLE_BASELINE_REVISION = 6;

function project(t) {
  const aliases = (t.aliases || [])
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((a) => a.name + '→' + a.entry)
    .join(',') || '无';
  return {
    provider: t.provider,
    version: t.version,
    url: t.url,
    sha256: t.sha256,
    entry: t.entry,
    aliases,
    判据: t.verify && t.verify.node,
    判据形状: t.verify && t.verify.criterion,
  };
}

function fail(msg, title) {
  console.error('::error title=' + (title || '线上清单与仓内声明分家') + '::' + msg);
  console.log((IMMUTABLE ? '[gate]' : '[drift]') + ' 判红');
  process.exit(1);
}

if (!LOCAL) fail(IMMUTABLE ? '--immutable 后面没给即将上传的清单文件（用法见文件头）' : '没给仓内投影文件（用法见文件头）');

let parsed;
try {
  parsed = JSON.parse(fs.readFileSync(LOCAL, 'utf8'));
} catch (e) {
  fail('本地清单读不出（' + LOCAL + '）: ' + e.message);
}
let localTools;
let localRevision = 0;
if (IMMUTABLE) {
  if (!Number.isInteger(parsed.revision) || parsed.revision < 1) {
    fail('即将上传的清单里 revision 不是正整数（读到 ' + JSON.stringify(parsed.revision) + '）', 'C 层清单版本号非法');
  }
  localRevision = parsed.revision;
  localTools = parsed.tools;
} else {
  localTools = parsed;
}
if (!Array.isArray(localTools) || localTools.length === 0) {
  fail((IMMUTABLE ? '即将上传的清单' : '仓内投影') + '是空的 —— 没有件可对照，不许当作通过');
}

const anchor = JSON.parse(
  fs.readFileSync(path.join(ROOT, 'container/app/src/main/assets/supply/channel.json'), 'utf8'),
);
const onlinePath = process.env.COMPONENT_ONLINE_FILE;
const onlineFrom = onlinePath || 'component-' + CHANNEL + '/' + anchor.manifestName;
const onlineUrl = anchor.baseUrl.replace(/\/+$/, '') + '/component-' + CHANNEL + '/' + anchor.manifestName
  + '?t=' + Date.now();

function fetchOnline() {
  return new Promise((resolve, reject) => {
    if (onlinePath) {
      if (!fs.existsSync(onlinePath)) return resolve({ missing: true });
      return resolve({ buf: fs.readFileSync(onlinePath) });
    }
    const req = https.get(onlineUrl, { timeout: 15_000 }, (res) => {
      if (res.statusCode === 404) return resolve({ missing: true });
      if (res.statusCode !== 200) return reject(new Error('HTTP ' + res.statusCode + ' ' + onlineUrl));
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ buf: Buffer.concat(chunks) }));
    });
    req.on('timeout', () => req.destroy(new Error('取线上清单超时')));
    req.on('error', reject);
  });
}

function gate(online, onlineRevisionRaw, firstPublish) {
  const onlineRevision = Number.isInteger(onlineRevisionRaw) ? onlineRevisionRaw : 0;
  const baselineCredentialed = Number.isInteger(onlineRevisionRaw)
    && onlineRevisionRaw >= REPRODUCIBLE_BASELINE_REVISION;
  console.log('[gate] revision 线上=' + onlineRevision + ' 本次=' + localRevision
    + '，件 线上 ' + online.length + ' 颗 / 本次 ' + localTools.length + ' 颗（取自 ' + onlineFrom + '）');
  if (firstPublish) console.log('[gate] 这条键上还没有清单 —— 本次是该通道按新发布连投的第一份');
  else if (onlineRevisionRaw === undefined) console.log('[gate] 线上那份没有 revision 格（本方案之前的旧形状，按 0 计）—— 本次之后每份清单都必须带');
  const byName = new Map(online.map((t) => [t.name, t]));
  const reds = [];
  if (!(localRevision > onlineRevision)) {
    reds.push('revision 不单调：线上已经是 ' + onlineRevision + '，本次要发 ' + localRevision
      + ' —— 同号或倒退就是「版本号没变而内容变了」；把发布 tag 里的 revision 提上去重跑');
  }
  let checked = 0;
  if (baselineCredentialed) {
    for (const t of localTools) {
      const got = byName.get(t.name);
      if (!got || got.version !== t.version) continue;
      checked++;
      if (got.sha256 !== t.sha256) {
        reds.push(t.name + '@' + t.version + ' 换了字节：线上 sha ' + String(got.sha256).slice(0, 12)
          + '… 本次 ' + String(t.sha256).slice(0, 12)
          + '… —— 件按内容寻址命名，所以这**不会**盖掉旧对象（设备按 sha 命中与否自己会重下， integrity 不破）；'
          + '破的是口径：同一个号在两份清单里指向两批源码，凡按号说话的证据（债表、真机读数）从此失去所指。'
          + '同一版本号只许一批字节：要么让构建可复现（源码钉值 + 归档内 mtime + 工具链），要么提升件的版本');
      }
    }
  } else {
    console.log('[gate] 线上基线 revision=' + onlineRevision + ' 早于可复现世代 ' + REPRODUCIBLE_BASELINE_REVISION
      + ' —— 那一代的件带着**重造不出的字节**（源码批次未钉 / 构建墙钟被编进件里），'
      + '它不是「同一版本号的旧一批字节」的凭据，第 ② 格对它不判；本次投出的这一份起，这一格严格生效');
  }
  if (reds.length) {
    for (const r of reds) console.log('[gate] ' + r);
    return fail(reds.length + ' 条不合格 —— 本次不投递', 'C 层发布闸门');
  }
  console.log('[gate] 通过：revision 单调，同版本下比对的 ' + checked + ' 颗件没有一颗换字节'
    + (baselineCredentialed ? '' : '（线上基线无凭据，这一格本次未比）'));
}

fetchOnline().then(({ buf, missing }) => {
  if (missing) {
    if (!IMMUTABLE) return fail('线上这个键上还没有清单：' + onlineFrom + ' —— 改过声明却没发出去，与「没有漂移」不是一回事');
    return gate([], undefined, true);
  }
  let man;
  try {
    man = JSON.parse(buf.toString('utf8'));
  } catch (e) {
    return fail('线上清单不是合法 JSON（' + onlineFrom + '）: ' + e.message);
  }
  const online = Array.isArray(man.tools) ? man.tools : [];
  if (IMMUTABLE) return gate(online, man.revision);
  console.log('[drift] 仓内投影 ' + localTools.length + ' 颗，线上清单 ' + online.length
    + ' 颗（线上 version=' + man.version + '，取自 ' + onlineFrom + '）');
  const byName = new Map(online.map((t) => [t.name, project(t)]));
  const localNames = new Set();
  const diffs = [];
  for (const t of localTools) {
    localNames.add(t.name);
    const want = project(t);
    const got = byName.get(t.name);
    if (!got) { diffs.push(t.name + ': 线上整颗缺失（仓内声明了，线上没有这件）'); continue; }
    for (const k of Object.keys(want)) {
      if (String(want[k]) !== String(got[k])) {
        diffs.push(t.name + '.' + k + ': 线上=' + (got[k] === undefined ? '∅' : got[k])
          + ' 仓内=' + (want[k] === undefined ? '∅' : want[k]));
      }
    }
  }
  for (const name of byName.keys()) if (!localNames.has(name)) diffs.push(name + ': 线上有而仓内不声明（谁投的？）');
  if (diffs.length) {
    for (const d of diffs) console.log('[drift] ' + d);
    return fail(diffs.length + ' 格不一致 —— 推 `component-' + CHANNEL + '-<revision>` tag 重发清单，或改回仓内声明');
  }
  console.log('[drift] 逐格一致：' + localTools.length + ' 颗件的版本/哈希/入口/别名/判据都在线上');
}).catch((e) => fail('取不到线上清单：' + e.message + '（看不清 ≠ 没有漂移，也 ≠ 可以发）'));
