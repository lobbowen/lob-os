#!/usr/bin/env bash

ELF_FORM_EXEC="exec"
ELF_FORM_DYN_INTERP="dyn-interp"
ELF_FORM_DYN_PLAIN="dyn-plain"
ELF_FORM_SHEBANG="shebang"
ELF_FORM_UNKNOWN="unknown"

elf_readelf() {
  if [ -n "${LLVM_READELF:-}" ] && [ -x "${LLVM_READELF}" ]; then printf '%s\n' "$LLVM_READELF"; return 0; fi
  if command -v llvm-readelf >/dev/null 2>&1; then command -v llvm-readelf; return 0; fi
  if command -v readelf >/dev/null 2>&1; then command -v readelf; return 0; fi
  return 1
}

elf_type_of() {
  local f="$1" re t
  re="$(elf_readelf)" || return 1
  t="$(LC_ALL=C "$re" -h "$f" 2>/dev/null | sed -n 's/^[[:space:]]*Type:[[:space:]]*\([A-Z][A-Z]*\).*/\1/p' | head -1)"
  [ -n "$t" ] || { printf 'OTHER\n'; return 0; }
  printf '%s\n' "$t"
}

elf_has_interp() {
  local f="$1" re
  re="$(elf_readelf)" || return 1
  if LC_ALL=C "$re" -lW "$f" 2>/dev/null | awk '$1=="INTERP"{found=1} END{exit !found}'; then
    printf 'yes\n'
  else
    printf 'no\n'
  fi
}

elf_form() {
  local f="$1" t
  if [ ! -f "$f" ]; then printf '%s\n' "$ELF_FORM_UNKNOWN"; return 0; fi

  if [ "$(head -c 2 "$f" 2>/dev/null)" = "#!" ]; then

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

elf_role_of_form() {
  case "$1" in
    "$ELF_FORM_EXEC"|"$ELF_FORM_DYN_INTERP"|"$ELF_FORM_SHEBANG") printf 'program\n' ;;
    "$ELF_FORM_DYN_PLAIN") printf 'library\n' ;;
    *) printf 'unknown\n' ;;
  esac
}

elf_form_is_program() { case "$1" in "$ELF_FORM_EXEC"|"$ELF_FORM_DYN_INTERP"|"$ELF_FORM_SHEBANG") return 0 ;; *) return 1 ;; esac; }
elf_form_is_library() { [ "$1" = "$ELF_FORM_DYN_PLAIN" ]; }

elf_soname() {
  local f="$1" re
  re="$(elf_readelf)" || return 0
  LC_ALL=C "$re" -d "$f" 2>/dev/null | sed -n 's/.*SONAME).*\[\(.*\)\]/\1/p' | head -1
}

elf_chain_root() {
  local n="$1"
  case "$n" in
    *.so) printf '%s\n' "$n" ;;
    *.so.*) printf '%s.so\n' "${n%%.so.*}" ;;
    *) printf '%s\n' "$n" ;;
  esac
}

elf_chain_names() {
  local dirpath="$1" base="$2" root out=""
  root="$(elf_chain_root "$base")"
  local n
  for n in $(cd "$dirpath" 2>/dev/null && ls -A 2>/dev/null | LC_ALL=C sort); do
    [ "$(elf_chain_root "$n")" = "$root" ] || continue
    out="$out$n "
  done

  local ent son
  ent=""

  for n in $out; do
    [ -e "$dirpath/$n" ] || continue
    local real_n
    real_n="$(readlink -f "$dirpath/$n" 2>/dev/null)"
    [ -n "$real_n" ] && [ -f "$real_n" ] && { ent="$n"; break; }
  done
  if [ -n "$ent" ]; then
    son="$(elf_soname "$dirpath/$ent")"
    if [ -n "$son" ] && [ "$son" != "$root" ]; then
      case " $out " in
        *" $son "*) ;;
        *) [ -e "$dirpath/$son" ] && out="$out$son " ;;
      esac
    fi
  fi
  printf '%s\n' "$out"
}

