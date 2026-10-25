#!/usr/bin/env bash
# 打标准件归档 —— 一件一个包，装配器只读包。
#
# 归档格式：zip。扩展名跟内容走 —— 名字叫 .tar.gz 而内容是 zip
# 会让看名字判断版本的人被骗；所以名字必须与内容一致。
#
# 形态（照抄 .deb：包里自带全部信息，装的时候不查外部清单）：
#   component-<id>-<版本>-<sha12>.zip
#     ├ component-meta.json    自描述：id / version / entry / form / requires
#     ├ piece-shape.json      形态说明（构建期算的形态与逐项摘要）
#     ├ bin/<名>               命令（entry=bin/… 时）
#     └ lib/<名>.so            库（entry=lib/… 时）
#
# 包内结构与包名规则见规范（声明处）：
#   lob-os-team/02-windows-architect/PACKAGING-SPEC.md
# 本文件不再复述规范 —— 复述即漂移，规范才是权威。
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
#  而第 6 个参数是「这一件自己声明的标记文件」（  01 今天裁的候选 (a)：
#    **声明在那一件自己那边**， 而打包那一支**去问那一件**，
#    **而不是自己知道八个名字**）
#    而它是什么： 那一件在产出之后、在进包之前，  落在「产物的目录」里的一个文件；
#      拿它当「这一件走到哪一步了」的痕迹。
#    而今天我据实说核过：**七件里只有 busybox 有**（<id>.version，
#    产在 build-native-busybox.sh:331）， 而其余六件**零** —— 
#    ** 而「没声明」就**不查**， 而不能当「缺」**（ C-05：  缺字段报缺， 而那是指缺的那个字段）
MARKER="${6:-}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$ROOT"

META_SRC="$ROOT/scripts/component-sources.json"
[ -f "$META_SRC" ] || { echo "::error title=缺钉值表::$META_SRC 不在"; exit 1; }

# 形态判据（ELF 头：e_type + PT_INTERP）—— 判「有没有程序那一侧」要用它。
source "$ROOT/scripts/toolchain/elf-form.sh"

# 先判「这一件在磁盘上有没有程序那一侧」—— 判据是**形态**（ELF 头），
# 不是目录名、不是文件名、也不是「bin/ 下有没有东西」。
#  缺陷名（C-102）库件也拿到一个 entry —— 原来这一支（entry 为空时）是：
#   bin/ 下有东西  取 bin/<第一个>；否则  取 lib/<第一个 .so>。
#    纯库件（crypto/curl/zlib）因此被指了一份**库**当入口 
#     运行期按那份 entry 去调起  那五件起不来（07 今天的复算表：
#     crypto/curl/zlib 复算 role=library 且 bin/ 目录不存在）。
#   而 openssl 更隐蔽：它 bin/ 下**有** bin/openssl（程序形态），
#   而 entry 却指向 lib/libssl.so（库形态） 被当成库起，崩。
#  现在：有程序那一侧  entry 指向**那一侧**（按形态挑，不按名字顺序）；
#   没有  **entry 留空**（按 C-05「没有」就是没有，不是「随便挑一个 .so」）。
#   而「库装库目录」说的是**库字节落在库目录**，那件事由运行期 dirForRole 保证 ——
#   与「库件没有 entry」（入口）不冲突。
#  **entry 总是按形态算** —— 调用方传的那一份降级为「声明」。
#   缺陷名（C-102）调用方声明的 entry 被当真相：
#     原来这一支是「if [ -z "$ENTRY" ] 才按形态算」—— 而 openssl 那一件显式传了
#     lib/libssl.so（库形态） 按形态算那一支根本不进  entry 指了库那一份 
#     运行期去 exec 一个库  崩（07 今天的复算表：openssl bin/ 下**有** bin/openssl）。
#     而更早一版（只搬 entry 那一份） bin/openssl 根本进不了包。
#    现在 entry 由形态决定：这一件在磁盘上有没有程序那一侧？
#       有  指向那一侧（按形态挑，不按名字顺序）；
#       没有  **留空**（按 C-05「没有」就是没有，不是「随便挑一个 .so」）。
#     而调用方声明的那一份若与算出来的不一致  **说出来**（不采纳声明）。
DECLARED_ENTRY="$ENTRY"
PROG_ENTRY=""
if [ -d "$OUT/bin" ]; then
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

