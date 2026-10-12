package lobos.kernel.layout

import android.content.Context
import java.io.File
import java.nio.file.Files
import lobos.services.log.RuntimeDiagnostics

object EntryLinks {

    fun purge(context: Context, pieceId: String, dest: File): Int {
        val roots = listOf(
            SystemDirs.bin(context),
            SystemDirs.lib(context),
            SystemDirs.include(context),
        )
        val destReal = runCatching { dest.canonicalFile.path }.getOrDefault(dest.absolutePath)
        var n = 0
        for (d in roots) {
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (!Files.isSymbolicLink(k.toPath())) continue
                val target = runCatching {
                    File(k.parentFile, Files.readSymbolicLink(k.toPath()).toString())
                }.getOrNull() ?: continue
                val tp = runCatching { target.canonicalFile.path }.getOrDefault(target.absolutePath)
                if (tp == destReal || tp.startsWith(destReal + File.separator)) {
                    if (k.delete()) n++
                }
            }
        }
        return n
    }

    fun record(dest: File, links: List<String>) {
        if (links.isEmpty()) return
        val f = File(dest.parentFile, "." + dest.name + ".links")
        runCatching { f.writeText(links.joinToString("\n") + "\n") }
    }

    fun previous(dest: File): List<String> {
        val f = File(dest.parentFile, "." + dest.name + ".links")
        if (!f.isFile) return emptyList()
        return runCatching { f.readText().lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    fun drop(dest: File) {
        runCatching { File(dest.parentFile, "." + dest.name + ".links").delete() }
    }
}
