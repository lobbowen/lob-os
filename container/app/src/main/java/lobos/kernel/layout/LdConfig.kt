package lobos.kernel.layout

import android.content.Context
import java.io.File
import lobos.kernel.elf.ElfFacts
import lobos.services.log.RuntimeDiagnostics

object LdConfig {

    private const val CONF = "ld.so.conf"
    private const val CACHE = "ld.so.cache"

    fun run(ctx: Context): Int {
        val opt = SystemDirs.opt(ctx)
        val usrBin = SystemDirs.bin(ctx)
        val usrLib = SystemDirs.lib(ctx)
        val etc = SystemDirs.etc(ctx)
        usrBin.mkdirs(); usrLib.mkdirs(); etc.mkdirs()

        val pieces = opt.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        var linked = 0
        val unresolved = mutableListOf<String>()

        for (p in pieces) {
            val cur = File(p, "CURRENT").takeIf { it.isFile }?.readText()?.trim()
            val ver = cur?.takeIf { it.isNotBlank() }
                ?: p.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.name
                ?: continue
            val root = File(p, ver)
            if (!root.isDirectory) continue

            File(root, "lib").listFiles()?.sortedBy { it.name }?.forEach { so ->
                if (!so.isFile) return@forEach
                val soname = runCatching { ElfFacts.read(so)?.soname }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: so.name
                val dst = File(usrLib, soname)
                if (linkTo(so, dst, soname)) linked++
            }

            File(root, "bin").listFiles()?.sortedBy { it.name }?.forEach { exe ->
                if (!exe.isFile) return@forEach
                if (!lobos.kernel.elf.ExecBits.isRunnable(exe)) return@forEach
                val dst = File(usrBin, exe.name)
                if (linkTo(exe, dst, exe.name)) linked++
            }
        }

        File(etc, CONF).writeText(usrLib.absolutePath + "\n" + usrBin.absolutePath + "\n")
        File(etc, CACHE).writeText(buildString {
            append("# lobos ld.so.cache — 扫到的件\n")
            usrLib.listFiles()?.sortedBy { it.name }?.forEach { append(it.name).append('\t').append(it.absolutePath).append('\n') }
        })

        RuntimeDiagnostics.append(
            ctx, "ldconfig", unresolved.isEmpty(),
            "ldconfig：建了 $linked 条软链（${pieces.size} 个件）",
            "usr/lib=" + usrLib.absolutePath + "  usr/bin=" + usrBin.absolutePath +
                if (unresolved.isEmpty()) "" else "  未解析=" + unresolved.joinToString(","),
        )
        return linked
    }

    private fun linkTo(target: File, link: File, name: String): Boolean {
        return runCatching {
            if (link.isSymbolicLink) {
                val cur = runCatching { File(link.parentFile, runCatching { java.nio.file.Files.readSymbolicLink(link.toPath()).toString() }.getOrDefault("")).canonicalFile }.getOrNull()
                if (cur == target.canonicalFile) return true
            }
            if (link.exists() && !link.delete()) return false
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrDefault(false)
    }
}
