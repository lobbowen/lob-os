#!/usr/bin/env bash
# 兼容语义门禁：不得静默抹掉「隔离尚未建立」这个已知缺口。
#
# 形态与 check-api-surface.sh 同构：bash 壳 + node 内联，只读源文件，不编译。
#
# 为什么不用 check-api-surface.sh：它只读 ApiSpec.kt 与 CapabilityBroker.kt
# （那两文件的路径写死在脚本第 14-15 行），全文不含 CompatSemantics。
# 复用它等于对不相干的文件做正则 —— 必然假绿。
#
# 归属：自有机制。它断言的是 LOB-OS 自身对「与宿主文件系统边界」的如实声明，
# 与宿主是谁无关；离开本宿主仍然成立。
#
# 判据（linux-architect 三条 + 解析健壮性）：
#   a. CompatSemantics.kt 里必须能定位到 id == isolation 的 Item —— 否则红
#      （防止项被删掉后门禁因「找不到 isolation」而空过）
#   b. 该 Item 的 status 命中唯一且等于 "absent" —— 否则红
#   c. 该 Item 的 carrier 非空且含「不可得原因」标记 —— 否则红
#   d. 全部 Item 里 status == absent 的计数 >= 1 —— 否则红
#   e. 任一 Item 内 status 字面量命中数 != 1 —— 否则红
#      （用命中数而非固定下标：carrier 是多行拼接字符串，逐行/定下标拍会错位）
#   f. id == isolation 必须**唯一匹配** —— 0 个或多个都判红
#      （用「首个匹配」会让同名项抢先命中，判红信息指向错误的项）
#
# ★ 本判据的**上界**（有意为之，不是缺陷，勿顺手加强）：
#   门禁守的是「缺口不许被静默抹掉」，**不守**「缺口必须被论证充分」。
#   c 条只要求 carrier 非空且含不可得原因标记（为什么难/不可得/无法获得/拿不到 之一），
#   不要求论证质量。把这条也判红属于扩大判据，需走 OPINIONS 流程，不在修复范围内。
#
#   上界的**可复现具体值**（2026-10-15 本机实测，影子根 /tmp/negcheck，工程零接触）：
#     carrier = "隔离暂不可得。"  → exit 0（判绿）
#     carrier = "不可得。"        → exit 0（判绿）—— 两个标记词之一即算「有原因」
#     carrier = ""（空串）        → exit 1「isolation 的 carrier 为空」
#   即：判据做的是**二值「字符串里含任一标记词」**，判的是「有没有」，
#   **不判「写得够不够」**。A5d（"不可得。" 两字判绿）把这条上界钉在了一个可复现的具体值上，
#   比抽象描述「不校验充分性」更有用 —— 后人会改文案，前者可复跑。
#
# 假红时的绕行：修 CompatSemantics.kt 使其可被解析（不是关掉本脚本）。
# 本脚本不接受命令行参数关闭自己，也没有环境变量旁路。
#
# ★ 解封条件（不是永久豁免）：一旦宿主形态变为 root / device-owner 可建命名空间，
#   即 chroot / pivot_root / unshare(CLONE_NEWNS) 在本系统里真的可用，
#   isolation 就应当被真正实现 —— 届时本判据必须同步删除或翻转。
#   判据写死 status == absent 的那一天起就绑在「隔离未实现」这个前提上；
#   前提变了而判据不变，它就从「保护缺口」变成「阻止正确设计」。
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

SRC="container/app/src/main/java/lobos/services/app/CompatSemantics.kt"
BAD=0

echo "== 兼容语义 =="

[ -f "$SRC" ] || { echo "::error file=$SRC,title=找不到兼容语义源::"; exit 1; }

node - "$SRC" <<'NODE' || BAD=1
const fs = require('fs');
const path = process.argv[2];
const src = fs.readFileSync(path, 'utf8');

const STATUS = ['"aligned"', '"partial"', '"differ"', '"decided"', '"absent"'];
const REASON = /为什么难|不可得|无法获得|拿不到/;
const lits = (s) => [...s.matchAll(/"(?:[^"\\]|\\.)*"/g)].map((m) => m[0]);

let bad = 0;
const start = src.indexOf('private val ITEMS');
if (start < 0) {
  console.log('::error file=' + path + ',title=找不到 ITEMS 表::门禁无法定位数据源，请人工确认');
  process.exit(1);
}

