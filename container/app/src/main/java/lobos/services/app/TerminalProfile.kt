package lobos.services.app

import android.content.Context
import lobos.kernel.proc.ExecEntry
import lobos.services.reg.ProgramIndex
import lobos.services.supply.ProgramDir
import lobos.services.supply.PieceScan
import java.io.File

object TerminalProfile {

    private const val LINKER = "/system/bin/linker64"

    fun ensureBashrc(ctx: Context) {
        val out = File(ctx.filesDir, ".bashrc")
        val text = build(ctx)
        runCatching { out.writeText(text) }
        ensureNodePosixShim(ctx)
        ensureNodeAddonShims(ctx)
        ensureNodeGypEntry(ctx)
        ensureCompilerWrappers(ctx)
    }

    private fun ensureCompilerWrappers(ctx: Context) {
        val llvmv = runCatching {
            val cur = runCatching { ProgramDir(ctx, "llvm").currentVersion() }.getOrNull()
            cur ?: runCatching { ProgramDir(ctx, "llvm").installedVersions().singleOrNull() }.getOrNull()
        }.getOrNull() ?: return
        val home = File(File(ctx.filesDir, "opt/llvm"), llvmv).absolutePath
        val clang = File(File(home, "bin"), "clang-21").absolutePath
        val clangPp = File(File(home, "bin"), "clang++").absolutePath
        if (!File(clang).isFile) return
        val extra = "--target=aarch64-linux-android35 -resource-dir=$home/lib/clang/21 " +
            "--sysroot=$home/sysroot -B $home/bin"
        val ld = "$home/lib:${ctx.filesDir.absolutePath}/usr/lib"
        val binDir = File(ctx.filesDir, "usr/bin")
        for ((name, exe) in listOf("cc-wrap" to clang, "cxx-wrap" to clangPp)) {
            val text = "#!/system/bin/sh\nexport LD_LIBRARY_PATH=$ld\nexec $exe $extra \"$@\"\n"
            runCatching {
                val out = File(binDir, name)
                out.writeText(text)
                out.setExecutable(true)
            }
        }
    }

