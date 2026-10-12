# Partition E 深度审计报告：lob-os build toolchain scripts

已逐行读完 scripts/ 下全部 67 个文件（7562 行，含 verify/native-deps.txt）。未读任何 .md。
结论来自代码本身 + 本机实测。实测 gen-component-meta.js 时误写了 scripts/component-verify.json，已 git checkout 还原，git status 干净（不影响结论）。

## 一、逐文件分析（用途 / 输入 / 输出 / 关键逻辑 / 外部命令）

### 1. scripts/piece-criteria.json（128 行）
用途：每件「怎么算成了」的可执行验收判据（人读 + 机器可跑）。输入：无入参；被 publish-component-manifest.js 读取。输出：无。
结构：键 = 件名（node/npm/pnpm/jq/git/curl/sysroot/make/cmake/libcxx/bash/ripgrep/busybox/zlib/openssl/crypto/sqlite/python/llvm/pkgconf），每件含 criterion（人读）、node（可直接执行的验收 JS 字符串）、versionArgs、required。
关键逻辑：判据全部「真跑一次」而非只看 --version。node 跑 6*7=42 并起 http 服务 bind 随机端口；git 真做 init→add→commit→log 再 git ls-remote https 探测 TLS；jq 解析 {"a":42}；make 编真 makefile（含隐式规则 %.o: %.c）再 clean；cmake configure+build 最小项目；python import 标准库 + 文件读写 + f-string；clang 用 --sysroot 真编一个 .so 并校验 e_machine=0xB7；busybox/sysroot/libcxx 为形状判据（required:true）。
外部命令：node、jq、git、curl、make、cmake、python3、clang、ld.lld、llvm-ar、llvm-strip、llvm-nm。

### 2. scripts/component-sources.json（255 行）
用途：构建期唯一钉值表。顶层键 buildTimeEpoch=1767225600、ndkVersion="30.0.16248370"、llvmVersion="21.0.0"、sources{23 项}。输入：被 fetch-pinned.sh / cache-key.sh / pin-github-release.js / piece-env.sh 读取；pin-github-release.js 写它。输出：自身。
关键逻辑：每项 version/sha256(64 hex)/urls[](https only)/entry/form。ripgrep 特殊：无 urls（走 cargo），sha256 是产物哈希非源码哈希，首轮空则建基线。crypto 用 sameAs:"openssl"。
外部命令：无（纯数据）。

### 3. scripts/component-verify.json（16 行）
用途：判据状态说明（criteria 已拆到 piece-criteria.json）。仅 $criterionStatus 键，列 7 个「没有自动检查的项」（sysroot/libcxx/bash/ripgrep/openssl/crypto/pkgconf）。输出：无。外部命令：无。
关联：package-component.sh:22 把它当 $META_SRC 传给 gen-component-meta.js 第 5 参（B1）。

### 4. scripts/node-versions.json（8 行）
用途：node/npm 版本钉值。default="24.21.0"、npm="11.19.0"、abi="arm64-v8a"、lts=["24.21.0","22.23.1"]、schema=1。唯一读者 scripts/registry/read-node-versions.sh。输出：无。

### 5. scripts/recipes/piece-env.sh（149 行，source 用）
用途：编单件的公共前置。set -uo pipefail（无 -e）。
输入：$1=件名；env ABI(arm64-v8a)、OUT(dist)、CC、ANDROID_API(35)、JOBS(2)。
输出变量：PIECE_NAME/PIECE_HERE/ROOT_DIR/OUT/JNI/WORK/OUT_DIR/TC_DIR/LLVM_AR/LLVM_RANLIB/LLVM_STRIP/LLVM_READELF/AR_BIN/RANLIB_BIN/NDK_ROOT/API；函数 die()/check_so()/check_exe()/version_of()/emit_piece()。
关键逻辑：cd 仓根；建 OUT/bin、JNI、WORK；CC 为空则子进程跑 locate-ndk.sh（不 source，避免污染 set 选项与 exit 1 终止父进程），从 $GITHUB_ENV 临时文件读回 CC/CXX/NDK/LLVM_*/TRIPLE；从 CC 反推 NDK_ROOT（6 层上溯）；check_so 用 file -bL（跟随软链，否则版本链软链被误判）+ wc -c 最小值；version_of 用 node 读 component-sources.json；emit_piece 校验产物 → 拷共享库整条链（cp -Pf，失败回退 cp -f）→ 调 package-piece-zip.sh。
外部命令：node、bash、mktemp、file、wc、ls、cp、basename、dirname、sed、mkdir。

### 6. scripts/recipes/build-piece-bash.sh（70 行）
用途：编 bash → OUT_DIR/bin/bash。set -uo pipefail（无 -e）。输入：ABI/OUT/CC/JOBS。
输出：work/bash/out/、dist/component-bash-*.zip、work/bash/bash-configure.log、work/bash/bash-make.log。
逻辑：fetch-pinned --src-version bash → --pin bash → tar xzf → 编 termcap_stub.c（tputs/tgetent 等 7 个桩）与 container/native/bionic-compat.c 成 .a → ./configure --host=aarch64-linux-android（LIBS 带两个 .a、bash_cv_* 缓存变量、--without-bash-malloc）→ make -j4 bash → emit_piece bash bin/bash 300000。
外部命令：bash、node、tar、cat、mkdir、rm、cc、llvm-ar、make、grep、tail、ls、tr、cp。

### 7. scripts/recipes/build-piece-zlib.sh（108 行）
用途：编 zlib → libz.so 整条链。set -uo pipefail（无 -e）。
逻辑：fetch-pinned --pin zlib → tar --strip-components=1 → 从 zlib.h 的 ZLIB_VERSION 读出版本拼 SHAREDLIB/SHAREDLIBV/SHAREDLIBM，显式给 LDSHARED 与三个库名绕开 configure 的 .so 探测（交叉编探测失败会静默退回静态）→ 从 Makefile 读回 SHAREDLIBV 验证非空 → make -j$JOBS → 手工 cp libz.so* 与 zlib.h/zconf.h 到 OUT_DIR/{lib,include}（不用 make install，免带 example/minigzip）→ 校验 libz.so.1 存在 → emit_piece zlib lib/libz.so 1000。
外部命令：bash、node、sed、tar、mkdir、rm、make、cp、ls、echo。

### 8. scripts/recipes/build-piece-openssl.sh（55 行）
用途：编 openssl → libssl.so + libcrypto.so。set -uo pipefail（无 -e）。
输入：另读 SOURCE_DATE_EPOCH（调用 fetch-pinned.sh --source-date-epoch，子命令不存在，B3）。
逻辑：export PATH 前置 TC；export ANDROID_NDK_HOME/ROOT；fetch-pinned --pin openssl → ./Configure android-arm64 shared -fPIC -D__ANDROID_API__=$API no-tests no-ui-console → make build_libs → make install_sw → 校验 include/openssl/opensslv.h 在位 → OPENSSL_LIBS=1 emit_piece openssl lib/libssl.so 1000（带同目录所有 .so，crypto 由 build-crypto.yml 拆）。
外部命令：bash、node、tar、mkdir、make、ls、file。

### 9. scripts/recipes/build-piece-curl.sh（69 行）
用途：编 curl → libcurl.so。set -uo pipefail（无 -e）。
输入：依赖 work/zlib/out 与 work/openssl/out 的 include/lib（硬编码相对路径）。
逻辑：SYSROOT_PREFIX="$WORK/../zlib-prefix"（建了但从未使用，死代码）；校验 ssl.h/zlib.h 在位 → fetch-pinned --pin curl → ./configure --enable-shared --disable-static + ac_cv_*/curl_cv_* 缓存 + --with-ca-path=/system/etc/security/cacerts + 大量 --disable-* → make -C lib → make -C lib install → make -C include install（|| true）→ emit_piece curl lib/libcurl.so 1000。
外部命令：bash、node、mkdir、tar、make、ls、file。

### 10. scripts/recipes/build-piece-jq.sh（78 行）
用途：编 jq → OUT_DIR/lib/liblobosjq.so（jq 是单可执行件，落 lib/ 下）。set -uo pipefail。
逻辑：fetch-pinned --pin jq → configure --host=aarch64-linux-android --with-oniguruma=builtin + ac_cv_prog_cc_cross=yes + ac_cv_func_malloc_0_nonnull=yes → make LDFLAGS="-static" → 从 src/jq 或 src/.libs/jq 取（make install 目标不存在）→ cp 成 lib/liblobosjq.so + chmod +x → emit_piece jq lib/liblobosjq.so 300000。
外部命令：bash、node、mkdir、tar、make、cp、chmod、ls、find、tail。

### 11. scripts/recipes/build-piece-rg.sh（54 行）
用途：编 ripgrep（cargo）→ OUT_DIR/bin/rg。set -uo pipefail。
输入：env CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CC、RUSTFLAGS="-C link-arg=-Wl,-rpath,$ORIGIN"、CC_aarch64_linux_android、CXX_aarch64_linux_android、AR_aarch64_linux_android=$LLVM_AR。
逻辑：无 cargo 则 curl -fsSf https://sh.rustup.rs -o rustup.sh && sh rustup.sh -y --profile minimal；rustup target add aarch64-linux-android（失败仅 warn）；从 component-sources.json 读 ripgrep.version 并断言无 urls；cargo install --locked --version；cp rgbin/bin/rg；emit_piece ripgrep bin/rg 300000；再 sha256sum "$WORK/liblobosrg.so" 与表内 sha256 比对（B4：路径错，判据失效）。
外部命令：curl、sh、rustup、cargo、node、cp、chmod、sha256sum、cut、ls、tail。

