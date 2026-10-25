#!/usr/bin/env bash
# 判一份字节的「形态」，并在构建期把形态写进产物。
#
# 照 container/app/src/main/java/lobos/kernel/elf/ExecBits.kt:26-30 的三态
# （那个 Kotlin 文件本轮不改，这里只照它的形状在 shell 里实现一份）：
#   exec        ET_EXEC                 —— 直接能 exec
#   dyn-interp  ET_DYN 且有 PT_INTERP   —— 位置无关可执行，需经 linker64 驱动
#   dyn-plain   ET_DYN 且没有 PT_INTERP —— 共享库，不是程序
#   unknown     不是 ELF（或是 #! 脚本）
#
# 取值域就这四个字符串，别的值一律当 unknown（不许猜）。
#
# 用法：source scripts/toolchain/elf-form.sh
#   form=$(elf_form <文件>)
#
# 为什么要有它（今天下午定的三行规则第一条）：形态是**生产时就知道**的事。
# 构建期算一次、写进产物；运行期只读那一份 —— 不判断、不探测、不查外部表。

ELF_FORM_EXEC="exec"
ELF_FORM_DYN_INTERP="dyn-interp"
ELF_FORM_DYN_PLAIN="dyn-plain"
ELF_FORM_SHEBANG="shebang"
ELF_FORM_UNKNOWN="unknown"

# 找 readelf —— 优先 NDK 的 llvm-readelf（构建期一定有），其次系统的。
elf_readelf() {
  if [ -n "${LLVM_READELF:-}" ] && [ -x "${LLVM_READELF}" ]; then printf '%s\n' "$LLVM_READELF"; return 0; fi
  if command -v llvm-readelf >/dev/null 2>&1; then command -v llvm-readelf; return 0; fi
  if command -v readelf >/dev/null 2>&1; then command -v readelf; return 0; fi
  return 1
}

# ELF 头里的 Type：EXEC / DYN / OTHER（不是 ELF 时是 OTHER）
elf_type_of() {
  local f="$1" re t
  re="$(elf_readelf)" || return 1
  t="$(LC_ALL=C "$re" -h "$f" 2>/dev/null | sed -n 's/^[[:space:]]*Type:[[:space:]]*\([A-Z][A-Z]*\).*/\1/p' | head -1)"
  [ -n "$t" ] || { printf 'OTHER\n'; return 0; }
  printf '%s\n' "$t"
}

# 程序头里有没有 PT_INTERP 段（有 => 这份字节要靠解释器驱动）
elf_has_interp() {
  local f="$1" re
  re="$(elf_readelf)" || return 1
  if LC_ALL=C "$re" -lW "$f" 2>/dev/null | awk '$1=="INTERP"{found=1} END{exit !found}'; then
    printf 'yes\n'
  else
    printf 'no\n'
  fi
}

# 形态（本文件唯一的判别入口）
elf_form() {
  local f="$1" t
  if [ ! -f "$f" ]; then printf '%s\n' "$ELF_FORM_UNKNOWN"; return 0; fi
  # ★ 缺陷名（C-102）**这一族的取值域按「能不能被当入口调起」那一问定**（=> 而 01 裁的，=> 而 S117）。
  #   而那判别形式的第 ② 步是「它的头是不是 #!」（=> 而而那一行空则不是可调起的）。
  #   而原来这一族**只**读 ELF 头（=> 而 elf_type_of 逐字是非 ELF 打印 OTHER），
  #   => 而而 #! 脚本落到 OTHER => 而而它那一档是 unknown => 而而它被那一列**整个漏掉**。
  #   => 而那正是「整类漏掉」那一族（=> 而判别形式：那一类的成员在那一列里**一条都没有**）。
  #   => 而处置：这一族**先**认 #! 头，=> 而而那一档答 shebang（=> 而而它是入口形态）。
  if [ "$(head -c 2 "$f" 2>/dev/null)" = "#!" ]; then
    #   而那一行空则不是可调起的（=> 而 01 裁的判别形式第 ② 步：=> 而那一行空则不是入口形态）
    #   => 而那一行**去掉 #! 之后**为空才算空（=> 而 head -n 1 那一行本身**含** #!，=> 而它永远非空）
    if [ -n "$(head -n 1 "$f" 2>/dev/null | cut -c3-)" ]; then
      printf '%s\n' "$ELF_FORM_SHEBANG"; return 0
    fi
  fi
  t="$(elf_type_of "$f" 2>/dev/null)" || { printf '%s\n' "$ELF_FORM_UNKNOWN"; return 0; }
  case "$t" in
    EXEC) printf '%s\n' "$ELF_FORM_EXEC" ;;
    DYN)
      if [ "$(elf_has_interp "$f" 2>/dev/null || echo no)" = yes ]; then
        printf '%s\n' "$ELF_FORM_DYN_INTERP"
      else
        printf '%s\n' "$ELF_FORM_DYN_PLAIN"
      fi ;;
    *) printf '%s\n' "$ELF_FORM_UNKNOWN" ;;
  esac
}

