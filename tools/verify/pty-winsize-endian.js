#!/usr/bin/env node
'use strict';

const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const KT = path.resolve(__dirname, '../../container/app/src/main/java/lobos/runtime/PtySession.kt');
const C = path.resolve(__dirname, '../../container/native/d3/pty-session.c');
const kt = fs.readFileSync(KT, 'utf8');
const c = fs.readFileSync(C, 'utf8');

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

console.log('== winsize 字节序：默认大端会把 24 变成 6144 ==');

const bigEndian = Buffer.alloc(8);
bigEndian.writeInt16BE(24, 0); bigEndian.writeInt16BE(80, 2); bigEndian.writeInt16BE(0, 4); bigEndian.writeInt16BE(0, 6);
const littleEndian = Buffer.alloc(8);
littleEndian.writeInt16LE(24, 0); littleEndian.writeInt16LE(80, 2); littleEndian.writeInt16LE(0, 4); littleEndian.writeInt16LE(0, 6);
const readAs = (buf, le) => {
  const rows = le ? buf.readInt16LE(0) : buf.readInt16BE(0);
  const cols = le ? buf.readInt16LE(2) : buf.readInt16BE(2);
  return rows + 'x' + cols;
};
console.log('  真终端尺寸 24x80；小端编码 = ' + littleEndian.toString('hex'));
console.log('  用大端读它 → ' + readAs(littleEndian, false) + '（错：字节交换了）');
console.log('  用小端读它 → ' + readAs(littleEndian, true) + '（对）');
t('大端读法确实会把 24x80 读成 6144x20480', readAs(littleEndian, false) === '6144x20480',
  '实得 ' + readAs(littleEndian, false));

console.log('\n  本机架构: ' + process.arch);
const HOST_IS_LE = (() => { const p = Buffer.alloc(4); p.writeUInt16LE(1, 0); return p[0] === 1; })();
const LE_AS_BE = (() => { const p = Buffer.alloc(4); p.writeInt16BE(24, 0); return p.readInt16LE(0); })();
t('字节序探针本身可信（小端写 1 读回 1；同法大端写 24 会读成别的值）',
  HOST_IS_LE === (LE_AS_BE !== 24),
  '探针自相矛盾：小端写读=' + HOST_IS_LE + ' 大端写后小端读=' + LE_AS_BE);
t('本机是小端 —— 原生侧 memcpy 的 struct winsize 主机序即小端', HOST_IS_LE,
  '本机是大端（' + process.arch + '），那个前提不成立，判据要另作处理');

t('C 侧用 memcpy 直拷 struct winsize（主机序）',
  /memcpy\(&ws, payload, sizeof\(ws\)\)/.test(c), 'C 侧不是 memcpy → 主机序前提不成立');
t('C 侧写 READY 帧也是 memcpy 整个结构',
  /memcpy\(&ws, sizeof\(ws\)\)|memcpy\(reply \+ n, &ws, sizeof\(ws\)\)/.test(c),
  'READY 帧的 winsize 传递方式变了');

const bufUsages = [...kt.matchAll(/java\.nio\.ByteBuffer\.(allocate|wrap)\([^)]*\)/g)].map(m => m[0]);
console.log('\n  Kotlin 侧 ByteBuffer 用法共 ' + bufUsages.length + ' 处');
t('Kotlin 侧确有 ByteBuffer 用法需要检查', bufUsages.length >= 2, '实得 ' + bufUsages.length);

const orderCount = (kt.match(/ByteOrder\.LITTLE_ENDIAN/g) || []).length;
console.log('  显式 LITTLE_ENDIAN 声明数: ' + orderCount);
t('两处 ByteBuffer 都显式指定了字节序', orderCount >= 2,
  '只有 ' + orderCount + ' 处 —— 有一处仍用默认大端');

const resizeBlock = /fun resize\([\s\S]{0,1200}?host\.send\(F_WINSIZE/.exec(kt);
t('resize() 里在 putShort 之前设置了 order',
  resizeBlock && /order\(java\.nio\.ByteOrder\.LITTLE_ENDIAN\)[\s\S]{0,200}?putShort/.test(resizeBlock[0]),
  'order 不在 putShort 之前');

const readyBlock = /private fun onReady[\s\S]{0,900}?rows = b\.short/.exec(kt);
t('onReady() 里在读 short 之前设置了 order',
  readyBlock && /order\(java\.nio\.ByteOrder\.LITTLE_ENDIAN\)[\s\S]{0,300}?b\.short/.test(readyBlock[0]),
  'order 不在 b.short 之前');

t('帧头 length 用显式移位而非 ByteBuffer（所以本来就对）',
  /h\[4\] = \(len and 0xFF\)/.test(kt) && !/h\[4\]/.test(kt.replace(/h\[4\] = \(len and 0xFF\)[\s\S]{0,300}?h\[7\] = \(\(len shr 24\)/, '')),
  '帧头的写法变了');

console.log('\n== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);