### 12. scripts/recipes/package-piece-zip.sh（99 行）
用途：打标准件包 component-<id>-<版本>-<sha12>-android-arm64.zip。set -euo pipefail（本分区少数带 -e 的）。
输入：$1=id、$2=OUT_DIR、$3=版本、$4=entry(可省)；env OPENSSL_LIBS=1。
输出：dist/component-*.zip + .sha256；包内 component-meta.json + bin/或 lib/。
逻辑：ENTRY 缺省从 bin/ 第一个文件或 lib/ 第一个 .so 猜；校验 entry 形状 bin/*|lib/*；STAGE=mktemp -d + trap 清理；只拷 entry 指的文件 + 同目录版本链（OPENSSL_LIBS=1 时拷所有 .so/.so.*，否则只拷 $eb.*，且 case 过滤 *.so|*.so.*）；调 gen-component-meta.js；find -exec touch -h -t 198001010000.00 统一 mtime；zip -q -r -X；sha256 前 12 位进文件名；写 .sha256。
外部命令：node、find、touch、zip、sha256sum、mv、mkdir、basename、dirname、cp、mktemp。

### 13. scripts/recipes/gen-component-meta.js（75 行）
用途：生成 component-meta.json。输入：argv = id version entry outPath（4 个，全必需）。
输出：outPath（JSON indent=1）。
逻辑：读 component-sources.json 取 src；校验 entry 形状 ^(bin|lib)/；deps 通过 cp.execSync("bash scripts/toolchain/cache-key.sh deps-list " + JSON.stringify(id)) 取（B6：字符串拼命令）；form 由 entry 前缀决定；ABI 取 env.ABI 或 arm64-v8a，arch=ABI.split("-")[0]；写 schema/id/version/arch/entry/form/requires/essential/source(urls[0])/sourceSha256(+sameAs)。
外部命令：bash（cache-key.sh deps-list）。

### 14. scripts/recipes/package-component.sh（40 行）
用途：老的 component 打包入口（自实现另一套 zip 逻辑）。set -euo pipefail。
输入：$1=TOOL；env OUT(dist)、GITHUB_STEP_SUMMARY。
输出：dist/component-<t>-<ver>-<sha12>-android-arm64.zip、dist/SHA256SUMS。
逻辑：VER=$(cat dist/<t>.version || echo unknown)；ENTRY=read-component-entry.sh；cp -a dist/. "$STAGE/"；删 *.version/SHA256SUMS/*.zip；调 gen-component-meta.js（B1：5 参 bug）；touch 19800101；zip -q -r -X -y（-y 存符号链接）；sha256sum >> SHA256SUMS（追加累积）；写 GITHUB_STEP_SUMMARY。
外部命令：bash、node、find、zip、sha256sum、mv、mkdir、cp、rm。

### 15. scripts/recipes/build-component-cmake.sh（204 行）
用途：交叉编 CMake → dist/bin/cmake + dist/share/cmake。set -euo pipefail。
输入：CC（必需）、CXX($TC/aarch64-linux-android${API}-clang++)、LLVM_STRIP/READELF、ABI、API、JOBS、OUT。
输出：dist/bin/cmake、dist/share/cmake/、dist/cmake.version、work/cmake/*.log。
逻辑：fetch-pinned --src-version cmake；源码树复用（$SRC 存在则跳过下载）；从 Source/CMakeVersion.cmake 三段拼版本与钉值比对；打 patches/cmake-cmlibuv-no-cpumask-on-android.patch（--dry-run 三态判别：能干净应用/反向能干净应用=已打过/都失败=真打不上，附 libuv 只认 __linux__ 而 Bionic 无 cpu_set_t 的详尽取证）；拷 patches/cmake-cmlibarchive-contrib/android_lf.h 并用 SHA256 校验；宿主 cmake 交叉编（NDK android.toolchain.cmake，因 bootstrap 的 cmake_try_run 会在 x86_64 上 Exec format error）；cmake --build --target cmake；cmake --install；file 判架构；llvm-strip --strip-unneeded；check-elf-deps.sh；16KB LOAD 对齐检查；校验 share/cmake*/Modules 存在（否则是空壳）；cp 到 OUT/bin + OUT/share/cmake。
外部命令：bash、awk、node、patch、sha256sum、cmake、file、stat、find、grep、tail、ls、printf、rm、cp、mkdir、du、sed。

### 16. scripts/recipes/build-component-git.sh（200 行）
用途：交叉编 git → dist/bin/git + libexec/git-core。set -euo pipefail。
输入：CC（必需，退出码 1 若不设）；OUT；LLVM_STRIP。
输出：dist/bin/git、dist/libexec/git-core/、dist/git.version、dist/link-farm.txt。
逻辑：fetch-pinned --pin git --version-file；tar xJf；ANDROID_API 硬编码 35（D2）；打 8 个 termux 补丁（config.mak.uname/run-command.c/disable-fdsan/disable_daemon_syslog/compat-posix.h/config.c/help.c/tempfile.c.patch）；删 PROGRAM_OBJS 里的 imap-send.o（找不到就 exit 1）；从 work/{zlib,openssl,curl}/out 取 include/lib（硬编码相对路径）；make + make install（CURL_LIBCURL/OPENSSL_LIBSSL/CPPFLAGS/LDFLAGS 指向三个 prefix，NO_RUST=1 RUNTIME_PREFIX=1 等）；strip bin/git 与 git-core/*；链接农场瘦身：同 inode 或同 size 的 git-core 文件删掉并在 link-farm.txt 记 ../../bin/git；件树体积 >60 MiB 判红（只量 bin+libexec，避免把 dist/ 里的 zip 算进去）。
外部命令：bash、node、tar、grep、sed、patch、make、stat、readlink、du、ls、rm、printf、find、sort、basename、cp、mkdir。

### 17. scripts/recipes/build-component-llvmtoolchain.sh（269 行）
用途：交叉编 LLVM clang/lld → dist/llvmtoolchain/bin。set -euo pipefail。
输入：CC（宿主 x86_64 clang，用于编 LLVM）、LLVM_AR/RANLIB/STRIP/READELF、CMAKE、NINJA、ABI、API、JOBS、OUT。
输出：dist/llvmtoolchain/bin/（clang clang++ ld.lld llvm-ar llvm-nm llvm-strip llvm-objdump llvm-readobj）、dist/llvmtoolchain/package.json、dist/llvmtoolchain.version。
逻辑：先跑 verify-ndk-llvm.sh；比较 fetch-pinned --llvm 与 --src-version llvm 的 major 是否同线（否则 die）；先编宿主 tblgen（env -u CC -u CXX -u CMAKE_TOOLCHAIN_FILE -u ANDROID_NDK*，-DCMAKE_C_COMPILER=$(command -v cc)，LLVM_TARGETS_TO_BUILD=AArch64），校验 CMakeCache.txt 的 CMAKE_C_COMPILER 非 aarch64/android，拷 llvm-tblgen/llvm-min-tblgen/clang-tblgen 到 native-tools；再交叉编（-DLLVM_NATIVE_TOOL_DIR/-DLLVM_TABLEGEN/-DLLVM_USE_HOST_TOOLS=OFF，NDK android.toolchain.cmake，-DLLVM_ENABLE_ZLIB=OFF 等）；--install；校验 8 个必需 bin 在位；形态自检（aarch64 + 16KB LOAD 对齐 + PT_INTERP=/system/bin/linker64）；cp bin 到 TOOLDIR；写 package.json 别名映射（node -e 扫 TOOLDIR 所有文件，bin[n]="bin/"+n，删掉本名键）。
外部命令：bash、node、cmake、ninja、file、stat、awk、grep、tail、ls、cp、rm、mkdir、env、command、sed。

### 18. scripts/recipes/build-component-make.sh（108 行）
用途：交叉编 GNU make。set -euo pipefail。
逻辑：fetch-pinned --src-version make；configure.ac 的 AC_INIT 版本比对；configure --host=aarch64 --disable-shared --enable-static --without-guile --disable-posix-spawn；make；cp BUILD/make 到 OUT/bin；file 架构；llvm-strip；check-elf-deps.sh；16KB 对齐。
外部命令：bash、node、sed、awk、make、file、stat、cp、chmod、grep、tail、ls、printf、rm。

### 19. scripts/recipes/build-component-node.sh（112 行）
用途：把已编译的 libnode.so 落成组件（不编译）。set -euo pipefail。
输入：NODE_VERSION（默认读 node-versions.json default）、ABI（读 abi 键）、CC（找 llvm-readelf 用）、OUT。
输出：dist/bin/node、dist/node.version、work/libnode.so、work/libnode.sha256。
逻辑：从 5 个候选路径找 libnode.so（work/、dist/、dist/node-runtime/、dist/node/、jniLibs/arm64-v8a/）；挪到 work/libnode.so 并删 dist/node-runtime、dist/node；sha256 存档；写 dist/node.version；cp 成 dist/bin/node + chmod 0755；check_elf（llvm-readelf -h 或 file -b grep ELF 64-bit LSB arm64）；Machine；PT_INTERP 必须是 */linker64|*/linker|*/dynlink*；DT_NEEDED 含 libc++_shared.so 时只提示（由 nativeLibraryDir + LD_LIBRARY_PATH 供给）；size >= 50000000。
外部命令：bash、node、sha256sum、stat、cp、chmod、file、ls、printf、mv、rm、grep、sed。