# 形态 → 角色：program / library / unknown
elf_role_of_form() {
  case "$1" in
    "$ELF_FORM_EXEC"|"$ELF_FORM_DYN_INTERP"|"$ELF_FORM_SHEBANG") printf 'program\n' ;;
    "$ELF_FORM_DYN_PLAIN") printf 'library\n' ;;
    *) printf 'unknown\n' ;;
  esac
}

elf_form_is_program() { case "$1" in "$ELF_FORM_EXEC"|"$ELF_FORM_DYN_INTERP"|"$ELF_FORM_SHEBANG") return 0 ;; *) return 1 ;; esac; }
elf_form_is_library() { [ "$1" = "$ELF_FORM_DYN_PLAIN" ]; }

# DT_SONAME（共享库才有；没有就空）
elf_soname() {
  local f="$1" re
  re="$(elf_readelf)" || return 0
  LC_ALL=C "$re" -d "$f" 2>/dev/null | sed -n 's/.*SONAME).*\[\(.*\)\]/\1/p' | head -1
}

# 链的根名：libz.so.1.3.2 -> libz.so ; libcrypto.so.3 -> libcrypto.so
elf_chain_root() {
  local n="$1"
  case "$n" in
    *.so) printf '%s\n' "$n" ;;
    *.so.*) printf '%s.so\n' "${n%%.so.*}" ;;
    *) printf '%s\n' "$n" ;;
  esac
}

# 某个目录里属于同一条链的那些名字（字典序，短名在前）
elf_chain_names() {
  local dirpath="$1" base="$2" root out=""
  root="$(elf_chain_root "$base")"
  local n
  for n in $(cd "$dirpath" 2>/dev/null && ls -A 2>/dev/null | LC_ALL=C sort); do
    [ "$(elf_chain_root "$n")" = "$root" ] || continue
    out="$out$n "
  done
  printf '%s\n' "$out"
}