# 只取 entry 指的那个文件，外加与它同名的版本链 —— **不整个目录搬**。
#
# openssl 的构建产物目录里还有 libcrypto.a、cmake/、engines-3/、ossl-modules/
# 这些不是这个件的运行时所需（openssl 的包实测 7.6MB，而它只该有
# libssl.so 与 libcrypto.so 两个文件）。整个搬进来等于把构建残留也装进系统。
#
# 版本链指共享库的 soname 一族（libz.so → libz.so.1 → libz.so.1.3.2）：
# 它们是同一份字节的三个名字，而 linker 按 DT_NEEDED 找的是带版本的那一层。
if [ -n "$ENTRY" ]; then
  mkdir -p "$STAGE/$(dirname "$ENTRY")"
  cp -Pf "$OUT/$ENTRY" "$STAGE/$ENTRY" 2>/dev/null || cp -f "$OUT/$ENTRY" "$STAGE/$ENTRY"
  eb="$(basename "$ENTRY")"
  ed="$(dirname "$ENTRY")"
  #  **混合件（这一件既有程序那一侧又有库那几份） lib/ 下的库也要进包**。
  #   缺陷名（C-102）库那几份被丢掉：entry 改成程序那一份之后，上面那一行只搬它一个，
  #   而 lib/ 下的 libssl.so / libcrypto.so 不在任何一支里  包里没有库 
  #   而 bin/openssl NEEDED libssl.so + libcrypto.so  那一件起来就找不到依赖。
  #    现在：这一件 lib/ 下有字节  那些字节**全搬**（库字节落在库目录）。
  if [ -d "$OUT/lib" ]; then
    mkdir -p "$STAGE/lib"
    for f in $(ls -A "$OUT/lib" 2>/dev/null | LC_ALL=C sort); do
      case "$f" in *.so|*.so.*) cp -Pf "$OUT/lib/$f" "$STAGE/lib/$f" 2>/dev/null || cp -f "$OUT/lib/$f" "$STAGE/lib/$f" ;; esac
    done
    echo "  （混合件：lib/ 下搬了 $(ls -A "$STAGE/lib" 2>/dev/null | wc -l) 个名字）"
  fi
  #  **bin/ 下搬的是「这一件**声明**的那几项」**（=> 而那 = entry + BINS + entry 那一条自己的软链目标）。
  #
  #  ★ 缺陷名（C-102）**这一格搬的是「bin/ 下有什么」而不是「这一件承诺提供什么」**
  #   => 而而上游 `make install_sw` 会把**上游自己那些程序**一并装进来
  #      （=> 而 openssl 那一件逐字是 bin/c_rehash，=> 而它第一行逐字是 #!/usr/bin/env perl）。
  #   => 而而 perl 在这台宿主上**不是一件**（=> 而 02 今天核过：仓里 28 个件键无 perl、
  #      => 而设备上 files/usr/bin 那 24 条无 perl、=> 而设备上 files/opt 那 8 个目录无 perl）；
  #   => 而而 07 今天在设备侧真跑了一次：退出码 126、=> 而 stderr 逐字是
  #      「…/files/usr/bin/c_rehash: No such file or directory」
  #      （=> 而那 126 就是「解释器找不到」，=> 而而 07 加的阳性对照逐字证了
  #      「同样的调用方式与同样的 shebang 机制走得通、=> 而失败的是解释器找不到」）。
  #   => 而 02 第十一轮裁了：那一格**不该带**（=> 而按 C-126 已过）。
  #
  #   => 而而这一格**该**搬的是「这一件对外承诺要提供的东西」：
  #      一，entry 那一项（=> 而它可能**不在** bin/ 下，=> 而比如 openssl 那一件逐字是 lib/libssl.so）；
  #      二，BINS 那一列（=> 而那是作者声明的**命令名**清单）；
  #      三，而 bin/ 下**那几项软链的指向目标**（=> 而 busybox 那一件逐字是 bin/libbusybox.so，
  #         => 而它是那 18 条软链的指向目标、=> 而而它**不在** BINS 那一列里、
  #         => 而而它不搬那 18 条软链就**全**是断链）。
  #
  #   => 而而**不**搬「bin/ 下其余那些」（=> 而比如 c_rehash 那一项）。
  #      （=> 而那正是 02 裁的那一格：=> 而而那个字节所依赖的解释器在这台宿主上**不是一件**。）
  #
  #   => 而这一格**不是**「收全了之后该不该收」（=> 而那是 0a00489 那一族），
  #      => 而它是「**声明**的那几项搬不搬」（=> 而那两族**不顶替**）。
  if [ -d "$OUT/bin" ]; then
    mkdir -p "$STAGE/bin"
    #  一，BINS 那一列（=> 而那逐字是「这个件要在 usr/bin 出现的命令名」）
    for bn in $BINS; do
      if [ -e "$OUT/bin/$bn" ]; then
        cp -Pf "$OUT/bin/$bn" "$STAGE/bin/$bn" || {
          echo "::error title=$ID 命令入口搬运失败::bin/$bn 搬不进暂存目录（它是不是断链？先查构建侧那一件）"
          exit 1
        }
      fi
    done
    #  二，bin/ 下**那几项软链的指向目标**（=> 而 busybox 那一件逐字是 bin/libbusybox.so）
    #    => 而那**必须**搬，=> 而不然那几条软链在 $STAGE 里**全是断链**
    #    （=> 而而 07 核到过：清单生成器读那些软链读不出实体、=> 而它判「没有一个是程序形态」）。
    for f in $(ls -A "$OUT/bin" 2>/dev/null | LC_ALL=C sort); do
      p="$OUT/bin/$f"
      [ -L "$p" ] || continue
      real="$(readlink -f "$p" 2>/dev/null || true)"
      [ -n "$real" ] && [ -f "$real" ] || continue
      #  而那目标**相对** $OUT 的路径（=> 而软链逐字是 ./libbusybox.so）
      rel="${real#$OUT/}"
      [ "$rel" != "$real" ] || continue
      if [ ! -e "$STAGE/$rel" ]; then
        mkdir -p "$(dirname "$STAGE/$rel")"
        cp -Pf "$OUT/$rel" "$STAGE/$rel" || {
          echo "::error title=$ID 软链目标搬运失败::bin/$f 指向 $rel，=> 而那一项搬不进暂存目录"
          exit 1
        }
      fi
    done
    echo "  （bin/ 下搬了 $(ls -A "$STAGE/bin" 2>/dev/null | wc -l) 个名字；=> 而那是 BINS 那一列 + 那几条软链的指向目标）"
  fi
