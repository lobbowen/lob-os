package lobos.kernel.layout

import android.content.Context
import android.util.Log
import java.io.File
import lobos.kernel.elf.ElfFacts
import lobos.services.log.RuntimeDiagnostics

object LdConfig {

    private const val TAG = "lobos.ldconfig"

    fun run(ctx: Context): Int {
        val opt = SystemDirs.opt(ctx)
        val usrBin = SystemDirs.bin(ctx)
        val usrLib = SystemDirs.lib(ctx)
        val etc = SystemDirs.etc(ctx)
        usrBin.mkdirs(); usrLib.mkdirs(); etc.mkdirs()

        val idx = lobos.services.reg.ProgramIndex.all(ctx)
            .filter { lobos.services.reg.ProgramIndex.isPiece(ctx, it) }
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

            // C-102: 今天这里只建一条「同名软链 -> SONAME 名字」，丢掉了 ldconfig(8) 的三段链形状
            // （libfoo.so -> libfoo.so.<major> -> libfoo.so.<full>），linker 用的那个名字与
            // 实体文件之间的中间层都没建出来。改为按 SonameChain 落三段链。
            File(root, "lib").listFiles()?.sortedBy { it.name }?.forEach { so ->
                if (!so.isFile) return@forEach
                val landed = SonameChain.land(so, usrLib)
                linked += landed.created
                if (!landed.ok) unresolved += landed.detail
                log += landed.log
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

        // C-102: ld.so.conf / ld.so.cache 在本仓零读者（证据见 05-core-developer/env-cell.md），
        // 真正被 native 读的只有 SysEnv.FILE 一份，故不再生成这两份影子配置。
        // 链接器的库搜索路径由 RuntimeEnvironment.treeRootEnv 设的 LD_LIBRARY_PATH 承担。
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

    // C-102: 原来这里只判 link.exists()，而 exists() 跟随符号链接：
    // 断链的旧软链 exists() 为 false，于是跳过 delete 直接 createSymbolicLink，
    // 在已有同名项的位置上必然失败。改为把「是软链」和「存在」分开判。
    // 软链的建/查/删统一走 SonameChain.put（1=新建 0=已一致 -1=失败）。
    private fun linkTo(target: File, link: File): Boolean =
        SonameChain.put(link, target) >= 0
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
                if (!lobos.services.reg.ProgramIndex.isPiece(ctx, e)) continue
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
