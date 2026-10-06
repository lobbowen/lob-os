#!/usr/bin/env node
'use strict';

const ESC = String.fromCharCode(0x1b);

class Screen {
  constructor(rows, cols) {
    this.rows = rows; this.cols = cols;
    this.g = Array.from({ length: rows }, () => Array.from({ length: cols }, () => ({ ch: ' ', w: 1 })));
    this.r = 0; this.c = 0;
    this.pending = '';
    this.st = 'G';
  }
  charWidth(ch) {
    const k = ch.codePointAt(0);
    if (k === 0) return 0;
    if (k >= 0x0300 && k <= 0x036f) return 0;
    if ((k >= 0x1100 && k <= 0x115f) || (k >= 0x2e80 && k <= 0x303e) ||
        (k >= 0x3041 && k <= 0x33ff) || (k >= 0x3400 && k <= 0x4dbf) ||
        (k >= 0x4e00 && k <= 0x9fff) || (k >= 0xa000 && k <= 0xa4cf) ||
        (k >= 0xac00 && k <= 0xd7a3) || (k >= 0xf900 && k <= 0xfaff) ||
        (k >= 0xfe30 && k <= 0xfe6f) || (k >= 0xff00 && k <= 0xff60) ||
        (k >= 0xffe0 && k <= 0xffe6)) return 2;
    return 1;
  }
  put(ch) {
    if (this.c >= this.cols) { this.c = 0; this.nl(); }
    const w = this.charWidth(ch);
    if (w === 0) return;
    if (this.c + w > this.cols) { this.c = 0; this.nl(); }
    this.g[this.r][this.c] = { ch, w };
    if (w === 2 && this.c + 1 < this.cols) this.g[this.r][this.c + 1] = { ch: ' ', w: 0 };
    this.c += w;
  }
  nl() {
    this.r++;
    if (this.r >= this.rows) {
      for (let i = 0; i < this.rows - 1; i++) this.g[i] = this.g[i + 1].slice();
      this.g[this.rows - 1] = Array.from({ length: this.cols }, () => ({ ch: ' ', w: 1 }));
      this.r = this.rows - 1;
    }
  }
  feed(s) {
    const buf = this.pending + s; this.pending = '';
    for (const ch of buf) {
      if (this.st === 'G') {
        if (ch === ESC) this.st = 'E';
        else if (ch === '\r') this.c = 0;
        else if (ch === '\n') this.nl();
        else this.put(ch);
      } else if (this.st === 'E') {
        if (ch === '[') this.st = 'C';
        else if (ch === ']') this.st = 'O';
        else if (ch === '7') { this.sr = this.r; this.sc = this.c; this.st = 'G'; }
        else if (ch === '8') { this.r = this.sr; this.c = this.sc; this.st = 'G'; }
        else this.st = 'G';
      } else if (this.st === 'O') {
        if (ch === String.fromCharCode(7)) this.st = 'G';
      } else if (this.st === 'C') {
        if (/[0-9;?]/.test(ch)) this.pending += ch;
        else { this.csi(this.pending, ch); this.pending = ''; this.st = 'G'; }
      }
    }
  }
  csi(p, f) {
    const q = p.startsWith('?') ? p.slice(1) : p;
    const n = q.split(';').map(x => parseInt(x, 10) || 0);
    const a = (i, d) => (n[i] > 0 ? n[i] : d);
    switch (f) {
      case 'A': this.r = Math.max(0, this.r - a(0, 1)); break;
      case 'B': this.r = Math.min(this.rows - 1, this.r + a(0, 1)); break;
      case 'C': this.c = Math.min(this.cols - 1, this.c + a(0, 1)); break;
      case 'D': this.c = Math.max(0, this.c - a(0, 1)); break;
      case 'H': case 'f': this.r = a(0, 1) - 1; this.c = a(1, 1) - 1; break;
      case 'J': {
        const m = n[0] || 0;
        for (let c = this.c; c < this.cols; c++) this.g[this.r][c] = { ch: ' ', w: 1 };
        for (let rr = this.r + 1; rr < this.rows; rr++) this.g[rr] = Array.from({ length: this.cols }, () => ({ ch: ' ', w: 1 }));
        if (m === 2 || m === 3) for (let rr = 0; rr < this.rows; rr++) this.g[rr] = Array.from({ length: this.cols }, () => ({ ch: ' ', w: 1 }));
        break;
      }
      case 'K': {
        const m = n[0] || 0;
        if (m === 0) for (let c = this.c; c < this.cols; c++) this.g[this.r][c] = { ch: ' ', w: 1 };
        else if (m === 1) for (let c = 0; c <= this.c; c++) this.g[this.r][c] = { ch: ' ', w: 1 };
        else this.g[this.r] = Array.from({ length: this.cols }, () => ({ ch: ' ', w: 1 }));
        break;
      }
      case 'm': break;
      default: break;
    }
  }
  lineText(r) {
    let s = '';
    for (let c = 0; c < this.cols; c++) {
      const cell = this.g[r][c];
      if (cell.w === 0 && c > 0) continue;
      s += cell.ch;
    }
    return s.replace(/\s+$/, '');
  }
}