### 20. scripts/recipes/build-component-npm.sh（76 行）
用途：取 npm tarball → dist/。set -euo pipefail。
硬编码钉值：TARBALL_SHA512_B64（88 字 base64）、ENTRY_SHA256（bin/npm-cli.js 的 sha256）、ENTRY_SIZE=54（D5：明显错误）。
输入：NPM_VERSION（read-node-versions.sh npm）、OUT。
输出：dist/（整个 npm 树）、dist/npm.version、work/npm-<v>.tgz。
逻辑：校验两个钉值自身形状（88 字 + == 结尾；64 hex）；curl -fsSL 取 tarball；openssl dgst -sha512 -binary | openssl base64 -A 与钉值比对；tar 解开 --strip-components=1；删 bin/npm、bin/npx 与 *.cmd/*.ps1；校验 npm-cli.js 的 sha256 + size + 前二字节 #!；cp -a work/npm/. dist/；chmod 0755 三个 bin 文件。
外部命令：bash、curl、openssl、tar、sha256sum、stat、head、find、chmod、cp、du、printf。

### 21. scripts/recipes/build-component-pkg-config.sh（109 行）
用途：交叉编 pkgconf → dist/bin/pkg-config。set -euo pipefail。
输入：CC、LLVM_AR/RANLIB/STRIP/READELF、API、JOBS、OUT。
输出：dist/bin/pkg-config、dist/pkg-config.version。
逻辑：fetch-pinned --src-version pkgconf；configure.ac 版本比对；configure --disable-shared --enable-static --disable-dlopen*；make + make install；bin 下找 pkg-config 或 pkgconf；架构/strip/check-elf-deps/16KB。
外部命令：bash、node、sed、make、file、stat、cp、chmod、grep、tail、ls、printf、rm。

### 22. scripts/recipes/build-component-pnpm.sh（71 行）
用途：取 @pnpm/exe.android-arm64 预编 ELF。set -euo pipefail。
硬编码：PNPM_VERSION=12.7.0（D6：与表内 "10.x" 不符）、TARBALL_SHA512_B64、ELF_SHA256、ELF_SIZE=47033992。
输出：dist/bin/pnpm、dist/pnpm.version、work/pnpm-exe.tgz。
逻辑：校验钉值形状；curl -fsSL 取 tarball；sha512 比对；tar；找 package/pnpm；sha256 + size + 前四字节 ELF 魔数；cp 成 dist/bin/pnpm + chmod 0755。
外部命令：curl、openssl、tar、sha256sum、stat、head、od、chmod、cp、printf。

### 23. scripts/recipes/build-component-python3.sh（202 行）
用途：交叉编 CPython + 带标准库。set -euo pipefail。
输入：PY_SLOT（多版本槽位，SRC_KEY 变 python-<slot>）、PY_VERSION、BUILD_PYTHON（宿主 python，要求同 major.minor，否则 die）、CC、API、JOBS、OUT。
输出：dist/bin/python3、dist/python3.version、dist/lib/python<X.Y>/（标准库）。
逻辑：fetch-pinned --src-version python；Include/patchlevel.h 三段版本比对；configure --host=aarch64 --with-build-python --without-ensurepip + ac_cv_file__dev_ptmx=no/ptc=no/tty=no + PKG_CONFIG_LIBDIR=<空目录> 隔离宿主 pkg-config；make；找 python3.* 或 python 且必须是 ELF 魔数；size>1e6；架构；NEEDED 不得含 libc.so.6/libstdc++.so.6/libpthread.so.0（glibc 构建判红）；16KB；make install；拷 lib/python<X.Y> 到 OUT/<LIBROOT>/python<X.Y>；统计扩展模块数（0 个只提示）。
外部命令：bash、node、awk、grep、make、file、stat、head、od、find、cp、chmod、rm、sed、tail、ls、command。

### 24. scripts/recipes/build-component-sqlite3.sh（35 行）
用途：取 sqlite amalgamation → 直接编 shell.c+sqlite3.c。set -euo pipefail。
输入：CC、OUT。
输出：dist/bin/sqlite3、dist/sqlite3.version、work/sqlite.zip。
逻辑：fetch-pinned --pin sqlite --version-file；unzip；find sqlite-amalgamation-*；"$CC" -O2 -DNDEBUG -DSQLITE_THREADSAFE=1 -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_JSON1 -o "$OUT/bin/sqlite3" "$SRC/shell.c" "$SRC/sqlite3.c" -lm -ldl。
D7：全脚本无任何架构/ELF/16KB/依赖闭包判据。
外部命令：bash、unzip、find、stat、mkdir、rm。

### 25. scripts/recipes/build-native-busybox.sh（278 行）
用途：编静态 busybox → libbusybox.so + applet 软链。set -euo pipefail。
输入：BUSYBOX_VER（默认 1.36.1，与钉值表双向比对）、CC、API、ABI、JOBS、OUT。
输出：work/busybox/out/lib/libbusybox.so + applet 软链、dist/busybox.version、dist/component-busybox-*.zip。
逻辑：fetch-pinned --pin busybox；tar xjf；Makefile 三段版本比对；make defconfig；白名单式关所有 CONFIG_XXX=y 再只开 18 个 applet（TAR GZIP GUNZIP GREP SED AWK LS CP MV CAT MKDIR RM LN VI DF PS TRUE FALSE）；调 find-platform-h-android-branch.js --patch 给 platform.h 的 Android 分支打 API 分级补丁（修 duplicate symbol strchrnul/mempcpy）；set_conf STATIC y / PIE n；CONFIG_EXTRA_CFLAGS="-O2 -fPIC -D__ANDROID_API__=$API"；make oldconfig < <(answers)（自己产 400 个空行，不用 yes "" 以免 Broken pipe）；make（命令行给 CC/AR/NM/STRIP/OBJCOPY，绕开 CC=$(CROSS_COMPILE)gcc 与 CROSS_COMPILER_PREFIX 提问）；make install CONFIG_PREFIX；cp busybox 成 lib/libbusybox.so；为每个 applet 建 ln -sf libbusybox.so；size>500000；strip；架构；无 PT_DYNAMIC 判据；16KB 对齐；emit_piece busybox lib/libbusybox.so 500000。
外部命令：bash、node、awk、grep、sed、tar、make、file、stat、llvm-strip、llvm-readelf、cp、chmod、ln、ls、printf、find、rm、mkdir、touch。

### 26. scripts/toolchain/cache-key.sh（152 行）
用途：算件的固化标识。set -euo pipefail。
输入：子命令 key|tag|asset|deps-list|deps + 件名；env RUN_OS(Linux)/RUN_ARCH(X64)。
输出：key=uw-<tool>-<deps+ver>-ndk<ndk>-api35-<os>-<arch>；tag=<bucket>-<tool>；asset=<tool>-<safe>.tar.gz；deps-list=依赖件名。
逻辑：deps_for()（curl→openssl zlib；openssl→zlib；crypto→openssl；git→curl openssl zlib；python→openssl zlib；node→空；bash|ripgrep|busybox|jq|sqlite|npm|pnpm|llvm|make|cmake|pkgconf|zlib|libcxx|sysroot→空）；bucket_for()（base: bash/ripgrep/busybox/jq/curl/zlib/openssl/crypto；rt: node/python；tool: git/sqlite/npm/pnpm/llvm/make/cmake/pkgconf；apkonly: libcxx/sysroot）；依赖版本拼进 VER（fetch-pinned --src-version 逐个取）；git 额外拼 8 个 patch 的版本指纹（key 前 6 字符 + 版本前 8 字符）；node 用 read-node-versions.sh 的 default+abi；NDK 从 fetch-pinned --ndk；API 硬编码 35。
外部命令：bash、node、tr。

### 27. scripts/toolchain/cache-ndk.sh（60 行）
用途：装/缓存 NDK。set -euo pipefail。
输入：ANDROID_SDK_ROOT 或 ANDROID_HOME（必需，否则 exit 1）；NDK 版本从 fetch-pinned --ndk。
输出：$SDK/ndk/<ver>、$HOME/.cache/actions-setup-ndk。
逻辑：SDK/ndk/<ver> 在位则退出 0；~/.cache/actions-setup-ndk 命中则 mkdir + rm -rf + cp -a 还原；否则找 sdkmanager（三个候选路径 + command -v）；--licenses；装 ndk;<ver>（日志进 mktemp，失败打末 30 行）；校验目录；cp -a 进缓存。
外部命令：bash、awk、ls、cp、rm、mkdir、sdkmanager、tail、command、mktemp。

### 28. scripts/toolchain/collect-piece-diag.sh（80 行）
用途：件构建失败时写 Job Summary + RUNNER_TEMP/piece-diag-<id>.md。set -uo pipefail（无 -e）。
输入：$1=件名；env GITHUB_STEP_SUMMARY、RUNNER_TEMP(默认 /tmp)。
输出：${RUNNER_TEMP}/piece-diag-<id>.md（同时 tee 到 GITHUB_STEP_SUMMARY）。
逻辑：{ ... } | tee "$FILE" >> "$OUT" 2>/dev/null || true；列 bash/node 版本、CC、cache-key.sh key/tag/asset、work/<id> 与 dist/ 的 ls -la、所有 *.log 的 tail -25、dist/component-*.zip 的 stat + tar -tzf（D8：zip 包用 tar 恒失败）。
外部命令：bash、node、ls、stat、tail、tar、tee、mkdir、dirname。

### 29. scripts/toolchain/ensure-native-capabilities.sh（96 行）
用途：逐件编译 base 筐。set -uo pipefail（无 -e）。
输入：--only=<件名>（可多次，不带 .sh）。
输出：work/<id>/out/、dist/component-*.zip、jniLibs/<ABI>/liblobos*.so。
逻辑：无 --only 时先跑 build-kernel-compat.sh，再依次跑 build-piece-{zlib,openssl,curl,jq,bash,rg}.sh 与 build-native-busybox.sh；逐件 printf ::notice title=逐件::，失败累计 failed=1 最后统一 exit 1。crypto 不在此列（由 openssl 连带落位）。
B12：:93 调未定义的 die，且无 set -e → 垫片失败不阻断。
外部命令：bash、printf、echo。

### 30. scripts/toolchain/fetch-component-artifacts.sh（112 行）
用途：从 GH Actions artifacts 取 tool 筐的件。set -euo pipefail。
输入：REPO 或 GITHUB_REPOSITORY；GH_TOKEN 或 GITHUB_TOKEN（必需）；DIST(默认 dist)。
输出：$DIST/component-<t>-*-android-arm64.zip。
逻辑：list-bucket-components.js 出全部件；NATIVE=base/apkonly 筐的件（cache-key.sh tag 判定，跳过）；件名→workflow 文件名映射表（ripgrep→build-rg.yml、pkgconf→build-pkg-config.yml、sqlite→build-sqlite3.yml、python→build-python3.yml、llvm→build-llvmtoolchain.yml、jq→build-jq.yml、其余 build-<t>.yml）；校验 workflow 文件存在（否则 exit 1）；gh run list --status success --limit 5 --json databaseId；gh api 查 artifact 未过期；gh run download；compgen -G 找 zip（0 个=warning missing，>1 个=error）；汇总 got/missed/skipped；got=0 则 exit 1。
外部命令：node、bash、gh、compgen、echo、mkdir。

### 31. scripts/toolchain/fetch-pinned.sh（181 行）
用途：仓内唯一源码下载+校验入口。set -euo pipefail。
输入：--pin <键> <落点> [--version-file F] / --time-base / --ndk / --llvm / --src-version <键> / 位置参数 <落点> <sha256> <url...>。
输出：落点文件；或打印 time-base/ndk/llvm/src-version 值。
逻辑：每个子命令用 node -e 校验表内格子形状（sha256 必须 ^[0-9a-f]{64}$、version 非空、urls 非空数组；buildTimeEpoch 必须正整数且 *1000 < Date.now()；ndkVersion 必须 x.y.z；llvmVersion 可空但形态须为 20/20.1/20.1.8）；下载循环：只认 https://（file:// 明确拒绝）；curl -fsSL --max-time 900；sha256sum 与钉值比对，不符换下一条；全不合则 die。
B3：无 --source-date-epoch 子命令。B13：mapfile -t 不产尾空元素（已核查）。
外部命令：node、curl、sha256sum、mv、mkdir、printf、mktemp、basename、cut。

### 32. scripts/toolchain/fetch-prebuilt-pieces.sh（89 行）
用途：从 GH Release 取固化件包进 APK assets。set -euo pipefail。
输入：GITHUB_TOKEN（可选，只影响 API 限流）、GITHUB_REPOSITORY（默认 lobbowen/lob-os）。
输出：container/app/src/main/assets/pieces/<id>.zip。
逻辑：PIECES=(zlib openssl crypto curl jq bash ripgrep busybox)；每件算 tag/asset；curl api.github.com（--retry 3 --retry-delay 2，Accept: application/vnd.github+json）取 release → node 解析 browser_download_url → curl 下载；若 <asset>.sha256 存在则 sha256sum -c 校验（B15）；cp 成 assets/pieces/<id>.zip；missing>0 则 exit 1。
B14：ok 计数器从不递增。B16：$ASSETS 未校验是目录。
外部命令：bash、curl、node、mkdir、cp、rm、printf、sha256sum、stat。

### 33. scripts/toolchain/fetch-release-asset.sh（47 行）
用途：按精确名从 Release 取一个资产（不用 gh --pattern）。set -uo pipefail（无 -e）。
输入：TAG/ASSET/DEST；GITHUB_REPOSITORY、GITHUB_TOKEN。
输出：$DEST/$ASSET。
逻辑：curl api.github.com（--retry 3，Accept + User-Agent: lobos）→ node 解析 browser_download_url → curl -o；校验非空。
发现：无 sha256 校验；"${HDR[@]}" 空数组 + set -u 在 bash<4.4 会炸（D10）。
外部命令：curl、node、mkdir。

### 34. scripts/toolchain/fmt.sh（23 行）
用途：判包实际格式（看魔数不看扩展名）。定义 fmt_of()：head -c 4 | od -An -tx1；504b→zip、1f8b→gzip、否则 tar tf 试读 → tar / 未知。
D11：只有函数无可执行主线（被 source）。外部命令：head、od、tar。

### 35. scripts/toolchain/locate-ndk.sh（90 行）
用途：定位 NDK 并导出 CC 等到 GITHUB_ENV。set -euo pipefail。
输入：ANDROID_SDK_ROOT/ANDROID_HOME、ANDROID_NDK_LATEST_HOME、INSTALL_NDK。
输出：$GITHUB_ENV（CC/CXX/LLVM_AR/LLVM_RANLIB/LLVM_STRIP/LLVM_READELF）、$GITHUB_STEP_SUMMARY（CC）。
逻辑：fetch-pinned --ndk；SDK/ndk/<ver> 在位→用；否则 ANDROID_NDK_LATEST_HOME 的 source.properties Pkg.Revision 与钉值相等→用；否则 INSTALL_NDK=1 且 PATH 有 sdkmanager→yes | sdkmanager 装；否则 die（提示改钉值或开 INSTALL_NDK，并警告「不要只改钉值了事」）。TC=NDK/toolchains/llvm/prebuilt/linux-x86_64/bin（D12 写死）；CC=$TC/aarch64-linux-android35-clang（API 35 写死）；clang --version 探活；-print-target-triple/-dumpmachine 探目标，退化则编 .o 看 e_machine=0xB7；Pkg.Revision 与钉值比对。
外部命令：bash、awk、sdkmanager、yes、mktemp、tail、rm、command、printf、od。

### 36. scripts/toolchain/piece-key.sh（31 行）
用途：算 key/tag/asset 并 tee 到 $GITHUB_OUTPUT。set -euo pipefail。
输入：$1=件名、$2=work 目录（默认 pwd）。
输出：$GITHUB_OUTPUT（key/tag/asset）。
逻辑：run() 调 cache-key.sh 三个子命令；{ echo key=...; echo tag=...; echo asset=...; } | tee -a "$GITHUB_OUTPUT"；最后再 echo 一行 [key] ... 摘要。
D13：最后那行摘要也被 tee 进 GITHUB_OUTPUT（无 = 的行），污染格式。
外部命令：bash、tee。

### 37. scripts/toolchain/piece-workflow-name.sh（15 行）
用途：件名→workflow 名映射。set -euo pipefail。含 ripgrep→build-rg.yml、pkgconf→build-pkg-config.yml、python→build-python3.yml、sqlite→build-sqlite3.yml、crypto→build-openssl.yml 五条特例。D9：与 fetch-component-artifacts.sh 重复实现。外部命令：echo。

### 38. scripts/toolchain/reusable-asset.sh（104 行）
用途：判固化产物能否直接用（验内容不只验存在）。set -uo pipefail（无 -e）。
输入：<tag> <asset> [entry] [extra_files] 或 --probe-dir <目录> <asset> <entry> [extra]。
输出：exit 0 可复用 / 1 不可用。
逻辑：source fmt.sh；verify_local 判：非空、列表里有 component-meta.json、meta.entry == WANT_ENTRY、且列表里有 entry/extra 指的文件或软链（unzip -l 的 l 类型或 tar -tzvf 的 l 前缀，用 awk 判首字符 - 或 l）；远程模式 gh release download --pattern（D14）。
外部命令：gh、unzip、tar、node、grep、awk、sed、mktemp、basename、printf。

### 39. scripts/toolchain/build-kernel-compat.sh（107 行）
用途：编 4 个内核兼容垫片（非件，不进清单/不铺位/不 OTA）。set -euo pipefail。
输入：CC（空则子进程跑 locate-ndk.sh 读回 GITHUB_ENV）、ABI、NODE_INCLUDE_DIR。
输出：jniLibs/<ABI>/liblobosposix.so、liblobosptyprobe.so、librivospty.so、liblobosflock.so。
逻辑：编 posix（-shared，link-interpose.c + open-fallback.c + tmp-paths.c + exec-path.c + -ldl）、ptyprobe（-static）、ptysession（-static，输出名 librivospty.so）、flock（-shared -DNAPI_VERSION=9，需 NODE_INCLUDE_DIR，缺或编不过则不随包带）；每个 so_ok 校验最小字节；最后 chmod 644 所有 liblobos*.so/librivospty.so。
外部命令：bash、mkdir、stat、cp、find、chmod、ls、tail。

### 40. scripts/toolchain/build-node-android.sh（560 行）
用途：从源码交叉编 node → dist/libnode.so + jniLibs/libc++_shared.so。set -euo pipefail。
输入：$1=NODE_VERSION(默认 24.21.0)、ANDROID_NDK（${VAR:?} 必需）、ANDROID_API(默认 24，D15)、ARCH=arm64、NODE_OUT_DIR、NODE_BUILD_JOBS、CC_host/CXX_host/AR_host/LDFLAGS_host。
输出：$NODE_OUT_DIR/libnode.so（默认 dist/）、jniLibs/arm64-v8a/libc++_shared.so。
逻辑：git clone --depth 1 --branch v<V>（B17 无 pin）；python3 heredoc 打三个补丁（bionic backtrace 关 execinfo / V8 trap-handler 中和 + 语义自证（#if 0 && 前缀 + 预处理器探针）/ aligned_alloc→memalign + NDK clang --no-undefined 链接探针）；挑宿主编译器（逐个实测编译 C++20+atomics 探针，排除 NDK android 工具链，要求 file 报 x86-64）；./android-configure；校验 out/Makefile 的 CC.host/CXX.host 非 android 且能编 C++ 头；make -n 干跑展开并断言 -Wl,-rpath,\x27$$ORIGIN\x27 与 --enable-new-dtags 落在 node 本体那次链接（NODE_LINK_PATTERN）；修补 gyp zlib 目标 ARMV8_OS_ANDROID→LINUX（找不到则 FATAL）；按内存算并行度（cgroup memory.max 或 /proc/meminfo，3584MB/job + 2GB 余量，NODE_BUILD_JOBS 可覆盖）；后台进度循环（每 120s 数 .o）；make；cp out/Release/node；从 NDK sysroot 拷 libc++_shared.so；与 .github/native-assets.txt 双向对账；跑 verify-runtime-elf.sh 两次。
外部命令：git、python3、g++、file、make、grep、sed、awk、find、wc、nproc、date、sleep、kill、cp、chmod、sha256sum、ls、stat、bash、env、command、head、tail、tr、cat、rm。

### 41. scripts/registry/gen-component-keys.js（72 行）
用途：生成 ed25519 密钥对（公钥焊进 APK）。无 set -e（node）。
输入：--force、[OUT_DIR]（默认 <repo>/../_secrets）。
输出：container/app/src/main/assets/supply/component-public.pem（×2，PUB_SUPPLY 与 PUB_OTA 同路径，D16）、OUT_DIR/component-ed25519-private.pem（mode 0600 + chmodSync 双保险）。
逻辑：OUT_DIR 不存在则 exit 2；已有配对（公钥==私钥派生）则跳过；公钥在但无私钥则拒绝（孤儿防护，除非 --force）；crypto.generateKeyPairSync(\x27ed25519\x27)；自验 sign/verify；写公钥×2 + 私钥；打印 sha256(SPKI DER) 指纹；提示下一步用 publish-component-manifest.js 的第三位置参数。
外部命令：无（纯 node crypto）。

### 42. scripts/registry/list-bucket-components.js（62 行）
用途：从 cache-key.sh 的 bucket_for() 正则解析件名与筐。实测跑通，输出 19 个件。
输入：--buckets（带筐名输出）。
输出：stdout 件名（或 name<TAB>bucket）。
逻辑：正则 /bucket_for\(\)\s*\{[\s\S]*?\n?\}/ 抓函数体；split(\x27;;\x27) 逐分支；两正则匹配 echo "筐名"；重名检测（同名出现两次则 error）；非四筐名则 error。
B18：:34 + bad[2] + \x27"\x27 数字与字符串相加 → 报错乱码（仅错误路径）。
外部命令：无（纯 node）。

### 43. scripts/registry/pin-github-release.js（123 行）
用途：从 GitHub release 的 digest 字段取 sha256 写进钉值表。
输入：--repo --tag --key --match [--version] [--dry]。
输出：scripts/component-sources.json 的 sources.<key>。
逻辑：https.get api.github.com/repos/<repo>/releases/tags/<encodeURIComponent(tag)>（30s 超时，跟随 30x，User-Agent: lobos-pin-github-release，无 Authorization → 60 req/h 限流，D17）；--match 正则必须只命中一个资产；要求 asset.digest 是 ^sha256:[0-9a-f]{64}$，否则 die 并提示手工 curl + sha256sum；写 {version(默认 tag 去 llvmorg- 前缀), sha256, urls:[browser_download_url]}；--dry 只打印。
B19：只写三项，抹掉原项其它字段。B20：:121 提示 node scripts/registry/gen-native-assets.js（文件不存在）。
外部命令：https（node 内建）、fs。

### 44. scripts/registry/read-component-entry.sh（52 行）
用途：读件 entry。set -euo pipefail。
输入：$1=tool。
输出：stdout entry 字符串。
逻辑：优先 node 读 component-sources.json 的 sources.<tool>.entry（校验含 /、不以 / 开头、不含 ..）；退化 python3 读 **criteria** 键（B21：与 node 路径读的键不一致，恒失败）；两者都没有则 exit 1。
外部命令：node、python3。

### 45. scripts/registry/read-node-versions.sh（37 行）
用途：读 node-versions.json 的键。set -euo pipefail。
输入：$1=key。
输出：stdout 值（字符串原样，非字符串 JSON.stringify）。
逻辑：node 优先（键不存在则 exit 1），python 退化（print 值）。两条路径读同一键，无 B21 类问题。
外部命令：node、python3。

### 46. scripts/publish/inject-apk-keystore.sh（60 行）
用途：把 keystore 注入构建并导出锚点证书。set -euo pipefail。
输入：KS_B64(ANDROID_KEYSTORE_BASE64)、KS_PASS(ANDROID_KEYSTORE_PASSWORD)、KS_ALIAS(默认 lobos)、KS_KEYPASS(默认=KS_PASS)、$1=DEST(默认 keys)。
输出：keys/release.keystore、keys/release.cert；向 $GITHUB_ENV 写 LOBOS_KEYSTORE_PASSWORD/LOBOS_KEY_ALIAS/LOBOS_KEY_PASSWORD/LOBOS_APK_CERT_FILE。
逻辑：KS_B64 空则 exit 10（明确区分「没配」与「配坏」，附后果说明）；KS_PASS 空则 exit 2；umask 077 + base64 -d（解码失败/0 字节都 exit 2）；keytool -list 验 storepass（-storepass:env KS_PASS）；keytool -exportcert -rfc 验 alias/keypass（-keypass:env KS_KEYPASS）；校验导出的证书是 PEM；打印证书 fingerprint；口令只走 env 不进命令行（好）。
C4：keystore 落 keys/ 但不清理。外部命令：base64、keytool、mkdir、umask、printf、rm、command、echo。

### 47. scripts/publish/keygen-android-keystore.sh（80 行）
用途：生成 APK 签名 keystore。set -euo pipefail。
输入：$1=ALIAS(lobos)、$2=DAYS(10000)。
输出：keys/release.keystore(600)、keys/keystore.properties(600)。
逻辑：已存在则拒绝覆盖（防丢升级能力）；gen_pw = head -c 32 /dev/urandom | base64 | tr -d \x27\n=+/\x27 | cut -c1-40；keytool -genkeypair RSA4096 PKCS12（-storepass/-keypass 走命令行，C6）或 openssl req -x509 + pkcs12 -export 退路；写 props（含三个口令）；打印 SHA1/SHA256 指纹；提示 source <(sed \x27s/^/export /\x27 props)。
C5：口令只 240 bit 且 store/key 相同。外部命令：head、base64、tr、cut、keytool、openssl、chmod、cat、grep、mkdir。

### 48. scripts/publish/keygen.sh（23 行）
用途：生成 OTA ed25519 密钥对。set -euo pipefail。
输出：keys/ota-private.pem(600)、keys/ota-public.pem、container/app/src/main/assets/supply/component-public.pem（锚点）。
逻辑：私钥已存在则跳过（不重生、不报警）；openssl genpkey -algorithm ed25519；openssl pkey -pubout；cp 公钥当锚点。
C7：与 gen-component-keys.js 两套并行实现，写同一锚点文件；无孤儿防护。外部命令：openssl、chmod、mkdir、cp。

### 49. scripts/publish/make-release.sh（40 行）
用途：打印 node 发布三步指引（纯 echo，不执行任何动作）。set -euo pipefail。
输入：$1=VER（必需）。
输出：stdout 指引文本。
逻辑：校验 dist/node/bin/node 存在；打 sha256/size/version；cat heredoc 指引（package-component.sh node → publish-component-manifest.js dist → upload-qiniu.js）。
外部命令：bash、sha256sum、stat、cat。

### 50. scripts/publish/publish-component-manifest.js（204 行）
用途：签 C 层组件清单。无 set -e（node）。
输入：argv = [DIST(dist), OUT(release), KEY(keys/ota-private.pem), CHANNEL(canary)]、--project；env COMPONENT_BASE_URL(默认 https://lobcdn.zll.ink)、COMPONENT_PUBKEY、LOBOS_COMPONENT_REVISION（必需正整数，来自 tag component-<channel>-<revision>）。
输出：OUT/component-manifest.json + .sig。
逻辑：toolsFromDist 扫 dist/component-([a-z0-9-]+)-([0-9][^-]*)-([0-9a-f]{12})-android-arm64.zip；读整包算 sha256 与文件名 sha12 比对；entryOf 调 scripts/read-component-entry.sh（B23）；aliasesOf 读包内 package.json 并校验 bin[name]==declaredEntry + 别名指向件内存在的相对路径；assertNameUniqueness 全局名字冲突检测；criteria() 读 __dirname/component-sources.json（B22）；每件必须有 node 判据字符串（≥20 字）；manifest 含 schema/channel/revision/version(YYYY.MM.DD.run)/sequence/expiresEpochMs(30d)/tools；crypto.sign(null, body, key) ed25519 → 立刻用仓内公钥 verify 自检，不配对则 exit 1；写盘。
B24：version/sequence/expiresEpochMs 用 Date.now() → 非确定输出。C8：私钥 readFileSync 无存在性预检。
外部命令：bash、unzip、node。

### 51. scripts/publish/publish-native-manifest.js（244 行）
用途：签 native 层清单。无 set -e。
输入：argv=[ZIP, OUT(release), KEY(keys/ota-private.pem), CHANNEL(canary)]、--project；env NATIVE_BASE_URL、NATIVE_PUBKEY、LOBOS_COMPONENT_REVISION、ABI。
输出：OUT/native-manifest.json + .sig。
逻辑：parseRegistry 读 .github/native-capabilities.txt（<档位> <libName> <id>，取 libName/id）；capsIndex 同表（lib→id）；pinEntry 读 .github/native-capabilities-pin.json（B25 不存在 → pin=null，取最后一个键）；versionOf 先查 VERIFY(=component-sources.json) 的 sources.<id>.version，再 PIN_KEY 映射（crypto→openssl、libcxx→@ndkVersion 取 tab.ndkVersion）；zipHas 判 APK zip 里有没有 libName；写 manifest（schema/channel/revision/version(version.json 的 shell.versionName+versionCode)/sequence/expiresEpochMs/fingerprint/components）；ed25519 + 自验。
B26：VERIFY 与 SOURCES 两常量同值。B27：stripComments() 从未调用。
外部命令：unzip、node。

### 52. scripts/publish/upload-qiniu.js（161 行）
用途：上传件包到七牛并回读校验。无 set -e。
输入：argv=[local, key]、--cache-control=<s>；env QINIU_AK、QINIU_SK、QINIU_BUCKET、QINIU_UPLOAD_HOST(默认 https://up.qiniup.com)、QINIU_PUBLIC_BASE（必需，缺则 exit 2「没有回读锚就不许声称投放生效」）、QINIU_READBACK_ATTEMPTS(默认 5)。
输出：无本地文件；上传到 bucket:key。
逻辑：≤8MB 走表单上传（multipart，4xx 不重试直接 exit 1，3 次指数退避 3s/6s/12s）；>8MB 走分片（4MB/块，mkblk 3 次重试，host 可按响应切换，mkfile 3 次重试）；token = ak + \x27:\x27 + B64URL(HMAC-SHA1(B64URL(policy))) + \x27:\x27 + B64URL(policy)，policy 含 scope= bucket:key、deadline=now+3600、insertOnly=0；verifyPublic 回读 PUBLIC_BASE/<key>（encodeURIComponent 每段）+ ?t=Date.now()，最多 5 次（5s/10s/15s/20s 退避），全量 sha256 比对，不符 ::error title=投放未生效 + exit 1。
C9：SK 只进 HMAC 不进日志，处理正确。C10：回读全量进内存（buf + got 双份）。
外部命令：fetch（node 内建）、crypto。无 shell 命令。

### 53. scripts/verify/check-component-manifest-drift.js（167 行）
用途：比对仓内投影与线上清单。无 set -e。
输入：argv=[LOCAL, CHANNEL(canary)]、--immutable；env COMPONENT_ONLINE_FILE；锚点 container/app/src/main/assets/supply/channel.json（manifestName/baseUrl）。
输出：exit 0 一致 / 1 不一致。
逻辑：project() 取 provider/version/url/sha256/entry/aliases(排序后 name→entry join)/判据(verify.node)/判据形状(verify.criterion)；--immutable 模式额外判 revision 单调（localRevision > onlineRevision）与同版本 sha256 不变（线上 >= REPRODUCIBLE_BASELINE_REVISION=6 时严格比，否则说明「那一代件带着重造不出的字节」并跳过）；线上 404 → 非 immutable 判红、immutable 走 firstPublish；https.get 15s 超时；逐格 diff；线上有而仓内不声明也报。
D18：project() 键名中英混用；「判据形状」在 undefined 时输出字符串 "undefined"，与仓内投影相碰相等，脆弱。
外部命令：https、fs。

### 54. scripts/verify/check-elf-deps.sh（179 行）
用途：判 ELF 依赖闭包。set -euo pipefail。
输入：$1=ELF、$2=label；env LLVM_READELF、CC、ANDROID_NDK*、APK_JNI_DIR、ROOT。
输出：exit 0 闭环 / 1 断闭包。
逻辑：找 readelf（LLVM_READELF → $(dirname $CC)/llvm-readelf → PATH llvm-readelf/readelf → NDK glob）；读 native-deps.txt（20 项白名单）；APK 基础库从 jniLibs 目录实测（APK_JNI_DIR 或 ROOT 反推，不再硬编码名单，注释说明 libz.so 同名冲突已修；目录不在则明确说明少判一类）；无 .dynamic 段则 exit 0（静态）；解析 NEEDED/RUNPATH/RPATH；chain_head 把 libz.so.1 归到 libz.so；三类判定（系统/APK 基础库/件自己的 lib/）；版本化命名且两侧都无 → NO_JUDGE 不判红（tool 筐 workflow 不编 base 件）；同目录依赖必须有含 $ORIGIN 的 RUNPATH，否则 die（RPATH 存在也 die，bionic 忽略 DT_RPATH）。
B28/B29 已核查。D19：SYSTEM_LIBS 首尾空格 + case 匹配正确。
外部命令：readelf、grep、sed、tr、ls、basename、dirname、awk、printf、wc、command。

### 55. scripts/verify/native-deps.txt（40 行）
用途：bionic 系统库白名单（check-elf-deps.sh 与 verify-runtime-elf.sh 共用同一份）。20 项：libc.so libm.so libdl.so liblog.so libandroid.so libEGL.so libGLESv1_CM.so libGLESv2.so libGLESv3.so libOpenSLES.so libOpenMAXAL.so libvulkan.so libaaudio.so libcamera2ndk.so libmediandk.so libnativewindow.so libstdc++.so libsync.so libc++.so。
D20：头注释写「19 项」实际 20 项。libz.so 不在内（它与 bionic 同名但是我们的件，归 APK 基础库类）。

### 56. scripts/verify/check-no-comments.sh（40 行）
用途：Kotlin 零注释门禁。set -uo pipefail（无 -e）。
输入：无（固定扫 container/app/src/main/java）。
输出：exit 0 零注释 / 1 有注释。
逻辑：find *.kt | sort；grep -cE \x27^[[:space:]]*(//|/\*|\*|\*/)\x27 计数；BAD 累加；打前 3 行。
B30：grep -c 无匹配返回 1 打印 0，|| true 兜底，行为正确；文件不存在时 n 为空会误判。
外部命令：grep、find、sed、head。

