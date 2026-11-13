#!/usr/bin/env bash
# 打标准件归档：一件一个包，装配器只读包。
# 归档格式 zip，扩展名跟内容走（名字叫 .tar.gz 而内容是 zip 会让看名字判版本的人被骗）。
# 形态照抄 .deb（包里自带全部信息，装的时候不查外部清单）：component-<id>-<版本>-<sha12>.zip，内含
#   component-meta.json（id / version / entry / form / requires）、piece-shape.json（构建期算的形态与逐项摘要）、
#   bin/<名>（entry=bin/… 时）、lib/<名>.so（entry=lib/… 时）。
# 包内结构与包名规则见 lob-os-team/02-windows-architect/PACKAGING-SPEC.md；本文件不复述规范（复述即漂移）。
set -euo pipefail

ID="${1:?需要件名}"
OUT="${2:?需要 OUT_DIR}"
VER="${3:?需要版本}"
ENTRY="${4:-}"
# 第 5 个参数：这个件要在 usr/bin 出现的命令名，空格分隔。
# 由调用方从自己既有的变量派生后传入（如 busybox 从 $APPLETS 转小写）——
# 不在本脚本里另写一份名单，否则两份名单会各自漂移。
BINS="${5:-}"
# 第 6 个参数是「这一件自己声明的标记文件」（01 裁的候选 (a)）：声明在那一件自己那边，打包那一支去问它，
# 而不是自己知道八个名字。它是那一件产出之后、进包之前落在产物目录里的一个文件，当「走到哪一步了」的痕迹。
# 今天据实说核过：七件里只有 busybox 有（<id>.version，产在 build-native-busybox.sh:331），其余六件零个。
# 没声明就不查，不能当「缺」（C-05：缺字段报缺，指的是缺的那个字段）。
MARKER="${6:-}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$ROOT"

META_SRC="$ROOT/scripts/component-sources.json"
[ -f "$META_SRC" ] || { echo "::error title=缺钉值表::$META_SRC 不在"; exit 1; }

# 形态判据（ELF 头：e_type + PT_INTERP）—— 判「有没有程序那一侧」要用它。
source "$ROOT/scripts/toolchain/elf-form.sh"

# 件的作者声明的入口名，取自 component-sources.json 的 sources.<件>.entryName。
# [!] 缺陷名 C-102：「按形态挑」挑的是「第一个程序形态的项」，不是「这一件的入口」。
#   「这一件有多个入口时该取哪一个」的答案本来就存在（就是这一格），它与「多调用那一族」是两问（按 C-135）。
#   这一格与 bundle-env.sh 的 emit_piece 那一支是同一句，两处必须同源（不然一处改了另一处盖掉它）。
author_entry_name_of() {
  node -e '
    const s = require(process.argv[1]).sources || {};
    const v = (s[process.argv[2]] || {}).entryName;
    if (v) process.stdout.write(String(v));
  ' "$ROOT/scripts/component-sources.json" "$1"
}

# 先判「这一件在磁盘上有没有程序那一侧」——判据是形态（ELF 头），不是目录名、文件名或「bin/ 下有没有东西」。
#  缺陷名 C-102：旧版（entry 为空时）bin/ 有东西就取 bin/<第一个>、否则取 lib/<第一个 .so>，纯库件（crypto/curl/zlib）
#  因此被指了一份库当入口、运行期调不起（07 复算表：role=library 且 bin/ 目录不存在）；openssl 更隐蔽——
#  bin/ 下有 bin/openssl（程序形态），entry 却指向 lib/libssl.so（库形态），被当成库起、崩。
#  现在：有程序那一侧就指向那一侧（按形态挑，不按名字顺序），没有就 entry 留空（C-05：「没有」就是没有）。
#  entry 总是按形态算、调用方那份降级为「声明」（旧版只有 entry 为空才算，openssl 显式传库形态就绕过了）；不一致时说出来。
DECLARED_ENTRY="$ENTRY"
PROG_ENTRY=""
# [!] 缺陷名 C-102：这一格挑的是「第一个程序形态的项」，那不是「这一件的入口」。
#  bin/ 下多个程序形态项时（openssl 有 bin/c_rehash 与 bin/openssl；c_rehash 是 #! 脚本，按 01 裁的 S117 也是入口形态、
#  按 LC_ALL=C 排序在前），一支挑到的是排序靠前那个，不一定是作者声明那个。实测：build-openssl run 40164564474
#  （sha=84df912）印「（这一件有程序那一侧：entry=bin/c_rehash）」，而声明的是 bin/openssl（sources.openssl.entryName）。
#  bundle-env.sh 的 emit_piece 已改成「先取作者声明的那一个」，这一支也要改，不然它盖掉前面那一支。
#  处置：先取作者声明的那一个；它不在 bin/ 下或不是入口形态时，才退回「按形态挑第一个」。
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