# 把目录里的库摆成 ldconfig(8) 的三段链：
#   libfoo.so  ->  libfoo.so.1  ->  libfoo.so.1.12
#
# 三段的来历：
#   第一段 开发名   libfoo.so            —— 链接期用的名字
#   第二段 SONAME   libfoo.so.1          —— **运行时** linker 按 DT_NEEDED 找的就是它
#   第三段 版本名   libfoo.so.1.12       —— 实体字节所在的名字
#
# 参照 zlib 那一件今天已有的做法（scripts/recipes/build-bundle-zlib.sh:41-44/49/84
# 用 cp -Pf 建链）：那里上游自己带了三段（libz.so -> libz.so.1 -> libz.so.1.3.2），照搬即可。
# 另外两种上游形状由本函数收拾：
#
#   形态甲（openssl/crypto）：libfoo.so 与 libfoo.so.3 两份都是实体字节
#     => 挑最具体的那份留作实体，另一份改成软链，补第三段 root.3.6.3。
#   形态乙（curl）：只有 libcurl.so.4.8.0 一份实体
#     => 补 libcurl.so.4（SONAME）与 libcurl.so（开发名），第三段沿用上游那份。
#
# 用法：elf_ensure_chain <目录> <版本>  → 打印新建/改名的名字（每个一行，没有则空）
elf_ensure_chain() {
  local dirpath="$1" ver="$2"
  local out="" n root names cnt entity soname major third versions n2 have_version
  # 目录里所有 .so 家族的根名（去重）
  for root in $(cd "$dirpath" 2>/dev/null && ls -A 2>/dev/null | sed -n 's/^\(lib.*\)\.so\(\.\|$\).*/\1/p' | LC_ALL=C sort -u); do
    root="$root.so"
    names="$(elf_chain_names "$dirpath" "$root")"
    cnt="$(printf '%s' "$names" | wc -w)"
    [ "$cnt" -ge 1 ] || continue

    # 实体字节：这一族里**最具体**的那个真实文件（版本段数最多的）
    entity=""
    for n in $(printf '%s\n' $names | LC_ALL=C sort -r); do
      if [ -f "$dirpath/$n" ] && [ ! -L "$dirpath/$n" ]; then entity="$n"; break; fi
    done
    [ -n "$entity" ] || continue

    # ★ **两问分开**（01 今天指出、03 今天更正其位置）：
    #   (a) 那份字节**自己说**它的 SONAME 是什么？读不到 => 按 C-05 报缺，**不替它编**。
    #   (b) 我们要不要给它建一条链？
    #   原来这一行 `[ -n "$soname" ] || soname="$root"` 把两问合成了一问：
    #   「上游没给 SONAME」与「上游给了无版本层的 SONAME」都变成 soname=开发名 => 不可区分。
    soname_raw="$(elf_soname "$dirpath/$entity")"
    if [ -z "$soname_raw" ]; then
      # (a) 读不到 => 那份字节没自述 SONAME => 按 C-05 报缺：不建链、不替它编一个
      #   （这个文件没有 say()，输出走 out —— 每个名字一行）
      out="$out${out:+ }（$(basename "$entity") 读不到 DT_SONAME —— 报缺，不建链）"
      continue
    fi
    # (b) 有 SONAME => 按它自述的名字建链（同名就是同名，1 段也是它的实际形状）
    soname="$soname_raw"
    major="${soname#$root.}"
    case "$major" in ''|*[!0-9]*) major="" ;; esac
    case "$ver" in ''|*[!0-9.]*) ver="" ;; esac

    # 已经有三段了就一个字都不动（zlib 那一件就是这种）
    have_version=0
    for n2 in $names; do
      case "$n2" in
        "$root".*.*) have_version=1 ;;
      esac
    done
    if [ "$have_version" -eq 1 ] && [ "$cnt" -ge 3 ]; then
      continue
    fi

    # ── 第三段的名字 ──────────────────────────────────────────
    # 实体自己已经带着版本段（如 libcurl.so.4.8.0）=> 它就是第三段，不再另造一个。
    # 实体挂在开发名或 SONAME 层上（如 libssl.so / libcrypto.so.3）=> 用件自己的
    # 版本号造第三段 root.3.6.3。
    third=""
    if [ "$entity" != "$root" ] && [ "$entity" != "$soname" ]; then
      third="$entity"
    elif [ -n "$major" ] && [ -n "$ver" ]; then
      case "$ver" in
        "$major".*) third="$root.$ver" ;;
        *) third="$root.$major.$ver" ;;
      esac
    fi

    # ── 补第三段 ──────────────────────────────────────────────
    if [ -n "$third" ] && [ "$entity" != "$third" ] && [ ! -e "$dirpath/$third" ]; then
      # 实体挂在开发名/SONAME 层上 —— 挪到第三段
      mv -f "$dirpath/$entity" "$dirpath/$third" 2>/dev/null || true
      entity="$third"
      out="$out${out:+ }$third"
    fi

    # ── 补第二段（SONAME 层）──────────────────────────────────
    if [ -n "$soname" ] && [ "$soname" != "$root" ]; then
      if [ ! -e "$dirpath/$soname" ] || [ -L "$dirpath/$soname" ]; then
        ln -sfn "$entity" "$dirpath/$soname" 2>/dev/null || true
        out="$out${out:+ }$soname"
      fi
    fi

    # ── 补第一段（开发名）──────────────────────────────────────
    if [ ! -e "$dirpath/$root" ] || [ -L "$dirpath/$root" ]; then
      ln -sfn "$soname" "$dirpath/$root" 2>/dev/null || true
      out="$out${out:+ }$root"
    fi
  done
  [ -n "$out" ] && printf '%s\n' "$out"
  return 0
}

