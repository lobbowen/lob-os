#!/usr/bin/env bash

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

DECL="$ROOT_DIR/scripts/toolchain/pc-target.json"
[ -f "$DECL" ] || { echo "::error title=没有那 PC 侧那一个目标三元组声明::$DECL 不在"; exit 1; }

get_field() {
  node -e '
const fs = require("node:fs");
const d = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const v = d[process.argv[2]];
if (typeof v !== "string" || !v.trim()) { process.exit(3); }
if (v.indexOf("=>") >= 0 || v.indexOf("**") >= 0) {
  // 一格逐字**还**是那散文、那**值**逐字**还没**定出来
  console.error("报缺：那 " + process.argv[2] + " 那一格逐字**还**是那散文、"
    + "那**值**逐字**还没**定出来（按 C-05 报缺、那**不**猜填）");
  process.exit(3);
}
process.stdout.write(v.trim());
' "$DECL" "$1" 2>/dev/null
}

cmd="${1:-}"
case "$cmd" in
  triple|arch|carrier|product-segment|host-flag)
    key="$cmd"

    [ "$cmd" = "carrier" ] && key="carrierForm"
    [ "$cmd" = "product-segment" ] && key="productSegment"
    [ "$cmd" = "host-flag" ] && key="triple"
    out="$(get_field "$key")" || {
      echo "::error title=那声明逐字读不到::$DECL 里逐字没有那 $key 那一格**的值**"
      echo "::error::按 C-05 报缺、那**不**猜填、那**不**写成「测试失败」"
      exit 1
    }
    printf '%s\n' "$out"
    ;;
  check)
    name="${2:-}"
    [ -n "$name" ] || { echo "::error title=没有产物名::用法：pc-target.sh check <产物名>"; exit 2; }
    base="$(basename "$name")"

    base="${base%.zip}"
    decl_seg="$(get_field productSegment)" || {
      echo "::error title=那声明逐字读不到::那 productSegment 那一格逐字**还没**定出来（按 C-05 报缺）"
      exit 1
    }

    got_seg=""
    case "$base" in
      *-"$decl_seg")
        got_seg="$decl_seg"

        _prefix="${base%-"$decl_seg"}"
        _hash="${_prefix##*-}"
        case "$_hash" in
          ''|*[!0-9a-f]*) got_seg="" ;;
        esac
        ;;
    esac
    if [ -z "$got_seg" ]; then

      echo "  [红] 那产物名逐字 = $base、声明逐字 = $decl_seg"
      echo "  [红]目标段逐字**不是**那声明那一档、按 C-05 报缺、那**不**猜"
      exit 1
    fi
    if [ "$got_seg" = "$decl_seg" ]; then
      echo "  [ok] 那产物名那目标段逐字 = $got_seg、声明逐字 = $decl_seg（那**相等**）"
      exit 0
    fi

    echo "  [红] 那产物名那目标段逐字 = $got_seg、声明逐字 = $decl_seg（那**不等**）"
    echo "  [红] 那一档逐字是「承载形态」（格乙逐字是那声明那一档的回声、**不是**真源）"
    echo "  [红]按 C-05 报缺、那**不**猜那该是哪一档"
    exit 1
    ;;
  *)
    echo "用法：pc-target.sh {triple|arch|carrier|product-segment|host-flag|check <产物名>}"
    exit 2
    ;;
esac