# 只取 entry 指的那个文件，外加与它同名的版本链，不整个目录搬。
# openssl 的构建产物目录里还有 libcrypto.a、cmake/、engines-3/、ossl-modules/，不是运行时所需
# （openssl 的包实测 7.6MB，而它只该有 libssl.so 与 libcrypto.so 两个文件），整个搬等于把构建残留装进系统。
# 版本链指 soname 一族（libz.so -> libz.so.1 -> libz.so.1.3.2）：同一份字节的三个名字，linker 按 DT_NEEDED 找带版本那层。
if [ -n "$ENTRY" ]; then
  mkdir -p "$STAGE/$(dirname "$ENTRY")"
  cp -Pf "$OUT/$ENTRY" "$STAGE/$ENTRY" 2>/dev/null || cp -f "$OUT/$ENTRY" "$STAGE/$ENTRY"
  eb="$(basename "$ENTRY")"
  ed="$(dirname "$ENTRY")"
  #  混合件（既有程序那一侧又有库那几份）：lib/ 下的库也要进包。
  #   缺陷名 C-102：旧版把库丢掉——entry 改成程序那一份之后只搬它一个，bin/openssl NEEDED libssl.so + libcrypto.so
  #   就找不到依赖。现在 lib/ 下有字节就全搬（库字节落在库目录）。
  if [ -d "$OUT/lib" ]; then
    mkdir -p "$STAGE/lib"
    for f in $(ls -A "$OUT/lib" 2>/dev/null | LC_ALL=C sort); do
      case "$f" in *.so|*.so.*) cp -Pf "$OUT/lib/$f" "$STAGE/lib/$f" 2>/dev/null || cp -f "$OUT/lib/$f" "$STAGE/lib/$f" ;; esac
    done
    echo "  （混合件：lib/ 下搬了 $(ls -A "$STAGE/lib" 2>/dev/null | wc -l) 个名字）"
  fi
  #  bin/ 下搬的是「这一件声明的那几项」= entry + BINS + entry 那条软链的目标。
  #  [!] 缺陷名 C-102：旧版搬「bin/ 下有什么」而不是「这一件承诺提供什么」。上游 make install_sw 会把上游自己的程序装进来：
  #    openssl 是 bin/c_rehash（#!/usr/bin/env perl），而 perl 在这台宿主上不是一件（02 核过：28 个件键、设备 files/usr/bin
  #    那 24 条、files/opt 那 8 个目录都无 perl）；07 真跑：退出码 126，stderr「…/c_rehash: No such file or directory」。02 裁：不带（C-126 已过）。
  #  该搬三样：entry 那一项（可能不在 bin/ 下，openssl 是 lib/libssl.so）、BINS 那一列、bin/ 下那几项软链的指向目标
  #    （busybox 是 bin/libbusybox.so，18 条软链的目标、不在 BINS 里，不搬就全是断链）；其余不搬，两族不顶替。
  if [ -d "$OUT/bin" ]; then
    mkdir -p "$STAGE/bin"
    #  一，BINS 那一列（这个件要在 usr/bin 出现的命令名）。
    for bn in $BINS; do
      if [ -e "$OUT/bin/$bn" ]; then
        cp -Pf "$OUT/bin/$bn" "$STAGE/bin/$bn" || {
          echo "::error title=$ID 命令入口搬运失败::bin/$bn 搬不进暂存目录（它是不是断链？先查构建侧那一件）"
          exit 1
        }
      fi
    done
    # 二，bin/ 下那几项软链的指向目标（busybox 是 bin/libbusybox.so），必须搬，
    # 不然那几条软链在 $STAGE 里全是断链（07 核到：清单生成器读那些软链读不出实体、判「没有一个是程序形态」）。
    for f in $(ls -A "$OUT/bin" 2>/dev/null | LC_ALL=C sort); do
      p="$OUT/bin/$f"
      [ -L "$p" ] || continue
      real="$(readlink -f "$p" 2>/dev/null || true)"
      [ -n "$real" ] && [ -f "$real" ] || continue
      #  目标相对 $OUT 的路径（软链是 ./libbusybox.so）。
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
    #  缺陷名 C-102：旧版没建 link-farm 那一族软链。link-farm.txt 是构建侧写的清单（格式「落位相对路径<TAB>指向目标」），
    #  它在产物里，而打包这一族没读它。后果：git 按 exec-path 找 remote-https（不带 git- 前缀）找不到，
    #  退成那一个 15 字节的文字桩。
    #  处置：link-farm 逐条在暂存目录上建软链（它是落位事实），不是「DT_RUNPATH 要改」也不是「加载器坏了」。
    if [ -f "$OUT/link-farm.txt" ]; then
      N_FARM=0
      while read -r FLINE; do
        FPATH="${FLINE%%$'\t'*}
        FTGT="${FLINE#*$'\t'}
        [ -n "$FPATH" ] || continue
        [ -n "$FTGT" ] || continue
        mkdir -p "$STAGE/$(dirname "$FPATH")"
        # 目标要先在（它指向暂存目录里的那一份）。
        if [ ! -e "$STAGE/$FPATH" ]; then
          ln -sf "$FTGT" "$STAGE/$FPATH" 2>/dev/null || true
        fi
        N_FARM=$((N_FARM+1))
      done < "$OUT/link-farm.txt"
      echo "  （link-farm：建了 $N_FARM 条软链）"
      # link-farm.txt 要跟着件走（落位侧读它才建得出软链）。
      if [ -f "$OUT/link-farm.txt" ]; then
        cp -f "$OUT/link-farm.txt" "$STAGE/link-farm.txt" 2>/dev/null || true
      fi
    fi
    #  缺陷名 C-102：件实际要的那几层没有搬。bin/ 与 lib/ 是通用两层，而别的层（git 要 libexec/、cmake 要 share/）
    #  是一件自己的事，旧版只搬那两层。后果：git 按 exec-path 找不到 remote-https、cmake 是空壳（模块目录不在旁边）。
    #  处置：件根底下除 bin/ 与 lib/ 之外的每一层都搬（落位事实就是这样），不是「DT_RUNPATH 要改」也不是「加载器坏了」。
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
  #  纯库件（没有程序那一侧）entry 留空，把 lib/ 下的字节全搬进包：库字节落在库目录，这就是「库装库目录」在产物侧的形态。
  #  它没有入口，运行期不会被当程序调起。主名（libfoo.so）要带：主名是「包里哪一份是这一件那一份」的标记，
  #  而 linker 按 DT_NEEDED 找的是带版本那层（libfoo.so.1）。
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
# OPENSSL_LIBS=1 时把同目录下所有 .so 都带上（openssl 一次编出 libssl.so 与 libcrypto.so，
# 而 crypto 是清单里的独立一件，component-sources.json 里 crypto.sameAs=openssl，由 build-crypto.yml 拆出去）。
# 只带 entry 指的那个，拆包时就无货可拆。
if [ "${OPENSSL_LIBS:-0}" = "1" ]; then
  # 所以两份都要在包里，但只有本件的 entry 那一份带主名（libfoo.so）：主名是「包里哪个是入口」的标记
  # （gen-component-meta.js 的 computeEntry 靠 lib/ 下唯一的主名 .so 算 entry）。
  # 别的件那份只带版本层（libcrypto.so.3），拆包时由 build-crypto.yml 重新摆成它自己的主名。
  for extra in "$OUT/$ed/"*.so "$OUT/$ed/"*.so.*; do
    [ -f "$extra" ] || continue
    xb="$(basename "$extra")"
    case "$xb" in
      *.so)
        #  主名只有一个——这条规则管的是「消费者找得到它」，不是「库里带几份」。
        #  库是三段链 libfoo.so -> libfoo.so.1 -> libfoo.so.1.12，只有中间那个是 SONAME；主名是给消费者找的（Lead 定的路甲）。
        #  本件 entry 同族那份带主名（libssl.so，computeEntry 靠 lib/ 下唯一主名算 entry）；别的件那份（libcrypto.so）带版本层，
        #  不带主名，拆包时由 build-crypto.yml 摆成自己的主名。上一版把 libcrypto.so 整个滤掉，包里连 libcrypto.so.3 都没有，
        #  build-crypto.yml 拆不出那份字节，crypto 产不出包。
        if [ "$xb" != "$eb" ]; then
          # 别的件那份：只带版本层（libcrypto.so.3），不带主名。
          #   主名那条软链是 elf_ensure_chain（构建侧，elf-form.sh）建的开发名（libcrypto.so -> libcrypto.so.3），不搬：
          #   搬了 lib/ 下就有两个主名，computeEntry 判「落位结果不唯一」、包装不出来。版本层那份才是要拆出去的字节。
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
    #  落到这里的只有两种：本件 entry 同族那份（主名）与版本层那份（libcrypto.so.3）。
    #  别的件的主名软链（libcrypto.so，elf_ensure_chain 建的开发名）也在这个循环里，上面 *.so 分支已 continue 掉；
    #  这里再排一次：凡不是本件 entry 同族的、又以 .so 结尾的，一律不搬。
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

