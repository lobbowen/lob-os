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
    }

    private fun ensureNodePosixShim(ctx: Context) {
        val shim = File(ctx.filesDir, ".node-posix-shim.js")
        val text = buildString {
            appendLine("// Android 版 node 不提供 getuid/getgid/getgroups；isexe 需要它们才能判断可执行文件。")
            appendLine("// 值取任意数即可：交付件都有 o+x 位，isexe 的 mod&o 分支先命中，与真实 uid 无关。")
            appendLine("if (typeof process.getuid !== 'function') process.getuid = () => 1;")
            appendLine("if (typeof process.getgid !== 'function') process.getgid = () => 1;")
            appendLine("if (typeof process.getgroups !== 'function') process.getgroups = () => [1];")
        }
        runCatching { shim.writeText(text) }
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
        // clang 从 NDK 源码交叉编出、无默认 triple；显式给 target + 资源目录 + sysroot + 链接器搜索路径。
        val extra = "--target=$target -resource-dir=$resource --sysroot=$sysroot -B $bin"
        sb.appendLine("export CC=\"$LINKER ${clang.absolutePath} $extra\"")
        sb.appendLine("export CXX=\"$LINKER ${clangPp.absolutePath} $extra\"")
        val nodeHome = runCatching {
            val nv = runCatching { ProgramDir(ctx, "node").currentVersion() }.getOrNull()
                ?: runCatching { ProgramDir(ctx, "node").installedVersions().singleOrNull() }.getOrNull()
            if (nv == null) null else File(File(base, "opt/node"), nv).absolutePath
        }.getOrNull()
        // clang 自带 libc++ 在 llvmHome/lib；node 需要在自己的版本根找 libc++；
        // make/python 等在 usr/lib。三者都进全局 LD，覆盖终端里所有子进程。
        val ldParts = listOfNotNull(nodeHome, llvmHome, sysLib).joinToString(":")
        sb.appendLine("export LD_LIBRARY_PATH=\"$ldParts\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"")
    }
}
