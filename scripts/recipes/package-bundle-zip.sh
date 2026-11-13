#!/usr/bin/env bash

set -euo pipefail

ID="${1:?需要件名}"
OUT="${2:?需要 OUT_DIR}"
VER="${3:?需要版本}"
ENTRY="${4:-}"

BINS="${5:-}"

MARKER="${6:-}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$ROOT"

META_SRC="$ROOT/scripts/component-sources.json"
[ -f "$META_SRC" ] || { echo "::error title=缺钉值表::$META_SRC 不在"; exit 1; }

source "$ROOT/scripts/toolchain/elf-form.sh"

author_entry_name_of() {
  node -e '
    const s = require(process.argv[1]).sources || {};
    const v = (s[process.argv[2]] || {}).entryName;
    if (v) process.stdout.write(String(v));
  ' "$ROOT/scripts/component-sources.json" "$1"
}

DECLARED_ENTRY="$ENTRY"
PROG_ENTRY=""

WANT_ENTRY="$(author_entry_name_of "$ID")"
if [ -n "$WANT_ENTRY" ] && [ -f "$OUT/bin/$WANT_ENTRY" ]; then
  wf="$(elf_form "$OUT/bin/$WANT_ENTRY" 2>/dev/null)"
  if elf_form_is_program "$wf"; then PROG_ENTRY="bin/$WANT_ENTRY"; fi
fi
if [ -z "$PROG_ENTRY" ] && [ -d "$OUT/bin" ]; then
  for f in $(ls -A "$OUT/bin" 2>/dev/null | LC_ALL=C sort); do
    p="$OUT/bin/$f"
    [ -f "$p" ] || continue
    ff="$(elf_form "$p" 2>/dev/null)"
    if elf_form_is_program "$ff"; then PROG_ENTRY="bin/$f"; break; fi
  done
fi
if [ -n "$PROG_ENTRY" ]; then
  ENTRY="$PROG_ENTRY"
  echo "  （这一件有程序那一侧：entry=$ENTRY）"
else
  ENTRY=""
  echo "  （这一件只有库形态的字节：entry 留空）"
fi
if [ -n "$DECLARED_ENTRY" ] && [ "$DECLARED_ENTRY" != "$ENTRY" ]; then
  echo "  （调用方声明的 entry=$DECLARED_ENTRY，而按形态算是 '$ENTRY' —— 以形态为准，声明只记录）"
fi