# 校验该件声明的命令入口（搬由上面 :121 那一支做了，它把 bin/ 下那批全搬）。
#  这一支今天只校验、不搬（2026-10-20）：原来它负责搬，而一个件可能有多个命令入口（busybox 有 18 个 applet 软链），
#  上面那段只搬 entry 与其同名版本链，bin/ 下其余可执行件若无此步就全丢失、而包仍「合法」
#  （2026-10-16 实测：构建日志说「18 个 applet 软链进包」，unzip 出来 0 个）。
#  校验必须留：声明了而盘上没有直接判红（那意味着「声明了但没产出」）。01 裁的 S34：BB_BINS 若取 .rodata 那张表（19 个）
#  会含 awk，而 awk 在 $OUT/bin 下不存在、这一格会判红——正是「取表会判红」的可机械核形态。
N_BINS=0
for bn in $BINS; do
  if [ -e "$OUT/bin/$bn" ]; then
    mkdir -p "$STAGE/bin"
    # C-102：旧版用 `|| cp -f` 兜底把软链解引用成实体（cp -f 跟随符号链接），于是「这一条命令入口」从软链变成独立字节副本。
    # 真机读数（07，base-busybox 那一版）：files/opt/busybox/1.36.1/bin/cat 与 lib/libbusybox.so 的 sha256 相同
    # （87e32d67…b51e0），同一份字节出现在 18 个名字下。构建侧写的是 ln -sf（build-native-busybox.sh:260），
    # 01 按 man1 INSTALL 段裁定「make install 连带把全部软链建好」，那些是官方产物、不该在打包时被展平。
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

