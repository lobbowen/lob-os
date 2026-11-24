#!/usr/bin/env bash
# Patch glibc ld-linux to install a SIGSYS handler at exec-entry time.
# Solves Android untrusted_app seccomp SIGSYS on rseq(293) for glibc guests.
# Technique: branch from original entry to shellcode in .eh_frame (RX segment),
# install SIGSYS handler via raw rt_sigaction, replay overwritten instructions,
# branch back. All branches are PC-relative (PIE-safe); direct b needs no BTI.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/container/native/loader-patch/entry.S"
LOADER="$ROOT/dist-glibc-runtime/lib/ld-linux-aarch64.so.1"
OUT="${1:-$ROOT/dist-glibc-runtime/lib/ld-linux-patched.so.1}"
TC="${GLIBC_TC_PREFIX:-/tmp/glibc-toolchain/root}"
[ -f "$SRC" ] || { echo "[patch-loader] 缺 $SRC" >&2; exit 1; }
[ -f "$LOADER" ] || { echo "[patch-loader] 缺 $LOADER" >&2; exit 1; }
export LD_LIBRARY_PATH="$TC/usr/lib/x86_64-linux-gnu:$TC/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
AS="$TC/usr/bin/aarch64-linux-gnu-as"; OBJCOPY="$TC/usr/bin/aarch64-linux-gnu-objcopy"
[ -x "$AS" ] || { echo "[patch-loader] 缺 aarch64 as，先取 glibc 交叉工具链" >&2; exit 1; }
OBJ=$(mktemp /tmp/lp.XXXXXX.o); BIN=$(mktemp /tmp/lp.XXXXXX.bin)
trap 'rm -f "$OBJ" "$BIN"' EXIT
"$AS" -o "$OBJ" "$SRC"; "$OBJCOPY" -O binary --only-section=.text "$OBJ" "$BIN"
cp -f "$LOADER" "$OUT"
python3 - "$OUT" "$BIN" <<'PY'
import struct, sys
out, scfile = sys.argv[1], sys.argv[2]
SC, ORIG, RESUME = 0x23850, 0x1b280, 0x1b290
sc = bytearray(open(scfile,'rb').read()[SC:])  # .org-padded: take from SC
d = bytearray(open(out,'rb').read())
# entry branch: b ORIG->SC
bi=(SC-ORIG)//4 & 0x7ffff
d[ORIG:ORIG+4]=(0x14000000|bi).to_bytes(4,'little')
# fix shellcode's backward branch (at offset 0x70) to RESUME, PC-relative
pc = SC+0x70
insn = 0x14000000 | (((RESUME-pc)//4) & 0x3ffffff)
struct.pack_into('<I', sc, 0x70, insn)
d[SC:SC+len(sc)] = sc
open(out,'wb').write(d)
print(f'[patch-loader] OK entry b->{SC:#x}, resume b->{RESUME:#x}, sc={len(sc)}B')
PY
chmod 755 "$OUT"
sha256sum "$OUT" | sed 's/^/  /'
