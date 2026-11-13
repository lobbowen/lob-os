#!/usr/bin/env bash

fmt_magic() {
  local f="$1"
  [ -s "$f" ] || { echo ""; return 1; }
  head -c 4 "$f" 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n'
}

fmt_outer_is_zip() {
  local f="$1" m
  [ -s "$f" ] || return 1
  m="$(fmt_magic "$f")"
  case "$m" in
    504b*) return 0 ;;
    *) return 1 ;;
  esac
}

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
  // 那**定位器不在位**时（哨兵逐字在位、定位器逐字坏了）
  //01 裁的 S607-01-CONTAINER-FORM-RULING.md 逐字「**那一档必须单独报、不许当 zip 处理**」。
  process.stdout.write(loc === 0x07064b50 ? "zip64" : "zip64-sentinel-no-locator");
} catch (e) {
  process.stdout.write("unreadable");
}
' "$f" 2>/dev/null
}

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

  local seen=" "
  for z in "$work"/component-*.zip "$work"/*/component-*.zip "$work"/*.zip "$work"/*/*.zip; do
    [ -f "$z" ] || continue

    case "$(fmt_magic "$z")" in 504b*) ;; *) continue ;; esac
    local real
    real="$(cd "$(dirname "$z")" && pwd)/$(basename "$z")"
    case "$seen" in *" $real "*) continue ;; esac
    seen="$seen$real "
    printf '%s\n' "$z"
  done
  return 0
}

fmt_pick_piece() {
  local f="$1" work="$2" want="$3"
  [ -n "$want" ] || { echo "用法: fmt_pick_piece <文件> <解开到哪儿> <件名>" >&2; return 2; }
  [ -s "$f" ] || { echo "那一份是空的" >&2; return 1; }
  local m
  m="$(fmt_magic "$f")"

  case "$m" in
    1f8b*) tar xzf "$f" -C "$work" 2>/dev/null || { echo "那 gzip 解不开" >&2; return 1; } ;;
    504b*) : ;;
    *) return 1 ;;
  esac

  case "$m" in
    504b*) ;;
    *) : ;;
  esac
  if [ "${m#504b}" != "$m" ]; then
    local outer_id
    outer_id="$(unzip -p "$f" component-meta.json 2>/dev/null | node -e '
let d = ""; process.stdin.on("data", x => d += x); process.stdin.on("end", () => {
  try { process.stdout.write(String(JSON.parse(d).id || "")); } catch (e) { process.stdout.write(""); }
});')"
    if [ -n "$outer_id" ]; then
      if [ "$outer_id" = "$want" ]; then
        printf '%s\n' "$f"
        return 0
      fi
      echo "那**外层自己**就是那一颗件、它的 id 逐字是 $outer_id（要的是 $want）—— 判红" >&2
      return 1
    fi
  else

    local outer_id
    outer_id="$(cat "$work"/component-meta.json 2>/dev/null | node -e '
let d = ""; process.stdin.on("data", x => d += x); process.stdin.on("end", () => {
  try { process.stdout.write(String(JSON.parse(d).id || "")); } catch (e) { process.stdout.write(""); }
});')"
    if [ -n "$outer_id" ]; then
      if [ "$outer_id" = "$want" ]; then
        printf '%s\n' "$f"
        return 0
      fi
      echo "那**解开处自己**就是那一颗件、它的 id 逐字是 $outer_id（要的是 $want）—— 判红" >&2
      return 1
    fi
  fi

  local cands n id hit ids
  cands="$(fmt_inner_zip_candidates "$f" "$work/.inner")"
  n=0; hit=""; ids=""
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

fmt_name_of() {
  fmt_of "$1"
}

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

      case "$(fmt_is_zip64 "$f")" in
        zip64) echo "zip64"; return 0 ;;

        zip64-sentinel-no-locator) echo "zip64-定位器坏了"; return 1 ;;
        *)     echo "zip"; return 0 ;;
      esac ;;
    1f8b*)                                            echo "gzip"; return 0 ;;
    *) ;;
  esac

  if tar tf "$f" >/dev/null 2>&1; then echo "tar"; return 0; fi
  echo "未知"; return 1
}
