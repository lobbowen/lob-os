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
        val log = mutableListOf<String>()
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
                if (linkTo(so, dst)) linked++ else unresolved += so.absolutePath + " → " + dst.absolutePath
                log += "lib  " + so.name + " → " + dst.name + "   " + so.absolutePath
            }

            File(root, "bin").listFiles()?.sortedBy { it.name }?.forEach { exe ->
                if (!exe.isFile) return@forEach
                if (!lobos.kernel.elf.ExecBits.isRunnable(exe)) return@forEach
                val dst = File(usrBin, exe.name)
                if (linkTo(exe, dst)) linked++ else unresolved += exe.absolutePath + " → " + dst.absolutePath
                log += "bin  " + exe.name + " → " + dst.name + "   " + exe.absolutePath
            }
        }

        File(etc, CONF).writeText(usrLib.absolutePath + "\n" + usrBin.absolutePath + "\n")
        File(etc, CACHE).writeText(buildString {
            append("# lobos ld.so.cache — 扫到的件\n")
            usrLib.listFiles()?.sortedBy { it.name }?.forEach { append(it.name).append('\t').append(it.absolutePath).append('\n') }
        })

        RuntimeDiagnostics.append(
            ctx, "ldconfig", unresolved.isEmpty(),
            "ldconfig：建了 $linked 条软链（${pieces.size} 个件）" +
                if (unresolved.isEmpty()) "" else "；${unresolved.size} 条建链失败",
            "usr/lib=" + usrLib.absolutePath + "  usr/bin=" + usrBin.absolutePath +
                "\n" + log.joinToString("\n") +
                if (unresolved.isEmpty()) ""
                else "\n建链失败=" + unresolved.joinToString("  |  "),
        )
        return linked
    }

    private fun linkTo(target: File, link: File): Boolean = runCatching {
        if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
            val cur = File(link.parentFile, java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
            if (runCatching { cur.canonicalFile }.getOrNull() == runCatching { target.canonicalFile }.getOrNull()) {
                return@runCatching true
            }
        }
        if (link.exists() && !link.delete()) return@runCatching false
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    }.getOrDefault(false)
}
