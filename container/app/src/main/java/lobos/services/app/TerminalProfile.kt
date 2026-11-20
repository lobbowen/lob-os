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
            val ver = runCatching { ProgramDir(ctx, e.id).currentVersion() }.getOrNull() ?: continue
            val binDir = File(File(File(base, "opt/${e.id}"), ver), "bin")
            if (!binDir.isDirectory) continue
            val files = binDir.listFiles()?.toList().orEmpty()
            val verHome = binDir.parentFile?.absolutePath ?: continue
            val isPython = e.id == "python"
            for (f in files) {
                if (!f.isFile) continue
                val name = f.name
                if (!seen.add(name)) continue
                if (name == "bash" || name == "sh") continue
                if (ExecEntry.detect(f) !is ExecEntry.Shape.Elf) continue
                val path = f.absolutePath
                if (isPython) {
                    sb.appendLine(
                        "$name() { PYTHONHOME=$verHome LD_LIBRARY_PATH=$verHome/lib:$verHome/bin:$sysLib $LINKER $path \"\$@\"; }"
                    )
                } else {
                    sb.appendLine("$name() { $LINKER $path \"\$@\"; }")
                }
            }
        }
        return sb.toString()
    }
}
