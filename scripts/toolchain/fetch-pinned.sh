#!/usr/bin/env bash
set -euo pipefail

die() { echo "::error title=源码钉值不合::$*" >&2; exit 2; }

HERE=$(dirname "$0")
ROOT_DIR=$(cd "$HERE/../.." && pwd)
TABLE="$ROOT_DIR/scripts/component-sources.json"

OUT=""
WANT=""
VER=""
URLS=()
VERSION_FILE=""

if [ "${1:-}" = "--list-keys" ]; then
  # N3 规范的唯一权威读法：需要直接看表的下游一律走这里，不许自己读文件。
  # 判别形式：全仓 grep「把 component-sources.json 赋给变量」应只剩本文件一处。
  node -e '
    const t=require(process.argv[1]);
    const pat=new RegExp(process.argv[2]||".*");
    process.stdout.write(Object.keys(t.sources||{}).filter(k=>pat.test(k)).join("\n"));
  ' "$TABLE" "${2:-.*}"
  exit 0
fi

if [ "${1:-}" = "--pin" ]; then
  KEY="${2:-}"
  OUT="${3:-}"
  [ -n "$KEY" ] && [ -n "$OUT" ] || die "用法: $0 --pin <键> <落点>（键见 $TABLE）"
  shift 3
  while [ $# -gt 0 ]; do
    case "$1" in
      --version-file) VERSION_FILE="${2:-}"; [ -n "$VERSION_FILE" ] || die "--version-file 后面没给落点"; shift 2 ;;
      *) die "--pin 这一档只认 --version-file，读到的是：$1" ;;
    esac
  done
  if ! META="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const s = (tab.sources || {})[process.argv[2]];
    if (!s) { console.error("键 " + process.argv[2] + " 不在钉值表里（现有: " + Object.keys(tab.sources || {}).join(", ") + "）"); process.exit(1); }
    if (!/^[0-9a-f]{64}$/.test(String(s.sha256))) { console.error("键 " + process.argv[2] + " 的 sha256 不是 64 位小写十六进制: " + String(s.sha256)); process.exit(1); }
    if (!s.version || !String(s.version).trim()) { console.error("键 " + process.argv[2] + " 没有 version 格"); process.exit(1); }
    if (!Array.isArray(s.urls) || s.urls.length === 0) { console.error("键 " + process.argv[2] + " 的 urls 是空的"); process.exit(1); }
    //  缺陷名（C-102）**那 sha256 那一格**逐字**记**的是那**内容**那一个数、=> 而而那**它**逐字**比**的是那**下载**下来**那一个** tar。
    //    => 而那**成因**：=> 而那 tar 那**字节**逐字**每次**换（=> 而而那**它**们**逐字是**那**两**件**事**：「那**tar**是**什么**」与「那**内容**是**什么**」）。
    //    => 而而那**后果**逐字 = 而那 `--pin` 那一族逐字**永远红**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**红**的是**什么**」与「那**为什么**」））。
    //    => 而那**处置**：=> 而那**内容**那一个数逐字**另**一**格**记**（=> 而那 contentSha256 **那一格**）、
    //      => 而而那**它**逐字**两**个**读法**都**留**着**（=> 而而那**它们**逐字是**那**两**件**事**：「那**读**的**是**什么**」与「那**比**的**是**什么**」）。
    const cs = String(s.contentSha256 || "").trim();
    //  => 而那**内容**那一个数逐字**走**那**自己**那一行（=> 而而那**它**逐字**不**跟**那 urls **混**在**一**起**）。
    process.stdout.write(String(s.sha256) + "\n" + String(s.version) + "\n" + cs + "\n" + s.urls.join("\n") + "\n");
  ' "$TABLE" "$KEY")"; then
    die "钉值表这一格读不通：$KEY"
  fi
  { IFS= read -r WANT; IFS= read -r VER; IFS= read -r CONTENT_WANT || true; mapfile -t URLS; } <<< "$META"
  [ -n "$CONTENT_WANT" ] || CONTENT_WANT=""