### 57. scripts/verify/check-workflow-shell.sh（67 行）
用途：把每个 workflow 的 run: 块抠出来 bash -n。set -uo pipefail（无 -e）。
输入：无（固定扫 .github/workflows）。
输出：exit 0 全合法 / 1 有语法错。
逻辑：node + js-yaml 解析 *.yml；每步 run 写到 $WORK/<key>.sh；TSV 输出 key/file/tag/引号奇偶（奇数双引号直接判错）；bash -n 逐块。
B31：js-yaml 缺失 → 空尺子假绿。外部命令：node、bash、mktemp、rm。

### 58. scripts/verify/find-platform-h-android-branch.js（148 行）
用途：算 platform.h 的 Android 分支区间并打 API 分级补丁。无 set -e。
输入：$2=platform.h 路径、--patch。
输出：stdout "begin,end"（非 patch 模式）或改文件（patch 模式）。
逻辑：栈式配对 #if/#endif 得所有区间；按 (e-b) 升序（内层优先）；判据是分支头写着 #if defined(ANDROID) || defined(__ANDROID__)（不是「区间里含 undef」）；取含 # undef HAVE_STRCHRNUL 或 # undef HAVE_MEMPCPY 的那一处；已打过（含 TIERED_MARK 注释）则幂等跳过 exit 0；--patch 时把这两行包进 # if __ANDROID_API__ < 21，保留 HAVE_STRVERSCMP/HAVE_WAIT3 的 undef（bionic 确实没有）；替换后必须至少命中 1 次 STRCHRNUL，否则 notice + exit 0（不改文件）。
B32：依赖上游 platform.h 结构，未实跑。外部命令：无（纯 node）。

