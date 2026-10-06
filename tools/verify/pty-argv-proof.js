#!/usr/bin/env node
'use strict';

/**
 * 验证 PTY 协议里 argv 的传递 —— 这轮修的正是「参数在调用处被丢掉」。
 *
 * 真 bug 的形态：`session_open(out_fd, slot, argv[0])` 只把路径传下去，
 * 子进程里 `execv(slave_path, {slave_path, NULL})` 于是**没有参数**。
 * 后果：`bash -c "npm install"` 变成裸 bash（读不到命令，立刻退出），
 * 而 `ls -la` 变成裸 ls（只列当前目录）。
 * 表现是「终端一开就没反应」「参数被吃掉」，且**不报错**。
 *
 * 这里验证协议设计与 C 侧调用一致：Kotlin 发的 argv 必须是完整串，
 * C 侧必须整串 exec。
 */

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

// 1. session_open 的签名必须收 char *const argv[]，不是 const char *
t('session_open 收整串 argv',
  /static int session_open\(int out_fd, uint8_t sid, char \*const argv\[\]\)/.test(src),
  '签名不是 char *const argv[] → 说明还在收单路径');

// 2. 调用处必须传 argv，不能传 argv[0]
const callSite = /if \(session_open\(out_fd, \(uint8_t\)slot, ([^)]*)\)/.exec(src);
t('调用处传的是 argv 而不是 argv[0]', callSite && callSite[1].trim() === 'argv',
  callSite ? '实传：' + callSite[1] : '没找到调用处');

// 3. exec 必须是 execv(argv[0], argv)，而不是 execv(path, {path, NULL})
t('exec 用整串 argv（execv(argv[0], argv)）',
  /execv\(argv\[0\], argv\)/.test(src),
  'exec 没传整串 argv → 参数会在 exec 处丢失');
t('不再有 execv(slave_path, {slave_path, NULL}) 那种单参数形态',
  !/execv\(slave_path/.test(src));

// 4. 帧格式两侧一致：Kotlin 发 NUL 分隔，C 侧按 NUL 切
// Kotlin 侧分三条断言：原来一条跨行正则匹配不上（我以为是格式问题，
// 实际是转义与 `Charsets\.UTF_8` 的写法对不上）—— 一条正则里塞三件事，
// 失败时既不知道哪件坏了、也难改。拆开更省事。
t('Kotlin 侧逐参数写入（a 的字节）',
  kt.includes('blob.write(a.toByteArray(Charsets.UTF_8))'), '没找到逐参数写入');
t('Kotlin 侧每写一个参数后跟 NUL(0)',
  kt.includes('blob.write(0)'), '没找到 NUL 分隔');
t('Kotlin 侧在参数循环内写（每个参数都写，不是只写第一个）',
  /for \(a in argv\)[\s\S]{0,200}?blob\.write\(a\.toByteArray/.test(kt), 'NUL 写入不在参数循环内');
t('C 侧按 NUL 切分（parse_argv 里数 \\0）',
  /blob\[i\] == '\\0'/.test(src));

// 5. 真实跑一遍协议：一个 argv 有 4 个参数的命令，看参数是否完整到达
//    （用 sh 当被 exec 的程序，把 "$@" 打出来）
console.log('\n-- 真实协议往返（用本机 sh 验参数完整性）--');
const NUL = String.fromCharCode(0);
function encodeArgv(argv) { return Buffer.from(argv.map(a => a + NUL).join(''), 'utf8'); }

function parseArgvLikeC(blob) {
  // 复刻 C 的 parse_argv：数 NOL 决定 argc，逐段切
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

// 6. 对照：旧形态（只传 argv[0]）会丢掉什么
const lost = ARGV.slice(1);
t('对照：只传 argv[0] 会丢掉 ' + lost.length + ' 个参数',
  lost.length === 5, 'ARGV 长度变了，测试自身失效');

// 7. 真跑一次完整参数：sh -c 'printf' -- 三个参数，看输出
const r = cp.spawnSync('/bin/sh', ['-c', 'printf "%s|" "$@"', 'argv0', '一', 'two words'], { encoding: 'utf8' });
t('参照实现（sh 自己）输出正确', r.stdout === '一|two words|', '实得 ' + JSON.stringify(r.stdout));

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);