elf_ensure_chain() {
  local dirpath="$1" ver="$2"
  local out="" n root names cnt entity soname major third versions n2 have_version

  for root in $(cd "$dirpath" 2>/dev/null && ls -A 2>/dev/null | sed -n 's/^\(lib.*\)\.so\(\.\|$\).*/\1/p' | LC_ALL=C sort -u); do
    root="$root.so"
    names="$(elf_chain_names "$dirpath" "$root")"
    cnt="$(printf '%s' "$names" | wc -w)"
    [ "$cnt" -ge 1 ] || continue

    entity=""
    for n in $(printf '%s\n' $names | LC_ALL=C sort -r); do
      if [ -f "$dirpath/$n" ] && [ ! -L "$dirpath/$n" ]; then entity="$n"; break; fi
    done
    [ -n "$entity" ] || continue

    soname_raw="$(elf_soname "$dirpath/$entity")"
    if [ -z "$soname_raw" ]; then

      out="$out${out:+ }（$(basename "$entity") 读不到 DT_SONAME —— 报缺，不建链）"
      continue
    fi

    soname="$soname_raw"
    major="${soname#$root.}"
    case "$major" in ''|*[!0-9]*) major="" ;; esac
    case "$ver" in ''|*[!0-9.]*) ver="" ;; esac

    have_version=0
    for n2 in $names; do
      case "$n2" in
        "$root".*.*) have_version=1 ;;
      esac
    done
    if [ "$have_version" -eq 1 ] && [ "$cnt" -ge 3 ]; then
      continue
    fi

    third=""
    if [ "$entity" != "$root" ] && [ "$entity" != "$soname" ]; then
      third="$entity"
    elif [ -n "$major" ] && [ -n "$ver" ]; then
      case "$ver" in
        "$major".*) third="$root.$ver" ;;
        *) third="$root.$major.$ver" ;;
      esac
    fi

    if [ -n "$third" ] && [ "$entity" != "$third" ] && [ ! -e "$dirpath/$third" ]; then

      mv -f "$dirpath/$entity" "$dirpath/$third" 2>/dev/null || true
      entity="$third"
      out="$out${out:+ }$third"
    fi

    if [ -n "$soname" ] && [ "$soname" != "$root" ]; then
      if [ ! -e "$dirpath/$soname" ] || [ -L "$dirpath/$soname" ]; then
        ln -sfn "$entity" "$dirpath/$soname" 2>/dev/null || true
        out="$out${out:+ }$soname"
      fi
    fi

    if [ ! -e "$dirpath/$root" ] || [ -L "$dirpath/$root" ]; then
      ln -sfn "$soname" "$dirpath/$root" 2>/dev/null || true
      out="$out${out:+ }$root"
    fi
  done
  [ -n "$out" ] && printf '%s\n' "$out"
  return 0
}

elf_json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

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

  local rels="" s
  for s in "${scopes[@]}"; do
    [ -d "$stage/$s" ] || continue
    rels="$rels$(cd "$stage/$s" && find . -mindepth 1 \( -type f -o -type l \) -printf '%P\n' 2>/dev/null | sed "s|^|$s/|" | LC_ALL=C sort)
"
  done

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

  rels=""
  for s in "${scopes[@]}"; do
    [ -d "$stage/$s" ] || continue
    rels="$rels$(cd "$stage/$s" && find . -mindepth 1 \( -type f -o -type l \) -printf '%P\n' 2>/dev/null | sed "s|^|$s/|" | LC_ALL=C sort)