// 按平衡括号提取每个 Item(...) 块：carrier 是多行拼接字符串，只有括号配对才切得准。
const body = src.slice(start);
const blocks = [];
for (let i = 0; i < body.length; ) {
  const at = body.indexOf('Item(', i);
  if (at < 0) break;
  let depth = 0;
  let k = body.indexOf('(', at);
  for (; k < body.length; k++) {
    if (body[k] === '(') depth++;
    else if (body[k] === ')') { depth--; if (depth === 0) break; }
  }
  if (k >= body.length) break;
  blocks.push(body.slice(at, k + 1));
  i = k + 1;
}

if (blocks.length === 0) {
  console.log('::error file=' + path + ',title=抽不出任何 Item 块::门禁不能空过，请人工确认');
  process.exit(1);
}

// (e) 每个块的 status 字面量必须恰好命中 1 个，否则解析不可信
for (const b of blocks) {
  const l = lits(b);
  const hits = l.filter((x) => STATUS.includes(x));
  const id = (l[0] || '(取不到 id)').replace(/"/g, '');
  if (hits.length !== 1) {
    console.log('::error file=' + path + ',title=Item 解析不可信::' + id + ' 内 status 字面量命中 ' + hits.length + ' 个（应恰为 1）。'
      + '多半是实参顺序被改动 —— 请改回 id/semantics/status/carrier 的顺序，而不是放宽本判据。');
    bad++;
  }
}

const parsed = blocks.map((b) => {
  const l = lits(b);
  const hitIdx = [];
  l.forEach((x, i) => { if (STATUS.includes(x)) hitIdx.push(i); });
  const hits = hitIdx.map((i) => l[i]);
  // carrier 取「status 命中之后的所有字面量」，不写死 l.slice(3)：
  //   那样写等于假设实参恰好 4 个；一旦 Item 增删实参，carrier 会整体错位，
  //   而错位后的 carrier 仍可能命中原因标记 —— 判据会在数据已乱时判绿。
  const firstStatus = hitIdx.length ? hitIdx[0] : 2;
  return {
    id: (l[0] || '').replace(/"/g, ''),
    status: hits[0] || '',
    carrier: l.slice(firstStatus + 1).join(''),
  };
});

// (d) absent 总计 >= 1
const absentTotal = parsed.filter((p) => p.status === '"absent"').length;
if (absentTotal < 1) {
  console.log('::error file=' + path + ',title=没有已知缺口了::全表 absent 计数 = ' + absentTotal + '（应 >= 1）。'
    + '若隔离等缺口确已补上，请走 OPINIONS 流程重新评估「不得宣称未实现的隔离」，不要直接改判据。');
  bad++;
}

// (a)(b)(c) isolation 三条
const isoMatches = parsed.filter((p) => p.id === 'isolation');
if (isoMatches.length !== 1) {
  // 0 个 = 项被删；多个 = 有别的项冒用了同一个 id（「首个匹配」会命中错的那个）。
  // 两种都属「不可判定」，必须表现为红 —— 绝不能因为找不到就静默放行。
  console.log('::error file=' + path + ',title=isolation 解析不可信::全表 ' + parsed.length
    + ' 项里 id == isolation 的匹配数 = ' + isoMatches.length + '（应恰为 1）'
    + (isoMatches.length === 0 ? '。删掉它会让「隔离未建立」这个缺口从声明里消失。'
       : '。有多个项冒用同一 id，取「首个匹配」会指向错误的项。'));
  bad++;
} else {
  const iso = isoMatches[0];
  if (iso.status !== '"absent"') {
    console.log('::error file=' + path + ',title=isolation 状态被改动::现为 ' + (iso.status || '(取不到)')
      + '，本门禁要求 "absent"。若隔离已被真正实现，走 OPINIONS 流程改判据，别直接改状态。');
    bad++;
  }
  const carrierText = iso.carrier;
  if (carrierText.replace(/"/g, '').trim().length === 0) {
    console.log('::error file=' + path + ',title=isolation 的 carrier 为空::一个 known 缺口不许没有理由。');
    bad++;
  } else if (!REASON.test(carrierText)) {
    console.log('::error file=' + path + ',title=isolation 的 carrier 缺不可得原因::'
      + '需含「为什么难 / 不可得 / 无法获得 / 拿不到」之一即可（本判据不要求论证充分）。carrier=' + carrierText.slice(0, 80) + '…');
    bad++;
  }
}

if (!bad) {
  console.log('[ok] 兼容语义：' + parsed.length + ' 项，absent ' + absentTotal
    + ' 项；isolation 在场且为 absent，carrier 含不可得原因');
}
process.exit(bad ? 1 : 0);
NODE

if [ "$BAD" -ne 0 ]; then
  echo "::error title=兼容语义判据未通过::"
  exit 1
fi