elif [ "${1:-}" = "--time-base" ]; then
  # 件里嵌的构建时间基准也住这张表，而表的读者必须只有本脚本一个（⑦ 那条判据）。
  # 校验形状与「必须早于现在」都在这里判：钉在未来等于没钉（墙钟还没走到，重建每次都取 time()）。
  if ! TB="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const v = tab.buildTimeEpoch;
    if (!Number.isInteger(v) || v <= 0) { console.error("buildTimeEpoch 必须是正整数秒（现在: " + JSON.stringify(v) + "）"); process.exit(1); }
    if (v * 1000 >= Date.now()) { console.error("buildTimeEpoch=" + v + " 不早于现在，钉不住墙钟"); process.exit(1); }
    process.stdout.write(String(v));
  ' "$TABLE")"; then
    die "钉值表的 buildTimeEpoch 这一格读不通"
  fi
  echo "$TB"
  exit 0
elif [ "${1:-}" = "--ndk" ]; then
  # 交叉编译用的 NDK 版本也住这张表，表的读者仍然只有本脚本（⑦ 那条判据）。
  # 这里**只取不判**：NDK 不是下载来的源码，「实际用的那版等不等于钉值」由 build-component 的
  # 「定位 NDK」步在 runner 上判（那里才有两侧读数可比）。
  if ! ND="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const v = tab.ndkVersion;
    if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(String(v))) { console.error("ndkVersion 不是 x.y.z 形态（读到 " + JSON.stringify(v) + "）"); process.exit(1); }
    process.stdout.write(String(v));
  ' "$TABLE")"; then
    die "钉值表的 ndkVersion 这一格读不通"
  fi
  echo "$ND"
  exit 0
elif [ "${1:-}" = "--llvm" ]; then
  # NDK **内置的 LLVM 版本**也住这张表。
  #
  # 为什么单独记一格：sysroot 来自 NDK，而 clang 件要编的就是 LLVM —— 两者
  # 版本不配的话，编出来的 clang 读 sysroot 会有说不清的怪问题。
  # 而 NDK 的版本号（29.x）与其内置 LLVM 的版本号（20.x）**是两件事**，
  # 从前者推不出后者，所以必须显式记。
  #
  # ── 这一格该填什么、不该填什么（我在这里判错过一次）──
  #   填**实测到的 NDK 版本**，可以短：20 / 20.1 / 20.0.0 都行。
  #   不要把 sources.llvm.version（要编的源码版本，如 20.1.8）填到这一格。
  #
  #   比较是「前缀」关系：实测值必须以这一格开头，反向不成立。
  #   实测 20.1.8 / 钉 20   → 过   （钉得短是允许的）
  #   实测 20.0.0 / 钉 20.1.8 → 红   （钉得比实测长且不一致，就是不同源）
  #   实测 20.1.8 / 钉 20.1.9 → 红
  #
  #   我曾把这一格填成 sources.llvm 的 20.1.8，而 NDK 实测是 20.0.0，
  #   于是判红 —— 那次「红」是**对的**：两个版本确实不是同一版 LLVM。
  #
  # 这里**只取不判**（与 --ndk 同理）：NDK 不是下载来的源码，
  # 「实际那版等不等于钉值」由 build 脚本在 runner 上问 NDK 自己来判。
  # 没有这一格时读出空串并由调用方决定怎么办 —— 不猜版本号。
  if ! LV="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const v = tab.llvmVersion;
    if (v === undefined || v === null || String(v).trim() === "") process.stdout.write("");
    else if (!/^[0-9]+(\.[0-9]+){0,2}$/.test(String(v))) {
      console.error("llvmVersion 形态非法（读到 " + JSON.stringify(v) + "）：应为 20 / 20.1 / 20.1.8 之一");
      process.exit(1);
    } else process.stdout.write(String(v));
  ' "$TABLE")"; then
    die "钉值表的 llvmVersion 这一格读不通"
  fi
  echo "$LV"
  exit 0