else
  #  纯库件（这一件没有程序那一侧） entry 留空  **把 lib/ 下的字节全搬进包**。
  #   库字节落在库目录（lib/）—— 这就是「库装库目录」在产物这一侧的形态。
  #   而它没有入口 —— 运行期不会被当程序调起（那正是它该有的状态）。
  #    而它的主名（libfoo.so）**要带**：主名是「这个包里哪一份是这一件的那一份」的标记，
  #   而 linker 按 DT_NEEDED 找的是带版本的那一层（libfoo.so.1）。
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
        #  **主名只有一个** —— 但这条规则管的是「消费者找得到它」，不是「库里带几份」。
        #   库是三段链 libfoo.so -> libfoo.so.1 -> libfoo.so.1.12，而只有中间那个是 SONAME；
        #   主名是给消费者找的，不是给库自己带的（Lead 今天定的路甲）。
        #    本件 entry 同族的那份带主名（libssl.so，computeEntry 靠 lib/ 下唯一的主名算 entry）；
        #    别的件的那份（libcrypto.so）**带它的版本层**（libcrypto.so.3），
        #   而**不带它的主名** —— 拆包时由 build-crypto.yml 自己摆成它自己的主名。
        #    上一版这一行把 libcrypto.so 整个滤掉了  包里连 libcrypto.so.3 都没有
        #    build-crypto.yml 拆不出那份字节  crypto 那一件产不出包。
        if [ "$xb" != "$eb" ]; then
          # 别的件的那份：**只带它的版本层**（libcrypto.so.3），不带主名。
          #    而主名那条软链是 elf_ensure_chain（构建侧，elf-form.sh）建的开发名
          #     （libcrypto.so -> libcrypto.so.3）—— 那一条**不搬**：
          #     搬了它，lib/ 下就有两个主名（libssl.so 与 libcrypto.so）
          #     computeEntry 判「落位结果不唯一」 包装不出来。
          #    而版本层那一份是实体（libcrypto.so.3），它才是要从这个包里拆出去的那份字节。
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
    #  落到这里的只有两种： 本件 entry 同族的那份（主名） 版本层那一份（libcrypto.so.3）
    #   而**别的件的主名软链**（libcrypto.so -> libcrypto.so.3，elf_ensure_chain 建的开发名）
    #   也在这个循环里（它以 .so 结尾） 上面那个 *.so 分支已把它 continue 掉了。
    #    这里再排一次：凡不是本件 entry 同族的、又以 .so 结尾的（主名），一律不搬。
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

