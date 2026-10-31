#!/usr/bin/env bash
# 判一个包的实际格式 —— **看内容，不看文件名**。
#
# 为什么不能看文件名：
#   Release 上的资产名沿用旧的 <id>-<deps>+…tar.gz，
#   而里面的内容已经是 zip（package-bundle-zip.sh 改成了 zip）。
#   按扩展名分派会对 zip 内容走 tar 分支，解不开，判据一律拒绝。
#   —— 这个问题我踩过一次：资产名与内容格式不一致时，扩展名是骗人的。
#
#  为什么要有这一件（判据 C-994，位置 CONSTRAINTS.md 节标题「一百八十二」）：
#   **判别源必须唯一**。=> 而仓内曾有两套：一套在这里（fmt_of）、
#   => 而另一套在 fetch-prebuilt-pieces.sh 里（它自己 head -c 4 判 magic）、
#   => 而那两套逐字**各判各的**（=> 而那一套**没有** zip64 那一档）。
#   => 而现在：**这一件是唯一判别源**，=> 而其余各处逐字**改成调它**。
#
# 用法：. fmt.sh <文件>   → 打印 zip | zip64 | gzip | tar | 未知
#
# 追加的两个读数（族七 · 容器层）：
#   fmt_magic <文件>      → 头四字节（十六进制、逐位）
#   fmt_zip_entries <文件> → 中央目录里那一条一条（名字 + 未压缩大小）
#   fmt_outer_is_zip <文件> → 这一份**是不是**一个 zip 容器（zip/zip64 都算）
#   fmt_inner_zip <文件>  → 「外层套内层」那一格里，**内层**那个 zip 的路径（解开了才有）
#
# 而「哪一层才算那一颗件」那一问**不**是这一件答的（=> 而那是 01 裁的判别形式）。

# 头四字节（逐位十六进制、逐字不带空格）。
#   => 而它逐字是「那**头**是什么」那一格（=> 而不是「那**内容**是什么」那一格）。
fmt_magic() {
  local f="$1"
  [ -s "$f" ] || { echo ""; return 1; }
  head -c 4 "$f" 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n'
}

# 这一份是不是一个 zip 容器（zip32 与 zip64 都算）？
#   => 而它逐字只看那**头四字节**（=> 而那 504b0304 逐字是 zip 的本地文件头魔数）。
fmt_outer_is_zip() {
  local f="$1" m
  [ -s "$f" ] || return 1
  m="$(fmt_magic "$f")"
  case "$m" in
    504b*) return 0 ;;
    *) return 1 ;;
  esac
}

# 这一份是不是 zip64？
#   => 而 zip64（单包 >4 GiB 或条目数 >65535 时 zip 才写它）那几格逐字是哨兵值
#      （条目数 0xffff、目录大小与目录偏移 0xffffffff），
#      => 而那真实的逐字在 zip64 EOCD 里、=> 而那 zip64 定位器（魔数 0x07064b50）
#      逐字**在**那 EOCD **前面 20 字节**。
#   => 而这一段逐字**只读尾部**（=> 而那 64 KiB + 22 字节），
#      => 而**不整读**（=> 而那 8.4 GB 那一颗整读逐字会 ERR_FS_FILE_TOO_LARGE）。
fmt_is_zip64() {
  local f="$1"
  [ -s "$f" ] || return 1
  node -e '
const fs = require("node:fs");
let fd;
try { fd = fs.openSync(process.argv[1], "r"); } catch (e) { process.stdout.write("unreadable"); process.exit(0); }
try {
  const size = fs.fstatSync(fd).size;
  const len = Math.min(size, 65536 + 22);
  const b = Buffer.allocUnsafe(len);
  const got = fs.readSync(fd, b, 0, len, size - len);
  const tail = b.subarray(0, got);
  let e = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail.readUInt32LE(i) === 0x06054b50) { e = i; break; }
  }
  if (e < 0) { process.stdout.write("no-eocd"); process.exit(0); }
  const count = tail.readUInt16LE(e + 10);
  const cdSize = tail.readUInt32LE(e + 12);
  const cdOff = tail.readUInt32LE(e + 16);
  if (count !== 0xffff && cdSize !== 0xffffffff && cdOff !== 0xffffffff) {
    process.stdout.write("zip32"); process.exit(0);
  }
  const loc = e >= 20 ? tail.readUInt32LE(e - 20) : 0;
  //  => 而那**定位器不在位**时（=> 而那哨兵逐字在位、=> 而而那定位器逐字坏了）
  //     => 而 01 裁的 S607-01-CONTAINER-FORM-RULING.md 逐字「**那一档必须单独报、不许当 zip 处理**」。
  process.stdout.write(loc === 0x07064b50 ? "zip64" : "zip64-sentinel-no-locator");
} catch (e) {
  process.stdout.write("unreadable");
}
' "$f" 2>/dev/null
}

