#!/usr/bin/env bash
# 能力面判据：声明表与实现表必须对得上。
#
# 这是写法层面的检查，不是设计判断：
#   · CANONICAL 里每个规范名的短名，必须在 METHODS ∪ OS_METHODS 里有实现
#   · SCOPES / IDEMPOTENCE 里每个规范名，必须能反查到那个短名
#
# 它存在的原因：这套能力面曾经 39 个规范名一个都调不通
#（声明用lobos.*、实现用短名，而 dispatch 没有调 canonical()）
# 而当时没有任何门禁、文档或诊断会发现它。
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

API="container/app/src/main/java/lobos/api/ApiSpec.kt"
BROKER="container/app/src/main/java/lobos/api/CapabilityBroker.kt"
BAD=0

node - "$API" "$BROKER" <<'NODE' || BAD=1
const fs = require('fs');
const [apiPath, brokerPath] = process.argv.slice(2);
const api = fs.readFileSync(apiPath, 'utf8');
const broker = fs.readFileSync(brokerPath, 'utf8');

const keys = new Set([...broker.matchAll(/"([A-Za-z._]+)" to MethodDef/g)].map((m) => m[1]));
const pairs = [...api.matchAll(/"([A-Za-z._]+)" to "([A-Za-z._]+)"/g)].map((m) => [m[1], m[2]]);
const reverse = new Map(pairs.map(([s, l]) => [l, s]));

function section(name) {
  const i = api.indexOf('val ' + name + ' =');
  if (i < 0) return [];
  let depth = 0;
  let start = api.indexOf('(', i);
  let end = start;
  for (; end < api.length; end++) {
    if (api[end] === '(') depth++;
    if (api[end] === ')') { depth--; if (!depth) break; }
  }
  return [...api.slice(start, end).matchAll(/"([A-Za-z._]+)"/g)].map((m) => m[1]);
}

let bad = 0;

const dangling = pairs.filter(([short]) => !keys.has(short));
if (dangling.length) {
  bad++;
  console.log('::error title=CANONICAL 指向不存在的实现::' + dangling.map((p) => p[0]).join(', '));
}

for (const name of ['SCOPES', 'IDEMPOTENCE']) {
  const entries = section(name);
  const missing = entries.filter((k) => !keys.has(reverse.get(k) || k));
  if (missing.length) {
    bad++;
    console.log('::error file=' + apiPath + ',title=' + name + ' 悬空::' + missing.join(', '));
  }
}

const dupKey = [];
const seen = new Map();
for (const [k, v] of pairs) {
  if (seen.has(k)) dupKey.push(k);
  seen.set(k, v);
}
if (dupKey.length) {
  bad++;
  console.log('::error file=' + apiPath + ',title=CANONICAL 重复 key::mapOf 会静默覆盖::' + dupKey.join(', '));
}

const multi = new Map();
for (const [k, v] of pairs) multi.set(v, (multi.get(v) || 0) + 1);
const multiSrc = [...multi].filter(([, c]) => c > 1);
if (multiSrc.length) {
  bad++;
  console.log('::error file=' + apiPath + ',title=一个规范名有多个源::' + multiSrc.map(([v, c]) => v + '×' + c).join(', '));
}

if (!bad) console.log('[ok] 能力面判据通过');
process.exit(bad ? 1 : 0);
NODE

if [ "$BAD" -ne 0 ]; then
  echo "[fail] 能力面判据未通过"
  exit 1
fi
echo "[ok] 能力面：声明表与实现表一致"
