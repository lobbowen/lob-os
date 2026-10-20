#!/usr/bin/env bash
# 打标准件归档 —— 一件一个包，装配器只读包。
#
# 形态（照抄 .deb：包里自带全部信息，装的时候不查外部清单）：
#   component-<id>-<版本>-<sha12>.zip
#     ├── component-meta.json    自描述：id / version / entry / form / requires
#     ├── bin/<名>               命令（entry=bin/… 时）
#     └── lib/<名>.so            库（entry=lib/… 时）
#
# package-component.sh 已转发到这里（单一入口，避免两套打包逻辑漂移）。
# 这个从 OUT_DIR 读、用 tar —— 本机与 CI 都有 tar，zip 不在 base 镜像里。
#
# 用法：package-bundle-zip.sh <件名> <OUT_DIR> <版本> [entry]
set -euo pipefail

ID="${1:?需要件名}"
OUT="${2:?需要 OUT_DIR}"
VER="${3:?需要版本}"
ENTRY="${4:-}"
# 第 5 个参数：这个件要在 usr/bin 出现的命令名，空格分隔。
# 由调用方从自己既有的变量派生后传入（如 busybox 从 $APPLETS 转小写）——
# 不在本脚本里另写一份名单，否则两份名单会各自漂移。
BINS="${5:-}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$ROOT"

META_SRC="$ROOT/scripts/component-sources.json"
[ -f "$META_SRC" ] || { echo "::error title=缺钉值表::$META_SRC 不在"; exit 1; }

if [ -z "$ENTRY" ]; then
  if [ -d "$OUT/bin" ] && [ -n "$(ls -A "$OUT/bin" 2>/dev/null)" ]; then
    ENTRY="bin/$(ls "$OUT/bin" | head -1)"
  else
    ENTRY="lib/$(ls "$OUT/lib" | grep '\.so$' | head -1)"
  fi
fi