# 中央目录里那一条一条（名字 + 未压缩大小 + 压缩大小），一条一行。
#   => 而它逐字**只读中央目录那一段**（=> 而那一段不是内容、=> 而它通常很小），
#      => 而**不整读**。
#   => 而 zip64 那几格逐字是哨兵值时，=> 而那真实的逐字在中央目录项的 zip64 扩展字段里
#      （=> 而那个扩展字段的头两个字节逐字是 0x0001）。
fmt_zip_entries() {
  local f="$1"
  [ -s "$f" ] || return 1
  node -e '
const fs = require("node:fs");
let fd;
try { fd = fs.openSync(process.argv[1], "r"); } catch (e) { process.exit(1); }
const readAt = (pos, len) => {
  if (len <= 0) return Buffer.alloc(0);
  const b = Buffer.allocUnsafe(len);
  let got = 0;
  while (got < len) {
    const n = fs.readSync(fd, b, got, len - got, pos + got);
    if (n <= 0) break;
    got += n;
  }
  return b.subarray(0, got);
};
try {
  const size = fs.fstatSync(fd).size;
  const tailLen = Math.min(size, 65536 + 22);
  const tail = readAt(size - tailLen, tailLen);
  let e = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail.readUInt32LE(i) === 0x06054b50) { e = i; break; }
  }
  if (e < 0) { process.stderr.write("不是 zip（找不到中央目录结束记录）\n"); process.exit(1); }
  let count = tail.readUInt16LE(e + 10);
  let cdSize = tail.readUInt32LE(e + 12);
  let cdOff = tail.readUInt32LE(e + 16);
  if (count === 0xffff || cdSize === 0xffffffff || cdOff === 0xffffffff) {
    const loc = e >= 20 ? tail.readUInt32LE(e - 20) : 0;
    if (loc !== 0x07064b50) { process.stderr.write("zip64 哨兵在位，但找不到 zip64 定位器\n"); process.exit(1); }
    const z64Off = Number(tail.readBigUInt64LE(e - 12));
    const z64 = readAt(z64Off, 56);
    if (z64.readUInt32LE(0) !== 0x06064b50) { process.stderr.write("zip64 结束记录坏了\n"); process.exit(1); }
    count = Number(z64.readBigUInt64LE(32));
    cdSize = Number(z64.readBigUInt64LE(40));
    cdOff = Number(z64.readBigUInt64LE(48));
  }
  const cd = readAt(cdOff, cdSize);
  let off = 0;
  for (let n = 0; n < count; n++) {
    if (off + 46 > cd.length || cd.readUInt32LE(off) !== 0x02014b50) { process.stderr.write("中央目录项坏了\n"); process.exit(1); }
    let csize = cd.readUInt32LE(off + 20);
    let usize = cd.readUInt32LE(off + 24);
    const nameLen = cd.readUInt16LE(off + 28);
    const extraLen = cd.readUInt16LE(off + 30);
    const commentLen = cd.readUInt16LE(off + 32);
    let localOff = cd.readUInt32LE(off + 42);
    const name = cd.toString("utf8", off + 46, off + 46 + nameLen);
    if (csize === 0xffffffff || usize === 0xffffffff || localOff === 0xffffffff) {
      let e2 = off + 46 + nameLen;
      const eEnd = e2 + extraLen;
      while (e2 + 4 <= eEnd) {
        const hid = cd.readUInt16LE(e2);
        const hsz = cd.readUInt16LE(e2 + 2);
        if (hid === 0x0001) {
          let p = e2 + 4;
          if (usize === 0xffffffff && p + 8 <= e2 + 4 + hsz) { usize = Number(cd.readBigUInt64LE(p)); p += 8; }
          if (csize === 0xffffffff && p + 8 <= e2 + 4 + hsz) { csize = Number(cd.readBigUInt64LE(p)); p += 8; }
          if (localOff === 0xffffffff && p + 8 <= e2 + 4 + hsz) { localOff = Number(cd.readBigUInt64LE(p)); p += 8; }
        }
        e2 += 4 + hsz;
      }
    }
    process.stdout.write(name + "\t" + usize + "\t" + csize + "\t" + localOff + "\n");
    off += 46 + nameLen + extraLen + commentLen;
  }
} catch (e) {
  process.stderr.write("读中央目录失败: " + e.message + "\n");
  process.exit(1);
} finally {
  try { fs.closeSync(fd); } catch (e) {}
}
' "$f"
}