### 59. scripts/verify/meta-field.js（27 行）
用途：从 component-meta.json 读一个字段。无 set -e。
输入：argv = <meta文件> <字段名>。
输出：stdout 字段值（null/undefined 输出空串）。
退出码：0=取到、1=读不出/坏 JSON/字段不存在、2=用法错。外部命令：无（纯 node）。

### 60. scripts/verify/pick.sh（79 行）
用途：find 多命中时按内容判歧义。set -uo pipefail（无 -e）。
输入：<标签> [--last] [--allow-empty] <find 参数…>。
输出：stdout 选中的文件路径。
逻辑：find 2>err；零命中 + allow_empty → warning exit 0，否则 error exit 1；多命中时对前两个不同摘要判「内容是否同一」；strict 模式多命中且内容不同 → error exit 1；--last 按摘要去重再 sort -k3,3V | tail -1（版本排序取末位）；否则 head -1。
B33/C11 已核查。外部命令：find、mktemp、sha256sum、stat、cut、sort、sed、head、tail、rm、printf。

### 61. scripts/verify/verify-apk-native.sh（267 行）
用途：审计 APK 原生件在位。set -euo pipefail。
输入：$1=APK、$2=ABI(或 env ABI)、$3=--report。
输出：exit 0 通过 / 1 缺项；--report 只列事实不判红。
逻辑：unzip/zipinfo 必须存在；zipinfo -1 列条目（Empty zipfile 特判）；has_exact/has_prefix/count_prefix；查 assets/kernel（必须为空，ADR-0005 Program 不随 APK 分发）；查 assets/npm/（必须为空，npm 走 C 层清单）；native-assets.txt 逐资产查 lib/<ABI>/<a>；adb-client 源码 vs APK 件数一致（0 个走软降级 note_degraded）；预装件包（assets/pieces/*.zip）逐个解包验 component-meta.json 的 id/version/entry + 与钉值表 entry 一致 + entry 指的文件在；件包数 vs 钉值表 8 件；压缩方式报告（unzip -v）。
B34/B35/C12 已核查。外部命令：unzip、zipinfo、node、bash、stat、grep、printf、mktemp、rm、mkdir、awk。

### 62. scripts/verify/verify-apk-signing.sh（115 行）
用途：核验 APK 签名身份。set -euo pipefail。
输入：$1=APK、--cert <release.cert>、--require-stable；env ANDROID_HOME。
输出：exit 0 放行 / 1 判红。
逻辑：pick.sh --allow-empty --last 找 apksigner（find ANDROID_HOME/build-tools -name apksigner），退化 command -v；apksigner verify --print-certs；fp_from 解析 SHA-256 指纹（小写去冒号，取 64 hex token）；DN 含 "android debug" → IS_DEBUG；有锚点则 keytool -printcert（退化 openssl x509 -fingerprint -sha256）取锚点指纹并与 APK 比对，不等判红；--require-stable + debug → 判红；无锚点 + 非 debug → warning「只证明不是 debug，没证明是哪把 key」。
发现：「无从核验即不放行」执行最严格的一处。外部命令：bash、keytool、openssl、apksigner、command、pick.sh。

### 63. scripts/verify/verify-component-artifact.sh（104 行）
用途：审件产物形状。set -euo pipefail。
输入：$1=TOOL；env LLVM_READELF。
输出：exit 0 形状合规 / 1 不合规。
逻辑：read-component-entry.sh 取 ENTRY；judge_shape(rel,label)：ELF 魔数 → 架构（file -b 三种措辞都收）→ 静态件只记 STATIC_LIST 不判红（注释论证 LD_PRELOAD 四 hook 拿不到，属已知形态差别）→ LLVM_READELF 在位则跑 check-elf-deps.sh；非 ELF 则要求 shebang 且必须是 #!/usr/bin/env 、#!/usr/bin/ 、#!/bin/ 三种形态之一；size；再读 dist/package.json 的 bin 映射逐别名 judge_shape。
B36：别名判据路径 dist/bin/<rel> 与 llvmtoolchain 实际落位不符。外部命令：bash、node、file、stat、head、od、grep、readelf（经 check-elf-deps）。

### 64. scripts/verify/verify-component-build-date.sh（23 行）
用途：验 openssl 的 buildinf.h 日期钉在 buildTimeEpoch。set -euo pipefail。
输入：$1=openssl 源码目录。
输出：exit 0 钉住 / 1 没钉住或找不到。
逻辑：fetch-pinned --time-base；date -u -d "@$EPOCH" \x27+%a %b %e %H:%M:%S %Y\x27 拼期望串；find buildinf.h（找不到判红「本判据成了空尺子」）；grep \x27built on:\x27；grep -qF "$EXPECT"。
B37：与 build-piece-openssl.sh:39-44 结论相反，恒判红。外部命令：bash、find、grep、date、printf。

### 65. scripts/verify/verify-ndk-llvm.sh（119 行）
用途：核 NDK 版本与内置 LLVM 同源。set -euo pipefail。
输入：CC（反推 NDK）、ANDROID_NDK_LATEST_HOME/HOME/ROOT、ANDROID_HOME。
输出：exit 0 同源 / 1 不同源 / 2 同源未坐实。
逻辑：NDK = $(dirname $CC)/../../../../.. 或 ANDROID_NDK_* 或 ANDROID_HOME/ndk/* 按 sort -V 取末位；Pkg.Revision 与 fetch-pinned --ndk 比对；找 toolchains/llvm/prebuilt/*/bin/clang；--version 取版本串与修订号（clang-r<6+hex>）；从 AndroidVersion.txt/clang_source_info.md 取语义版本（多路径回退，最后 resource dir 名）；与 fetch-pinned --llvm 比 major；同 major 但来源是 clang --version（无 clang_source_info.md）→ warning + exit 2「同源尚未坐实」。
D22：BRE 正则 [ (] 正确。外部命令：bash、awk、ls、grep、sed、sort、tail、command。

### 66. scripts/verify/verify-ota-anchor.sh（89 行）
用途：验 OTA 公钥锚点与私钥配对。set -euo pipefail。
输入：--anchor F（默认 container/app/src/main/assets/supply/component-public.pem）、--private F；env OPENSSL。
输出：exit 0 锚点有效且配对 / 1 无效或不配对 / 2 无从核验。
逻辑：openssl pkey -pubin -pubout 规范化（空则判红「此前只打 ::warning:: 就继续出包，等于把坏锚点焊进 APK」）；算法必须是 Ed25519（焊 RSA 进去所有包 signature-invalid）；sha256(DER) 指纹；给了 --private 则派生公钥并与锚点逐字比对（规范化后），指纹不等则判红「OTA 静默死亡，而 CI 全绿」。
发现：判据严格、错误路径清晰。外部命令：openssl、grep、awk、command、printf。

### 67. scripts/verify/verify-runtime-elf.sh（327 行）
用途：审 jniLibs 原生产物形态。set -euo pipefail。
输入：[--deps F] [--manifest F] [--caps F] <目录>；env READELF、ANDROID_NDK。
输出：exit 0 形态正确 / 1 有不合格。
逻辑：readelf（PATH → NDK glob，须 --version 可执行）；native-deps.txt 白名单；.github/native-assets.txt 的「依赖库」「可执行资产本体」两段（sed -n 按区间标题切）；.github/native-capabilities.txt 的 soft 档位（缺失则 exit 2「soft 件的降级语义无从判定」）；ls | sed -n \x27/\.so\(\.[0-9][0-9.]*\)\{0,1\}$/p\x27 收全部共享库形态（含 .so.N）；逐文件判：Machine(AArch64/aarch64/arm64/ARM64 四措辞)、LOAD 段 16KB 对齐（0x 前缀，非十六进制记「取数取错列」）、PT_INTERP=/system/bin/linker64、PT_DYNAMIC 在否（清单说它是依赖库却没有 → 判红）、DT_NEEDED 闭环（系统白名单/BUNDLED）、随包依赖解析路径（RUNPATH=$ORIGIN 更好，否则靠 usr/lib 全局软链 + LD_LIBRARY_PATH，注释引用 PrefixProvisioner.kt 第 70~90 行与 RuntimeEnvironment.kt 第 56/96 行）；soft 档位件不判红只降级 warning。
B38：for f in "$DIR"/*.so 漏版本链中间层。B39/D23 已核查。外部命令：readelf、awk、sed、grep、ls、basename、printf、wc、command。

## 二、Pipeline Map

### A. 源码钉值（唯一真相）
scripts/component-sources.json（version/sha256/urls/entry/form，23 项 + buildTimeEpoch + ndkVersion + llvmVersion）
→ scripts/toolchain/fetch-pinned.sh（--pin/--src-version/--time-base/--ndk/--llvm）是唯一下载 + sha256 校验入口（只认 https，逐字节比对，全不合则拒绝编译）
→ scripts/registry/pin-github-release.js 写回该表（从 GitHub release 的 digest 字段，未下载）
→ scripts/node-versions.json → scripts/registry/read-node-versions.sh

### B. 分类与键（cache-key.sh 是唯一分类真相）
deps_for()/bucket_for() → key=uw-<t>-<deps+ver>-ndk<v>-api35-Linux-X64、tag=<bucket>-<t>、asset=<t>-<safe>.tar.gz
→ piece-key.sh 写 GITHUB_OUTPUT（key/tag/asset）；piece-workflow-name.sh 件名→workflow
→ list-bucket-components.js（正则解析 bucket_for，实测输出 19 件）供 fetch-component-artifacts.sh 用

### C. 件构建（base 筐：随 APK 内置）
ensure-native-capabilities.sh [--only=X]
→ build-kernel-compat.sh（4 个垫片 posix/ptyprobe/ptysession/flock，非件，直接进 jniLibs，由 kernel/compat/Compat.kt 定位）
→ 逐件：build-piece-{zlib,openssl,curl,jq,bash,rg}.sh + build-native-busybox.sh
  → 共性前置 source recipes/piece-env.sh <件名>（定位 NDK → check_so → emit_piece）
  → emit_piece → package-piece-zip.sh <id> <OUT_DIR> <ver> <entry>
     → gen-component-meta.js（调 cache-key.sh deps-list 取 requires）→ component-meta.json
     → zip + find touch 198001010000.00 + sha256 前 12 位命名
     → dist/component-<id>-<ver>-<sha12>-android-arm64.zip + .sha256
→ 固化发布 → GH Release tag=<bucket>-<id>（reusable-asset.sh 验 meta/entry/路径三件事）
→ fetch-prebuilt-pieces.sh 取回 → container/app/src/main/assets/pieces/<id>.zip（启动时 PreinstalledPieces 走 ProgramInstallPipeline 装，与 OTA 同路）

### D. 件构建（tool 筐：控制面板按清单下发）
build-component-{cmake,git,llvmtoolchain,make,node,npm,pkg-config,pnpm,python3,sqlite3}.sh
→ 各自产 dist/bin/<t> + dist/<t>.version（node 例外：dist/bin/node + work/libnode.so + dist/libnode.so；llvmtoolchain 例外：dist/llvmtoolchain/bin/ + dist/llvmtoolchain/package.json）
→ package-component.sh <t>（当前因 B1 的 5 参 bug 实际不可用）
→ fetch-component-artifacts.sh 从 GH Actions artifacts 取 → dist/

### E. 编译工具链
locate-ndk.sh（钉值 NDK 版本，写 CC/CXX/LLVM_* 到 GITHUB_ENV）· cache-ndk.sh（装/缓存 NDK 到 ~/.cache/actions-setup-ndk）
build-node-android.sh（git clone node + 3 个 python3 补丁 + android-configure + make -n 判据 + 内存限流 + make）→ dist/libnode.so + jniLibs/libc++_shared.so

### F. 发布
publish-component-manifest.js → release/component-manifest.json + .sig（ed25519，当前恒失败 B22/B23）
publish-native-manifest.js → release/native-manifest.json + .sig（pin 文件缺失 → OTA 通道关闭 B25）
upload-qiniu.js → 七牛分片/表单上传 + 回读全量 sha256 校验
make-release.sh（纯指引，不执行）
密钥链：keygen.sh（openssl ed25519）/ gen-component-keys.js（node crypto，双写同一公钥路径）/ keygen-android-keystore.sh（RSA4096 PKCS12）→ inject-apk-keystore.sh（-storepass:env 注入 + keytool 导出锚点证书）

### G. 验证链
构建期：check-elf-deps.sh（依赖闭包，native-deps.txt 白名单）· verify-component-artifact.sh（件形状）· verify-ndk-llvm.sh（NDK/LLVM 同源）· verify-component-build-date.sh（恒红 B37）· verify-runtime-elf.sh（jniLibs 形态）
发布期：verify-ota-anchor.sh（钥配对 + 算法）· check-component-manifest-drift.js（仓内 vs 线上逐格 + revision 单调 + 同版本同字节）· verify-apk-signing.sh（签名身份 + 锚点指纹）
APK 期：verify-apk-native.sh（在位审计：assets/kernel 必须空、assets/npm 必须空、native-assets 逐条、pieces 包逐个验 meta）
卫生：check-no-comments.sh（Kotlin 零注释）· check-workflow-shell.sh（空尺子 B31）· pick.sh（find 消歧）· fmt.sh（格式探测）· collect-piece-diag.sh（取证，tar 用法失效 D8）

## 三、Findings

### A. 正确性 bug（按严重度）
B1 package-component.sh:22 传 5 参给只收 4 参的 gen-component-meta.js（const [, , id, version, entry, outPath] = process.argv）。实测：第 4 参 $META_SRC(=scripts/component-verify.json) 被当成 outPath，meta 写进 component-verify.json 覆盖构建期表；$STAGE/component-meta.json 不生成 → zip 无 meta。已实测复现。
B12 ensure-native-capabilities.sh:93 bash build-kernel-compat.sh || die ...，但本脚本没有定义 die()，且 set -e 未开（只有 -uo pipefail）→ die: command not found (127) 后脚本不中止，继续编译 7 个件。「垫片编不出来」判据实际不阻断。已实测确认无 die 定义。
B22 publish-component-manifest.js:19 VERIFY = path.join(__dirname, \x27component-sources.json\x27) → scripts/publish/component-sources.json 不存在 → criteria() 抛 ENOENT → 脚本恒失败。已实测复现 throw。
B23 publish-component-manifest.js:23 entryOf 调 path.join(ROOT, \x27scripts\x27, \x27read-component-entry.sh\x27)，实际在 scripts/registry/ → ENOENT 恒失败。
B37 verify-component-build-date.sh:12 find buildinf.h，OpenSSL 3.x 已不生成该文件；build-piece-openssl.sh:39-44 明确说这一判据已成空尺子并改判 opensslv.h。两脚本对同一事实结论相反 → 本脚本恒判红。
B31 check-workflow-shell.sh:23 require(\x27js-yaml\x27)，仓库无 package.json/node_modules（实测 node -e require(\x27js-yaml\x27) → rc=1）→ node 报错被 2>/dev/null 吞 → list.tsv 空 → TOTAL=0/BAD=0 → 打印 "[ok] 全部合法" 假绿，一个 run: 都没检查。
B3 build-piece-openssl.sh:18 调 fetch-pinned.sh --source-date-epoch，该子命令不存在（只有 --time-base）→ fallback echo 0 → SOURCE_DATE_EPOCH=0，OpenSSL mkbuildinf.pl 会用 0，不报错但钉值静默失效。
B4 build-piece-rg.sh:44 sha256sum "$WORK/liblobosrg.so" 路径错（产物在 OUT_DIR/lib/）→ RG_GOT 恒空 → 表内 sha256 非空时恒判红、为空时恒 warn。判据失效。
B8 build-component-llvmtoolchain.sh:261 写 dist/llvmtoolchain/package.json，而 package-component.sh 的 STAGE 是 cp -a dist/.（package.json 在 dist/llvmtoolchain/ 下，不在 STAGE 根）→ zip 内无 package.json → publish-component-manifest.js 的 aliasesOf 读不到 → llvmtoolchain 40+ 工具只有入口 clang 可达。
B19 pin-github-release.js:118 只写 {version,sha256,urls} 三项，抹掉原项所有其它字段（ripgrep 的 builtFrom/productName/installName、bash/busybox 的 installName、crypto 的 sameAs/note、node 的 prebuilt/note）。
B21 read-component-entry.sh:38 python 退化路径读 j.get(\x27criteria\x27)，node 路径读 .sources → python 路径恒失败（键不存在）。
B25 publish-native-manifest.js:28 读 .github/native-capabilities-pin.json，该文件不存在（实测）→ pin=null → 所有 native 件 source=\x27apk\x27、无 url/sha256 → OTA 通道实际关闭且不报错。
B11 cache-key.sh:148 asset 名 <t>-<safe>.tar.gz 与实际 .zip 资产名不符 → reusable-asset.sh:92 的 gh release download --pattern "$ASSET" 恒 miss。
B7 build-component-git.sh:52-53 在 cd work/git-src（:65）之前 grep Makefile → 查仓根（不存在）→ 两行诊断恒空，死代码。
B14 fetch-prebuilt-pieces.sh:48 ok 计数器从不递增 → 结尾「已取到 0 个」恒假。
B18 list-bucket-components.js:34 + bad[2] + \x27"\x27 数字与字符串相加 → 报错乱码（仅错误路径）。
B36 verify-component-artifact.sh:94-96 别名判据路径 dist/bin/<rel> 与 llvmtoolchain 实际落位 dist/llvmtoolchain/bin/<rel> 不符 → 恒判「缺产物」。
B2 piece-env.sh:141-148 emit_piece 的版本链拷贝：ls 守卫不匹配时（无版本链的件，如 entry=bin/* 的 bash/rg/jq）静默无操作；虽这类件本无版本链，但守卫失败被完全静默，无法区分「没有版本链」与「拷贝失败」。实测确认 glob 不匹配时 for 循环体一次都不执行且无任何输出。

### B. 未加引号 / set -e / pipefail
C1 7 个 build-piece-*.sh 与 piece-env.sh、collect-piece-diag.sh、ensure-native-capabilities.sh、fetch-release-asset.sh、fmt.sh、reusable-asset.sh、check-no-comments.sh、check-workflow-shell.sh、pick.sh 用 set -uo pipefail 无 -e（build-component-*.sh 与 verify/*.sh 基本都有 -e）。
C2 build-component-git.sh:117 make -j2 $MAKE_ARGS（字符串，路径含空格会裂）；build-piece-bash.sh:30,41 与 build-piece-rg.sh:11,33-35 的 $WORK/... 未加引号；verify-apk-native.sh:188 for p in $(...) 未加引号。
B6 gen-component-meta.js:43 execSync("bash .../cache-key.sh deps-list " + JSON.stringify(id)) —— 字符串拼命令（JSON.stringify 缓解注入，仍是反模式，应 execFileSync + 数组参数）。

### D. Checksum 验证缺口
B15 fetch-prebuilt-pieces.sh:63 只在 <asset>.sha256 恰好随 Release 上传时才校验，否则整包零校验进 APK。
B17 build-node-android.sh:18 git clone --depth 1 --branch v<V> 无 commit pin、无 tag 签名校验 → 同版本号实际可变（上游可强推 tag）。
B16 build-piece-rg.sh:11 rustup 安装脚本无校验；ripgrep 走 cargo/crates.io 源码无 sha256（component-sources.json 的 $comment-verify 已自认）。
B31 check-workflow-shell.sh 因 js-yaml 缺失变成零检查（空尺子）。
D14 reusable-asset.sh:92-93 用 gh release download --pattern，文件头注释自己论证了 --pattern 对含 + 的资产名不可靠。

### E. 密钥 / 口令处理
C5 keygen-android-keystore.sh:20 gen_pw = head -c 32 /dev/urandom | base64 | tr -d \x27\n=+/\x27 | cut -c1-40（≈240 bit，偏弱）；:23 STOREPASS 与 KEYPASS 相同，双层防护失效。
C6 keygen-android-keystore.sh:35-36,72 -storepass "$STOREPASS" -keypass "$KEYPASS" 口令进进程命令行（/proc/<pid>/cmdline 可读）；inject-apk-keystore.sh 用 -storepass:env/-keypass:env 是正确做法，此处没有。
C4 inject-apk-keystore.sh keystore 落 keys/（umask 077）但不清理，被当 artifact 上传即泄露。
C7 keygen.sh 与 gen-component-keys.js 是两套并行 ed25519 生成实现，写同一个锚点文件；keygen.sh 无「公钥在但无私钥」孤儿防护（gen-component-keys.js 有）。
D16 gen-component-keys.js:9-10 PUB_SUPPLY 与 PUB_OTA 同一路径 → :26 curSupply !== curOta 恒假，分叉检测是死代码。
C8 publish-component-manifest.js:185 / publish-native-manifest.js:224 私钥 readFileSync 无存在性预检（报 ENOENT 堆栈）。
C9 upload-qiniu.js：QINIU_SK 只进 HMAC 不进日志，token 走标准 UpToken header —— 处理正确。上传后回读 PUBLIC_BASE 全量 sha256 比对（最多 5 次），是本分区做得最好的一处完整性判据。
C10 upload-qiniu.js:141 res.arrayBuffer() 对 40MB+ 的 git 包全进内存，峰值 = 包大小×2（buf + got）。

### F. 不可复现构建
B17 node 源码 clone 无 pin。B3 SOURCE_DATE_EPOCH 回退 0。
D5 build-component-npm.sh:11 ENTRY_SIZE=54 —— npm-cli.js 不可能 54 字节，判据恒红（疑似占位符忘填）。
D6 build-component-pnpm.sh:8 PNPM_VERSION=12.7.0 与 component-sources.json 的 "10.x" 不符，且 URL 用硬编码版本 → 钉值表对 pnpm 无效。
D15 build-node-android.sh:6 ANDROID_API 默认 24，全仓其它脚本一律 35 → node 与其它件 API 不一致。
D12 locate-ndk.sh:48 宿主标签写死 linux-x86_64（macOS/arm64 runner 直接挂）。
D2 build-component-git.sh:29 ANDROID_API=35 硬编码不读 env。
B24 publish-component-manifest.js 的 version/sequence/expiresEpochMs 用 Date.now() → 清单非确定（签名保完整，drift 判据不含时间字段，故不冲突）。

### G. 死代码 / 硬编码 / spec 违背
D1 piece-env.sh:22 JNI 变量建了从未用；check_exe()(:104-108) 也无调用者。
D3 build-component-git.sh:150 link-farm.txt 生成后无消费者。
D7 build-component-sqlite3.sh（全 35 行）无任何架构/ELF/16KB/依赖闭包判据 —— 唯一不跑 check-elf-deps.sh 与 16KB 检查的件。
D8 collect-piece-diag.sh:75 tar -tzf "$p" 用于 zip 包 → 恒失败被 2>/dev/null 吞 → 取证失效。
D9 fetch-component-artifacts.sh:51-59 与 piece-workflow-name.sh 是两份件名→workflow 映射（crypto 一处 build-openssl.yml、一处会拼 build-crypto.yml；靠 crypto 在 NATIVE 跳过列表才没爆）。
D10 fetch-release-asset.sh:18 "${HDR[@]}" 空数组在 bash<4.4 + set -u 下会炸。
D13 piece-key.sh:32 最后的 echo "[key] ..." 与三个 output 行一起被 tee -a "$GITHUB_OUTPUT" 写进 GITHUB_OUTPUT → 污染格式（该行无 =）。
D17 pin-github-release.js:121 提示 node scripts/registry/gen-native-assets.js —— 文件不存在。
D20 native-deps.txt 头注释写「19 项」实际 20 项。
D11 fmt.sh 只有函数无可执行主线（作为库正确，但被 source 时无 set 选项保护）。
D18 check-component-manifest-drift.js 的 project() 键名中英混用；「判据形状: t.verify && t.verify.criterion」在 undefined 时输出字符串 "undefined"，与仓内投影的 undefined 相碰相等，脆弱。
D19 check-elf-deps.sh SYSTEM_LIBS 首尾空格 + case 匹配正确（已核查）。
B10 cache-key.sh:127 DEPS="build-apk-only" 分支永不可达。
B26 publish-native-manifest.js:25-26 VERIFY 与 SOURCES 两常量指向同一文件，冗余。
B27 publish-native-manifest.js:35 stripComments() 定义后从未调用。
B38 verify-runtime-elf.sh:119 for f in "$DIR"/*.so 只匹配 *.so，版本链中间层（libz.so.1）从不被逐文件判形态（SO_LIST/BUNDLED 收了，形态判据本身漏了）—— 若 libz.so.1 是坏 ELF 不判红。属覆盖缺口。

### H. 已核查为非 bug（记录备查）
B9 cache-key.sh deps_for/bucket_for 的 ) die ;; 在函数定义之后调用 die —— bash 执行时 die 已定义，安全。
B13 fetch-pinned.sh mapfile -t URLS <<< "$META" 不产生尾空元素，安全。
B28 check-elf-deps.sh [ x = y ] && continue 在 for 循环体内且非末条命令，不触发 set -e，安全。
B29 check-elf-deps.sh LOCAL_LIBS 只认 $(dirname $FILE)/lib —— form=exec 件（rg/jq/bash/sqlite/git）本不带库，走 NO_JUDGE 放行；覆盖不完整但不误判。
B30 check-no-comments.sh grep -c | head -1 || true 的 $n="0" 行为正确。
B33 pick.sh distinct 计算正确。
B34 verify-apk-native.sh $W/$p 路径拼接正确。
B39 verify-runtime-elf.sh note_fail 后每个分支都有 continue，配对正确。

### I. 无法确定
1. find-platform-h-android-branch.js 依赖 busybox 1.36.1 platform.h 实际结构，本环境无源码树，未实跑。
2. build-node-android.sh 的 make -n 判据依赖 node v24.21.0 gyp 行为，未实跑（需数小时编译）。
3. fetch-pinned.sh 多 URL 时 mapfile 行为按 bash 语义推理，未构造多 URL 表实跑。
4. check-elf-deps.sh 对 form=exec 件的 LOCAL_LIBS 覆盖未在真实产物上实跑（无 dist/）。
5. GH Actions 侧（GITHUB_OUTPUT/ENV 写入语义、gh CLI 可用性、artifact 保留期）未实跑。
6. .github/native-capabilities.txt、.github/native-assets.txt 实际内容未读（不在本分区）。
7. APK/jniLibs/Kotlin 侧（PrefixProvisioner.kt、RuntimeEnvironment.kt、Compat.kt、AdbClientRunner）未读，相关行号来自脚本注释。
8. 所有 build-*.sh 实际编译结果未验（无 NDK 环境）；「恒红/恒失败」均为代码路径推理 + 本机可复现部分（gen-component-meta.js 5 参、publish-component-manifest.js criteria throw 与 ENOENT 路径、js-yaml 缺失、list-bucket-components.js 解析、sha256sum -c 对裸哈希报 bad line、bucket_for 正则解析、emit_piece glob 静默无操作）。