case "$ENTRY" in
  bin/*|lib/*) ;;
  *) echo "::error title=entry 形状不对::$ENTRY"; exit 1 ;;
esac

[ -e "$OUT/$ENTRY" ] || {
  echo "::error title=缺件::$OUT/$ENTRY 不在（entry=$ENTRY）"
  find "$OUT" -type f 2>/dev/null | head -10 | sed 's/^/::error::    /'
  exit 1
}

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# 只取 entry 指的那个文件，外加与它同名的版本链 —— **不整个目录搬**。
#
# openssl 的构建产物目录里还有 libcrypto.a、cmake/、engines-3/、ossl-modules/
# 这些不是这个件的运行时所需（openssl 的包实测 7.6MB，而它只该有
# libssl.so 与 libcrypto.so 两个文件）。整个搬进来等于把构建残留也装进系统。
#
# 版本链指共享库的 soname 一族（libz.so → libz.so.1 → libz.so.1.3.2）：
# 它们是同一份字节的三个名字，而 linker 按 DT_NEEDED 找的是带版本的那一层。
mkdir -p "$STAGE/$(dirname "$ENTRY")"
cp -Pf "$OUT/$ENTRY" "$STAGE/$ENTRY" 2>/dev/null || cp -f "$OUT/$ENTRY" "$STAGE/$ENTRY"
eb="$(basename "$ENTRY")"
ed="$(dirname "$ENTRY")"
# OPENSSL_LIBS=1 时把同目录下所有 .so 都带上。
# openssl 一次编译产出 libssl.so 与 libcrypto.so，而 crypto 是清单里的独立一件
#（component-sources.json 里 crypto.sameAs=openssl），由 build-crypto.yml 从
# 本件的包里拆出去。只带 entry 指的那个，拆包时就无货可拆。
if [ "${OPENSSL_LIBS:-0}" = "1" ]; then
  # openssl 一次编出 libssl.so 与 libcrypto.so，而 crypto 是清单里的独立一件
  #（component-sources.json 里 crypto.sameAs=openssl），由 build-crypto.yml
  # 从本件的包里拆出去 —— 所以两份都要在包里。
  #
  # 但**只有本件的 entry 那一份带主名（libfoo.so）**：
  # 主名是「这个包里哪个是入口」的标记（gen-component-meta.js 的
  # computeEntry 就靠 lib/ 下唯一的主名 .so 算 entry）。
  # 别的件的那份只带版本层（libcrypto.so.3），拆包时由 build-crypto.yml
  # 重新摆成它自己的主名。
  for extra in "$OUT/$ed/"*.so "$OUT/$ed/"*.so.*; do
    [ -f "$extra" ] || continue
    xb="$(basename "$extra")"
    case "$xb" in
      *.so)
        # 主名：只有与 entry 同族的那份才带
        if [ "$xb" != "$eb" ]; then continue; fi ;;
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

# 带上该件声明的命令入口。
#
# 为什么必须显式列：entry 只能指一个文件，而一个件可能有多个命令入口
#（busybox 有 18 个 applet 软链）。上面那段只搬 entry 与其同名版本链，
# 所以 bin/ 下其余的可执行件若无此步就会**全部丢失**，而包仍然是「合法」的
# —— 2026-10-16 实测：构建日志说「18 个 applet 软链进包」，而 unzip 出来 0 个。
#
# 这里逐个查存在性：**清单里写了而盘上没有 ⇒ 直接判红**，
# 因为那意味着「声明了但没产出」，而它正是本轮那类静默失效。
N_BINS=0
for bn in $BINS; do
  if [ -e "$OUT/bin/$bn" ]; then
    mkdir -p "$STAGE/bin"
    # C-102 **`|| cp -f` 兜底把软链解引用成实体** —— 这一行原来在 cp -Pf 失败时退回 cp -f，
    # 而 cp -f 会跟随符号链接，于是「这一条命令入口」从软链变成一份独立的字节副本。
    # 真机读数（07，base-busybox 那一版）：files/opt/busybox/1.36.1/bin/cat 与 lib/libbusybox.so
    # sha256 相同（87e32d67…b51e0）⇒ 同一份字节出现在 18 个名字下。
    # 而构建侧写的是 ln -sf（build-native-busybox.sh:260），01 今天按 man1 INSTALL 段裁定：
    # 「make install 连带把全部软链建好」⇒ 那些是官方产物，**不该在打包时被展平**。
    # 处置：失败就判红，不兜底解引用。
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

# ── 形态：构建期算一次，写进产物 ──────────────────────────────
#
# 缺陷名（C-102）产物不携带形态，运行期只能靠文件名/目录名猜：
#   原来包里只有 component-meta.json（id/version/entry/requires），
#   没有任何一处写着「这份字节是库还是程序」——
#   而今天下午定的规则是「注册表只记库在哪里与程序在哪里；
#   运行期只读，不判断、不探测」。那就必须由产物自己带着这个答案。
#
# 形态写进 **piece-shape.json**（与 component-meta.json 并列，包根）：
#   · form 逐文件 ∈ {exec, dyn-interp, dyn-plain, unknown}
#   · role 逐文件 ∈ {program, library, unknown}
#   · kind 整件   ∈ {program, library, mixed, unknown}
#
# 顺带在这一步把两件事定死（都在构建期，运行期不再判断）：
#   · 程序形态的字节带 owner 执行位（0755）；库形态的不带（0644）
#   · 库形态的字节补齐 ldconfig(8) 的三段链 libfoo.so -> libfoo.so.1 -> libfoo.so.1.12
# ── 清单：entry 由落位结果算出（gen-component-meta.js 自己算，不是人填的）──
node "$ROOT/scripts/recipes/gen-component-meta.js" \
  "$ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
  || { echo "::error title=$ID 说明生成失败"; exit 1; }

# 算出来的 entry 与调用方声明的那一份不一致 ⇒ 两处形态说法分叉了。
# 不改判红（那样会把「算出来的」与「声明的」两个真相源永远锁死），
# 但要**说出来** —— 哪个是真相由 01/04 裁。
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
TMP_PKG="$DIST/.component-${ID}-${VER}.zip"

# C-102 **zip 不带 -y ⇒ 软链被按目标内容写进包** —— 暂存目录里即使还是软链，
# zip 也会跟随它把目标的字节写进去，于是「命令名」变成一份独立的字节副本。
# -y = 按符号链接存（存目标路径本身，不存内容）—— 那是这一族该有的形状：
# 那些是 make install 官方建的同目录软链（build-native-busybox.sh:239-241），
# 它们是**派生物**，落位时重建，不该在分发包里变成 N 份字节。
( cd "$STAGE" && zip -q -r -X -y "$TMP_PKG" . ) || { echo "::error title=$ID 打包失败"; exit 1; }

SHA="$(sha256sum "$TMP_PKG" | cut -c1-12)"
NAME="component-${ID}-${VER}-${SHA}-android-arm64.zip"
mv -f "$TMP_PKG" "$DIST/$NAME"
sha256sum "$DIST/$NAME" | cut -d' ' -f1 > "$DIST/$NAME.sha256"

echo "[ok] $DIST/$NAME"
echo "[ok] entry=$ENTRY  文件=$(find "$STAGE" -type f | wc -l)"
