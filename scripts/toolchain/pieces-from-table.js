#!/usr/bin/env node
// 「该取哪几件」—— 而它从那份表里算（01 今天裁）。
//
// 01 今天的裁定：
//   PIECES 从那份表里算，而**要取的是 notStandalone 不为 true 的那些**。
//   而列法是「不为 true」，**而不是「为 false」** —— 而其余项**不写**那一格。
//
// 而这一格有两个读者，而它们今天各自用不同办法数那一行：
//   scripts/verify/verify-apk-native.sh:215-216   按**文本** grep 那一行再 wc -w
//   .github/workflows/build-apk.yml:273-275        先试钩子 --print-pieces，★ ★ 而那钩子今天不存在
// ⇒ **=> 而 PIECES 那一行必须仍然是可 grep 的那一行文本**
//   ⇒ **=> 而这个脚本是那一行的生成者**，★ ★ 而它把结果写进那一行 ★★
//
// 用法：node scripts/toolchain/pieces-from-table.js
//   打印那一行的内容（空格分隔的件名）。

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..', '..');
const table = path.join(root, 'scripts', 'component-sources.json');

let doc;
try {
  doc = JSON.parse(fs.readFileSync(table, 'utf8'));
} catch (e) {
  // 读不到表 ⇒ 报缺（不猜）—— 而调用方据此报缺
  console.error('钉值表读不出: ' + e.message);
  process.exit(1);
}

const sources = (doc && doc.sources) || {};

// 而「哪些项是件」那一格今天还没有（★ ★ 而表里那 27 项里只有 8 项是件，★★ 其余是钉值项）★★
// ⇒ **=> 而这一份「件」的名单今天还是写死的**（★ ★ 而它归 01 那一格：★★
// ★ ★ **「哪些项是件」那一格今天不在表里**）★★
// 而「哪些项是件」今天**从表里读**：★★
//   ★ ★ 而那一格是 `piece`（布尔格，★ ★ 01 今天裁的键名）★★
//   ★ ★ 而它只落在那八项上（★★ 而其余项不写那一格）★★
// ★★ 而 formerly 那一行是写死的（★★ 而 01 今天读数二指出那是**第二份**清单）★★
const PIECE_IDS = Object.keys(sources).filter((id) => sources[id] && sources[id].piece === true);

const out = PIECE_IDS.filter((id) => {
  const e = sources[id] || {};
  // ★★ 而列法是「不为 true」★★（★ ★ 而其余项不写那一格，★ ★ 而「没有」不等于「false」）★★
  return e.notStandalone !== true;
});

process.stdout.write(out.join(' '));
