'use strict';

const fs = require('fs');
const path = require('path');

const SCHEMA = 2;

function runtimeJsonPath(home) {
  return path.join(home, 'supervisor', 'runtime.json');
}

function writeRuntimeJson({ home, nodePath, nodeBinDir, prefix, minNode, writtenBy }) {
  const dir = path.join(home, 'supervisor');
  fs.mkdirSync(dir, { recursive: true });
  const obj = {
    schema: SCHEMA,
    nodePath,
    nodeBinDir,
    ...(prefix ? { prefix } : {}),
    minNode: minNode || 'v24.12.0',
    writtenBy: writtenBy || 'lobos-os',
  };
  fs.writeFileSync(runtimeJsonPath(home), JSON.stringify(obj, null, 2), { mode: 0o600 });
  return obj;
}

function readRuntimeJson(home) {
  const p = runtimeJsonPath(home);
  if (!fs.existsSync(p)) return null;
  const o = JSON.parse(fs.readFileSync(p, 'utf8'));
  if (o.schema !== SCHEMA) throw new Error('runtime.json schema 不匹配：期望 ' + SCHEMA + ' 实际 ' + o.schema);
  return o;
}

module.exports = { SCHEMA, runtimeJsonPath, writeRuntimeJson, readRuntimeJson };
