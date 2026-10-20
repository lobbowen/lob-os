#!/usr/bin/env node
'use strict';
// 生成件的说明 —— 一件一份，装配器只读包里的这份，不查外部清单（照抄 .deb 的 control）。
//
// 用法：
//   node gen-component-meta.js <件名> <版本> <输出.json> [--payload <落位目录>]
//
// 三个位置参数 + 可选 --payload：
//   版本从参数拿（不是从钉值表猜 —— 打包时才知道确切版本）
//   落位目录默认取输出文件所在目录（打包脚本把该件的落位结果暂存到那里）
//   entry **由本脚本从落位结果算出来** —— 不从命令行拿、不从钉值表读、
//   也不从任何配置文件读。
//
// 为什么（2026-10-20 用户定的三行规则）：
//   一 构建时就知道哪些是库、哪些是程序 —— 这是生产时就知道的事
//   二 库装到库目录，程序装到程序目录
//   三 注册表只记「库在哪里」与「程序在哪里」；运行期只读，不判断、不探测
//   而清单里那个能手填的 entry 字段，**只能由安装流程按落位结果写，不能由人填**。
// ⇒ 缺陷名（C-102）：**清单的 entry 原先是人的字面量** —— 钉值表 18 处字面量 +
//   打包脚本把它当实参一路透传，人能改错，且改错时只有形状检查会红。
//   现在 entry 的唯一来源是**落位结果本身**（打包暂存目录里实际产出的那个文件）。
//
// 落位结果怎么读（规则是确定的，不含任何名单）：
//   1. 落位目录的 lib/ 下，**未带版本号的** *.so（libz.so 算，libz.so.1 不算）
//      恰好一个 ⇒ entry = lib/<那个名字>
//      （带版本号的那一族是同一份字节的其它名字，linker 认的是带版本的那层，
//        所以主名才是入口 —— 与 build-bundle-zlib.sh:38-44 的取法一致）
//   2. lib/ 下没有主名 .so 时，bin/ 下的普通文件恰好一个 ⇒ entry = bin/<那个名字>
//      （bin/ 下多于一个 = 该件的落位结果不唯一 ⇒ 判红，不猜）
//   3. 不唯一或没有 ⇒ 判红退出，**不写清单**。宁可没有清单，不要一份写着
//      人填的 entry 的清单。
//
// 落位结果不唯一时，调用方该做的是把该件自己的落位结果收窄（别的件的产物
// 拆出去），不是往命令行塞一个 entry —— 塞进来的那个就是人填的。

const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '../..');

const argv = process.argv.slice(2);
const positional = [];
let payloadArg = null;
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--payload') {
    payloadArg = argv[i + 1] || '';
    i++;
  } else if (a.startsWith('--')) {
    console.error('::error title=未知参数::' + a);
    process.exit(1);
  } else {
    positional.push(a);
  }
}

if (positional.length === 4) {
  // 旧形态：<件名> <版本> <entry> <输出.json> —— entry 是人填的实参，拒收。
  console.error(
    '::error title=entry 不再由命令行传入::用法: node gen-component-meta.js <件名> <版本> <输出.json> [--payload <落位目录>]'
    + '（entry 只能由本脚本从落位结果算出 —— 人填的那一条已废止）');
  process.exit(1);
}