    private fun ensureNodeGypEntry(ctx: Context) {
        val nv = runCatching {
            val cur = runCatching { ProgramDir(ctx, "node").currentVersion() }.getOrNull()
            cur ?: runCatching { ProgramDir(ctx, "node").installedVersions().singleOrNull() }.getOrNull()
        }.getOrNull() ?: return
        val node = File(File(File(ctx.filesDir, "opt/node"), nv), "bin/node").absolutePath
        val gypPath = runCatching {
            val npmv = runCatching { ProgramDir(ctx, "npm").currentVersion() }.getOrNull()
                ?: runCatching { ProgramDir(ctx, "npm").installedVersions().singleOrNull() }.getOrNull()
            if (npmv == null) null
            else File(File(File(File(ctx.filesDir, "opt/npm"), npmv), "node_modules/node-gyp/bin"), "node-gyp.js").absolutePath
        }.getOrNull() ?: return
        val sysLib = File(ctx.filesDir, "usr/lib").absolutePath
        val nodeHome = File(File(ctx.filesDir, "opt/node"), nv).absolutePath
        val text = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("export LD_LIBRARY_PATH=$nodeHome:$sysLib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}")
            appendLine("exec $node $gypPath \"\$@\"")
        }
        runCatching {
            val out = File(File(ctx.filesDir, "usr/bin"), "node-gyp")
            out.writeText(text)
            out.setExecutable(true)
        }
    }

    private fun ensureNodePosixShim(ctx: Context) {
        val shim = File(ctx.filesDir, ".node-posix-shim.js")
        val text = buildString {
            appendLine("// Android 版 node 不提供 getuid/getgid/getgroups；isexe 需要它们才能判断可执行文件。")
            appendLine("// 值取任意数即可：交付件都有 o+x 位，isexe 的 mod&o 分支先命中，与真实 uid 无关。")
            appendLine("if (typeof process.getuid !== 'function') process.getuid = () => 1;")
            appendLine("if (typeof process.getgid !== 'function') process.getgid = () => 1;")
            appendLine("if (typeof process.getgroups !== 'function') process.getgroups = () => [1];")
            // LobOS 是 Linux 兼容环境：让 Node 生态按标准 linux-arm64 解析平台包，
            // 而不是走没有预编件的 android 分支（Claude Code 等据此选 native 包）。
            appendLine("try { if (process.platform === 'android') Object.defineProperty(process, 'platform', { value: 'linux', configurable: true }); } catch (e) {}")
        }
        runCatching { shim.writeText(text) }
    }

    /**
     * 通用 Node 原生 addon 适配：把 node-addon-require-builtin 的入口替换为
     * 纯 JS 替身。该 addon 只做 requireBuiltin(id)=require(id)（取 Node 内部模块），
     * 上游无 android 平台件、且 glibc .node 无法在 bionic node 里加载；配合
     * --expose-internals（见 exec-path.c / node wrapper）几行 JS 即可等价实现。
     * 零编译、对所有依赖该 addon 的 Node CLI（dsh 及同类）通用。
     * 每次终端启动执行、幂等：重装 dsh 后自动重新应用，不靠手工补丁。
     */
    private fun ensureNodeAddonShims(ctx: Context) {
        val shim = """
            'use strict';
            // LobOS JS fallback for node-addon-require-builtin.
            // Requires --expose-internals (added by LobOS node wrapper / exec hook).
            function requireBuiltin(id) { return require(id); }
            function isAllowedInternalId() { return true; }
            function getNativeBindingInfo() {
              return { mode: 'js-shim', product: 'require-builtin', backend: 'napi', abi: 'napi-v9' };
            }
            var api = { requireBuiltin: requireBuiltin, isAllowedInternalId: isAllowedInternalId,
                        getNativeBindingInfo: getNativeBindingInfo };
            api.default = api;
            module.exports = api;
        """.trimIndent()
        val marker = "// lobos-js-shim-v1"
        val nmRoot = File(File(ctx.filesDir, "usr/local/lib"), "node_modules")
        runCatching {
            // 递归找所有 node-addon-require-builtin/lib/index.js（含嵌套 node_modules）
            walkNodeModules(nmRoot, "node-addon-require-builtin")
                .forEach { pkg ->
                    val idx = File(File(pkg, "lib"), "index.js")
                    if (!idx.isFile) return@forEach
                    val cur = runCatching { idx.readText() }.getOrNull() ?: return@forEach
                    if (cur.contains(marker)) return@forEach      // 已替身，幂等
                    val orig = File(idx.parentFile, "index.js.lobos-orig")
                    if (!orig.exists()) runCatching { idx.copyTo(orig, overwrite = true) }
                    runCatching { idx.writeText("$marker\n$shim\n") }
                }
        }
    }

    private fun walkNodeModules(root: File, pkgName: String): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(root to 0)
        var guard = 0
        // 有限深度（npm 嵌套 node_modules 可能很深）；只找名为 pkgName 的目录。
        while (stack.isNotEmpty() && guard++ < 8192) {
            val (dir, depth) = stack.removeLast()
            if (depth > 10) continue
            val direct = File(dir, pkgName)
            if (direct.isDirectory) out.add(direct)
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) stack.add(child to (depth + 1))
            }
        }
        return out
    }

    private fun build(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("set +m")
        sb.appendLine("export TERM=xterm")
        sb.appendLine("export LANG=C.UTF-8")
        // node-gyp 用 which 找 make/python3 的可执行文件，需 usr/bin（wrapper 之外还要真实路径）。
        sb.appendLine("export PATH=\"${ctx.filesDir.absolutePath}/usr/bin:\$PATH\"")

        val base = ctx.filesDir.absolutePath
        val sysLib = "$base/usr/lib"
        val entries = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
        val seen = HashSet<String>()

        for (e in entries) {
            if (e.role == PieceScan.LIBRARY) continue
            val pd = ProgramDir(ctx, e.id)
            val ver = runCatching { pd.currentVersion() }.getOrNull()
                ?: runCatching { pd.installedVersions().singleOrNull() }.getOrNull()
                ?: continue
            val binDir = File(File(File(base, "opt/${e.id}"), ver), "bin")
            if (!binDir.isDirectory) continue
            val files = binDir.listFiles()?.toList().orEmpty()
            val verHome = binDir.parentFile?.absolutePath ?: continue
            val isPython = e.id == "python"
            // node 必须直接 exec（不能用 linker64）：否则 process.execPath 变成 linker64，
            // cnoke 等构建工具据此推导 NODE_JS_EXECPATH 会把 .cjs 当 ELF 读而失败。
            val isNode = e.id == "node"
            val hasBundledSo = runCatching {
                File(verHome).listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true
            }.getOrDefault(false)
            val nodeHome = runCatching {
                val nv = runCatching { ProgramDir(ctx, "node").currentVersion() }.getOrNull()
                    ?: runCatching { ProgramDir(ctx, "node").installedVersions().singleOrNull() }.getOrNull()
                if (nv == null) null else File(File(base, "opt/node"), nv).absolutePath
            }.getOrNull()
            val nodeBin = nodeHome?.let { File(File(it, "bin"), "node").absolutePath }
            val nodeLibPath = nodeHome?.let { "$it:$it/lib:$it/bin:$sysLib" }
            for (f in files) {
                if (!f.isFile) continue
                val name = f.name
                if (!seen.add(name)) continue
                if (name == "bash" || name == "sh") continue
                val path = f.absolutePath
                val libPath = "$verHome:$verHome/lib:$verHome/bin:$sysLib"
                when {
                    ExecEntry.detect(f) is ExecEntry.Shape.Elf -> when {
                        isPython -> sb.appendLine(
                            "$name() { PYTHONHOME=$verHome LD_LIBRARY_PATH=$libPath $LINKER $path \"\$@\"; }"
                        )
                        isNode -> sb.appendLine(
                            // --expose-internals 让依赖 Node 内部模块的 CLI（dsh/cordis 等）
                            // 能直接 require("internal/*")，无需私有 native addon；
                            // NODE_OPTIONS 里放这个 flag 会被 bionic node 拒绝（RC=9），
                            // 必须走命令行参数。
                            "$name() { LD_LIBRARY_PATH=$libPath $path --expose-internals \"\$@\"; }"
                        )
                        hasBundledSo -> sb.appendLine(
                            "$name() { LD_LIBRARY_PATH=$libPath $LINKER $path \"\$@\"; }"
                        )
                        else -> sb.appendLine("$name() { $LINKER $path \"\$@\"; }")
                    }
                    name.endsWith(".js") && nodeBin != null && nodeLibPath != null -> {
                        val jsCmd = "$nodeBin $path \"\$@\""
                        val wrapped = "LD_LIBRARY_PATH=$nodeLibPath $jsCmd"
                        sb.appendLine("$name() { $wrapped; }")
                        val alias = when {
                            name.endsWith("-cli.js") -> name.removeSuffix("-cli.js")
                            name.substringBeforeLast('.').endsWith("-cli") -> name.substringBeforeLast('.').removeSuffix("-cli")
                            else -> null
                        }
                        if (alias != null && alias != name && seen.add(alias)) {
                            sb.appendLine("$alias() { $wrapped; }")
                        }
                    }
                }
            }
        }
        // npm 全局 bin（用户 npm install -g 的 CLI）：里面的入口是 #!/usr/bin/env node
        // 脚本，Android 没有 /usr/bin/env，不能直接 exec；用 bash 函数包成 node <脚本>。
        val globalBin = File(base, "usr/local/bin")
        if (globalBin.isDirectory) {
            val nv = runCatching {
                val cur = runCatching { ProgramDir(ctx, "node").currentVersion() }.getOrNull()
                cur ?: runCatching { ProgramDir(ctx, "node").installedVersions().singleOrNull() }.getOrNull()
            }.getOrNull()
            val nh = nv?.let { File(File(base, "opt/node"), it).absolutePath }
            if (nh != null && File(nh, "bin/node").isFile) {
                val nb = File(File(nh, "bin"), "node").absolutePath
                val nlp = "$nh:$nh/lib:$nh/bin:$sysLib"
                for (f in globalBin.listFiles().orEmpty()) {
                    if (!f.isFile || !seen.add(f.name)) continue
                    sb.appendLine("${f.name}() { LD_LIBRARY_PATH=$nlp $nb ${f.absolutePath} \"\$@\"; }")
                }
            }
        }
        appendNodeBuildEnv(ctx, sb, base, sysLib)
        return sb.toString()
    }

    private fun appendNodeBuildEnv(ctx: Context, sb: StringBuilder, base: String, sysLib: String) {
        val llvmHome = runCatching {
            val lv = runCatching { ProgramDir(ctx, "llvm").currentVersion() }.getOrNull()
                ?: runCatching { ProgramDir(ctx, "llvm").installedVersions().singleOrNull() }.getOrNull()
            if (lv == null) null else File(File(base, "opt/llvm"), lv).absolutePath
        }.getOrNull() ?: return
        val clang = File(File(llvmHome, "bin"), "clang-21")
        val clangPp = File(File(llvmHome, "bin"), "clang++")
        if (!clang.isFile || !clangPp.exists()) return
        val sysroot = File(llvmHome, "sysroot").absolutePath
        val resource = File(File(File(llvmHome, "lib"), "clang"), "21").absolutePath
        val bin = File(llvmHome, "bin").absolutePath
        val target = "aarch64-linux-android35"

        // 子 shell 的 node 需要 isexe 能判断可执行文件；Android node 缺这三个函数。
        sb.appendLine("export NODE_OPTIONS=\"--require=$base/.node-posix-shim.js\"")
        // node-gyp configure 要 Python；指向 usr/bin 的 python3 入口（which 靠上面的 shim 能找到）。
        sb.appendLine("export PYTHON=\"$base/usr/bin/python3\"")
        // /tmp 在 app 沙箱不可写；node-gyp 要 TMPDIR 建临时目录。
        sb.appendLine("export TMPDIR=\"$base/.tmp\"")
        // CC/CXX 指向单路径 wrapper（内部带全部参数）：node-gyp/make 当命令串用、
        // cmake 当可执行路径用（复合字符串 cmake 不认），一套环境同时满足两者。
        sb.appendLine("export CC=\"$base/usr/bin/cc-wrap\"")
        sb.appendLine("export CXX=\"$base/usr/bin/cxx-wrap\"")
        // CMake 检测宿主 Android 时从 $PREFIX/include/android/api-level.h 读 API 级别；
        // 不给会在 app 沙箱里解析成 /include/... 而失败。
        sb.appendLine("export PREFIX=\"$sysroot/usr\"")
        // PREFIX 被 CMake 占用，但 npm 也用它当全局安装前缀；显式给 npm 自己的前缀，
        // 避免 npm -g 把包装进 llvm sysroot。
        sb.appendLine("export npm_config_prefix=\"$base/usr/local\"")
        val nodeHome = runCatching {
            val nv = runCatching { ProgramDir(ctx, "node").currentVersion() }.getOrNull()
                ?: runCatching { ProgramDir(ctx, "node").installedVersions().singleOrNull() }.getOrNull()
            if (nv == null) null else File(File(base, "opt/node"), nv).absolutePath
        }.getOrNull()
        // node-gyp 默认联网下载 headers，下载到的那份在 Android 上带 android_ndk_path 变量会失败；
        // 指向它自己的缓存目录（已有同源 headers），configure 走本地路径。
        val nodeVer = nodeHome?.let { File(it).name }
        if (nodeVer != null) {
            sb.appendLine("export npm_config_nodedir=\"$base/.cache/node-gyp/$nodeVer\"")
        }
        // clang 自带 libc++ 在 llvmHome/lib；node 需要在自己的版本根找 libc++；
        // make/python 等在 usr/lib。三者都进全局 LD，覆盖终端里所有子进程。
        val ldParts = listOfNotNull(nodeHome, llvmHome, sysLib).joinToString(":")
        sb.appendLine("export LD_LIBRARY_PATH=\"$ldParts\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"")
    }
}
