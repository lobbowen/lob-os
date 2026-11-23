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
    }

    private fun build(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("set +m")
        sb.appendLine("export TERM=xterm")
        sb.appendLine("export LANG=C.UTF-8")

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
        return sb.toString()
    }
}
