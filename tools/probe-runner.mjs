import { spawnSync } from 'node:child_process';

let src = '';
process.stdin.setEncoding('utf8');
for await (const chunk of process.stdin) src += chunk;

if (!src.trim()) {
  process.stderr.write('probe-runner: 没有收到探针源码\n');
  process.exit(2);
}

import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

let mod;
try {
  mod = new Function('require', 'module', 'exports', 'process', 'console', src);
} catch (e) {
  process.stderr.write('probe-runner: 探针语法错误: ' + e.message + '\n');
  process.exit(3);
}

const shim = { exports: {} };
try {
  mod(require, shim, shim.exports, process, console);
} catch (e) {
  process.stderr.write('probe-runner: 探针抛错: ' + (e && e.message ? e.message : String(e)) + '\n');
  process.exit(4);
}
process.exit(0);
