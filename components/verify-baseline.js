const fs = require('fs');

const D = '/data/user/0/lobos.app/files/work';
const iso = '/data/user/0/lobos.app.verify/files';

const log = [];
const p = (s) => { log.push(s); console.log(s); };

p('══ 自签供给验证：装前基线 ══');
p('');

try {
  const m = JSON.parse(fs.readFileSync(D + '/ss-bundle/dist/userland-manifest-2.json', 'utf8'));
  p('① 供给清单（预签，7 件）:');
  p('   revision=' + m.revision + '  tools=' + m.tools.length + '  过期=' +
    new Date(Number(m.expiresEpochMs)).toISOString().slice(0, 10));
  p('   baseUrl=' + ((m.tools[0] || {}).url || '').replace(/\/userland\/.*/, ''));
  p('   件: ' + m.tools.map((t) => t.name + '@' + t.version).join(' '));
} catch (e) {
  p('① 读不到自签清单: ' + e.message);
}
p('');

p('② 隔离包状态:');
if (fs.existsSync(iso)) {
  p('   /data/user/0/lobos.app.verify 已存在 —— 装过了');
  const diag = iso + '/os/diag.jsonl';
  if (fs.existsSync(diag)) {
    const lines = fs.readFileSync(diag, 'utf8').trim().split('\n').filter(Boolean);
    const sup = lines.filter((l) => l.includes('"stage":"supply"'));
    p('   诊断里 supply 相关 ' + sup.length + ' 条：');
    for (const l of sup.slice(-3)) {
      try {
        const o = JSON.parse(l);
        p('     [' + o.level + '] ' + o.message + '  ' + (o.detail || ''));
      } catch (e) { p('     （解析失败）'); }
    }
    const tc = iso + '/usr/lib/toolchain';
    if (fs.existsSync(tc)) {
      p('   装出来的件: ' + fs.readdirSync(tc).filter((n) => !n.startsWith('.')).join(' '));
    } else {
      p('   还没有 toolchain 目录（供给还没产出件）');
    }
    const nb = iso + '/usr/bin/node';
    if (fs.existsSync(nb)) {
      const r = require('child_process').spawnSync(nb, ['-v'], { encoding: 'utf8' });
      p('   node -v → ' + (r.stdout || '').trim() + (r.status === 0 ? '' : '（跑失败）'));
      p('   node 链指向: ' + (fs.lstatSync(nb).isSymbolicLink() ? fs.readlinkSync(nb) : '（不是符号链接）'));
    } else {
      p('   还没有 usr/bin/node');
    }
  } else {
    p('   还没有 diag.jsonl（应用没起过，或供给还没写诊断）');
  }
} else {
  p('   还没装 —— 这是装前基线');
}
p('');
p('装完重跑本脚本（或 bash components/run-verify.sh）看差异。');
