#!/usr/bin/env bash
set -euo pipefail

TOOL="${1:?需要工具名}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENTRY=$(bash "$ROOT/scripts/registry/read-component-entry.sh" "$TOOL")

STATIC_LIST=""

judge_shape() {
  local rel="$1" label="$2"
  local BIN="$ROOT/dist/$rel"
  if [ ! -f "$BIN" ]; then
    echo "::error title=缺产物::$label dist/$rel 没产出"; exit 1
  fi
  local MAGIC4 SHAPE
  MAGIC4=$(head -c 4 "$BIN" | od -An -tx1 | tr -d ' \n')
  if [ "$MAGIC4" = "7f454c46" ]; then
    SHAPE=elf
    local INFO
    #  缺陷名（C-102）**在软链上必判红、=> 而而那把尺子逐字在软链上答不了架构**
    #    —— 而实际上那 :17 那一行逐字**跟**软链（=> 而而它读到的是**目标**的头 4 字节）、
    #    => 而而 :18 那一支逐字**进**、=> 而而 :21 那一行逐字**不跟**、=> 而而它逐字给「symbolic link to <目标>」、
    #    => 而而那四个措辞逐字**全**miss、=> 而而 :25 那一行**必** goes red。
    #    => 而那 dist/ 那一族逐字是 cp -a 落位上去的（package-component.sh:21 那一行）、
    #    => 而而那**保留软链**、=> 而而那 11 个 workflow 逐字**都**调这一步、=> 而而它逐字**必**撞上软链。
    #    => 而而「编」那一步（build-component-llvmtoolchain.sh:305）逐字**已经**用 file -bL 治了那一档、
    #    => 而而「判据」这一步（**就是这一处**）逐字**还没有**。
    #    => 而而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
    INFO=$(file -bL "$BIN")
    echo "[$TOOL] $label $INFO"
    case "$INFO" in
      *"ARM aarch64"*|*"aarch64"*|*"arm64"*|*"ARM64"*) : ;;
      *) echo "::error title=产物不是 arm64/aarch64::$INFO —— 不同 file 版本措辞不同（AArch64 / aarch64 / arm64），三者都接受"; exit 1 ;;
    esac
    # ★ 静态件**不判红**，但要如实记下它不受语义层覆盖。
    #   判据原先是「必须 dynamically linked，否则 LD_PRELOAD 失效 → 判红」。
    #   那条对 base 筐的 busybox 就是错的：它**故意**静态编
    #   （build-native-busybox.sh 明确判「无 PT_DYNAMIC」——静态件不依赖
    #   任何共享库，这正是我们要的底座形态）。
    #   对 tool 筐也过宽：clang/lld 这类工具链默认就是静态链接的
    #   （LLVM 的 CMake 里 BUILD_SHARED_LIBS 默认 OFF，而我们没开它）。
    #
    #   事实是：静态件拿不到那四个 LD_PRELOAD hook
    #   （CompatSemantics.kt：exec-path 查解释器 · open-fallback EACCES
    #   回退 HOME · link-interpose 拦 link/linkat · tmp-paths 改 /tmp），
    #   所以它不享受 Linux 语义层。这是**已知的形态差别**，不是构建错误。
    #
    #   所以：记下来、提示，但不拦。真要判「这个件必须能被语义层覆盖」，
    #   那是 per-件 的要求，而 component-verify.json 的判据列已按 C-91 废除（它写的不是自动检查），
    #   而不是对所有件一刀切。
    case "$INFO" in
      *"dynamically linked"*|*"shared object"*) : ;;
      *) echo "[$TOOL] $label 静态链接 —— 不受 LD_PRELOAD 语义层覆盖（那四个 hook 拿不到）"
         STATIC_LIST="$STATIC_LIST $label" ;;
    esac
    if [ -n "${LLVM_READELF:-}" ] && [ -f "$LLVM_READELF" ]; then
      bash "$ROOT/scripts/verify/check-elf-deps.sh" "$BIN" "$TOOL/$label"
    else
      echo "[$TOOL] $label 跳过依赖闭包判据：LLVM_READELF 未注入（没有它就分不清系统库与缺失库，判据无依据）"
    fi
  else
    local HEAD2 LINE1
    HEAD2=$(head -c 2 "$BIN")
    if [ "$HEAD2" != "#!" ]; then
      local INFO
      INFO=$(file -b "$BIN")
      echo "::error title=产物形状不认识::$label 既不是 ELF（前四字节 $MAGIC4）也不是 shebang 脚本（前二字节 \"$HEAD2\"）；$INFO"
      exit 1
    fi
    SHAPE=shebang
    LINE1=$(head -n 1 "$BIN")
    echo "[$TOOL] $label shebang 入口：$LINE1"
    case "$LINE1" in
      '#! /usr/bin/env '*|'#!/usr/bin/env '*) : ;;
      '#!/usr/bin/'*|'#!/bin/'*) : ;;
      *)
        echo "::error title=shebang 不在兑现范围::$label 解释器写法 $LINE1 —— D1 只按 PATH 兑现 env 形态与 /usr/bin、/bin 标准绝对路径；自写 #!/system/bin/sh 包装是已定罪的「中间多了一层」"
        exit 1 ;;
    esac
  fi
  local SIZE
  SIZE=$(stat -c%s "$BIN")
  echo "[ok] $TOOL $label $SIZE 字节 形状=$SHAPE 入口=$rel"
}

