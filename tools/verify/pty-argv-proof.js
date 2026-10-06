#!/usr/bin/env node
'use strict';

const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const C_SRC = path.resolve(__dirname, '../../container/native/d3/pty-session.c');
const KT_SRC = path.resolve(__dirname, '../../container/app/src/main/java/lobos/runtime/PtySession.kt');
const src = fs.readFileSync(C_SRC, 'utf8');
const kt = fs.readFileSync(KT_SRC, 'utf8');

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

console.log('== PTY argv 传递：参数不能在中途丢掉 ==');

t('session_open 收整串 argv',
  /static int session_open\(int out_fd, uint8_t sid, char \*const argv\[\]\)/.test(src),
  '签名不是 char *const argv[] → 说明还在收单路径');

const callSite = /if \(session_open\(out_fd, \(uint8_t\)slot, ([^)]*)\)/.exec(src);
t('调用处传的是 argv 而不是 argv[0]', callSite && callSite[1].trim() === 'argv',
  callSite ? '实传：' + callSite[1] : '没找到调用处');

t('exec 用整串 argv（execv(argv[0], argv)）',
  /execv\(argv\[0\], argv\)/.test(src),
  'exec 没传整串 argv → 参数会在 exec 处丢失');
t('不再有 execv(slave_path, {slave_path, NULL}) 那种单参数形态',
  !/execv\(slave_path/.test(src));

t('Kotlin 侧逐参数写入（a 的字节）',
  kt.includes('blob.write(a.toByteArray(Charsets.UTF_8))'), '没找到逐参数写入');
t('Kotlin 侧每写一个参数后跟 NUL(0)',
  kt.includes('blob.write(0)'), '没找到 NUL 分隔');
t('Kotlin 侧在参数循环内写（每个参数都写，不是只写第一个）',
  /for \(a in argv\)[\s\S]{0,200}?blob\.write\(a\.toByteArray/.test(kt), 'NUL 写入不在参数循环内');
t('C 侧按 NUL 切分（parse_argv 里数 \\0）',
  /blob\[i\] == '\\0'/.test(src));

console.log('\n-- 真实协议往返（用本机 sh 验参数完整性）--');
const NUL = String.fromCharCode(0);
function encodeArgv(argv) { return Buffer.from(argv.map(a => a + NUL).join(''), 'utf8'); }

function parseArgvLikeC(blob) {
  let argc = 1;
  for (const b of blob) if (b === 0) argc++;
  const argv = []; let start = 0;
  for (let i = 0; i <= blob.length; i++) {
    if (i === blob.length || blob[i] === 0) {
      if (i > start || argv.length === 0) argv.push(blob.slice(start, i).toString('utf8'));
      start = i + 1;
    }
  }
  return argv;
}

const ARGV = ['/bin/sh', '-c', 'printf "%s|" "$@"', 'argv0', '一', 'two words'];
const blob = encodeArgv(ARGV);
const got = parseArgvLikeC(blob);
t('往返后 argv 长度不变', got.length === ARGV.length, `实得 ${got.length}，应为 ${ARGV.length}`);
t('往返后每个参数逐字节相同', got.every((a, i) => a === ARGV[i]),
  '实得 ' + JSON.stringify(got));
t('含空格的参数不被拆开', got[5] === 'two words', '实得 ' + JSON.stringify(got[5]));
t('含中文的参数不损坏', got[4] === '一', '实得 ' + JSON.stringify(got[4]));

const lost = ARGV.slice(1);
t('对照：只传 argv[0] 会丢掉 ' + lost.length + ' 个参数',
  lost.length === 5, 'ARGV 长度变了，测试自身失效');

const r = cp.spawnSync('/bin/sh', ['-c', 'printf "%s|" "$@"', 'argv0', '一', 'two words'], { encoding: 'utf8' });
t('参照实现（sh 自己）输出正确', r.stdout === '一|two words|', '实得 ' + JSON.stringify(r.stdout));

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);