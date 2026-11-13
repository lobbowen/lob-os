#!/usr/bin/env node






const fs = require('node:fs');
const path = require('node:path');

const id = process.argv[2];
if (!id) {
  console.error('用法: read-marker.js <件名>');
  process.exit(2);
}

const root = path.resolve(__dirname, '..', '..');
const table = path.join(root, 'scripts', 'component-sources.json');

let doc;
try {
  doc = JSON.parse(fs.readFileSync(table, 'utf8'));
} catch (e) {
  
  process.exit(0);
}

const sources = doc && doc.sources ? doc.sources : {};
const entry = sources[id] || {};
const marker = entry.marker;

if (typeof marker === 'string' && marker.length > 0) {
  process.stdout.write(marker);
}