# 校验该件声明的命令入口（**搬**由上面 :121 那一支做了 —— 它把 bin/ 下那批全搬）。
#
#  这一支今天**只校验，不搬**（2026-10-20）：
#   原来它负责搬（entry 只能指一个文件，而一个件可能有多个命令入口 —— busybox 有 18 个 applet 软链；
#   而上面那段只搬 entry 与其同名版本链  bin/ 下其余的可执行件若无此步就会**全部丢失**，
#   而包仍然是「合法」的 —— 2026-10-16 实测：构建日志说「18 个 applet 软链进包」，而 unzip 出来 0 个）。
#    而今天上面 :121 那一支（bin/ 下那批全搬）已经把它们都搬了  这一支的「搬」是重复的。
#    而它的**校验**那一半必须留：**声明了而盘上没有  直接判红**，
#     因为那意味着「声明了但没产出」，而它正是本轮那类静默失效。
#      而这一格今天尤其要紧（01 今天裁的 S34）：BB_BINS 若取 .rodata 那张表（19 个），
#       它会含 **awk**，而 awk 在 $OUT/bin 下不存在（这一件按白名单关掉了它）
#        **这一格会判红** —— 而那正是「取表会判红」这个结论的可机械核形态。
N_BINS=0
for bn in $BINS; do
  if [ -e "$OUT/bin/$bn" ]; then
    mkdir -p "$STAGE/bin"
    # C-102 **`|| cp -f` 兜底把软链解引用成实体** —— 这一行原来在 cp -Pf 失败时退回 cp -f，
    # 而 cp -f 会跟随符号链接，于是「这一条命令入口」从软链变成一份独立的字节副本。
    # 真机读数（07，base-busybox 那一版）：files/opt/busybox/1.36.1/bin/cat 与 lib/libbusybox.so
    # sha256 相同（87e32d67…b51e0） 同一份字节出现在 18 个名字下。
    # 而构建侧写的是 ln -sf（build-native-busybox.sh:260），01 今天按 man1 INSTALL 段裁定：
    # 「make install 连带把全部软链建好」 那些是官方产物，**不该在打包时被展平**。
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

#  形态：构建期算一次，写进产物 
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
#  清单：entry 由落位结果算出（gen-component-meta.js 自己算，不是人填的）
# 格三（这一件是不是我们要的那一代）：把构建标识传下去。
#   而它必须区分两件事（01 今天核过）：
#     buildSha/buildRun = 这一件是哪一次流水线造出来的（跨构建会变）
#     sourceSha256     = 这一件的源码是哪一份（上游 tarball 的摘要，跨构建不变）
#     而拿后者答「是不是这一次构建的」必然答「是」—— 而它就在同一份清单里
#     （而读侧分不清这两格），所以**必须另写两格**。
#    而本地跑时这两个环境变量不存在   按 C-05 不填兜底值，
#      而是**判红**：「这一格缺证据就要说出来」—— 
#      而不能让它悄悄变成「永远答是」（04 今天那条判别的最危险那一档）。
#   本地造件时这两个环境变量不存在 => **填一个可识别的占位**，不报缺、不拦住落位。
#     依据（仓里已有同类先例）：scripts/publish/publish-component-manifest.js:138,143
#       process.env.GITHUB_RUN_NUMBER || '0'
#       process.env.LOBOS_COMPONENT_REVISION || ''
#     而**占位不能是空**（ 空与「没有」在读侧不可分， 而那正是这一族最隐蔽的那一格）。
#      而不填的后果：落位侧读不到就答「是」  「本地造的那一份」会被当成
#      「这一次构建的那一份」  而那正是今天要治的那一格。
BUILD_SHA="${GITHUB_SHA:-local}"
BUILD_RUN="${GITHUB_RUN_ID:-local}"
node "$ROOT/scripts/recipes/gen-component-meta.js" \
  "$ID" "$VER" "$STAGE/component-meta.json" --payload "$STAGE" \
  --build-sha="$BUILD_SHA" --build-run="$BUILD_RUN" \
  || { echo "::error title=$ID 说明生成失败"; exit 1; }


