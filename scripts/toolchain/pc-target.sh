#!/usr/bin/env bash
#  那 **PC 侧那一个承载**逐字那固化路（ 它逐字是「那产物侧」那一族）。
#
#  缺陷名（C-102）**那一族逐字不在仓内、 固化路逐字按整份那一档走** ——
# 旧读法逐字**没有**这一族（ `scripts/toolchain/` 逐字 25 个条目、
# 含 `pc` 或 `x86` 逐字 **0** 个）
# 两条要满足它的落点逐字是：
#      · `scripts/recipes/build-bundle-bash.sh:50` 逐字
#        `./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu`
#      · `scripts/recipes/package-component.sh:83` 逐字
#        `ZIP="dist/component-${TOOL}-${VER}-${SHA}-android-arm64.zip"`
# 产物名里那一个目标段逐字是 `android-arm64`、 PC 侧那一个逐字**还没有**
#
# **三项**逐字**写成声明**（ `scripts/toolchain/pc-target.json`）
# **三元组那一串**逐字是「那 `--host=` 那一格逐字要填的那一串」
#      （ 工具链那一串、 **不是**「宿主机器是什么」、 后一问是另一问）
# **一项架构**逐字是「那 ELF 头里那一个 `e_machine` 对应的那一档」
#      （ 它逐字**必须**与那格甲是**同一个值**）
# **一档承载形态**逐字是**两族共用的那一个值**
#      （ 格乙逐字是它的回声、 **不是**真源）
# **三个值**逐字**不许**由那产物倒推（ 是循环）
#
# **读数**逐字在**后面**（ **读数**归 02 与 07）：
#    · 那固化路逐字按它走、 产物逐字带那**一个目标段**
#    · 格乙逐字要机械判「那产物名那目标段与那声明**相等**，
# 不等判红并报出**哪一档**不等」
#
#  用法：
#    pc-target.sh triple 它逐字**报**那三元组那一串
#    pc-target.sh arch 它逐字**报**那一项架构
#    pc-target.sh carrier 它逐字**报**那一档承载形态
#    pc-target.sh product-segment 它逐字**报**那产物名里那一个目标段
#    pc-target.sh host-flag 它逐字**报**那 `--host=` 那一格逐字要填的那一串
#    pc-target.sh check <产物名> 它逐字机械核那产物名那目标段与那声明**相等**
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

# **声明**逐字**在**那一份里（ 它逐字**唯一**、 它逐字**不**散在几处）
DECL="$ROOT_DIR/scripts/toolchain/pc-target.json"
[ -f "$DECL" ] || { echo "::error title=没有那 PC 侧那一个目标三元组声明::$DECL 不在"; exit 1; }

# 一格逐字**要是那值**、 它逐字**不**是那一句散文
# 散文那一族逐字**带**那个 `=>` 逐字、 值那一族逐字**不**带
get_field() {
  node -e '
const fs = require("node:fs");
const d = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const v = d[process.argv[2]];
if (typeof v !== "string" || !v.trim()) { process.exit(3); }
if (v.indexOf("=>") >= 0 || v.indexOf("**") >= 0) {
  //  一格逐字**还**是那散文、 **值**逐字**还没**定出来
  console.error("报缺：那 " + process.argv[2] + " 那一格逐字**还**是那散文、"
    + "=> 而那**值**逐字**还没**定出来（=> 而那按 C-05 报缺、=> 而那**不**猜填）");
  process.exit(3);
}
process.stdout.write(v.trim());
' "$DECL" "$1" 2>/dev/null
}

