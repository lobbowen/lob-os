#!/usr/bin/env bash
# 取齐 base 筐的那些件 —— **一件一个脚本**，各自产 component-meta.json
# 并落到 usr/lib/<id>/<版本>/，最后建全局软链。
#
# 为什么现在只有「逐件编译」这一条路：
#   此前这里还有一条「固化产物」分支 —— 源指纹对上了就从 gh release 下载
#   一个打包好的 zip，解包取用，不重新编译。
#   那条分支依赖 .github/native-capabilities-pin.json 记录的 zip，
#   而**产出它的 workflow 已经删了**（仓里没有任何 workflow 打包原生件；
#   那些 gh release create 上传的都是 APK）。于是：
#     · 它永远走不到（指纹对不上就回退，对上了 zip 也不存在）
#     · 更坏的是它**看起来**能用 —— 日志先打印「命中固化」，随后必然失败
#   留着就是废弃逻辑。已删，配套的 pin.json 与 fingerprint.sh 一并不再需要。
#
# 用户要求：不要出现废弃文件还保留在工程当中。
#
# 判断依据（都在仓内可核）：
#   · ls .github/workflows/  —— 没有打包原生件的 workflow
#   · 各 workflow 的 gh release create 上传的是 APK（build-apk.yml 里明写 "$APK#$NAME"）
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
ABI="${ABI:-arm64-v8a}"

# 只编指定的几件（默认全量）。件名不带 .sh，可多次给：
#   ensure-native-capabilities.sh --only=zlib --only=openssl --only=curl
# build-git.yml 只要 git 的 DT_NEEDED 那三件；编齐 11 件既慢，
# 又把无关件的失败带进来。
ONLY=""
for a in "$@"; do
  case "$a" in
    --only=*) ONLY="$ONLY ${a#--only=}" ;;
    *) echo "::error title=认不得的参数::$a —— 只支持 --only=<件名>（可多次）"; exit 2 ;;
  esac
done
ONLY="${ONLY# }"

# 逐件编译 —— **一件一个脚本**，各自产 component-meta.json + 落到 usr/lib/<id>/<版本>/。
#
# 此前这里是 exec build-native-capabilities.sh：一个脚本编 11 件，
# 按 .github/native-capabilities.txt 的 self-c/upstream/soft 三档分别处理。
# 那是「能力件」时代的做法（三档是构建期的严重程度：缺件判红还是降级）——
# 现在按件走，与 ldconfig 扫目录一样，一件一件来。
build_each() {
  local failed=0
  if [ -n "$ONLY" ]; then
    local n
    for n in $ONLY; do
      printf "::notice title=逐件::build-piece-%s.sh " "$n"
      if bash "scripts/recipes/build-piece-$n.sh"; then
        echo "ok"
      else
        echo "FAILED"
        echo "[caps] ★ build-piece-$n.sh 失败"
        failed=1
      fi
    done
    [ "$failed" = 0 ] || { echo "::error title=有件编不出来::见上方各脚本的取证输出"; exit 1; }
    echo "[caps] 指定件编译完成：$ONLY"
    return 0
  fi
  for s in \
      build-piece-zlib.sh \
      build-piece-openssl.sh \
      build-piece-curl.sh \
      build-piece-jq.sh \
      build-piece-bash.sh \
      build-piece-rg.sh \
      build-native-busybox.sh
  do
    # crypto 不在此列 —— 它与 libssl.so 一并编出（OpenSSL 的一部分，见
    # component-sources.json 的 crypto.sameAs），由 build-piece-openssl.sh 落位。
    # 逐件发通知 —— 失败时在 GitHub 界面上一眼看到是哪一件，
    # 不用去翻被截断的日志（11 个脚本串在一起，日志会被截）
    printf "::notice title=逐件::%s " "$s"
    if bash "scripts/recipes/$s"; then
      echo "ok"
    else
      echo "FAILED"
      echo "[caps] ★ $s 失败 —— 上面那条通知标了是哪一件"
      failed=1
    fi
  done
  [ "$failed" = 0 ] || { echo "::error title=有件编不出来::见上方各脚本的取证输出"; exit 1; }
  echo "[caps] 逐件编译完成"
}

if [ -n "$ONLY" ]; then
  echo "[caps] 只编指定件：$ONLY"
else
[caps] 先编内核的兼容性垫片（posix · ptyprobe · ptysession · flock）
  这四个是 container/native 下我们自己的 C，随 APK 而来 —— 不是件、
  不铺位、不进清单、不可 OTA 替换。
bash scripts/toolchain/build-kernel-compat.sh || die "垫片编不出来" "内核缺了兼容层，程序跑不出 Linux 语义"

  echo "[caps] 逐件编译（11 个脚本，各产 component-meta.json 并落位）"
fi
build_each