#!/usr/bin/env node
'use strict';





const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '../..');

const argv = process.argv.slice(2);
const positional = [];
let payloadArg = null;
let printEntry = null;
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--payload') {
    payloadArg = argv[i + 1] || '';
    i++;
  } else if (a === '--print-entry') {
    
    
    
    
    
    
    
    printEntry = { id: argv[i + 1] || '', payload: argv[i + 2] || '' };
    i += 2;
  } else if (a.startsWith('--build-sha=') || a.startsWith('--build-run=')) {
    
    
  } else if (a.startsWith('--')) {
    console.error('::error title=未知参数::' + a);
    process.exit(1);
  } else {
    positional.push(a);
  }
}

if (positional.length === 4) {
  
  console.error(
    '::error title=entry 不再由命令行传入::用法: node gen-component-meta.js <件名> <版本> <输出.json> [--payload <落位目录>]'
    + '（entry 只能由本脚本从落位结果算出 —— 人填的那一条已废止）');
  process.exit(1);
}

const [id0, version0, outPath0] = positional;
const id = printEntry ? printEntry.id : id0;
const version = printEntry ? '' : version0;
const outPath = printEntry ? '' : outPath0;
if (!printEntry && (!id || !version || !outPath)) {
  console.error('用法: node gen-component-meta.js <件名> <版本> <输出.json> [--payload <落位目录>]');
  process.exit(1);
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

let PAYLOAD = payloadArg ? path.resolve(payloadArg) : (outPath ? path.dirname(path.resolve(outPath)) : process.cwd());

function listFiles(dir) {
  let names = [];
  try {
    names = fs.readdirSync(dir);
  } catch (e) {
    return null;
  }
  return names.filter((n) => {
    try {
      const st = fs.lstatSync(path.join(dir, n));
      return st.isFile() || st.isSymbolicLink();
    } catch (e) {
      return false;
    }
  }).sort();
}

function elf_form_of(file) {
  let fd;
  try { fd = fs.openSync(file, 'r'); } catch (e) { return 'unreadable'; }
  try {
    const head = Buffer.alloc(64);
    const n = fs.readSync(fd, head, 0, 64, 0);
    if (n < 18) return 'unreadable';
    if (head[0] !== 0x7f || head[1] !== 0x45 || head[2] !== 0x4c || head[3] !== 0x46) {
      
      
      
      
      
      
      
      
      
      if (n >= 2 && head[0] === 0x23 && head[1] === 0x21) {
        
        const line = fs.readFileSync(file, 'utf8').split('\n', 1)[0];
        if (line.slice(2).trim() !== '') return 'shebang';
      }
      return 'unreadable';
    }
    const etype = head.readUInt16LE(16);
    if (etype === 2) return 'exec';
    if (etype === 3) {
      const phoff = Number(head.readBigUInt64LE(0x20));
      const phentsize = head.readUInt16LE(0x36);
      const phnum = head.readUInt16LE(0x38);
      if (phoff > 0 && phnum > 0) {
        const ph = Buffer.alloc(phentsize);
        for (let i = 0; i < phnum; i++) {
          fs.readSync(fd, ph, 0, phentsize, phoff + i * phentsize);
          if (ph.readUInt32LE(0) === 3) return 'dyn-interp';
        }
      }
      return 'dyn-plain';
    }
    return 'unreadable';
  } catch (e) { return 'unreadable'; }
  finally { try { fs.closeSync(fd); } catch (e) {} }
}

function is_program_form(dir, name) {
  const f = elf_form_of(path.join(dir, name));
  return f === 'exec' || f === 'dyn-interp' || f === 'shebang';
}

function same_bytes(dir, names) {
  if (names.length === 0) return false;
  const shaOf = (n) => {
    try { return require('crypto').createHash('sha256').update(fs.readFileSync(path.join(dir, n))).digest('hex'); } catch (e) { return ''; }
  };
  const first = shaOf(names[0]);
  if (!first) return false;
  for (const n of names) { if (shaOf(n) !== first) return false; }
  return true;
}

function readAuthorEntryName(pieceId) {
  let table = {};
  try {
    table = readJson(path.join(ROOT, 'scripts/component-sources.json')).sources || {};
  } catch (e) {
    return '';
  }
  const e = table[pieceId] || {};
  const v = e.entryName;
  return (typeof v === 'string' && v.length > 0) ? v : '';
}

function computeEntry() {
  const libDir = path.join(PAYLOAD, 'lib');
  const binDir = path.join(PAYLOAD, 'bin');

  const libNames = listFiles(libDir) || [];
  const libPrimary = libNames.filter((n) => n.endsWith('.so'));

  const binNames = listFiles(binDir) || [];
  const binProgram = binNames.filter((n) => is_program_form(binDir, n));

  if (binProgram.length === 1) {
    return {
      entry: 'bin/' + binProgram[0],
      by: '落位结果：bin/ 下唯一程序形态字节（ELF e_type/PT_INTERP）'
        + (libPrimary.length > 0 ? '；该件同时带库 ' + libPrimary.length + ' 份，入口只指程序那份' : ''),
      role: 'program',
      mixed: libPrimary.length > 0,
      libs: libPrimary,
    };
  }
  if (binProgram.length > 1) {
    
    
    
    
    
    

    const isMultiCall = same_bytes(binDir, binProgram);
    if (!isMultiCall) {
      

      const declaredEntry = readAuthorEntryName(id);
      if (!declaredEntry) {
        return { error: '落位目录 ' + PAYLOAD + ' 的 bin/ 下有 ' + binProgram.length
          + ' 个程序形态字节（' + binProgram.join('、')
          + '），而它们**不是同一份字节**（不是多调用二进制那一族，'
          + '是「这一件有多个入口」那一问）'
          + '—— 而这一件**没有**声明该取哪一个（一格落在'
          + 'component-sources.json 的 sources.<件>.entryName，它要**作者那一侧**给）' };
      }
      if (binProgram.indexOf(declaredEntry) < 0) {
        return { error: '这一件声明的入口名 ' + declaredEntry + ' 不在落位结果那几项里（'
          + binProgram.join('、') + '）—— 而那不是一个可用的入口名' };
      }
      return {
        entry: 'bin/' + declaredEntry,
        by: '落位结果：bin/ 下有 ' + binProgram.length + ' 个程序形态字节（'
          + binProgram.join('、') + '），而它们**不是同一份字节**（不是多调用那一族）'
          + '；入口取作者那一侧声明的那一个 bin/' + declaredEntry
          + (libPrimary.length > 0 ? '；该件同时带库 ' + libPrimary.length + ' 份' : ''),
        role: 'program',
        mixed: libPrimary.length > 0,
        libs: libPrimary,
      };
    }

    const shaOf = (f) => { try { return require('crypto').createHash('sha256').update(fs.readFileSync(f)).digest('hex'); } catch (e) { return ''; } };
    const first = path.join(binDir, binProgram[0]);
    const firstSha = shaOf(first);
    let sameTarget = firstSha !== '';
    if (sameTarget) {
      for (const n of binProgram) {
        if (shaOf(path.join(binDir, n)) !== firstSha) { sameTarget = false; break; }
      }
    }
    if (!sameTarget) {
      return { error: '落位目录 ' + PAYLOAD + ' 的 bin/ 下有 ' + binProgram.length
        + ' 个程序形态字节，而它们**不是同一份**（' + binProgram.join('、')
        + '）—— 那是多个入口，该件只应有一个' };
    }

    let body = binProgram[0];
    for (const n of binProgram) {
      const f = path.join(binDir, n);
      try { if (fs.realpathSync(f) === path.resolve(f)) { body = n; break; } } catch (e) {}
    }

    const aliasNames = binProgram.filter((n) => n !== body);
    
    const declared = readAuthorEntryName(id);
    if (!declared) {
      return { error: '落位目录 ' + PAYLOAD + ' 的 bin/ 下有 ' + binProgram.length
        + ' 个程序形态字节（多调用二进制），而这一件**没有**声明入口该取它自己 install 的那一组名字里的哪一个'
        + '（本体那份 bin/' + body + ' 不认自己的文件名；它自己 install 的是 '
        + (aliasNames.length ? aliasNames.join('、') : '空') + '）'
        + '—— 而「取哪一个」是**使用期**的分布（busybox 认全部那些名字，它没有「唯一一个更真」的名字）'
        + '它要**那一件的作者那一侧**给（一份落在 component-sources.json 的 sources.<件>.entryName）' };
    }
    if (aliasNames.indexOf(declared) < 0) {
      return { error: '这一件声明的入口名 ' + declared + ' 不在它自己 install 的那一组里（'
        + (aliasNames.length ? aliasNames.join('、') : '空') + '）—— 而本体那份 bin/' + body
        + ' 不认自己的文件名，不是一个可用的入口名' };
    }
    return {
      entry: 'bin/' + declared,
      by: '落位结果：bin/ 下 ' + binProgram.length + ' 项是同一份字节的多个名字（多调用二进制）；'
        + '它自己 install 的是 ' + (aliasNames.length ? aliasNames.join('、') : '空')
        + '（本体那份 bin/' + body + ' 不认自己的文件名）'
        + '；入口取作者那一侧声明的那一个 bin/' + declared
        + (libPrimary.length > 0 ? '；该件同时带库 ' + libPrimary.length + ' 份' : ''),
      role: 'program',
      mixed: libPrimary.length > 0,
      multiCall: binProgram.length,
      libs: libPrimary,
    };
  }

  if (binNames.length > 0) {
    return { error: '落位目录 ' + PAYLOAD + ' 的 bin/ 下有 ' + binNames.length
      + ' 个文件，但没有一个是程序形态（' + binNames.join('、') + '）—— 那它们不是入口，entry 不该从 bin/ 取' };
  }

  if (libPrimary.length >= 1) {
    return { entry: '', role: 'library', by: '落位结果：纯库件（bin/ 下无程序形态字节）=> 没有程序那一侧，entry 留空；库字节落在 lib/（'+libPrimary.length+' 份）', mixed: false, libs: libPrimary };
  }
  if (libPrimary.length > 1) {
    return { error: '落位目录 ' + PAYLOAD + ' 的 lib/ 下有 ' + libPrimary.length
      + ' 个主名 .so（' + libPrimary.join('、') + '）—— 该件的落位结果不唯一（把别的件拆出去，别往命令行塞 entry）' };
  }

  return { error: '落位目录 ' + PAYLOAD + ' 里没有落位结果（lib/ 下无主名 .so，bin/ 下无文件）—— 清单不写没有落位依据的 entry' };
}

if (printEntry) {
  if (!printEntry.id || !printEntry.payload) {
    console.error('用法: node gen-component-meta.js --print-entry <件名> <落位目录>');
    process.exit(1);
  }
  PAYLOAD = path.resolve(printEntry.payload);
  const c = computeEntry();
  if (c.error) {
    
    process.exit(0);
  }
  process.stdout.write(String(c.entry || ''));
  process.exit(0);
}

const computed = computeEntry();



















function isCommandInterpreter(computed, id) {
  
  if (!computed || computed.role !== 'program' || !computed.entry) return false;
  

  let decl = null;
  try {
    const p = path.join(ROOT, 'scripts', 'verify', 'expected-behaviour.json');
    decl = JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    
    console.error('::warning title=期望声明读不到::' + path.join(ROOT, 'scripts', 'verify', 'expected-behaviour.json')
      + ' 读不到（一件逐字**不**判成 shell、按 C-05 报缺）');
    return false;
  }
  const piece = decl && decl.pieces && decl.pieces[id];
  if (!piece) return false;
  if (piece.kind !== 'program') return false;
  

  const interp = Array.isArray(piece.interpreterArgs) ? piece.interpreterArgs : null;
  return !!interp && interp.indexOf('-c') >= 0;
}
if (computed.error) {
  console.error('::error title=' + id + ' 算不出 entry::' + computed.error);
  process.exit(1);
}
const entry = computed.entry;



if (entry !== '' && (!/^(bin|lib)\//.test(entry) || entry.includes('..') || entry.includes('\\'))) {
  console.error('::error title=' + id + ' entry 形状不对::' + entry + ' —— 应为 bin/<名> 或 lib/<名>.so，或空（纯库件）');
  process.exit(1);
}

if (entry !== '' && !fs.existsSync(path.join(PAYLOAD, entry))) {
  console.error('::error title=' + id + ' 落位结果缺文件::' + path.join(PAYLOAD, entry) + ' 不在');
  process.exit(1);
}

const sources = readJson(path.join(ROOT, 'scripts/component-sources.json')).sources || {};
const src = sources[id] || {};




const deps = (() => {
  try {
    const out = cp.execSync(
      "bash scripts/toolchain/cache-key.sh deps-list " + JSON.stringify(id),
      { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    );
    return out.trim().split(/\s+/).filter(Boolean);
  } catch (e) {
    return [];
  }
})();

const meta = {
  schema: 1,
  id: id,
  version: version,
  entry: entry,
  
  entryProvenance: 'gen-component-meta.js:落位结果（构建期算出，非人填）',
  
  
  
  
  
  by: computed.by || '',
  
  
  

  role: computed.role === 'program' && isCommandInterpreter(computed, id) ? 'shell' : (computed.role || ''),
  requires: deps,
  essential: src.essential === true,
  source: Array.isArray(src.urls) && src.urls.length ? src.urls[0] : '',
  sourceSha256: src.sha256 || '',
};




const BUILD_SHA = (argv.find(a => a.startsWith('--build-sha=')) || '').split('=').slice(1).join('=')
const BUILD_RUN = (argv.find(a => a.startsWith('--build-run=')) || '').split('=').slice(1).join('=')
if (BUILD_SHA) {
  meta.buildSha = BUILD_SHA;
  meta.buildRun = BUILD_RUN;
} else {
  console.log('[meta] ' + id
    + ' 提醒：调用方没给 --build-sha/--build-run —— 而运行期读到「没有那一格」'
    + ' 会答「无法判定是不是这一代」，不会答「是」');
}
if (src.sameAs) meta.sameAs = src.sameAs;

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(meta, null, 1) + '\n');
console.log('[meta] ' + id + ' → ' + path.relative(ROOT, outPath)
  + '（version=' + meta.version + ' entry=' + meta.entry
  + ' 依据=' + computed.by
  + (deps.length ? ' requires=' + deps.join(',') : '') + '）');
