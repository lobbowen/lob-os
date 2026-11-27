package lobos.system.fs

import java.io.File
import java.nio.file.Files

object EntryLinks {

    fun purge(dest: File): Int {
        val roots = listOf(
            SystemDirs.bin(),
            SystemDirs.lib(),
            SystemDirs.include(),
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
}