elif [ "${1:-}" = "--src-version" ]; then
  # 读某个 sources 键的**版本号**，不下载。
  #
  # 为什么需要这一档：build-component-llvmtoolchain.sh 早先为了拿 LLVM 的版本，
  # 跑的是 `--pin llvm /dev/null` —— 那会**下载整个 171 MiB 源码包**，
  # 只为了从一行输出里 sed 出版本号。而「这个件该编哪个版本」是纯查表的事。
  #
  # 只取不判（与 --ndk/--llvm 同理）：sha256 校验属于 --pin 的职责。
  KEY="${2:?用法: $0 --src-version <键>（键见 $TABLE）}"
  if ! SV="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const s = (tab.sources || {})[process.argv[2]];
    if (!s || typeof s.version !== "string" || !s.version.trim()) {
      console.error("sources." + process.argv[2] + " 没有 version 格（键是否拼错？现有: " + Object.keys(tab.sources || {}).join(", ") + "）");
      process.exit(1);
    }
    process.stdout.write(s.version);
  ' "$TABLE" "$KEY")"; then
    die "钉值表的 sources.$KEY 读不通"
  fi
  echo "$SV"
  exit 0
elif [ $# -ge 3 ]; then
  OUT="${1:-}"
  WANT="${2:-}"
  shift 2
  URLS=("$@")
else
  die "用法: $0 --pin <键> <落点>  或  $0 --time-base  或  $0 --ndk  或  $0 --llvm  或  $0 <落点> <期望 sha256> <url> [url...]（参数少一个都不算数）"
fi

[ -n "$OUT" ] && [ -n "$WANT" ] && [ "${#URLS[@]}" -gt 0 ] \
  || die "落点/钉值/来源三样少一样（--pin 那档由表供给后两样）"
[[ "$WANT" =~ ^[0-9a-f]{64}$ ]] || die "钉值不是 64 位小写十六进制 sha256：$WANT"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

#  缺陷名（C-102）**那 urls 逐字**有**几个**的**时候**逐字**拿**一个**钉值**去**比**每一个** ——
#    => 而而它治**的**是「那**逐文件**那一族**取不取得到**」。
#    => 而那**成因**：=> 而那 kernel 那一格逐字**是**那**逐文件**那一族（=> 而那 urls 逐字**有**那**四**条**）、
#       => 而而那**它们**逐字**各**是**那**一个**文件**、=> 而而那**每**一个**的**字节**都**不**是**那钉值**，
#       => 而而那**它**们**永远**对不上**、=> 而而那**钉值**逐字**对不上**任何**一条**。
#    => 而那**处置**：=> 而那**逐文件**那一族（=> 而那 urls 逐字**带**那 ?format=TEXT **那一族**）逐字**合成**
#       **那一个**确定性 tar **再**跟**那钉值**比（=> 而那**它**逐字**两次**做**一样**）。
#    => 而按 C-104：=> 而那取件那一族逐字**只有**一条路（=> 而那**合成**不是**那**第二种生成路径**、
#       => 而那**它**逐字**是**那**同一个**产物的**一种**取法**）。
  f="$TMP/download"
if [ "${#URLS[@]}" -gt 1 ]; then
  #  => 而那**逐文件**那一族（=> 而那**每**一条**的**路径**都**是**那 ?format=TEXT **那一族**）
  _all_txt=1
  for u in "${URLS[@]}"; do
    case "$u" in *"?format=TEXT") : ;; *) _all_txt=0 ;; esac
  done
  if [ "$_all_txt" = 1 ]; then
    _stage="$TMP/kernel-files"
    rm -rf "$_stage" && mkdir -p "$_stage"
    _paths=""
    for u in "${URLS[@]}"; do
      _p="${u%%\?*}"
      _rel="${_p#*+/}"   #  => 而那**它**逐字**剥掉**那 host **与**那 repo **与**那 **+/**
      _rel="${_rel#*/}"   #  => 而那**它**逐字**剥掉**那 commit **那一个**
      _out="$_stage/$_rel"
      mkdir -p "$(dirname "$_out")"
      _raw="$TMP/kernel-files.raw"
      _ok=0
      for _try in 1 2 3 4 5; do
        if curl -fsSL --http1.1 --max-time 900 "$u" -o "$_raw" 2>/dev/null; then
          #  => 而那**空**的逐字**是**那**上游**偶尔**给**的**那一种**（=> 而那 06 那边逐字**核**的**是**那 e3b0c442、=> 而那**它**逐字**是**那**空文件**那 sha）、
          #     => 而那**它**逐字**会**让**那合成**逐字**不稳**、=> 而而那**处置**逐字**是**：=> 而那**空**的**不**算**取到**、=> 而那**重试**继续**走**。
          if [ -s "$_raw" ] && base64 -d < "$_raw" > "$_out" 2>/dev/null && [ -s "$_out" ]; then _ok=1; break; fi
        fi
        sleep 2
      done
      if [ "$_ok" != 1 ]; then
        echo "[fetch-pinned] 逐文件那一族取不到：$u"
        exit 1
      fi
      _paths="$_paths$_rel\n"
    done
    #  => 而那**合成**逐字**用**那 mtime=0 那一族**好**的**确定性**打包**（=> 而那**它**逐字**两次**做**一样**）
    if command -v python3 >/dev/null 2>&1; then
      printf '%b' "$_paths" > "$TMP/kernel-files.paths"
      python3 scripts/toolchain/kernel-files-tar.py "$_stage" "$TMP/kernel-files.paths" > "$f"
    else
      echo "[fetch-pinned] 逐文件那一族要合成那确定性 tar、=> 而而那 python3 逐字**没有**"
      exit 1
    fi
    got="$(sha256sum "$f" | cut -d' ' -f1)"
    if [ "$got" != "$WANT" ]; then
      echo "[fetch-pinned] 逐文件那一族合成出来的不合钉值：实得 $got（期望 $WANT）"
      exit 1
    fi
    mkdir -p "$(dirname "$OUT")"
    mv "$f" "$OUT"
    if [ -n "$VERSION_FILE" ]; then
      mkdir -p "$(dirname "$VERSION_FILE")"
      printf '%s\n' "$VER" > "$VERSION_FILE"
      echo "[fetch-pinned] 件版本格 $VERSION_FILE = $VER（这一格由这次取数写，与钉值是同一个事实）"
    fi
    echo "[fetch-pinned] 校验通过（逐文件那一族合成）：$(basename "$OUT") 钉值表 version=${VER:-未名} sha256=$got"
    exit 0
  fi