const [id, version, outPath] = positional;
if (!id || !version || !outPath) {
  console.error('用法: node gen-component-meta.js <件名> <版本> <输出.json> [--payload <落位目录>]');
  process.exit(1);
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

// 落位目录：默认是输出文件所在目录（打包脚本把落位结果暂存在那里）。
const PAYLOAD = payloadArg ? path.resolve(payloadArg) : path.dirname(path.resolve(outPath));

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

// 那一份字节是不是「程序形态」。判据只有两个 ELF 事实：e_type 与 PT_INTERP。
// 与 container/.../kernel/elf/ExecBits.kt 那一族同形，但那是运行期的、已删 ——
// 这一处是构建期（用户规则第一条：构建时就知道），所以判定在打包这一侧做完。
function elf_form_of(file) {
  let fd;
  try { fd = fs.openSync(file, 'r'); } catch (e) { return 'unreadable'; }
  try {
    const head = Buffer.alloc(64);
    const n = fs.readSync(fd, head, 0, 64, 0);
    if (n < 18) return 'unreadable';
    if (head[0] !== 0x7f || head[1] !== 0x45 || head[2] !== 0x4c || head[3] !== 0x46) return 'unreadable';
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
  return f === 'exec' || f === 'dyn-interp';
}

// entry 唯一的来源：落位结果。
// C-102 **给 lib/ 设了优先级** —— 原来这里先判 lib/ 下唯一主名 .so，命中就返回，
// 而 bin/ 那一支今天一件都没走到过。后果：既有库又有程序的件（jq / busybox / openssl）
// 它的 entry 指向了 lib 那一份，而验收要求「入口指向程序形态那份」。
// 现在按用户三行规则第二条（库装库目录、程序装程序目录）：
// 入口只可能落在 bin/ 那一份；纯库件（bin/ 下没有可执行形态那份）才把 entry 指向 lib。
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
      mixed: libPrimary.length > 0,
      libs: libPrimary,
    };
  }
  if (binProgram.length > 1) {
    // bin/ 下有多个程序形态字节 —— 那是**多调用二进制**的形状（01 今天裁定）：
    // 「一份程序字节 + 若干个命令名」，而命令名是**派生物**（man1 INSTALL 段：
    // make install 自己知道编了哪些 applet，并连带把软链建好）。
    // 入口指向**那一份字节**（它们全是软链，指向同一份），那批名字不进清单。
    // ★ 上一版在这里判红「该件只应有一个入口」—— 那个假设对 busybox 是错的
    //   （CI 实测：落位目录的 bin/ 下有 17 个程序形态字节 ⇒ 说明生成失败 ⇒ 该件产不出包）。
    const first = path.join(binDir, binProgram[0]);
    let sameTarget = true;
    try {
      const firstReal = fs.realpathSync(first);
      for (const n of binProgram) {
        if (fs.realpathSync(path.join(binDir, n)) !== firstReal) { sameTarget = false; break; }
      }
    } catch (e) {
      sameTarget = false;
    }
    if (!sameTarget) {
      return { error: '落位目录 ' + PAYLOAD + ' 的 bin/ 下有 ' + binProgram.length
        + ' 个程序形态字节，而它们**不是同一份**（' + binProgram.join('、')
        + '）—— 那是多个入口，该件只应有一个' };
    }
    return {
      entry: 'bin/' + binProgram[0],
      by: '落位结果：bin/ 下 ' + binProgram.length + ' 个程序形态字节是同一份的多调用名字（多调用二进制），入口指那一份；那些名字是派生物，不进清单'
        + (libPrimary.length > 0 ? '；该件同时带库 ' + libPrimary.length + ' 份' : ''),
      mixed: libPrimary.length > 0,
      multiCall: binProgram.length,
      libs: libPrimary,
    };
  }
  if (binNames.length > 0) {
    return { error: '落位目录 ' + PAYLOAD + ' 的 bin/ 下有 ' + binNames.length
      + ' 个文件，但没有一个是程序形态（' + binNames.join('、') + '）—— 那它们不是入口，entry 不该从 bin/ 取' };
  }

  // ★ **纯库件 ⇒ entry 留空**（不是「指 lib 那一份」）。
  //   缺陷名（C-102）库件也被指了一份库当入口：原来这一支是
  //   「lib/ 下唯一主名 .so ⇒ entry=lib/<它>」—— 而那一份是**库**，被当入口去调起必然崩
  //   （07 今天的复算表：crypto/curl/zlib 复算 role=library 且 bin/ 目录不存在）。
  //   判别：这一件在磁盘上有没有程序那一侧（bin 下有程序形态字节）？
  //     有 ⇒ entry 指向那一侧；没有 ⇒ **entry 那一格是空的**（按 C-05「没有」就是没有）。
  //   而「库装库目录」说的是**库字节落在库目录**，那件事由运行期 dirForRole 保证 ——
  //   与「库件没有 entry」（入口）不冲突。
  if (libPrimary.length >= 1) {
    return { entry: '', by: '落位结果：纯库件（bin/ 下无程序形态字节）⇒ 没有程序那一侧，entry 留空；库字节落在 lib/（'+libPrimary.length+' 份）', mixed: false, libs: libPrimary };
  }
  if (libPrimary.length > 1) {
    return { error: '落位目录 ' + PAYLOAD + ' 的 lib/ 下有 ' + libPrimary.length
      + ' 个主名 .so（' + libPrimary.join('、') + '）—— 该件的落位结果不唯一（把别的件拆出去，别往命令行塞 entry）' };
  }

  return { error: '落位目录 ' + PAYLOAD + ' 里没有落位结果（lib/ 下无主名 .so，bin/ 下无文件）—— 清单不写没有落位依据的 entry' };
}

const computed = computeEntry();
if (computed.error) {
  console.error('::error title=' + id + ' 算不出 entry::' + computed.error);
  process.exit(1);
}
const entry = computed.entry;

// entry 允许为空 —— 那是「这一件没有程序那一侧」（纯库件）。
// 非空时它必须形如 bin/<名> 或 lib/<名>.so，且在落位结果里真的存在。
if (entry !== '' && (!/^(bin|lib)\//.test(entry) || entry.includes('..') || entry.includes('\\'))) {
  console.error('::error title=' + id + ' entry 形状不对::' + entry + ' —— 应为 bin/<名> 或 lib/<名>.so，或空（纯库件）');
  process.exit(1);
}

// 该件落位结果里必须真的有这个文件 —— 算出来的名字指向不存在的东西 = 落位坏了。
// （entry 为空 = 纯库件，那一件没有入口，不查这一格。）
if (entry !== '' && !fs.existsSync(path.join(PAYLOAD, entry))) {
  console.error('::error title=' + id + ' 落位结果缺文件::' + path.join(PAYLOAD, entry) + ' 不在');
  process.exit(1);
}

const sources = readJson(path.join(ROOT, 'scripts/component-sources.json')).sources || {};
const src = sources[id] || {};

// 依赖：唯一真相源是 cache-key.sh 的 deps_for。
// 用它的 deps-list 子命令 —— 不能用 deps 子命令，那个返回的是缓存键
//（uw-curl-openssl-…）而不是件名。
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
  // entry 由谁算 —— 写进清单，让读清单的人不必再猜。
  entryProvenance: 'gen-component-meta.js:落位结果（构建期算出，非人填）',
  requires: deps,
  essential: src.essential === true,
  source: Array.isArray(src.urls) && src.urls.length ? src.urls[0] : '',
  sourceSha256: src.sha256 || '',
};
if (src.sameAs) meta.sameAs = src.sameAs;

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(meta, null, 1) + '\n');
console.log('[meta] ' + id + ' → ' + path.relative(ROOT, outPath)
  + '（version=' + meta.version + ' entry=' + meta.entry
  + ' 依据=' + computed.by
  + (deps.length ? ' requires=' + deps.join(',') : '') + '）');