"
  done

  local extra_rels=""
  local ex
  for ex in $(cd "$stage" && ls -A 2>/dev/null | LC_ALL=C sort); do
    case "$ex" in
      bin|lib) continue ;;
    esac
    [ -d "$stage/$ex" ] || continue
    extra_rels="$extra_rels$(cd "$stage/$ex" && find . -mindepth 1 \( -type f -o -type l \) -printf "%P\n" 2>/dev/null)"
  done
  for rel in $extra_rels; do
    [ -e "$stage/$ex/$rel" ] || continue
    if [ -L "$stage/$ex/$rel" ]; then real="$(readlink -f "$stage/$ex/$rel" 2>/dev/null || true)"; else real="$stage/$ex/$rel"; fi
    [ -n "$real" ] && [ -f "$real" ] || continue
    form="$(elf_form "$real")"
    role="$(elf_role_of_form "$form")"
    case "$role" in
      program) chmod 0755 "$real" 2>/dev/null || true ;;
      library) chmod 0644 "$real" 2>/dev/null || true ;;
    esac
  done

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

  rels=""
  for s in "${scopes[@]}"; do
    [ -d "$stage/$s" ] || continue
    rels="$rels$(cd "$stage/$s" && find . -mindepth 1 \( -type f -o -type l \) -printf '%P\n' 2>/dev/null | sed "s|^|$s/|" | LC_ALL=C sort)
"
  done

  local sys_role=""
  local meta_file="$stage/component-meta.json"
  if [ -f "$meta_file" ]; then
    sys_role="$(node -e 'let d="";try{d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))}catch(e){process.stdout.write("")};process.stdout.write(String(d&&d.role||""))' "$meta_file" 2>/dev/null || echo '')"
  fi
  [ -n "$sys_role" ] || sys_role="unknown"

  local files_json="" programs="" libraries="" derived_links=""
  local n_prog=0 n_lib=0 n_bin=0 n_libdir=0
  local mode bytes sha soname chain chain_json entry_form="" entry_role="" dir n
  for rel in $rels; do
    [ -e "$stage/$rel" ] || continue

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

    printf '  "systemRole": "%s",\n' "$sys_role"
    printf '  "kind": "%s",\n' "$kind"
    printf '  "programs": [%s],\n' "$programs"
    printf '  "libraries": [%s],\n' "$libraries"
    printf '  "derivedChain": [%s],\n' "$derived"
    printf '  "derivedLinks": [%s],\n' "$derived_links"
    printf '  "files": [%s],\n' "$files_json"
    local extra_json=""
    local ed
    for ed in $(cd "$stage" && ls -A 2>/dev/null | LC_ALL=C sort); do
      case "$ed" in
        bin|lib) continue ;;
      esac
      [ -d "$stage/$ed" ] || continue
      _ej=$(elf_json_escape "$ed")
      extra_json="$extra_json${extra_json:+,}"\"$_ej\"
    done
    printf '  "extraDirs": [%s]\n' "$extra_json"

    printf '}\n'
  } > "$out"
  echo "[shape] $piece：kind=$kind entryForm=$entry_form 程序 $n_prog 份 · 库 $n_lib 份 → $(basename "$out")"
  return 0
}

elf_applet_names() {
  local f="$1"
  [ -f "$f" ] || return 1
  local re
  re="$(elf_readelf)" || return 1

  local line off size
  line="$("$re" -S -W "$f" 2>/dev/null | awk '$0 ~ /\.rodata/ {print; exit}')"
  [ -n "$line" ] || return 1

  local off_hex size_hex
  off_hex="$(printf '%s\n' "$line" | awk '{print $6}')"
  size_hex="$(printf '%s\n' "$line" | awk '{print $7}')"

  off="$(printf '%d' "0x$off_hex" 2>/dev/null)"
  size="$(printf '%d' "0x$size_hex" 2>/dev/null)"
  case "$off" in ''|*[!0-9]*) return 1 ;; esac
  case "$size" in ''|*[!0-9]*) return 1 ;; esac

  local head
  head="$(dd if="$f" bs=1 skip="$off" count=1024 2>/dev/null | tr '\0' '\n')"
  local n=0
  printf '%s\n' "$head" | while IFS= read -r w; do

    case "$w" in
      ''|[!a-z0-9._+-]*) break ;;
    esac
    [ "${#w}" -le 24 ] || break
    printf '%s\n' "$w"
    n=$((n+1))
    [ "$n" -ge 64 ] && break
  done
}
