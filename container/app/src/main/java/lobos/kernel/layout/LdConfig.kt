package lobos.kernel.layout

import android.content.Context
import android.util.Log
import java.io.File
import lobos.kernel.elf.ElfFacts
import lobos.services.log.RuntimeDiagnostics

object LdConfig {

    private const val CONF = "ld.so.conf"
    private const val CACHE = "ld.so.cache"
    private const val TAG = "lobos.ldconfig"

    fun run(ctx: Context): Int {
        val opt = SystemDirs.opt(ctx)
        val usrBin = SystemDirs.bin(ctx)
        val usrLib = SystemDirs.lib(ctx)
        val etc = SystemDirs.etc(ctx)
        usrBin.mkdirs(); usrLib.mkdirs(); etc.mkdirs()

        val idx = lobos.services.reg.ProgramIndex.all(ctx)
            .filter { lobos.services.reg.ProgramIndex.isPiece(it) }
            .sortedBy { it.id }
        val pieces = idx.map { File(opt, it.id) }
        var linked = 0
        val log = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val claimed = mutableMapOf<String, String>()
        val binInstalled = mutableListOf<String>()

        for (e in idx) {
            val p = File(opt, e.id)
            val ver = e.version.takeIf { it.isNotBlank() }
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
                val prev = claimed[exe.name]
                if (prev != null) {
                    unresolved += "bin/" + exe.name + " 被两处声明：已有 " + prev + "，忽略 " + exe.absolutePath
                    log += "bin  " + exe.name + "   冲突，保留 " + prev
                    return@forEach
                }
                claimed[exe.name] = exe.absolutePath
                binInstalled += exe.name
                if (linkTo(exe, dst)) linked++ else unresolved += exe.absolutePath + " → " + dst.absolutePath
                log += "bin  " + exe.name + " → " + dst.name + "   " + exe.absolutePath
            }
        }

        File(etc, CONF).writeText(usrLib.absolutePath + "\n" + usrBin.absolutePath + "\n")
        File(etc, CACHE).writeText(buildString {
            append("# lobos ld.so.cache — 扫到的件\n")
            usrLib.listFiles()?.sortedBy { it.name }?.forEach { append(it.name).append('\t').append(it.absolutePath).append('\n') }
        })
        val sysenv = SysEnv.write(ctx)

        RuntimeDiagnostics.append(
            ctx, "ldconfig", unresolved.isEmpty(),
            "ldconfig：建了 $linked 条软链（${pieces.size} 个件）" +
                if (unresolved.isEmpty()) "" else "；${unresolved.size} 条建链失败",
            "usr/lib=" + usrLib.absolutePath + "  usr/bin=" + usrBin.absolutePath +
                "\n" + log.joinToString("\n") +
                if (unresolved.isEmpty()) ""
                else "\n建链失败=" + unresolved.joinToString("  |  "),
        )
        val declaredBins = declaredBins(ctx, "")
        val missingBins = declaredBins.filterNot { binInstalled.contains(it) }
        val items = listOf(
                lobos.services.supply.SelfCheckReport.Item(
                    "usr-bin-entries",
                    missingBins.isEmpty(),
                    "usr/bin 里的命令入口（声明 ${declaredBins.size} · 已建 ${binInstalled.size}）",
                    if (missingBins.isEmpty()) "声明的命令全部建出"
                    else "声明了但没建出：" + missingBins.joinToString(", ") +
                        " —— 这些命令在真机上会是 command not found，而系统其余部分完全正常（无任何症状）",
                ),
        )
        val selfCheck = lobos.services.supply.SelfCheckReport.format(items)
        RuntimeDiagnostics.append(ctx, "selfcheck", missingBins.isEmpty(), selfCheck)
        if (missingBins.isEmpty()) {
            Log.i(TAG, "selfcheck 通过：" + lobos.services.supply.SelfCheckReport.verdict(items))
        } else {
            Log.w(TAG, "selfcheck 未通过：" + lobos.services.supply.SelfCheckReport.verdict(items))
        }
        if (unresolved.isNotEmpty()) {
            Log.w(TAG, "ldconfig 有 " + unresolved.size + " 条未解决（详见 diag.jsonl 的 ldconfig 记录）")
            for (u in unresolved) Log.w(TAG, "  " + u)
        }
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
    fun landedIds(ctx: Context): List<String> {
        val out = sortedSetOf<String>()
        SystemDirs.lib(ctx).listFiles()?.forEach { out += it.name }
        SystemDirs.bin(ctx).listFiles()?.forEach { out += it.name }
        return out.toList()
    }

    fun entryLinkOwners(ctx: Context): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        val roots = listOf(SystemDirs.lib(ctx), SystemDirs.bin(ctx))
        for (r in roots) {
            r.listFiles()?.forEach { l ->
                val t = runCatching { l.canonicalPath }.getOrNull() ?: return@forEach
                val seg = t.split(File.separatorChar)
                val i = seg.indexOf("opt")
                if (i >= 0 && i + 2 < seg.size) {
                    val owner = seg[i + 1]
                    out.getOrPut(owner) { mutableListOf() } += l.name
                }
            }
        }
        return out
    }
    fun declaredBins(ctx: Context, pieceId: String): List<String> {
        if (pieceId.isBlank()) {
            val out = mutableListOf<String>()
            for (e in lobos.services.reg.ProgramIndex.all(ctx)) {
                if (!lobos.services.reg.ProgramIndex.isPiece(e)) continue
                out += declaredBins(ctx, e.id)
            }
            return out.distinct()
        }
        val opt = lobos.kernel.layout.SystemDirs.opt(ctx)
        val base = File(opt, pieceId)
        val verDir = base.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return emptyList()
        return File(verDir, "bin").listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }
}