#  形态：构建期算一次，写进产物。缺陷名 C-102：产物不携带形态，运行期只能靠文件名/目录名猜——
#  原来包里只有 component-meta.json（id/version/entry/requires），没有一处写着「这份字节是库还是程序」，
#  而今天定的规则是「注册表只记库在哪里与程序在哪里，运行期只读，不判断、不探测」，所以必须由产物自己带着答案。
# 形态写进 piece-shape.json（与 component-meta.json 并列，包根）：
#  form 逐文件 ∈ {exec, dyn-interp, dyn-plain, unknown}；role 逐文件 ∈ {program, library, unknown}；
#  kind 整件 ∈ {program, library, mixed, unknown}。entry 由落位结果算出（gen-component-meta.js 算，不是人填）。

# 顺带把两件事定死（都在构建期）：程序形态的字节带 owner 执行位（0755）、库形态不带（0644）；
#  库形态补齐 ldconfig(8) 的三段链 libfoo.so -> libfoo.so.1 -> libfoo.so.1.12。
# 格三（是不是我们要的那一代）：buildSha/buildRun = 哪一次流水线造的（跨构建会变）；sourceSha256 = 源码是哪一份
#  （上游 tarball 摘要，跨构建不变）。拿后者答「是不是这一次构建的」必然答「是」，所以必须另写两格；
#  这两个变量本地不存在时，按 C-05 填可识别占位（先例：publish-component-manifest.js:138,143）而不填空值。
BUILD_SHA="${GITHUB_SHA:-local}"
BUILD_RUN="${GITHUB_RUN_ID:-local}"
node "$ROOT/scripts/recipes/gen-component-meta.js" \
  "$ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
  --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN" \
  || { echo "::error title=$ID 说明生成失败"; exit 1; }


# ---- $ORIGIN（归产物那一侧；01 裁的）----
# 在这一支加而不在安装侧：$ORIGIN 只有在产物里才成立，落位侧改它 = 改那份字节、要重编；RUNPATH 归安装侧就是
#  「在安装期改产物属性」（C-16）。走事后 objcopy 而不走链期 -Wl：configure 那侧今天零 LDFLAGS（curl 与 openssl
#  各 grep 一次都零命中），而八件的链接由第三方 configure 驱动（build-bundle-curl.sh:41 ./configure，:53 只传 CC/AR/RANLIB）；
#  llvm-objcopy 仓里已有（build-native-busybox.sh:219-220），只是还没用来写 RUNPATH。
#  「改了之后那份 .so 的 RUNPATH 生效」今天未过（要一次运行），与上句不可互相顶替（C-145）。

