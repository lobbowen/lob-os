#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const cp = require('node:child_process');
const os = require('node:os');

const W = fs.mkdtempSync(path.join(os.tmpdir(), 'lobos-inc-'));
const PREFIX = path.join(W, 'usr');
const INCLUDE = path.join(PREFIX, 'include');
const PROGRAMS = path.join(W, 'files', 'programs', 'sysroot');

let PASS = 0, FAIL = 0;
const t = (name, cond, detail) => {
  if (cond) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + (detail ? '\n         ' + detail : '')); FAIL++; }
};

function makeSysroot(version) {
  const d = path.join(PROGRAMS, version, 'sysroot', 'include');
  fs.mkdirSync(d, { recursive: true });
  fs.writeFileSync(path.join(d, 'stdio.h'), `/* sysroot ${version} */\nint marker_${version.replace(/\W/g, '_')};\n`);
  fs.writeFileSync(path.join(PROGRAMS, 'CURRENT'), version + '\n');
  return path.join(PROGRAMS, version, 'sysroot', 'include');
}

function linkSysrootInclude() {
  const curFile = path.join(PROGRAMS, 'CURRENT');
  if (!fs.existsSync(curFile)) return null;
  const version = fs.readFileSync(curFile, 'utf8').trim();
  if (!version) return null;
  const inc = path.join(PROGRAMS, version, 'sysroot', 'include');
  if (!fs.existsSync(inc)) return null;
  try {
    fs.mkdirSync(PREFIX, { recursive: true });
    const cur = fs.existsSync(INCLUDE) ? fs.realpathSync(INCLUDE) : null;
    if (cur === fs.realpathSync(inc)) return 'include';
    if (fs.existsSync(INCLUDE) && !fs.lstatSync(INCLUDE).isSymbolicLink()) return null;
    if (fs.existsSync(INCLUDE)) fs.unlinkSync(INCLUDE);
    fs.symlinkSync(inc, INCLUDE);
    return 'include';
  } catch (e) { return null; }
}

const probe = () => cp.spawnSync('sh', ['-c',
  'test -f "' + path.join(INCLUDE, 'stdio.h') + '" && echo 有 || echo 无',
], { encoding: 'utf8' }).stdout.trim();

console.log('== $PREFIX/include 软链行为验证 ==');

fs.rmSync(W, { recursive: true, force: true });

let r = linkSysrootInclude();
t('sysroot 不在位时不建链', r === null && !fs.existsSync(INCLUDE));

makeSysroot('29.0.14206865');
r = linkSysrootInclude();
t('sysroot 在位时建链', r === 'include' && fs.existsSync(INCLUDE));
t('链是真软链（不是拷贝 —— 升级才跟得上）', fs.lstatSync(INCLUDE).isSymbolicLink());
t('configure 按 /usr/include 约定能找到 stdio.h', probe() === '有');
t('找到的是当前版本的头文件',
  fs.readFileSync(path.join(INCLUDE, 'stdio.h'), 'utf8').includes('29.0.14206865'));

makeSysroot('30.0.12345678');
r = linkSysrootInclude();
t('升级后重建链', r === 'include');
t('链指向新版本（否则升级后头文件还是旧的 → 静默错）',
  fs.readFileSync(path.join(INCLUDE, 'stdio.h'), 'utf8').includes('30.0.12345678'));
t('configure 仍能找到（回归）', probe() === '有');

const before = fs.lstatSync(INCLUDE).ino;
linkSysrootInclude();
t('重复 provision 不重建链（省 IO，且避免瞬时不可用）', fs.lstatSync(INCLUDE).ino === before);

fs.unlinkSync(INCLUDE);
fs.mkdirSync(INCLUDE);
fs.writeFileSync(path.join(INCLUDE, 'marker'), 'user put this here');
r = linkSysrootInclude();
t('include 下有真文件时不覆盖（那可能是用户自己放的）', r === null && fs.existsSync(path.join(INCLUDE, 'marker')));

fs.rmSync(W, { recursive: true, force: true });
console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);