# JSON 字符串转义（只处理反斜杠与双引号；路径里不会有别的）
elf_json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

# 写形态文件 —— 产物自带的那一份（运行期只读它）
#
# 用法：elf_write_shape <件名> <entry> <落位目录> <输出文件> <版本> [范围目录...]
# 范围目录默认 bin lib（件的运行时文件只在这两个目录里）。
#
# 缺陷名（C-102）形态只活在外部表里，产物自身不携带：
#   原来一个包里没有任何一处写着「这份字节是库还是程序」，
#   运行期只能靠文件名/目录名猜，或者现场探测 ELF ——
#   而规则是「运行期只读，不判断、不探测」。
#   所以形态必须在构建期算出来、写进产物。
#
# 写出的取值域（运行期照这几个值读，不许认别的）：
#   form  ∈ {exec, dyn-interp, dyn-plain, shebang, unknown}   逐文件的形态
#           （照 ExecBits.kt:26-30 的三态：ET_EXEC / ET_DYN+PT_INTERP / ET_DYN 无 PT_INTERP）
#           ★ shebang 那一档（=> 而 01 裁的 S117 那一件）：
#             而这一族的取值域按「**能不能被当入口调起**」那一问定，=> 而按文件长什么样不定域；
#             而 #! 脚本**是**可调起的（=> 而 c_rehash / npm-cli.js / pnpm 那几件都是），
#             => 而那一档答 shebang，=> 而它的 role 是 program。
#   role  ∈ {program, library, unknown}              逐文件的角色（按 form 判）
#   kind  ∈ {program, library, mixed, unknown}       件整体：
#              bin/ 与 lib/ 都有内容            => mixed（混合件：busybox / jq）
#              只有 bin/                       => program（单入口程序件：bash / rg）
#              只有 lib/                       => library（三段链库件：zlib / crypto / curl / openssl）
#   mode  ∈ 八进制权限（owner 执行位是「程序」的可见标记）
#
# ★ kind 的取值域与运行期 PieceScan 的 role 词汇**不同**（那边是 exec/library/headers/
#   shell/multi-command），因为那一边答的是「这一件在系统里扮演什么角色」，
#   而这一边答的是「这一件带的是库还是程序」。
#   两者是**两把尺**，不许互相顶替 —— 这条留给 01/04 裁（见报告第 3 节待裁清单第 1 条）。
elf_write_shape() {
  local piece="$1" entry="$2" stage="$3" out="$4" ver="$5"
  shift 5
  local scopes=("$@")
  [ "${#scopes[@]}" -eq 0 ] && scopes=(bin lib)
  local re
  re="$(elf_readelf)" || {
    echo "::error title=缺 readelf::算不了形态（LLVM_READELF 与系统 readelf 都没有）" >&2
    return 1
  }

  # 列出范围内的所有文件与软链（相对路径）
  local rels="" s
  for s in "${scopes[@]}"; do
    [ -d "$stage/$s" ] || continue
    rels="$rels$(cd "$stage/$s" && find . -mindepth 1 \( -type f -o -type l \) -printf '%P\n' 2>/dev/null | sed "s|^|$s/|" | LC_ALL=C sort)
"
  done

  # ── 第一步：补链（库形态的字节要有 ldconfig(8) 的三段）────────
  local derived="" rel real form role d
  for rel in $rels; do
    [ -e "$stage/$rel" ] || continue
    if [ -L "$stage/$rel" ]; then real="$(readlink -f "$stage/$rel" 2>/dev/null || true)"; else real="$stage/$rel"; fi
    [ -n "$real" ] && [ -f "$real" ] || continue
    form="$(elf_form "$real")"
    role="$(elf_role_of_form "$form")"
    [ "$role" = "library" ] || continue
    d="$(elf_ensure_chain "$(dirname "$stage/$rel")" "$ver")"
    [ -n "$d" ] && derived="$derived${derived:+,}\"$(elf_json_escape "$d")\""
  done

  # 补链可能新增了名字 —— 重新列一遍，后两步按新名单走
  rels=""
  for s in "${scopes[@]}"; do
    [ -d "$stage/$s" ] || continue
    rels="$rels$(cd "$stage/$s" && find . -mindepth 1 \( -type f -o -type l \) -printf '%P\n' 2>/dev/null | sed "s|^|$s/|" | LC_ALL=C sort)
"
  done

  # ── 第二步：定权限（程序形态带 owner 执行位；库形态不带）──────
  for rel in $rels; do
    [ -e "$stage/$rel" ] || continue
    if [ -L "$stage/$rel" ]; then real="$(readlink -f "$stage/$rel" 2>/dev/null || true)"; else real="$stage/$rel"; fi
    [ -n "$real" ] && [ -f "$real" ] || continue
    form="$(elf_form "$real")"
    role="$(elf_role_of_form "$form")"
    case "$role" in
      program) chmod 0755 "$real" 2>/dev/null || true ;;
      library) chmod 0644 "$real" 2>/dev/null || true ;;
    esac
  done

  # ── 第三步：重新列一遍（补链可能新增了名字）──────────────────
  rels=""
  for s in "${scopes[@]}"; do
    [ -d "$stage/$s" ] || continue
    rels="$rels$(cd "$stage/$s" && find . -mindepth 1 \( -type f -o -type l \) -printf '%P\n' 2>/dev/null | sed "s|^|$s/|" | LC_ALL=C sort)
