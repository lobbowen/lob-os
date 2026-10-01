'use strict';

const http = require('http');
const os = require('os');
const url = require('url');

const DEFAULT_PORT = 3080;

function resolvePort(argv) {
  const i = argv.indexOf('--port');
  if (i > -1 && argv[i + 1] !== undefined) return argv[i + 1];

  for (let k = 2; k < argv.length; k++) {
    if (/^\d+$/.test(argv[k])) return argv[k];
  }

  return String(DEFAULT_PORT);
}

const PORT = parseInt(resolvePort(process.argv), 10);
const HOST = '127.0.0.1';

if (!Number.isInteger(PORT) || PORT <= 0 || PORT >= 65536) {
  console.error(
    `[node-container] 端口无效: ${JSON.stringify(process.argv)}\n` +
    `  期望形式: --port <1-65535>  或  直接给数字\n` +
    `  解析结果: ${PORT}`
  );
  process.exit(2);
}

console.log('[node-container] argv =', JSON.stringify(process.argv));
console.log(`[node-container] node ${process.version} / ${process.platform}-${process.arch}`);

const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url, true);

  if (parsed.pathname === '/api/version') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      container: 'lobos',
      node: process.version,
      lts: process.release.lts || null,
      platform: process.platform,
      arch: process.arch,
      v8: process.versions.v8,
      openssl: process.versions.openssl,
      uptime: process.uptime(),
      cpus: os.cpus().length,
    }, null, 2));
    return;
  }

  if (parsed.pathname === '/api/echo' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ echo: body, receivedAt: new Date().toISOString() }));
    });
    return;
  }

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(
    `<h1>Node.js 已在 Android 上原生运行</h1>` +
    `<p>Node ${process.version} • ${process.platform}/${process.arch} • OpenSSL ${process.versions.openssl}</p>` +
    `<p><a href="/api/version">/api/version</a> · POST <code>/api/echo</code></p>`
  );
});

server.listen(PORT, HOST, () => {
  console.log(`[node-container] listening on http://${HOST}:${PORT}`);
});