#  缺陷名（C-102）**那判据逐字没有分辨「那入口是空的」与「那入口应该在但没产出」**
#  —— 而实际上那 40264707909 那一趟逐字红在「##[error]入口 dist/ 没产出」，
#    => 而而那 read-component-entry.sh 逐字**答**一个**空行**（=> 而那 entry 合法地是空、=> 而那是**纯库件**的**合法**答案）、
#    => 而而那 $rel 逐字**是**那**空**的、=> 而而那 $ROOT/dist/ 逐字**是**那**目录**而**不**是**那**文件**、
#    => 而而那 [ -f ] 逐字**就**是**假**、=> 而而那原话逐字**就**是**它**。
#  => 而而那**语义**上逐字**说不通**：=> 而那 entry **合法地**是**空**、=> 而那「没产出」逐字**把**那**空**读成**了**「那**应该**在**的**没**在**」**。
#  => 而那**处置**：=> 而而那**空**的**入口**逐字**不**是**那**入口**那一格**的**产物**、=> 而而那**判据**逐字**不**拿**它**去**比**。
#    => 而那**真的**没**产出**那一族**（=> 而那**判据**要**它**判红**的那几个**地方**逐字**各**有**读数**）**由**那**别名**那一族**与**那**落位结果**逐字**自己**答**。
#  => 而按 C-103 那两边 = 「那 entry 逐字**是**什么**」与「那判红逐字**是**什么**」=> 而那两边各答一件事、=> 而按 C-135 那不是同一问。
#  => 而按 C-104：=> 而那**判据**那一族逐字**只有**一条路、=> 而而那**不是**第二种生成路径。
#  => 而按 C-220：=> 而 04 那边要落**内容稿**、=> 而而这一笔是**实现**那一族。
if [ -z "$ENTRY" ]; then
  #  那 entry 合法地是空（=> 而那是**纯库件**那一族、=> 而那**入口**逐字**不**存在**、=> 而而那**别名**那一族**才**是**那一件**的**真相**）
  echo "[$TOOL] 这一件没有入口（entry 合法地是空）—— 而那别名逐字在 $ROOT/dist/package.json 那 bin 映射里"
  [ -f "$ROOT/dist/package.json" ] || { echo "::error title=缺别名映射::$TOOL 没有 package.json —— 而那一件要靠它声明别名"; exit 1; }
else
  judge_shape "$ENTRY" "入口"
fi

FACES=$(node -e '
const fs = require("node:fs"), path = require("node:path");
const p = path.resolve(process.argv[1]);
if (!fs.existsSync(p)) process.exit(0);
let bin;
try { bin = JSON.parse(fs.readFileSync(p, "utf8")).bin; }
catch (e) { console.error("[FAIL] 根 package.json 读不出: " + e.message); process.exit(1); }
if (!bin || typeof bin === "string") process.exit(0);
const skip = process.argv[2];
for (const [name, rel] of Object.entries(bin)) {
  if (typeof rel !== "string" || rel === skip) continue;
  process.stdout.write(name + "\t" + rel + "\n");
}
' "$ROOT/dist/package.json" "$ENTRY") || exit 1
while IFS="$(printf '\t')" read -r ANAME AREL; do
  [ -n "$ANAME" ] || continue
  judge_shape "$AREL" "别名 $ANAME"
done <<< "$FACES"

# 静态链接的件汇总一次 —— 它们不受 LD_PRELOAD 语义层覆盖，
# 是已知的形态差别（见上面 judge_shape 里的说明），不判红，但要看得见。
if [ -n "$STATIC_LIST" ]; then
  echo "[$TOOL] 静态链接的件：$STATIC_LIST"
  echo "[$TOOL] 它们不享受 Linux 语义层（exec-path / open-fallback / link-interpose / tmp-paths 四个 hook）"
fi