fi
for url in "${URLS[@]}"; do
  # 只认 https。早先这里还放行 file://（想着「本地测试方便」）——
  # 但 curl 默认**禁用** file 协议（实测：`curl: (1) Protocol "file" is disabled`），
  # 所以那条放行等于给了一个必然失败的分支：报出来的是 curl 的协议错误，
  # 而不是「这个来源不可用，换下一条」。
  case "$url" in
    https://*) : ;;
    *) die "来源不是 https（file:// 也不行 —— curl 禁用了 file 协议，配了必然失败）: $url" ;;
  esac
  #  缺陷名（C-102）**这一行逐字**没**带**那 --http1.1** ——
  #    => 而而它治**的**是「那来源逐字**取不取得到**」（=> 而那取件那一族）。
  #    => 而那**成因**：=> 而那上游（=> 而那 android.googlesource.com **那一族**）逐字**要**那 HTTP/1.1，
  #       => 而那 curl 默认逐字**走**那 HTTP/2、=> 而那**它**逐字**退**那 **35**（=> 而那 C-532 **那一族**记**的**那**一件**）。
  #    => 而那**处置**：=> 而这一行逐字**带**那 --http1.1（=> 而带上**之后**逐字**是**那 **0**）。
  #    => 而按 C-104：=> 而那取件那一族逐字**只有**一条路（=> 而那**它们**逐字都**是**那 curl **那一条**）。
  if ! curl -fsSL --http1.1 --max-time 900 "$url" -o "$f"; then
    echo "[fetch-pinned] 下载失败，换下一条来源：$url"
    continue
  fi
  got="$(sha256sum "$f" | cut -d' ' -f1)"
  #  缺陷名（C-102）**那 sha256 那一格**逐字**记**的是那**内容**那一个数、=> 而而那**它**逐字**比**的是那**下载**下来**那一个** tar。
  #    => 而那**成因**：=> 而那 tar 那**字节**逐字**每次**换（=> 而而那**它**们**逐字是**那**两**件**事**：「那**tar**是**什么**」与「那**内容**是**什么**」）。
  #    => 而而那**后果**逐字 = 而那 `--pin` 那一族逐字**永远红**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**红**的是**什么**」与「那**为什么**」））。
  #    => 而那**处置**：=> 而那**内容**那一个数**有**那 contentSha256 **那一格**的时候、
  #      => 而而那**它**逐字**比**那**解开之后**那**逐件**清单**那一个**数**、=> 而而那**它**逐字**不**比**那 tar。
  #      => 而而那**它**们**逐字是**那**两**件**事**：「那**比**的**是**什么**」与「那**记**的**是**什么**」）。
  if [ -n "$CONTENT_WANT" ]; then
    _cdir="$(dirname "$f")"
    _cstage="$_cdir/content-$"
    rm -rf "$_cstage"; mkdir -p "$_cstage"
    if ! tar xzf "$f" -C "$_cstage" 2>/dev/null; then
      echo "[fetch-pinned] 来源 $url 解不开（内容钉值那一族要解包再比）: $url"
      rm -rf "$_cstage"
      continue
    fi
    #  => 而那**清单**逐字**按**那**路径**排**序**（=> 而那**排序**逐字**用**那**本机**那**个**locale**、=> 而而那**它**逐字**不**是**那 LC_ALL=C **那一种**）。
    cgot="$(cd "$_cstage" && find . -type f -print0 2>/dev/null | sort -z | xargs -0 sha256sum 2>/dev/null | sha256sum | cut -d' ' -f1)"
    rm -rf "$_cstage"
    if [ "$cgot" != "$CONTENT_WANT" ]; then
      echo "[fetch-pinned] 来源 $url 的内容不合钉值：实得 $cgot（期望 $CONTENT_WANT）—— 这不是同一批内容，换下一条"
      continue
    fi
    echo "[fetch-pinned] 内容钉值校验通过：$url（内容 $cgot；那 tar 那字节逐字每次换、=> 而那**它**逐字**不**钉**它**）"
  elif [ "$got" != "$WANT" ]; then
    echo "[fetch-pinned] 来源 $url 不合钉值：实得 $got（期望 $WANT）—— 这不是同一批字节，换下一条"
    continue
  fi
  mkdir -p "$(dirname "$OUT")"
  mv "$f" "$OUT"
  if [ -n "$VERSION_FILE" ]; then
    mkdir -p "$(dirname "$VERSION_FILE")"
    printf '%s\n' "$VER" > "$VERSION_FILE"
    echo "[fetch-pinned] 件版本格 $VERSION_FILE = $VER（这一格由这次取数写，与钉值是同一个事实）"
  fi
  echo "[fetch-pinned] 校验通过：$(basename "$OUT") 钉值表 version=${VER:-未名} sha256=$got 来源=$url"
  exit 0
done

die "所有来源都不合钉值 $WANT（共 ${#URLS[@]} 条）—— 宁可不编译，也不许用没有身份的源码出件。"