# ---- $ORIGIN（归产物那一侧；01 今天裁的）----
# 为什么在这一支加（不在安装那一侧）：
#   · $ORIGIN 这个值只有在产物里才成立，而落位侧改它 = 改那份字节 => 那要重编，不是重装
#   · RUNPATH 是那份字节的属性 => 归安装侧就是「在安装期改产物属性」（C-16）
# 为什么走「事后 objcopy」而不是链期 -Wl：
#   [读数·实测] 那个 configure 那一侧今天**零 LDFLAGS**
#     （curl 与 openssl 两件各 grep 一次，都是零命中）=> 而 LDFLAGS 那一路无处可插
#   [源码·今天代码怎么写] 而八件的链接是**第三方 configure 驱动**的
#     （build-bundle-curl.sh:41 ./configure，:53 只传 CC/AR/RANLIB）
#      而这一行说的是「代码今天怎么写」，  而上面那一行才是读数 
#   [源码·今天代码怎么写] 而 llvm-objcopy 仓里已有（build-native-busybox.sh:219-220 从 NDK toolchain 取它），
#     而它只是今天还没有用它写 RUNPATH 的这个用法
#      而这一格是「代码今天怎么写」，  **不是**「改了之后那份 .so 的 RUNPATH 生效」
#      —— 而后者今天【未过】（  07 今天取， 而它要的是一次运行）
#       ** 而这两句不可互相顶替**（  那是 C-145：
#      **证据是「代码今天怎么写」，还是「那台机器上的一次运行」**）
#   [读数·实测] 那两行 configure 的行号是我今天 grep 出来的（可复跑）；
#     而「零 LDFLAGS」那半句今天也是 grep 出来的（curl 与 openssl 各一次，都是零命中）。
# 为什么写 RUNPATH 而不是 RPATH：
#   [读数·实测] curl 那份 libcurl.so 的 NEEDED 五条全是**直接依赖**
#     （libssl.so / libcrypto.so / libz.so.1 / libdl.so / libc.so）
#     —— 而这一格是我今天 readelf -d 实读出来的（  而它答的是「那一件今天有哪几个直接依赖」，
#      **不是**「RUNPATH 会不会生效」）
#   [原文] ld.so(8):77-82（03 今天取到逐字原文）—— 而**那是 glibc 那一侧的**，
#     DT_RUNPATH "only to find those objects required by DT_NEEDED entries and
#     do not apply to those objects' children"
#     => [原文 · glibc 那一侧] 直接依赖这一问：RUNPATH 就够
#        （  而今天没有需要下传的依赖）
#      而**这一句不能跨宿主**：
#        「证据回答的是一个更普遍的问题，而断言的是一个更具体的情况」——
#         把 glibc 那一项换到 bionic 上，证据就不成立了（04 今天那条判别）
#          ** 而「在 bionic 上实际生效」那一格今天[未过]**（07 今天去取，
#           而那是唯一能把强度从「据原文」升到「已验」的那一次运行）。
#   [转述·未核原文] 而 bionic 上 DT_RPATH 已知会白编
#     （仓里 build-node-android.sh:363 的诊断文案这么说，而我今天没找到它的逐字原文）
#
# 而这一格今天**只写「同一目录」那一侧**；跨目录那一格[未过]（见提交说明）。
#   · 库形态那几份落在 lib/，而它们的 $ORIGIN 就是 lib/ 自己 => 裸 $ORIGIN
#   · 程序形态那几份落在 bin/，而它们的 $ORIGIN 是 bin/
#     => 它要够到同件的 lib/ 就得写 $ORIGIN/../lib 这一类形态
#     => **而那一格今天【未过】**（要取的是 NDK 里那个 llvm-objcopy 加 --enable-new-dtags
#        之后它把 RUNPATH 写成什么形态），**所以今天不写它**