"
  done

  local files_json="" programs="" libraries="" derived_links=""
  local n_prog=0 n_lib=0 n_bin=0 n_libdir=0
  local mode bytes sha soname chain chain_json entry_form="" entry_role="" dir n
  for rel in $rels; do
    [ -e "$stage/$rel" ] || continue
    # ★ 软链**照它指向的实体**算形态（form/role/sha 都取实体那份）—— 而**它仍进 files[]**。
    #   ★★ 而它**必须**进 files[]（2026-10-20 实测出来的）：
    #     落位侧 ProgramInstallPipeline.landByShape 的目标映射（map）**是从 files[] 建的**
    #     => 而软链不在 files[] 里 => map 里就没有它 => **它不会被搬**
    #     => 落位后 bin/ 下只有本体那一份、**那 17 条命令软链一条都没有**
    #     => 而 usr/bin 下那 17 条命令软链正是从它们建的 => 那 17 个命令会消失。
    #   ★ 而 derivedLinks 那一格记的是「哪些是派生物」（它与 files[] 并存，不冲突）。
    #   ★ 而 01 今天裁的（S32）也说了：18 个名字**都是事实**（都在磁盘上、都在被调用）=> files[] 18 项是对的。
    if [ -L "$stage/$rel" ]; then
      real="$(readlink -f "$stage/$rel" 2>/dev/null || true)"
      [ -n "$real" ] && [ -f "$real" ] || continue
      derived_links="$derived_links${derived_links:+,}\"$(elf_json_escape "$rel")\""
    else
      real="$stage/$rel"
    fi
    [ -f "$real" ] || continue
    form="$(elf_form "$real")"
    role="$(elf_role_of_form "$form")"
    [ "$role" = "unknown" ] && continue
    dir="${rel%%/*}"
    [ "$dir" = "bin" ] && n_bin=$((n_bin+1))
    [ "$dir" = "lib" ] && n_libdir=$((n_libdir+1))
    # ★ **kind 该用 role 那把尺（形态），不该用目录名** ——
    #   缺陷名（C-102）kind 按目录名数：原来 kind 由 n_bin / n_libdir（`${rel%%/*}` 是 bin 还是 lib）决定，
    #   而「看目录名」按 §10.1:128 已判无效。
    #   => 今天两把尺会不一致：role 那把（形态）说 openssl 是 library（libssl.so 是 dyn-plain），
    #     而 kind 那把（目录名）说它是 mixed（bin/ 与 lib/ 两个目录都在）。
    #   => 现在 kind 由 n_prog（程序形态的份数）与 n_lib（库形态的份数）决定 —— 与 role 同一把尺。
    mode="$(stat -c%a "$real" 2>/dev/null || echo '')"
    bytes="$(stat -c%s "$real" 2>/dev/null || echo 0)"
    sha="$(sha256sum "$real" 2>/dev/null | cut -d" " -f1)"
    soname="$(elf_soname "$real")"
    chain="$(elf_chain_names "$(dirname "$stage/$rel")" "$(basename "$rel")")"
    chain_json=""
    for n in $chain; do
      chain_json="$chain_json${chain_json:+,}\"$(elf_json_escape "$n")\""
    done
    files_json="$files_json${files_json:+,}
    { \"path\": \"$(elf_json_escape "$rel")\", \"dir\": \"$dir\", \"form\": \"$form\", \"role\": \"$role\", \"mode\": \"$mode\", \"bytes\": $bytes, \"sha256\": \"$sha\", \"soname\": \"$(elf_json_escape "$soname")\", \"chain\": [$chain_json] }"
    if [ "$role" = program ]; then
      programs="$programs${programs:+,}\"$(elf_json_escape "$rel")\""
      n_prog=$((n_prog+1))
    else
      libraries="$libraries${libraries:+,}\"$(elf_json_escape "$rel")\""
      n_lib=$((n_lib+1))
    fi
    if [ "$rel" = "$entry" ]; then entry_form="$form"; entry_role="$role"; fi
  done
  # 件整体的形态：**按形态判**（程序形态的份数与库形态的份数）—— 与 role 同一把尺。
  # ★ 缺陷名（C-102）kind 按落位目录判：原来这一段是
  #   「n_bin>0 && n_libdir>0 => mixed；n_bin>0 => program；n_libdir>0 => library」，
  #   而 n_bin / n_libdir 数的是**目录名**（`${rel%%/*}` 是 bin 还是 lib）——
  #   「看目录名」按 §10.1:128 已判无效，而 role 那一格已经用形态那把尺了。
  #   => 两把尺会不一致：openssl 的 libssl.so 是 dyn-plain（role=library）
  #     而 bin/openssl 是程序形态 => role 说它有程序形态的字节、kind 却按目录名说 mixed。
  #   => 现在 kind 与 role 同源：n_prog（程序形态的份数）与 n_lib（库形态的份数）。
  local kind="unknown"
  if [ "$n_prog" -gt 0 ] && [ "$n_lib" -gt 0 ]; then kind="mixed"
  elif [ "$n_prog" -gt 0 ]; then kind="program"
  elif [ "$n_lib" -gt 0 ]; then kind="library"
  fi
  [ -n "$entry_form" ] || entry_form="unknown"
  mkdir -p "$(dirname "$out")"
  {
    printf '{\n'
    printf '  "schema": 1,\n'
    printf '  "piece": "%s",\n' "$(elf_json_escape "$piece")"
    printf '  "version": "%s",\n' "$(elf_json_escape "$ver")"
    printf '  "entry": "%s",\n' "$(elf_json_escape "$entry")"
    printf '  "entryForm": "%s",\n' "$entry_form"
    printf '  "entryRole": "%s",\n' "${entry_role:-unknown}"
    printf '  "kind": "%s",\n' "$kind"
    printf '  "programs": [%s],\n' "$programs"
    printf '  "libraries": [%s],\n' "$libraries"
    printf '  "derivedChain": [%s],\n' "$derived"
    printf '  "derivedLinks": [%s],\n' "$derived_links"
    printf '  "files": [%s]\n' "$files_json"
    printf '}\n'
  } > "$out"
  echo "[shape] $piece：kind=$kind entryForm=$entry_form 程序 $n_prog 份 · 库 $n_lib 份 → $(basename "$out")"
  return 0
}