if [ -n "$ENTRY" ]; then
  case "$ENTRY" in
    bin/*|lib/*) ;;
    *) echo "::error title=entry 形状不对::$ENTRY"; exit 1 ;;
esac

  [ -e "$OUT/$ENTRY" ] || {
    echo "::error title=缺件::$OUT/$ENTRY 不在（entry=$ENTRY）"
    find "$OUT" -type f 2>/dev/null | head -10 | sed 's/^/::error::    /'
    exit 1
  }
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

if [ -n "$ENTRY" ]; then
  mkdir -p "$STAGE/$(dirname "$ENTRY")"
  cp -Pf "$OUT/$ENTRY" "$STAGE/$ENTRY" 2>/dev/null || cp -f "$OUT/$ENTRY" "$STAGE/$ENTRY"
  eb="$(basename "$ENTRY")"
  ed="$(dirname "$ENTRY")"

  if [ -d "$OUT/lib" ]; then
    mkdir -p "$STAGE/lib"
    for f in $(ls -A "$OUT/lib" 2>/dev/null | LC_ALL=C sort); do
      case "$f" in *.so|*.so.*) cp -Pf "$OUT/lib/$f" "$STAGE/lib/$f" 2>/dev/null || cp -f "$OUT/lib/$f" "$STAGE/lib/$f" ;; esac
    done
    echo "  （混合件：lib/ 下搬了 $(ls -A "$STAGE/lib" 2>/dev/null | wc -l) 个名字）"
  fi

  if [ -d "$OUT/bin" ]; then
    mkdir -p "$STAGE/bin"

    for bn in $BINS; do
      if [ -e "$OUT/bin/$bn" ]; then
        cp -Pf "$OUT/bin/$bn" "$STAGE/bin/$bn" || {
          echo "::error title=$ID 命令入口搬运失败::bin/$bn 搬不进暂存目录（它是不是断链？先查构建侧那一件）"
          exit 1
        }
      fi
    done

    for f in $(ls -A "$OUT/bin" 2>/dev/null | LC_ALL=C sort); do
      p="$OUT/bin/$f"
      [ -L "$p" ] || continue
      real="$(readlink -f "$p" 2>/dev/null || true)"
      [ -n "$real" ] && [ -f "$real" ] || continue

      rel="${real#$OUT/}"
      [ "$rel" != "$real" ] || continue
      if [ ! -e "$STAGE/$rel" ]; then
        mkdir -p "$(dirname "$STAGE/$rel")"
        cp -Pf "$OUT/$rel" "$STAGE/$rel" || {
          echo "::error title=$ID 软链目标搬运失败::bin/$f 指向 $rel，一项搬不进暂存目录"
          exit 1
        }
      fi
    done
    echo "  （bin/ 下搬了 $(ls -A "$STAGE/bin" 2>/dev/null | wc -l) 个名字；是 BINS 那一列 + 那几条软链的指向目标）"

    if [ -f "$OUT/link-farm.txt" ]; then
      N_FARM=0
      while read -r FLINE; do
        FPATH="${FLINE%%$'\t'*}
        FTGT="${FLINE#*$'\t'}
        [ -n "$FPATH" ] || continue
        [ -n "$FTGT" ] || continue
        mkdir -p "$STAGE/$(dirname "$FPATH")"

        if [ ! -e "$STAGE/$FPATH" ]; then
          ln -sf "$FTGT" "$STAGE/$FPATH" 2>/dev/null || true
        fi
        N_FARM=$((N_FARM+1))
      done < "$OUT/link-farm.txt"
      echo "  （link-farm：建了 $N_FARM 条软链）"

      if [ -f "$OUT/link-farm.txt" ]; then
        cp -f "$OUT/link-farm.txt" "$STAGE/link-farm.txt" 2>/dev/null || true
      fi
    fi

    for extra in $(cd "$OUT" && ls -A 2>/dev/null | LC_ALL=C sort); do
      case "$extra" in
        bin|lib|*.zip|*.tar.gz|*.sha256|component-meta.json|piece-shape.json|program-manifest.json|link-farm.txt|*.version) continue ;;
      esac
      [ -d "$OUT/$extra" ] || continue
      mkdir -p "$STAGE/$extra"
      ( cd "$OUT/$extra" && find . -type d -exec mkdir -p "$STAGE/$extra/{}" \; )
      ( cd "$OUT/$extra" && find . \( -type f -o -type l \) | while read -r f; do
          mkdir -p "$STAGE/$extra/$(dirname "$f")"
          cp -Pf "$OUT/$extra/$f" "$STAGE/$extra/$f" 2>/dev/null || cp -f "$OUT/$extra/$f" "$STAGE/$extra/$f" 2>/dev/null || true
        done )
      echo "  （额外那一层 $extra/ 搬了 $(find "$STAGE/$extra" \( -type f -o -type l \) 2>/dev/null | wc -l) 项）"
    done
  fi
else

  eb=""
  ed="lib"
  mkdir -p "$STAGE/lib"
  if [ -d "$OUT/lib" ]; then
    for f in $(ls -A "$OUT/lib" 2>/dev/null | LC_ALL=C sort); do
      case "$f" in *.so|*.so.*) cp -Pf "$OUT/lib/$f" "$STAGE/lib/$f" 2>/dev/null || cp -f "$OUT/lib/$f" "$STAGE/lib/$f" ;; esac
    done
  fi
  echo "  （纯库件：lib/ 下搬了 $(ls -A "$STAGE/lib" 2>/dev/null | wc -l) 个名字）"
fi

if [ "${OPENSSL_LIBS:-0}" = "1" ]; then

  for extra in "$OUT/$ed/"*.so "$OUT/$ed/"*.so.*; do
    [ -f "$extra" ] || continue
    xb="$(basename "$extra")"
    case "$xb" in
      *.so)

        if [ "$xb" != "$eb" ]; then

          ver=""
          for cand in "$OUT/$ed/$xb".*; do
            [ -f "$cand" ] || continue
            ver="$(basename "$cand")"
            break
          done
          [ -n "$ver" ] || continue
          cp -Pf "$OUT/$ed/$ver" "$STAGE/$ed/$ver" 2>/dev/null || cp -f "$OUT/$ed/$ver" "$STAGE/$ed/$ver"
          continue
        fi ;;
    esac

    case "$xb" in
      *.so) if [ "$xb" != "$eb" ]; then continue; fi ;;
    esac
    cp -Pf "$extra" "$STAGE/$ed/$xb" 2>/dev/null || cp -f "$extra" "$STAGE/$ed/$xb"
  done
else
  for extra in "$OUT/$ed/$eb".*; do
    [ -f "$extra" ] || continue
    xb="$(basename "$extra")"
    case "$xb" in *.so|*.so.*) cp -Pf "$extra" "$STAGE/$ed/$xb" 2>/dev/null || cp -f "$extra" "$STAGE/$ed/$xb" ;; esac
  done
fi

N_BINS=0
for bn in $BINS; do
  if [ -e "$OUT/bin/$bn" ]; then
    mkdir -p "$STAGE/bin"

    cp -Pf "$OUT/bin/$bn" "$STAGE/bin/$bn" || {
      echo "::error title=$ID 命令入口搬运失败::bin/$bn 搬不进暂存目录（它是不是断链？先查构建侧那一件）"
      exit 1
    }
    N_BINS=$((N_BINS+1))
  else
    echo "::error title=$ID 清单里的命令入口不存在::bin/$bn 在 $OUT 下没有 —— 声明了但没产出"
    exit 1
  fi
done
[ "$N_BINS" -gt 0 ] && echo "[zip] $ID 另带 $N_BINS 个命令入口：$(echo $BINS)"

BUILD_SHA="${GITHUB_SHA:-local}"
BUILD_RUN="${GITHUB_RUN_ID:-local}"
node "$ROOT/scripts/recipes/gen-component-meta.js" \
  "$ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
  --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN" \
  || { echo "::error title=$ID 说明生成失败"; exit 1; }

META_ENTRY="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).entry||""))}catch(e){}})' < "$STAGE/component-meta.json")"
if [ -n "$META_ENTRY" ] && [ -n "$ENTRY" ] && [ "$META_ENTRY" != "$ENTRY" ]; then
  echo "::warning title=$ID 入口两处不一致::落位算出的 entry=$META_ENTRY，而调用方声明的是 $ENTRY —— 形态以落位结果为准（待 01/04 裁定）"
  ENTRY="$META_ENTRY"
fi

source "$ROOT/scripts/toolchain/elf-form.sh"
elf_write_shape "$ID" "$ENTRY" "$STAGE" "$STAGE/piece-shape.json" "$VER" bin lib \
  || { echo "::error title=$ID 形态算不出来::构建期必须能判形态（readelf 缺失）"; exit 1; }

find "$STAGE" -exec touch -h -t 198001010000.00 {} +

DIST="$ROOT/dist"
mkdir -p "$DIST"

rm -rf "$DIST"/* 2>/dev/null || true

if [ -n "$MARKER" ]; then
  if [ ! -e "$OUT/$MARKER" ]; then
    echo "::error title=$ID 这一件声明的标记文件不在::声明: $MARKER（ 而它是这一件自己声明的）"
    echo "::error:: 而它在产物的目录里：$OUT/$MARKER"
    echo "::error::   而这一格答的是「这一件走到哪一步了」 ——   而缺了它，"
    echo "::error::   而后面几步会报「没产出包」， 而那一句是后果不是原因 "
    ls -la "$OUT" 2>/dev/null | head -10 | sed 's/^/::error::   /'
    exit 1
  fi
  echo "  · $ID 标记文件在（$MARKER）——   而这一件走到了产包之前"
fi
TMP_PKG="$DIST/.component-${ID}-${VER}.zip"

( cd "$STAGE" && zip -q -r -X -y "$TMP_PKG" . ) || { echo "::error title=$ID 打包失败"; exit 1; }

SHA="$(sha256sum "$TMP_PKG" | cut -c1-12)"
NAME="component-${ID}-${VER}-${SHA}-android-arm64.zip"
mv -f "$TMP_PKG" "$DIST/$NAME"
sha256sum "$DIST/$NAME" | cut -d' ' -f1 > "$DIST/$NAME.sha256"

echo "[ok] $DIST/$NAME"
echo "[ok] entry=$ENTRY  文件=$(find "$STAGE" -type f | wc -l)"
