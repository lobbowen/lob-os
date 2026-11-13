#!/usr/bin/env node









const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..', '..');
const table = path.join(root, 'scripts', 'component-sources.json');

let doc;
try {
  doc = JSON.parse(fs.readFileSync(table, 'utf8'));
} catch (e) {
  
  console.error('钉值表读不出: ' + e.message);
  process.exit(1);
}

const sources = (doc && doc.sources) || {};








const PIECE_IDS = Object.keys(sources).filter((id) => sources[id] && sources[id].piece === true);

const out = PIECE_IDS.filter((id) => {
  const e = sources[id] || {};
  
  return e.notStandalone !== true;
});

process.stdout.write(out.join(' '));