#  而「写 DT_RUNPATH」那一格，**整段撤掉** —— 而**已定的结论**：
#   · CI 读数（run 39509725200，head=1946162）：
#     --help 输出的字节数: 11864（  而它非零  而那证明输出取到了）
#     而那 11864 字节里**零个含 rpath 的行** =>
#     **=> 而那份工具没有那个功能**（**不是**「未过」）
#     **=> 而 NDK 里那个 llvm-objcopy 根本不支持写 rpath**
#   ·   **=> 事后 objcopy 写 DT_RUNPATH 这条路从根上走不通** 
#   ·   而仓里唯一能写出 DT_RUNPATH 的先例是**链期参数**：
#     scripts/toolchain/build-node-android.sh:299
#       LDFLAGS_TARGET_OVERRIDE="LDFLAGS.target=-Wl,--enable-new-dtags -Wl,-rpath,'$ORIGIN'"
#     （  而另两处也写明「只有 DT_RPATH 不行，要 --enable-new-dtags」：
#      scripts/verify/verify-runtime-elf.sh:321 与 scripts/verify/check-elf-deps.sh:156）
#   ·   **=> 而「这一件能不能在链期拿到 DT_RUNPATH」是一个**逐件**的判据**（  八件四种构建系统：
#      configure 那一族 / openssl Configure / cargo / make+Kconfig）
#       而那个逐件判据**今天已核出来**（07 今天的逐件读数 + 我今天逐件改）：
#         zlib LDSHARED / curl configure / jq make / bash LDFLAGS / openssl Configure
#         而 ripgrep 那一件**本来就有**（  而 07 今天核到它的产物里 DT_RUNPATH [$ORIGIN]）
#         而 busybox **静态、无 .so**   而 RUNPATH 那一格对它不适用 
#   ·   **=> 而那一格今天不再是「缺一个判据」** ——  而**链期**那一路今天通了 
#       而**不能给一个不存在的通用能力加判据** =>  而今天整段撤掉 
#       （  而撤掉它之后， 七件不再在这一格判红）

# 算出来的 entry 与调用方声明的那一份不一致  两处形态说法分叉了。
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
#  而这一格：**打包之前先把 dist/ 里不属于这一件的东西清掉** 
#   缺陷名（C-102）**dist/ 是共用的，  而它跨件累积**：
#     · CI 上同一个 workspace 复用， 而三个 workflow（zlib/jq/rg）都**没有清 dist/**
#       （  阳性对照：  三个文件里 `actions/checkout@v4` 命中， 而 `rm -rf` 零命中）
#     ·   **而 dist/ 里会留着上一批的目录**（  真机读数 07：
#        jq / ripgrep / zlib 三件的 dist/ 那节**逐字相同**（sha 8a9242fca51699b1），
#          而三件形态各不相同   **而形态不解释它**   **而它是残留**）
#     ·  而真机读数：那个 dist/bin 是一个**空目录**（  `total 12` = 目录自身 + . + ..）
#     ** 而「谁建了它」那一格在 CI workspace 状态里， 而不在仓里**（ 而它今天【未核】）
#     ** 而这一格要防的是「拿一个反常的东西当线索」**：
#      **先问「它今天会不会影响判据」** ——  而今天它**不影响**（ 真红点不在那里）
#    ** 而处置：打包之前把 dist/ 清干净， 而只留这一件要产出的那几个名字** 
#      ** 而这样「dist/ 里有什么」这一格就永远只反映这一件， 而不用去追残留的来路** 
rm -rf "$DIST"/* 2>/dev/null || true

#  而这一格：**这一件声明了标记文件，就核它在不在**（  01 今天裁的候选 (a)）
#   缺陷名（C-102）**「这一件走到哪一步了」今天没有判据**：
#     · 而真机读数（07，2026-10-21）：  busybox 的 dist/ = 空 bin/ + **busybox.version（6 字节）**；
#        而 zlib / jq / ripgrep 那三件 = 只有空 bin/（  那三节 sha 逐字相同 8a9242fca51699b1）
#     ·   ** 而「dist/ 里有没有那一件该有的那个文件」是「哪一段 exit 了」的可跑形态** 
#       （ 而它比读日志可靠： 07 今天核到那七份日志里 objcopy/rpath **零命中**）
#    ** 而「该有的那个文件」由那一件自己声明**（  而打包这一支去问它）
#     而**没声明就不查**（  而那不是「缺」，  那是「没声明要查什么」）
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

# C-102 **zip 不带 -y  软链被按目标内容写进包** —— 暂存目录里即使还是软链，
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
