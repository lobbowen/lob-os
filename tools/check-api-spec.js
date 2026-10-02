const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const BROKER = path.join(ROOT, 'container/app/src/main/java/lobos/bridge/CapabilityBroker.kt');
const SPEC = path.join(ROOT, 'container/app/src/main/java/lobos/bridge/ApiSpec.kt');

const broker = fs.readFileSync(BROKER, 'utf8');
const spec = fs.readFileSync(SPEC, 'utf8');

const problems = [];

const methods = [...broker.matchAll(/"([a-z][a-zA-Z0-9_.]+)"\s+to\s+MethodDef/g)].map((m) => m[1]);
const uniq = [...new Set(methods)].sort();

if (uniq.length !== methods.length) {
  const seen = new Set();
  const dup = methods.filter((m) => (seen.has(m) ? true : (seen.add(m), false)));
  problems.push('方法表里有重复注册：' + [...new Set(dup)].join(', '));
}

for (const m of uniq) {
  if (m.startsWith('bridge.')) continue;
  const prefixed = m.startsWith('lobos.') || m.startsWith('os.');
  if (!prefixed) {
    const isAlias = spec.includes('"' + m + '" to "lobos.');
    if (!isAlias) {
      problems.push('方法 ' + m + ' 既没有 lobos.* 标准名前缀，也未在 ApiSpec 里登记为弃用别名');
    }
  }
}

const codesInSpec = [...spec.matchAll(/put\("CODE_[A-Z_]+",\s*(-?\d+)\)/g)].map((m) => m[1]);
const dupCodes = codesInSpec.filter((c, i) => codesInSpec.indexOf(c) !== i);
if (dupCodes.length) problems.push('错误码重复：' + [...new Set(dupCodes)].join(', '));

const codesInBroker = [...broker.matchAll(/const val CODE_[A-Z_]+ = (-?\d+)/g)].map((m) => m[1]);
for (const c of codesInBroker) {
  if (!codesInSpec.includes(c)) problems.push('桥里定义了错误码 ' + c + ' 但未登记进 ApiSpec.ERROR_CODES');
}

if (!/object ApiSpec/.test(spec)) problems.push('ApiSpec.kt 缺失或已改名');
if (!/protocol",\s*"/.test(broker) && !/put\("protocol"/.test(broker)) {
  problems.push('sys.api 不再返回 protocol 段，规范自描述不完整');
}

if (problems.length) {
  console.error('[api-spec] 桥接方法表与 ApiSpec 不一致：');
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}

console.log('[api-spec] ' + uniq.length + ' 个方法与 ApiSpec 一致：命名空间前缀、弃用别名、错误码登记均无漂移');
