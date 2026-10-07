#!/usr/bin/env node
'use strict';

const fs = require('fs');
const https = require('https');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const TABLE = path.join(ROOT, 'scripts', 'component-sources.json');

function die(m) {
  console.error('::error title=' + (m.title || '钉值失败') + '::' + (m.msg || ''));
  process.exit(1);
}

function getJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { 'User-Agent': 'lobos-pin-github-release' }, timeout: 30000 }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.destroy();
        return getJson(res.headers.location).then(resolve, reject);
      }
      if (res.statusCode !== 200) {
        return reject(new Error('HTTP ' + res.statusCode + ' —— ' + url));
      }
      let s = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { s += c; });
      res.on('end', () => {
        try { resolve(JSON.parse(s)); } catch (e) { reject(new Error('响应不是合法 JSON：' + e.message)); }
      });
    });
    req.on('error', (e) => reject(new Error('网络：' + (e.code || e.message))));
    req.on('timeout', () => { req.destroy(); reject(new Error('超时')); });
  });
}

async function main() {
  const a = process.argv.slice(2);
  const arg = (name) => {
    const i = a.indexOf('--' + name);
    return i >= 0 ? a[i + 1] : null;
  };
  const has = (name) => a.includes('--' + name);

  const repo = arg('repo');
  const tag = arg('tag');
  const key = arg('key');
  const match = arg('match');
  const dry = has('dry');

  if (!repo || !tag || !key || !match) {
    die({ msg: '用法: --repo <owner/repo> --tag <tag> --key <钉值表键> --match <资产名正则> [--dry]' });
  }

  const url = 'https://api.github.com/repos/' + repo + '/releases/tags/' + encodeURIComponent(tag);
  let rel;
  try {
    rel = await getJson(url);
  } catch (e) {
    die({ msg: '读 release 失败：' + e.message });
  }

  const re = new RegExp(match);
  const hits = (rel.assets || []).filter((x) => re.test(x.name));
  if (!hits.length) {
    const sample = (rel.assets || []).slice(0, 8).map((x) => x.name).join(', ');
    die({ msg: '没有资产名匹配 ' + match + '\n  该 release 的资产样例：' + (sample || '(无)') });
  }
  if (hits.length > 1) {
    die({
      msg: '有 ' + hits.length + ' 个资产匹配 ' + match + '，必须让 --match 只命中一个：\n  '
        + hits.map((x) => x.name).join('\n  '),
    });
  }
  const asset = hits[0];

  if (!asset.digest || !/^sha256:[0-9a-f]{64}$/.test(asset.digest)) {
    die({
      msg: '该资产没有可用的 sha256 digest（GitHub 有时不给）\n  '
        + '  资产：' + asset.name + '\n  拿到：' + JSON.stringify(asset.digest || null)
        + '\n  → 那就只能下载后自己算：\n'
        + '    curl -fsSL -o /tmp/a.txz "' + asset.browser_download_url + '"\n'
        + '    sha256sum /tmp/a.txz',
    });
  }
  const sha = asset.digest.slice('sha256:'.length);

  const out = {
    version: arg('version') || tag.replace(/^llvmorg-/, ''),
    sha256: sha,
    urls: [asset.browser_download_url],
    asset: asset.name,
    bytes: asset.size,
  };

  console.log('== 从 GitHub release 取到 ==');
  console.log('  repo     : ' + repo);
  console.log('  tag      : ' + tag);
  console.log('  资产     : ' + asset.name + '  (' + (asset.size / 1048576).toFixed(1) + ' MiB)');
  console.log('  sha256   : ' + sha + '   ← 取自 API 的 digest 字段，未下载');
  console.log('  url      : ' + asset.browser_download_url);
  console.log('');
  console.log('== 将写入 scripts/component-sources.json 的 sources.' + key + ' ==');
  console.log(JSON.stringify(out, null, 2));

  if (dry) {
    console.log('\n[dry] 没有写盘');
    return;
  }

  const tab = JSON.parse(fs.readFileSync(TABLE, 'utf8'));
  const prev = (tab.sources || {})[key];
  if (prev) {
    console.log('\n[替换] 原有：' + JSON.stringify({ version: prev.version, sha256: prev.sha256 }));
  }
  tab.sources = tab.sources || {};
  tab.sources[key] = { version: out.version, sha256: out.sha256, urls: out.urls };
  fs.writeFileSync(TABLE, JSON.stringify(tab, null, 2) + '\n');
  console.log('\n[ok] 已写入 ' + path.relative(ROOT, TABLE) + ' 的 sources.' + key);
  console.log('     校验：node scripts/gen-native-assets.js && node tools/check-components.js');
}

main().catch((e) => die({ msg: e && e.message ? e.message : String(e) }));