# 写 RUNPATH 而不写 RPATH：curl 那份 libcurl.so 的 NEEDED 五条全是直接依赖（libssl.so / libcrypto.so / libz.so.1 /
#  libdl.so / libc.so，readelf -d 实读）；ld.so(8):77-82：DT_RUNPATH "only to find those objects required by
#  DT_NEEDED entries and do not apply to those objects' children" 直接依赖这一问 RUNPATH 就够。
#  这一句不能跨宿主：换到 bionic 上证据不成立（04 那条判别），「在 bionic 上实际生效」今天未过；
#  [转述·未核原文] bionic 上 DT_RPATH 已知会白编（build-node-android.sh:363 的诊断文案这么说，未找到原文）。
# 今天只写「同一目录」那一侧（库落 lib/ 用裸 $ORIGIN、程序落 bin/）；跨目录的 $ORIGIN/../lib 那一格今天未过。

# 「写 DT_RUNPATH」那一格整段撤掉，已定的结论：
#   CI 读数（run 39509725200，head=1946162）：--help 输出 11864 字节（非零，证明输出取到了），其中零个含 rpath 的行
#   个工具没有这个功能（不是「未过」），NDK 里那个 llvm-objcopy 根本不支持写 rpath。
#   唯一先例是链期参数：build-node-android.sh:299 的 LDFLAGS_TARGET_OVERRIDE（--enable-new-dtags + -rpath '$ORIGIN'），
#   另两处也写明「只有 DT_RPATH 不行」：verify-runtime-elf.sh:321 与 check-elf-deps.sh:156。
#   「能不能在链期拿到 DT_RUNPATH」是逐件判据，八件今天都已核出来通了（busybox 静态无 .so 不适用），撤掉后七件不再判红。

# 算出来的 entry 与调用方声明的那一份不一致，两处形态说法分叉了。不判红（那样会把两个真相源永远锁死），
# 但要说出来——哪个是真相由 01/04 裁。
META_ENTRY="$(node -e 'let d="";process.stdin.on("data",cd+=c).on("end",(){try{process.stdout.write(String(JSON.parse(d).entry||""))}catch(e){}})' < "$STAGE/component-meta.json")"
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
# 这一格：打包之前把 dist/ 里不属于这一件的东西清掉。缺陷名 C-102：dist/ 共用、跨件累积——
#   · CI 上同一 workspace 复用，三个 workflow（zlib/jq/rg）都没清 dist/（阳性对照：checkout 命中、rm -rf 零命中）；
#   · dist/ 会留着上一批的目录（07 读数：jq / ripgrep / zlib 三件的 dist/ 那节相同，sha 8a9242fca51699b1，
#     而三件形态各不相同，形态不解释它、它是残留），那个 dist/bin 是空目录（`total 12`）。
#   而「谁建了它」在 CI workspace 状态里、不在仓里（今天未核）。先问「它今天会不会影响判据」——今天不影响。
#   处置：打包前把 dist/ 清干净，只留这一件要产出的那几个名字。
rm -rf "$DIST"/* 2>/dev/null || true

# 这一格：这一件声明了标记文件，就核它在不在（01 裁的候选 (a)）。缺陷名 C-102：「走到哪一步了」今天没有判据——
#   真机读数（07，2026-10-21）：busybox 的 dist/ = 空 bin/ + busybox.version（6 字节）；zlib / jq / ripgrep 只有空 bin/
#   （那三节 sha 相同 8a9242fca51699b1）。「dist/ 里有没有那件该有的文件」是「哪一段 exit 了」的可跑形态，
#   比读日志可靠（07 核到那七份日志里 objcopy/rpath 零命中）；该有的那个文件由那一件自己声明、没声明就不查。
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

# C-102：zip 不带 -y 会把软链按目标内容写进包（暂存目录里即使还是软链，zip 也跟随它写目标字节），
# 于是「命令名」变成一份独立的字节副本。-y = 按符号链接存（存目标路径本身，不存内容）。
# 那些是 make install 官方建的同目录软链（build-native-busybox.sh:239-241），是派生物、落位时重建，
# 不该在分发包里变成 N 份字节。
( cd "$STAGE" && zip -q -r -X -y "$TMP_PKG" . ) || { echo "::error title=$ID 打包失败"; exit 1; }

SHA="$(sha256sum "$TMP_PKG" | cut -c1-12)"
NAME="component-${ID}-${VER}-${SHA}-android-arm64.zip"
mv -f "$TMP_PKG" "$DIST/$NAME"
sha256sum "$DIST/$NAME" | cut -d' ' -f1 > "$DIST/$NAME.sha256"

echo "[ok] $DIST/$NAME"
echo "[ok] entry=$ENTRY  文件=$(find "$STAGE" -type f | wc -l)"