let PASS = 0, FAIL = 0;
const t = (name, got, want) => {
  const ok = got === want;
  if (ok) { console.log('  [ok]   ' + name); PASS++; }
  else { console.log('  [FAIL] ' + name + '\n         实得 ' + JSON.stringify(got) + '\n         应为 ' + JSON.stringify(want)); FAIL++; }
};

console.log('== TerminalScreen 算法验证（JS 同构复刻，非 Kotlin 真机测试）==');

{
  const s = new Screen(3, 10);
  s.feed('中文');
  t('中文占 2 列 → 光标在第 4 列', s.c, 4);
  t('续位格标记 w=0', s.g[0][1].w, 0);
  t('第二个汉字落在第 2 列', s.g[0][2].ch, '文');
}

{
  const s = new Screen(3, 10);
  s.feed('ls 中');
  t('ls(2) + 中(2) + 空 → 光标 5', s.c, 5);
}

{
  const s = new Screen(3, 10);
  s.feed('a' + ESC);
  t('半个 ESC 不落成字符', s.lineText(0), 'a');
  s.feed('[32mgreen' + ESC + '[0m');
  t('续上 [32m 后 green 正常显示且无残留', s.lineText(0), 'agreen');
}

{
  const s = new Screen(3, 10);
  s.feed('hello' + ESC + '[2J' + ESC + '[H');
  t('2J+H 后光标在 (0,0)', s.r + ',' + s.c, '0,0');
  t('2J 清掉了内容', s.lineText(0), '');
}

{
  const s = new Screen(3, 10);
  s.feed('hello world' + ESC + '[H' + ESC + '[5C' + ESC + '[K');
  t('CUP 到 (0,0) 后 CUF 5 格 → 光标 5', s.c, 5);
  t('EL 从光标(5)清到行尾 → 留 c=0..4 的 hello', s.lineText(0), 'hello');
}

{
  const s = new Screen(2, 8);
  s.feed('\r\n');
  s.feed('11111');
  s.feed('\r\n');
  s.feed('22222');
  s.feed('\r\n');
  s.feed('33333');
  t('CRLF 下上滚正确（行0 让给第2行）', s.lineText(0), '22222');
  t('末行为最新', s.lineText(1), '33333');
}
{
  const s = new Screen(3, 8);
  s.feed("11111\n222");
  t("LF 不回列：行1 从第5列起写 222", s.lineText(1), "     222");
}
{
  const s = new Screen(2, 5);
  s.feed('12345\n');
  t('填满行后光标停在末列（deferred wrap，不是立即换行）', s.c, 5);
  s.feed('X');
  t('下一字符才换行 → X 落在行1', s.lineText(1), 'X');
}

{
  const s = new Screen(3, 10);
  s.feed(ESC + ']0;my title' + String.fromCharCode(7) + 'X');
  t('OSC 吞掉只留 X', s.lineText(0), 'X');
}

{
  const s = new Screen(5, 20);
  s.feed('$ ls' + '\n' + 'file1' + '\n' + ESC + '[1;1H' + 'PROMPT> ');
  t('CUP 定位后从首格覆写（行尾空白被 trim）', s.lineText(0), 'PROMPT>');
  t('CUP 后光标落在第 8 列', s.c, 8);
}

console.log('== 通过 ' + PASS + '，失败 ' + FAIL + ' ==');
process.exit(FAIL === 0 ? 0 : 1);