# ── 多调用二进制的「我响应哪些名字」那张表 ──
#
# 01 今天从真机读出来的形态（十六进制原文）：
#   .rodata 的第一个字节就是第一个名字，紧跟一个 NUL，然后是下一个名字，
#   NUL 分隔、连续排列，直到某个不是名字的字符串为止。
#   它是一份**普通的字符串常量区**，不是 .data.rel.ro 里的指针数组。
#
# ★ **它不是「这份字节响应哪些名字」** —— 01 今天裁的那一句已被今天的读数更正：
#   01 说「那份字节的 .rodata 开头就是『我响应哪些名字』那张表」=> 读它，不另带名单。
#   ★ 而今天实测（这一件那份字节，01 今天逐字核过）：
#     表（.rodata）= **19 个**：awk cat cp df false grep gunzip gzip ln ls mkdir mv ps rm sed sh tar true vi
#     白名单（$APPLETS，我们写下的）= **17 个**：tar gzip gunzip grep sed ls cp mv cat mkdir rm ln vi df ps true false
#     落包集（make install 实际建出的软链）= **按轮次分清**（2026-10-20 逐个包数过）：
#       旧轮（BB_BINS 取 $APPLETS 派生时）= **17 个、无 sh** => 表 − 落包集 = {awk, sh}
#       新轮（BB_BINS 取落包集时）= **18 个、含 sh** => 表 − 落包集 = {awk}
#     ★★ 而**表 − 落包集 记的是什么**：它记的是「这一件编译时没编出那个能力」——
#       而它**不区分**「我们显式关掉了它」与「它依赖的那些被我们关掉了，于是它编不出来」（01 今天裁的）。
#       ★★ 而**「.rodata 里仍有 awk」**这一条已由今天实测坐实（那一件编译时按白名单关掉了 awk：
#         build-native-busybox.sh:79-82「AWK 不在清单里……CONFIG_AWK=y 单独存在不足以让它被编，
#         make install 不给它建软链」）=> **那张表是上游这份源码的 applet 全集**，
#         **不是「这份字节响应哪些名字」**。
#   => 而「这份字节响应哪些名字」的**真源**是 make install 连带建的那批软链
#     （man1 INSTALL 段：「If you enabled any applet installation scheme …
#     these will also be installed in the location pointed to by CONFIG_PREFIX」）
#     —— **构建系统自己知道编了哪些 applet**。
#   => 所以这一格今天**没有读者**（构建侧那一处已改成取落包集：
#     build-native-busybox.sh 的 BB_BINS = $OUT_DIR/bin 下那批软链的名字）。
#     而它保留着：它是「那份字节里写着哪些 applet 名」这一格的**核**手段
#     （可与落包集比对：差集 = 被白名单关掉的那些）。
#
# 用法：names=$(elf_applet_names <文件>)  —— 一行一个名字
elf_applet_names() {
  local f="$1"
  [ -f "$f" ] || return 1
  local re
  re="$(elf_readelf)" || return 1
  # .rodata 的文件内偏移与大小
  local line off size
  line="$("$re" -S -W "$f" 2>/dev/null | awk '$0 ~ /\.rodata/ {print; exit}')"
  [ -n "$line" ] || return 1
  # readelf -S -W 的列：1 '[' 2 'N]' 3 名字 4 类型 5 地址 6 **文件内偏移（十六进制）** 7 **大小（十六进制）**
  local off_hex size_hex
  off_hex="$(printf '%s\n' "$line" | awk '{print $6}')"
  size_hex="$(printf '%s\n' "$line" | awk '{print $7}')"
  # dd 的 skip 是十进制 => 这里必须转（0x290=656，而 "000290" 当十进制是 290）
  off="$(printf '%d' "0x$off_hex" 2>/dev/null)"
  size="$(printf '%d' "0x$size_hex" 2>/dev/null)"
  case "$off" in ''|*[!0-9]*) return 1 ;; esac
  case "$size" in ''|*[!0-9]*) return 1 ;; esac
  # 取 .rodata 的头若干字节，按 NUL 切开，逐个判「像不像命令名」
  local head
  head="$(dd if="$f" bs=1 skip="$off" count=1024 2>/dev/null | tr '\0' '\n')"
  local n=0
  printf '%s\n' "$head" | while IFS= read -r w; do
    # 名字的形状：非空、全是小写字母数字或 . _ - +，且不长
    case "$w" in
      ''|[!a-z0-9._+-]*) break ;;
    esac
    [ "${#w}" -le 24 ] || break
    printf '%s\n' "$w"
    n=$((n+1))
    [ "$n" -ge 64 ] && break
  done
}