# 「外层套内层」那一格里，**内层**那些 zip 的路径（=> 而那**全部**、=> 而不取第一个）。
#   => 而它逐字**先把外层解开**（=> 而那外层逐字可能是 gzip、=> 而也可能是 zip），
#      => 而再在那解开的地方逐字**找**那些内层 zip。
#   => 而「哪一层才算那一颗件」那一问**不**在这里答（=> 而那是 01 裁的判别形式）：
#     => 而那**挑选**规则逐字按 01 裁的 S607-01-CONTAINER-FORM-RULING.md §3：
#        「**按内容挑** —— 内层容器的中央目录里**逐字含 `component-meta.json` 且其 `id` 逐字等于要取的件名**；
#         => 而**名字 glob 只当第一道粗筛**」、
#        => 而「**零个候选与多过一个候选都判红、不取第一个**」。
#     => 而本函数逐字**只**给出那些候选（=> 而那**挑**那一格逐字在 fmt_pick_inner_zip）。
fmt_inner_zip_candidates() {
  local f="$1" work="$2" m
  [ -s "$f" ] || return 1
  [ -n "$work" ] || { echo "用法: fmt_inner_zip_candidates <文件> <解开到哪儿>"; return 2; }
  rm -rf "$work"; mkdir -p "$work"
  m="$(fmt_magic "$f")"
  case "$m" in
    1f8b*) tar xzf "$f" -C "$work" 2>/dev/null || { return 1; } ;;
    504b*) unzip -q -o "$f" -d "$work" 2>/dev/null || { return 1; } ;;
    *) return 1 ;;
  esac
  local z
  for z in "$work"/component-*.zip "$work"/*/component-*.zip "$work"/*.zip "$work"/*/*.zip; do
    [ -f "$z" ] || continue
    #  => 而那**按字节**筛（=> 而那候选逐字**头四字节**是 504b* 的那一个才算 zip 容器）。
    case "$(fmt_magic "$z")" in 504b*) printf '%s\n' "$z" ;; esac
  done
  return 0
}

