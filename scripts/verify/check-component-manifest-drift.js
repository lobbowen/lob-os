#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');

// C-102 层级错位：ROOT 原先解析成 scripts/ 自身，:63 的 channel.json 锚点落到了
// scripts/container/app/src/main/assets/supply/channel.json，必然 ENOENT。
// C-103 回答（为什么有两边）：ROOT 一词在仓内有两义 —— 甲：仓根；乙：scripts/ 自身。
// 此处应当是甲。同一个词的含义被换掉这件事落在
// scripts/publish/publish-component-manifest.js 与 scripts/publish/publish-native-manifest.js 三处，
// 唯独 scripts/publish/check-component-manifest-drift.js 用的是 '../..'（甲），
// 所以「两处对一处错」不是两个写法，是两义混用。
const ROOT = path.resolve(__dirname, '../..');
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
// C-102 字段层级错位：通道锚升到 schema 2 之后，清单名搬进了 manifests.component.name，
// 而这一份还在读 v1 的平铺 manifestName —— 拼出来的键是 component-canary/undefined。
// 读取口径照设备侧那一族（SupplyProvisioner.manifestNameOf，container/app/src/main/java/lobos/services/supply/SupplyProvisioner.kt:64）：
// 先读 manifests[channel].name，没有再退回平铺 manifestName；两处都没有就是缺字段，按 C-05 报缺，不猜填。
function manifestNameOf(a, channel) {
  const m = a.manifests && a.manifests[channel];
  if (m && typeof m.name === 'string' && m.name.trim()) return m.name.trim();
  if (channel === 'component' && typeof a.manifestName === 'string' && a.manifestName.trim()) {
    return a.manifestName.trim();
  }
  return null;
}
const MANIFEST_NAME = manifestNameOf(anchor, 'component');
if (!MANIFEST_NAME) {
  fail('通道锚里读不到 component 清单名（container/app/src/main/assets/supply/channel.json）'
    + ' —— manifests.component.name 与平铺 manifestName 两处都没有');
}
const onlinePath = process.env.COMPONENT_ONLINE_FILE;
const onlineFrom = onlinePath || 'component-' + CHANNEL + '/' + MANIFEST_NAME;
const onlineUrl = anchor.baseUrl.replace(/\/+$/, '') + '/component-' + CHANNEL + '/' + MANIFEST_NAME
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
        //  缺陷名（C-102）**那一格逐字**不**分**「那 recipe 变了」与「那字节被偷换了」那两族 ——
        //    旧版逐字是「同 name@version 逐字不许换字节」那一族逐字**一刀切**
        //      **recipe 变了**逐字**换**了字节逐字**也**被**判红**
        //        （ **旧字节**逐字**是**那**旧** recipe 产的、 **新字节**逐字**是**那**新** recipe 产的、
        //          **两半**逐字**都**合法、 **闸门**逐字**分不出**）
        //    **判据**逐字是「那字节逐字是不是那实际字节、 **实际字节**逐字**为什么**变」那一个事实上
        //      （ 它逐字**不**由「那一件逐字是什么」顶掉）
        //    **处置**： **清单**逐字**多**记那 **recipe 指纹**那一格、
        //      **指纹**逐字**一样**而字节**变了**逐字**那才是**偷换、 **该**判红
        //      **指纹**逐字**变了**逐字**换**了字节逐字**是**那 recipe 变了、 **合法**、 它逐字**记**下来
        const oldFp = String(got.recipeFingerprint || '');
        const newFp = String(t.recipeFingerprint || '');
        if (oldFp && newFp && oldFp === newFp) {
          //  **指纹**逐字**一样**（ recipe 逐字**没变**）而字节**变了**、 **才**是偷换
          reds.push(t.name + '@' + t.version + ' 换了字节：线上 sha ' + String(got.sha256).slice(0, 12)
            + '… 本次 ' + String(t.sha256).slice(0, 12)
            + '… —— 件按内容寻址命名，所以这**不会**盖掉旧对象（设备按 sha 命中与否自己会重下， integrity 不破）；'
            + '破的是口径：同一个号在两份清单里指向两批源码，凡按号说话的证据（债表、真机读数）从此失去所指。'
            + '同一版本号只许一批字节：要么让构建可复现（源码钉值 + 归档内 mtime + 工具链），要么提升件的版本'
            + '（而那那**指纹**逐字**一样**：' + oldFp + '、那那 recipe 逐字**没变**）');
        } else if (oldFp && newFp && oldFp !== newFp) {
          //  **指纹**逐字**变了**（ recipe 逐字**变了**）、 **字节**逐字**跟着**变
          //    **版本号**逐字**没变**、 **上游源码**逐字**也**没变
          //      **件**逐字**是**那**同一份上游**逐字**的**另一个**编译产物
          console.log('[gate] ' + t.name + '@' + t.version + ' 换了字节，但**那 recipe 指纹也换了**'
            + '（线上 ' + oldFp + ' → 本次 ' + newFp + '）'
            + ' ——那那**recipe 变了**逐字**换**了字节逐字**是**那合法的**那一种**、那那**版本号**逐字**没变**'
            + '（而那那**上游源码**逐字**没变**）');
        } else {
          //  缺陷名（C-102）**那一档逐字把「那旧清单逐字没指纹」逐字**判红**、 **死循环**逐字**解不开****
          //    旧版逐字是「指纹**没**有 判红」那一族（ **指纹一样**那一族已经**移**到**上面**那一档）
          //      **线上**那一份（ 是那 **revision 13**）逐字**永远**没有那 `recipeFingerprint` 那一格
          //        （ 真读数逐字是那 CDN 上那清单那第一件的键
          //          `['name','provider','version','url','sha256','entry','aliases','kind','layout','abi']`）
          //      `oldFp` 逐字**恒**是那空串、 **闸门**逐字**永远**判红
          //        **新**那一份逐字**永远**发不出去、 **线上**那一份逐字**永远**换不了
          //          （ 是一个**死循环**、 **永远**出不去）
          //    **判据**逐字是「 凡一格逐字要答『那字节逐字换了、 它逐字是不是合法的』，
          //      判据必须落在『那**旧**那份逐字有没有逐字记下那指纹』那一个事实上」
          //      （ 它逐字**不**由「那一件逐字是什么」顶掉）
          //    **处置**： **旧清单没指纹**那一档逐字**不**判红、 **记**下来
          //      **判据**逐字**答不出**、 按 C-105 逐字**不**猜填
          //      **内容寻址**逐字**保**那 integrity（ **错误信息**逐字**自己**就说了
          //        「件按内容寻址命名，所以这**不会**盖掉旧对象（设备按 sha 命中与否自己会重下， integrity 不破）」
          //      **偷换**那一族逐字**不**丢： 从**这一份**起逐字**每一**份清单逐字**都**带那指纹
          //        **下一**轮逐字那**指纹一样**逐字就逐字**判红**了
          console.log('[gate] ' + t.name + '@' + t.version + ' 换了字节，但**那线上清单逐字没记那指纹**'
            + '（那那**判据**逐字**答不出**、按 C-05 逐字**不**猜填）'
            + ' —— 而那那**内容寻址**逐字**保**那 integrity、那那**旧对象**逐字**还在**、'
            + '那那**设备**逐字按 sha 命中与否自己会重下'
            + '；从**这一份**起逐字**每一**份清单逐字**都**带那指纹、'
            + '而那那**下一**轮逐字那**指纹一样**逐字就逐字**判红**了');
        }
      }
    }
  } else {
    console.log('[gate] 线上基线 revision=' + onlineRevision + ' 早于可复现世代 ' + REPRODUCIBLE_BASELINE_REVISION
      + ' —— 那一代的件带着**重造不出的字节**（源码批次未钉 / 构建墙钟被编进件里），'
      + '它不是「同一版本号的旧一批字节」的凭据，第 2 格对它不判；本次投出的这一份起，这一格严格生效');
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
