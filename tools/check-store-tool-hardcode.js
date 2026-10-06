#!/usr/bin/env node
'use strict';

// 商店安装的件，代码里不许硬编码。
//
// 判据来源（本仓真实问题）：
//   · ProgramManager.nodeBin() 硬编码拼 stateDirOf("node")/<ver>/bin/node，
//     而 NodeRuntime.path() 又调它 —— 互相调用 + 换落位位置就找不到
//   · PrefixProvisioner 里有 linkNode / NODE_BIN_NAME，底座管理者替商店件建软链
//   · libc++_shared.so 被写成「node 在场才算应有件」，而它是 C++ 运行库，与 node 无关
//
// 结论：node/npm/curl/git/jq/sqlite3/npx/pnpm 走商店安装，代码不该单独认识某一件。
// 底座该有什么由 PrefixProvisioner 声明；某件装没装由安装器与注册表回答。
//
// 例外：node 在「程序宿主」里有正当角色（程序后端就是 node 进程）。
// 那些引用按 `node-gyp` / `NODE_BIN` / 诊断文案等白名单放行，
// 但不许再出现「自己拼 node 的落位路径」。

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'container/app/src/main/java');

function walk(dir, out) {
  out = out || [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (p.endsWith('.kt')) out.push(p);
  }
  return out;
}

const TOOLS = ['node', 'npm', 'npx', 'pnpm', 'curl', 'git', 'jq', 'sqlite3'];

// 同名不同物：AccessibilityNodeInfo 的局部变量 node
const NOT_A_TOOL = /AccessibilityNodeInfo|nodeToJson|ev\.source|getBoundsInScreen|node\.(recycle|performAction|childCount|text|is[A-Z]|contentDescription|className|packageName|viewIdResourceName|getChild)|matchNode|node: Accessibility/;

// 正当引用：诊断文案、对外能力暴露、环境变量名、asset 路径、说明性字符串
const LEGIT = /RuntimeDiagnostics\.append|Journal\.(append|note)|"node-stderr|node-stderr|NODE_BIN|node-gyp|assets\/node\/|put\("name", "node"\)|NodeRuntime\.(path|version|missing|NAME)|node 进程|node 标准错误|node 运行时/;

// 纯说明性字符串（不含可执行代码，如 note = "…" 跨行拼接的后半段）
const PROSE_STRING = /^\s*"[^"]*"\s*,?\s*$/;

const problems = [];
for (const tool of TOOLS) {
  const re = new RegExp('(^|[^A-Za-z0-9_])' + tool + '([^A-Za-z0-9_]|$)');
  for (const f of walk(JAVA)) {
    const rel = f.replace(JAVA + '/', '');
    if (rel.toLowerCase().includes(tool)) continue;
    const lines = fs.readFileSync(f, 'utf8').split('\n');
    lines.forEach((line, i) => {
      const t = line.trim();
      if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return;
      // 续行注释（字符串里含 // 或 *）也算注释
      if (/^\*?\s*\/\//.test(t) || /\/\/\s*"/.test(t) || /"\s*,\s*\/\//.test(t)) return;
      if (PROSE_STRING.test(line)) return;
      if (NOT_A_TOOL.test(line) || LEGIT.test(line)) return;
      if (!re.test(line)) return;
      // 真正要抓的：自己拼某个件的落位路径
      if (!/stateDirOf|ProgramDir|bin\/|usr\/lib|entryLink|File\(/.test(line)) return;
      problems.push(tool + '  ' + rel + ':' + (i + 1) + '  ' + t.slice(0, 76));
    });
  }
}

// 底座管理器不许替商店件干活
const prefixFile = path.join(JAVA, 'lobos/runtime/PrefixProvisioner.kt');
if (fs.existsSync(prefixFile)) {
  const t = fs.readFileSync(prefixFile, 'utf8');
  if (/linkNode|NODE_BIN_NAME/.test(t)) {
    problems.push('PrefixProvisioner 里有 linkNode/NODE_BIN_NAME：底座管理者不该替商店件建软链（那是安装器 linkEntry 的事）');
  }
  if (/nodePresent/.test(t)) {
    problems.push('PrefixProvisioner.expected() 仍按 nodePresent 分支：' +
      '底座必备性不能与某个商店件绑定（libc++ 与 node 无关）');
  }
}

if (problems.length) {
  console.log('FAIL 商店件硬编码门禁：' + problems.length + ' 处');
  for (const p of problems) console.log('  ✗ ' + p);
  console.log('');
  console.log('判据：商店安装的件，代码不单独认识它。');
  console.log('例外已在门禁里按白名单放行（诊断文案、对外能力、环境变量名、同名变量）。');
  process.exit(1);
}

console.log('PASS 商店件硬编码门禁：');
console.log('  · ' + TOOLS.join(' / ') + ' 均无自拼落位路径的硬编码');
console.log('  · 底座管理器（PrefixProvisioner）不替商店件建软链、不按商店件分支');
console.log('  · 正当引用已放行：诊断文案 / 对外能力 / 环境变量名 / AccessibilityNodeInfo 同名变量');
console.log('');