cmd="${1:-}"
case "$cmd" in
  triple|arch|carrier|product-segment|host-flag)
    key="$cmd"
    #  缺陷名（C-102）**那 `carrier` 子命令逐字映射到一个那 JSON 里逐字不存在的键** ——
    # 旧版逐字**没有**这一行
    # `carrier` 逐字落在那 `key="$cmd"` 那一档原样当键名
    # JSON 里逐字那一格叫 `carrierForm`
    # `carrier` 那一格逐字**不存在**
    # **后果**逐字是「**静默失效**」
    #        （ 三个值落进去之后、 `carrier` 子命令逐字仍会报缺、
    # 它的报错文案逐字与「值还没定」那一档逐字完全一样、
    # 读的人逐字分不出「值还没定」与「键名映射错了」这两件
    # 按 **C-05** 与 **C-112**，
    # 每一格逐字都要能答「这是服务端说的，还是我算不出来的」、
    # 这里逐字两者逐字长得一样、 答不出来）
    # **处置**： 逐字**加**一行映射
    # 理由逐字是「那一格逐字已经逐字在判据库 `$pcTripleThreeValues`
    #        与规范正文 `§122.98.1` 那一节里逐字被引了、 改键名逐字要动三处、
    # 加一行逐字只动一处」
    [ "$cmd" = "carrier" ] && key="carrierForm"
    [ "$cmd" = "product-segment" ] && key="productSegment"
    [ "$cmd" = "host-flag" ] && key="triple"
    out="$(get_field "$key")" || {
      echo "::error title=那声明逐字读不到::$DECL 里逐字没有那 $key 那一格**的值**"
      echo "::error::  => 而那按 C-05 报缺、=> 而那**不**猜填、=> 而那**不**写成「测试失败」"
      exit 1
    }
    printf '%s\n' "$out"
    ;;
  check)
    name="${2:-}"
    [ -n "$name" ] || { echo "::error title=没有产物名::用法：pc-target.sh check <产物名>"; exit 2; }
    base="$(basename "$name")"
    # 后缀逐字**要**在**去**那扩展名**之后**判
    #    （ 产物名逐字是 `component-<件>-<版>-<哈希>-<目标段>.zip`、
    # 目标段逐字**不**是那文件名逐字**最后**那一段、 它逐字**后面**还有那 `.zip`）
    base="${base%.zip}"
    decl_seg="$(get_field productSegment)" || {
      echo "::error title=那声明逐字读不到::那 productSegment 那一格逐字**还没**定出来（=> 而那按 C-05 报缺）"
      exit 1
    }
    # 格乙逐字要机械判「那产物名那目标段与那声明**相等**」
    # 目标段逐字是那产物名里那**后缀**（ 它逐字**不**能按那 `-` 逐字分段取）
    #      （ 目标段逐字**自己**就带 `-`（ `x86_64-linux-gnu` 那一档）、
    # 按「倒数第二段」取逐字**取错**、 **哈希**那一段才是那 `-` 分段的最后一段）
    # **正解**： 后缀逐字**从**那声明逐字**剥**（ 它逐字**最**长）
    got_seg=""
    case "$base" in
      *-"$decl_seg")
        got_seg="$decl_seg"
        # **相等**那一档逐字**不**是「那后缀**恰好**是那声明」、
        # 是「那声明**确实**是那产物名的一个后缀、 且那**前面**那一段逐字**是**那哈希」
        _prefix="${base%-"$decl_seg"}"
        _hash="${_prefix##*-}"
        case "$_hash" in
          ''|*[!0-9a-f]*) got_seg="" ;;
        esac
        ;;
    esac
    if [ -z "$got_seg" ]; then
      # 目标段逐字**不**是那声明那一档时逐字**报缺**（ 按 C-05 不猜）
      echo "  [红] 那产物名逐字 = $base、=> 而那声明逐字 = $decl_seg"
      echo "  [红] => 而那目标段逐字**不是**那声明那一档、=> 而那按 C-05 报缺、=> 而那**不**猜"
      exit 1
    fi
    if [ "$got_seg" = "$decl_seg" ]; then
      echo "  [ok] 那产物名那目标段逐字 = $got_seg、=> 而那声明逐字 = $decl_seg（=> 而那**相等**）"
      exit 0
    fi
    # 不等判红并报出**哪一档**不等
    echo "  [红] 那产物名那目标段逐字 = $got_seg、=> 而那声明逐字 = $decl_seg（=> 而那**不等**）"
    echo "  [红] 那一档逐字是「承载形态」（=> 而那格乙逐字是那声明那一档的回声、=> 而**不是**真源）"
    echo "  [红] => 而那按 C-05 报缺、=> 而那**不**猜那该是哪一档"
    exit 1
    ;;
  *)
    echo "用法：pc-target.sh {triple|arch|carrier|product-segment|host-flag|check <产物名>}"
    exit 2
    ;;
esac
