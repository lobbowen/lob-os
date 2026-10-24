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
let printEntry = null;
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--payload') {
    payloadArg = argv[i + 1] || '';
    i++;
  } else if (a === '--print-entry') {
    // ★ 只算 entry 并打印（=> 而不写清单）。
    //   而它治的是「**判据在产物之前跑**」那一族：
    //     · 判据那一处（verify-component-artifact.sh:6）与打包那一处（package-component.sh:15）
    //       都要读 entry，=> 而而打包那一支才是产出那份 zip 的那一个，
    //       => 而判据在打包**之前**跑 ⇒ 而那时 zip 还不存在。
    //   ⇒ 而这一形态让读侧**从落位结果算**（=> 而那是同一份算法、=> 而而那一份此刻就在 dist/ 下）。
    //   ⇒ 而用法：node gen-component-meta.js --print-entry <件名> <落位目录>
    printEntry = { id: argv[i + 1] || '', payload: argv[i + 2] || '' };
    i += 2;
  } else if (a.startsWith('--build-sha=') || a.startsWith('--build-run=')) {
    // 格三：构建标识由**调用方**给（打包那一支），而它在这里只是被带进来。
    // 而这两个 key 在下面 meta 写完之后才被用。
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
if (!printEntry && (!id || !version || !outPath)) {
  console.error('用法: node gen-component-meta.js <件名> <版本> <输出.json> [--payload <落位目录>]');
  process.exit(1);
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

// 落位目录：默认是输出文件所在目录（打包脚本把落位结果暂存在那里）。
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
    if (head[0] !== 0x7f || head[1] !== 0x45 || head[2] !== 0x4c || head[3] !== 0x46) {
      // ★ `#!` 脚本（=> 而 npm / pnpm 那两件的入口是 bin/npm-cli.js 与 bin/pnpm）：
      //   而 elf-form.sh 的取值域里 unknown 逐字是「不是 ELF（或是 #! 脚本）」，
      //   => 而而它**是**入口（=> 而 verify-component-artifact.sh 那一支逐字认 shebang 形态），
      //   => 而所以这一处把它单列成一个形态（=> 而而那是 02 核到的那一格）。
      if (n >= 2 && head[0] === 0x23 && head[1] === 0x21) return 'shebang';
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

// entry 唯一的来源：落位结果。
// C-102 **给 lib/ 设了优先级** —— 原来这里先判 lib/ 下唯一主名 .so，命中就返回，
// 而 bin/ 那一支今天一件都没走到过。后果：既有库又有程序的件（jq / busybox / openssl）
// 它的 entry 指向了 lib 那一份，而验收要求「入口指向程序形态那份」。
// 现在按用户三行规则第二条（库装库目录、程序装程序目录）：
// 入口只可能落在 bin/ 那一份；纯库件（bin/ 下没有可执行形态那份）才把 entry 指向 lib。

// **作者那一侧给的「入口该取哪一个名字」** —— 那一格落在
// component-sources.json 的 `sources.<件>.entryName`。
//
// 而 01 那一件 S115 第三节落点 ③ 逐字：「**候选集合与每条候选的正当性由那一件的作者那一侧给**
//（=> 而 busybox 那一族上游自带那一组，=> 而上游知道每个 applet 的定位）」，
// 而「取哪一个」是**使用期的分布**（=> 而 busybox 认全部那些名字，=> 而它没有「唯一一个更真」的名字）
// ⇒ 而构建期**不**按文件名字母序挑（=> 而「排序加 break」是实现细节）。
//
// 而**没给**（=> 而那是旧格式的产物）⇒ 而**报缺**而不猜（C-05）。
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
    // bin/ 下有多个程序形态字节 —— 那是**多调用二进制**的形状（01 今天裁定）：
    // 「一份程序字节 + 若干个命令名」，而命令名是**派生物**（man1 INSTALL 段：
    // make install 自己知道编了哪些 applet，并连带把软链建好）。
    // 入口指向**那一份字节**（它们全是软链，指向同一份），那批名字不进清单。
    // ★ 上一版在这里判红「该件只应有一个入口」—— 那个假设对 busybox 是错的
    //   （CI 实测：落位目录的 bin/ 下有 17 个程序形态字节 ⇒ 说明生成失败 ⇒ 该件产不出包）。
    // ★ 判「它们是同一份字节」：**按内容**（sha256），不按 realpath。
    //   缺陷名（C-102）本体那份自己指向自己 ⇒ realpath 不全同：
    //     busybox 那一件（本体搬到 bin/ 之后）bin/ 下有 18 项：17 条软链指向 ../bin/libbusybox.so
    //     而**本体那份指向自己** ⇒ 按 realpath 比，18 条里有一项不同 ⇒ 判红
    //     ⇒ 「落位目录的 bin/ 下有 17 个文件，但没有一个是程序形态」（旧版的 binProgram 名单不含本体时才出现）。
    //   ⇒ 现在：**本体那份**（它的 realpath 就是它自己）与那 17 条软链**是同一份字节**
    //     ⇒ 判据改成「这些项的内容是否逐字相同」（sha256）—— 那是「彼此是同一份吗」的可机械核形式。
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
    // 缺陷名（C-102）**entry 指到实现而实现不认那个名字** ——
    // 而 busybox 今天已经栽在这上面（=> 而真机读数：=> 而 exit 127，
    // => 而而 stderr 逐字是「libbusybox.so: applet not found」）。
    //
    // 而按 C-103 这一族它为什么有两边：
    //   一边「entry 该指向那个可执行体（本体）」；
    //   另一边「entry 该指向一个用户会叫的名字（applet 软链）」。
    // 而 04 裁的是**后者**（=> 而 01 那一件 S115 把它拆成三问，=> 而而本段按 S115 落）：
    //
    // 而 S115 的三问：
    //   S1 **「这一件是不是多调用二进制」**：=> 而 bin/ 下**程序形态**的项 > 1（binProgram.length > 1）。
    //      ⇒ 而单调用那一件走上面 `binProgram.length === 1` 那一支。
    //   S2 **「那些项是不是同一份字节」**：=> 而按**内容**（sha256）逐字相同
    //      （=> 而上面那一段已核，=> 而不成立就判红「多个入口，该件只应有一个」）。
    //      ⇒ 而**必须按内容不按 realpath**：=> 而本体搬进 bin/ 之后**本体指向自己**，
    //         而那 17 条软链指向 ../bin/libbusybox.so，=> 而按 realpath 比那 18 条里有一项不同。
    //   S3 **「本体那份是哪一个」**：=> 而那一族里 **realpath 指向自己**的那一个是本体。
    //
    // 而**「它自己 install 的」那一组名字**（=> 而 R1/R2/R3，=> 而 01 那一件 S115 逐条核过）：
    //   R1 **只有软链才算**（=> 而 busybox 那一件 build-native-busybox.sh:329 逐字是
    //       `[ -L "$l" ] || continue`）；
    //   R2 **那个名字自己不落包**（=> 而 :331 逐字是 `[ "$b" = busybox ] && continue`，
    //       ⇒ 而「busybox」不认自己）；
    //   R3 **它自己 install 的是这一族**（=> 而 :332 逐字是
    //       `ln -sf ./libbusybox.so "$OUT_DIR/bin/$b"`）。
    // ⇒ 而在**落位结果**里那一组就是**本体之外的每一个软链名**。
    //
    // ⇒ 而 04 那句「entry 该取那一族里被那件自己 install 的那个名字」**方向成立**
    //   （=> 而 01 那一件 S115 更正了它引的那个数：「19 个」是**表一**
    //   （那份字节 .rodata 开头那张表），而**它自己 install 的是表三**
    //   （make install 实际建出的软链），=> 而表三按轮次是 17 个无 sh 或 18 个含 sh，
    //   => 而**两轮都无 awk**）。
    //
    // ⇒ 而「组内哪一个」按 C-135 是**构建期答不了**的格（=> 而 01 那一件 S115 第三节）：
    //   「最常被叫」是**使用期的分布**，=> 而构建期只知道「哪些名字存在」；
    //   ⇒ 而 busybox **认全部**（appletlib.c:914 逐字
    //      `applet_name = bb_get_last_path_component_nostrip(argv[0]);`）
    //   ⇒ 而「用户真的会叫那个名字」对它**全部成立** ⇒ 而它**没有「唯一一个更真」的名字**。
    //   ⇒ 而按 C-126 那一格今天**未过**（=> 而它不是「未核」—— 而它是一个已裁的格子而它缺的是使用期那条读数）。
    //
    // ⇒ 而**候选集合与每条候选的正当性由那一件的作者那一侧给**（=> 而 busybox 那一族上游自带那一组，
    //   => 而上游知道每个 applet 的定位），=> 而**不在构建期按文件名字母序挑**
    //   （=> 而「排序加 break」是实现细节，=> 而它今天恰好选中 cat 纯属排序的偶然）。
    // ⇒ 而**作者那一侧给的那一份**落在 `component-sources.json` 的 `sources.<件>.entryName`（=> 而那是新增格，
    //   => 而按 C-11「新增数据形状要规范裁」—— => 而那一格归 01/04 裁，=> 而本稿**不新增**）。
    // ⇒ 而**它没给**（=> 而那是旧格式的产物，=> 而那一格今天**未过**）⇒ 而**报缺**而不猜（C-05）。
    let body = binProgram[0];
    for (const n of binProgram) {
      const f = path.join(binDir, n);
      try { if (fs.realpathSync(f) === path.resolve(f)) { body = n; break; } } catch (e) {}
    }
    // 那一组「它自己 install 的名字」= 那一族里**不是本体**的那几个软链名（=> 而 R1/R3）。
    const aliasNames = binProgram.filter((n) => n !== body);
    // 作者那一侧给的那一份（=> 而 01 那一件 S115 第三节落点 ③）。
    const declared = readAuthorEntryName(id);
    if (!declared) {
      return { error: '落位目录 ' + PAYLOAD + ' 的 bin/ 下有 ' + binProgram.length
        + ' 个程序形态字节（多调用二进制），而这一件**没有**声明入口该取它自己 install 的那一组名字里的哪一个'
        + '（本体那份 bin/' + body + ' 不认自己的文件名；它自己 install 的是 '
        + (aliasNames.length ? aliasNames.join('、') : '空') + '）'
        + '—— 而「取哪一个」是**使用期**的分布（=> 而 busybox 认全部那些名字，=> 而它没有「唯一一个更真」的名字）'
        + '=> 而它要**那一件的作者那一侧**给（=> 而那一份落在 component-sources.json 的 sources.<件>.entryName）' };
    }
    if (aliasNames.indexOf(declared) < 0) {
      return { error: '这一件声明的入口名 ' + declared + ' 不在它自己 install 的那一组里（'
        + (aliasNames.length ? aliasNames.join('、') : '空') + '）—— 而本体那份 bin/' + body
        + ' 不认自己的文件名，=> 而那不是一个可用的入口名' };
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

  // ★ **纯库件 ⇒ entry 留空**（不是「指 lib 那一份」）。
  //   缺陷名（C-102）库件也被指了一份库当入口：原来这一支是
  //   「lib/ 下唯一主名 .so ⇒ entry=lib/<它>」—— 而那一份是**库**，被当入口去调起必然崩
  //   （07 今天的复算表：crypto/curl/zlib 复算 role=library 且 bin/ 目录不存在）。
  //   判别：这一件在磁盘上有没有程序那一侧（bin 下有程序形态字节）？
  //     有 ⇒ entry 指向那一侧；没有 ⇒ **entry 那一格是空的**（按 C-05「没有」就是没有）。
  //   而「库装库目录」说的是**库字节落在库目录**，那件事由运行期 dirForRole 保证 ——
  //   与「库件没有 entry」（入口）不冲突。
  if (libPrimary.length >= 1) {
    return { entry: '', role: 'library', by: '落位结果：纯库件（bin/ 下无程序形态字节）⇒ 没有程序那一侧，entry 留空；库字节落在 lib/（'+libPrimary.length+' 份）', mixed: false, libs: libPrimary };
  }
  if (libPrimary.length > 1) {
    return { error: '落位目录 ' + PAYLOAD + ' 的 lib/ 下有 ' + libPrimary.length
      + ' 个主名 .so（' + libPrimary.join('、') + '）—— 该件的落位结果不唯一（把别的件拆出去，别往命令行塞 entry）' };
  }

  return { error: '落位目录 ' + PAYLOAD + ' 里没有落位结果（lib/ 下无主名 .so，bin/ 下无文件）—— 清单不写没有落位依据的 entry' };
}

// ★ 只算 entry 并打印（=> 而不写清单）。
//   而它治的是「**判据在产物之前跑**」那一族：
//     · 判据那一处（verify-component-artifact.sh:6）与打包那一处（package-component.sh:15）
//       都要读 entry，=> 而而打包那一支才是产出那份 zip 的那一个，
//       => 而判据在打包**之前**跑 ⇒ 而那时 zip 还不存在。
//   ⇒ 而这一形态让读侧**从落位结果算**（=> 而那是同一份算法、=> 而而那一份此刻就在 dist/ 下）。
if (printEntry) {
  if (!printEntry.id || !printEntry.payload) {
    console.error('用法: node gen-component-meta.js --print-entry <件名> <落位目录>');
    process.exit(1);
  }
  PAYLOAD = path.resolve(printEntry.payload);
  const c = computeEntry();
  if (c.error) {
    // 算不出来 ⇒ 答「没有」（=> 而 exit 0，=> 而而那与这一格是可选的那一族一致）。
    process.exit(0);
  }
  process.stdout.write(String(c.entry || ''));
  process.exit(0);
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
  // ★ 「**为什么是这一个**」落进清单（01 那一件 S115 第三节落点 ②）。
  //   而它**原来只打到控制台**（=> 而 `:395` 那一行逐字是「依据=」+ computed.by），
  //   => 而落位侧那三份（component-meta.json / piece-shape.json / program-manifest.json）
  //      **都没有 by 键**（=> 而 07 今天逐字核到设备侧那一格是空的）。
  //   => 而这一格**机器可读**（=> 而读清单的人不必再从控制台日志里捞）。
  by: computed.by || '',
  // ★ role 那一格 —— 落进**产物**这一份（01 那一件 S115 第三节落点 ③ 的那一族）。
  //   而它治的是「**role 那一格在两份清单里，而索引重建那一支读的那一份没有它**」：
  //     · 落位侧 program-manifest.json **有** role（=> 而 ManifestWriter 写它，=> 而 :27 那一行
  //       逐字是 `m.put("role", role)`），
  //     · 而产物自带的 component-meta.json **没有** role（=> 而下面这个 meta 对象里没有它），
  //     · 而 PieceScan.roleOf 读的是**后者**（=> 而 :93 那一行逐字是
  //       `metaOf(verDir)?.optString(ROLE_KEY, "")`），
  //     · => 而索引重建那一支（ProgramDir.kt:164 到 :165 逐字是
  //       `val role = if (landed) PieceScan.roleFromDisk(context, programId)`）
  //       拿到的就是 `entry-unusable`，
  //     · => 而真机读数（07，2026-10-25，build-apk 40134460082 / sha=9144b24）：
  //       索引里 busybox 那一行的 role 逐字是 `entry-unusable`，
  //       而落位侧那份 program-manifest.json 的 role 逐字是 `program`，=> 而两处逐字不同；
  //       => 而后果是 busybox 那一件**没进监督名单**（journal 逐字「监督名单=bash,jq,ripgrep」），
  //          => 而它没被启动，=> 而「exit 变成 0」那一格**不存在**（=> 而不是 0，=> 而按 C-126 是未过）。
  //   => 而按 C-103 这一族它为什么有两边：一边是「**产物自带的** component-meta.json 的 role」，
  //      另一边是「**落位侧写的** program-manifest.json 的 role」，
  //      而索引重建那一支读的是前者而监督环那一支读的是后者。
  //   => 而这里把 role **补进产物那一份**（=> 而两份就一致了，=> 而按 C-104「只有一条生成路径」），
  //      而运行期那一处（PieceScan.roleOf）**先读产物那一份**、=> 而读不到才落到落位侧那一份（=> 而那是兜底）。
  role: computed.role || '',
  requires: deps,
  essential: src.essential === true,
  source: Array.isArray(src.urls) && src.urls.length ? src.urls[0] : '',
  sourceSha256: src.sha256 || '',
};

// 格三（这一件是不是我们要的那一代）：产物要自带一个构建标识。
//   01 今天裁的裁定：该由**打包那一支**写进调用这一处，而不是由本脚本自己算
//     —— 因为**本脚本拿不到**：它的命令行参数只有 id / ver / out / --payload。
//   而本脚本也不自己从环境变量取，是为了单一来源：值由调用方给。
//   C-05（缺字段就报缺，不猜填）：调用方没给这两个参数 => 两格不写。
//     而「不写」与「写一个占位」在读侧是同一件事（读不到）——
//     所以调用方要自己决定要不要报错（打包那一支今天判红）。
//   而这两个值与 sourceSha256 **不是同一件事**（01 今天核过）：
//     buildSha/buildRun = 这一件是哪一次流水线造出来的（跨构建会变）
//     sourceSha256     = 这一件的源码是哪一份（上游 tarball 的摘要，跨构建不变）
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