# 那**内层**那个 zip（=> 而那按内容挑、=> 而那零个或多个都判红、=> 而不取第一个）。
#   => 而那件名逐字是第二个实参（=> 而那它逐字是那 `component-meta.json` 里的 `id`）。
fmt_pick_inner_zip() {
  local f="$1" work="$2" want="$3"
  [ -n "$want" ] || { echo "用法: fmt_pick_inner_zip <文件> <解开到哪儿> <件名>" >&2; return 2; }
  local cands n id
  cands="$(fmt_inner_zip_candidates "$f" "$work")"
  n=0
  local hit=""
  local ids=""
  while IFS= read -r c; do
    [ -n "$c" ] || continue
    id="$(unzip -p "$c" component-meta.json 2>/dev/null | node -e '
let d = ""; process.stdin.on("data", x => d += x); process.stdin.on("end", () => {
  try { process.stdout.write(String(JSON.parse(d).id || "")); } catch (e) { process.stdout.write(""); }
});')"
    [ -n "$id" ] || continue
    ids="$ids $id"
    if [ "$id" = "$want" ]; then
      hit="$c"
      n=$((n+1))
    fi
  done <<< "$cands"
  if [ "$n" -eq 0 ]; then
    echo "内层没有那一颗件（要 id=$want；候选逐字是${ids:- 无}）—— 报缺，不猜，不取第一个" >&2
    return 1
  fi
  if [ "$n" -gt 1 ]; then
    echo "内层不唯一（有 $n 个 id=$want 的候选）—— 判红，不取第一个" >&2
    return 1
  fi
  printf '%s\n' "$hit"
  return 0
}

# 「外层套内层」那一格里，**内层**那个 zip 的路径（=> 而那是那**第一个**候选、
#   => 而那**只**给取证用、=> 而那**挑选**那一格逐字用 fmt_pick_inner_zip）。
fmt_inner_zip() {
  local f="$1" work="$2" m
  [ -s "$f" ] || return 1
  [ -n "$work" ] || { echo "用法: fmt_inner_zip <文件> <解开到哪儿>"; return 2; }
  rm -rf "$work"; mkdir -p "$work"
  m="$(fmt_magic "$f")"
  case "$m" in
    1f8b*) tar xzf "$f" -C "$work" 2>/dev/null || { echo ""; return 1; } ;;
    504b*) unzip -q -o "$f" -d "$work" 2>/dev/null || { echo ""; return 1; } ;;
    *) echo ""; return 1 ;;
  esac
  local z
  for z in "$work"/component-*.zip "$work"/*/component-*.zip "$work"/*.zip "$work"/*/*.zip; do
    [ -f "$z" ] || continue
    printf '%s\n' "$z"
    return 0
  done
  echo ""
  return 1
}

# 那一份的**形态名**（=> 而那它逐字就是 fmt_of 那一档）。
fmt_name_of() {
  fmt_of "$1"
}

# 那一份的**外层中央目录条目数**（=> 而那它逐字是那中央目录里那几条）。
#   => 而那读不到时逐字是 -1（=> 而那 -1 逐字答的是「算不出来」、=> 而不是 0）。
fmt_outer_entries_of() {
  local f="$1" n
  n="$(fmt_zip_entries "$f" 2>/dev/null | grep -c . || true)"
  [ -n "$n" ] || n=-1
  printf '%s\n' "$n"
}

fmt_of() {
  local f="$1" m
  [ -s "$f" ] || { echo "空文件"; return 1; }
  m="$(fmt_magic "$f")"
  case "$m" in
    504b*)
      #  => 而 zip32 与 zip64 逐字**分**开报（=> 而那 zip64 那一档是这一件补上的、
      #     => 而它逐字来自 generate-native-capabilities-pin.js 那一处判别逻辑、
      #     => 而现在那逻辑**归到**这一件、=> 而那一处**改成调它**）。
      case "$(fmt_is_zip64 "$f")" in
        zip64) echo "zip64"; return 0 ;;
        #  => 而那**哨兵在位但定位器坏了**那一档逐字**单独报**
        #     （=> 而按 01 裁的 S607-01-CONTAINER-FORM-RULING.md 逐字「不许当 zip 处理」）。
        zip64-sentinel-no-locator) echo "zip64-定位器坏了"; return 1 ;;
        *)     echo "zip"; return 0 ;;
      esac ;;
    1f8b*)                                            echo "gzip"; return 0 ;;
    *) ;;
  esac
  # 不是 zip/gzip 魔数 —— 试着当 tar 读
  if tar tf "$f" >/dev/null 2>&1; then echo "tar"; return 0; fi
  echo "未知"; return 1
}
