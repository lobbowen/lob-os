package lobos.kernel.layout

import android.content.Context
import java.io.File
import lobos.kernel.elf.ElfFacts

object LdConfig {

    private const val TAG = "lobos.ldconfig"

    private const val LOG_HEAD_LINES = 200

    private const val FAILED_HEAD_LINES = 200

    private fun summarizeLog(log: List<String>): String {
        if (log.isEmpty()) return "（没有软链记录）\n"
        val decisive = log.filter {
            it.startsWith("kind2 ") || it.startsWith("kind3 ") || it.startsWith("ambiguous ")
        }
        val out = StringBuilder()
        out.append("软链记录共 ").append(log.size)
            .append(" 行（完整清单可由重跑 ldconfig 再生，不落盘）\n")
        out.append("前 ").append(LOG_HEAD_LINES).append(" 条：\n")
        for (l in log.take(LOG_HEAD_LINES)) out.append(l).append('\n')
        if (decisive.isNotEmpty()) {
            out.append("判定相关那几行（多提供者与歧义，共 ")
                .append(decisive.size).append(" 行）：\n")
            for (l in decisive.take(LOG_HEAD_LINES)) out.append(l).append('\n')
        }
        return out.toString()
    }

    fun run(ctx: Context): Int {
        val opt = SystemDirs.opt()
        val usrBin = SystemDirs.bin()
        val usrLib = SystemDirs.lib()
        val etc = SystemDirs.etc()
        usrBin.mkdirs(); usrLib.mkdirs(); etc.mkdirs()

        val idx = lobos.kernel.spi.KernelServices.programs().all()
            .sortedBy { it.programId }
        val pieces = idx.map { File(opt, it.programId) }
        var linked = 0
        val log = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val claimed = mutableMapOf<String, String>()

        val sonameCandidates = LinkedHashMap<String, MutableList<Triple<String, File, String>>>()
        val binInstalled = mutableListOf<String>()

        for (e in idx) {
            // glibc-runtime 是完整 Linux 用户态底座：它的 libc/libdl/libm 只给
            // guest loader 经 --library-path 用。建链到全局 usr/lib 会覆盖 bionic
            // 的 SONAME 语义，让所有 bionic 程序（bash/node）启动即崩。
            if (e.programId == "glibc-runtime") continue
            val p = File(opt, e.programId)
            val ver = e.version.takeIf { it.isNotBlank() }
                ?: p.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.name
                ?: continue
            val root = File(p, ver)
            if (!root.isDirectory) continue

            File(root, "lib").listFiles()?.sortedBy { it.name }?.forEach { so ->
                if (!so.isFile) return@forEach
                if (java.nio.file.Files.isSymbolicLink(so.toPath())) return@forEach
                sonameCandidates.computeIfAbsent(so.name) { ArrayList() }

                    .add(Triple(e.programId, so, SonameChain.contentDigest(so)))
            }

            File(root, "bin").listFiles()?.sortedBy { it.name }?.forEach { exe ->
                if (!exe.isFile) return@forEach
                if (!lobos.kernel.proc.ExecEntry.isRunnable(exe)) return@forEach
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

        for ((soname, cands) in sonameCandidates) {
            if (cands.size == 1) {
                val so = cands[0].second
                val landed = SonameChain.land(so, usrLib)
                linked += landed.created
                if (!landed.ok) unresolved += landed.detail
                log += landed.log
                log += "kind1 " + soname + " 只有一件提供（" + cands[0].first + "）指向它"
                continue
            }
            val shas = cands.map { it.third }.toSet()
            if (shas.size > 1) {
                val who = cands.joinToString(",") { it.first }
                unresolved += "kind3 歧义 SONAME " + soname + " 由 " + cands.size +
                    " 件提供而字节不同（" + who + "）—— 报歧义、不建链" +
                    "（指向哪一处会改变程序行为，而让字母序决定它不可接受）"
                log += "ambiguous " + soname + "  提供者=" + who
                continue
            }
            val first = cands[0]
            val landed = SonameChain.land(first.second, usrLib)
            linked += landed.created
            if (!landed.ok) unresolved += landed.detail
            log += landed.log
            log += "kind2 " + soname + " 共 " + cands.size +
                " 处提供（字节相同）指向 " + first.first +
                "；而「无害」不该靠「恰好扫到了哪一个」"
            log += "note " + soname + " 共 " + cands.size + " 处提供（字节相同）指向 " +
                first.first + "；而「无害」不该靠「恰好扫到了哪一个」"
        }

        val sysenv = SysEnv.write(ctx)

        lobos.kernel.spi.KernelServices.logger().log("ldconfig", unresolved.isEmpty(),
            "ldconfig：建了 $linked 条软链（${pieces.size} 个件）" +
                if (unresolved.isEmpty()) "" else "；${unresolved.size} 条建链失败",
            "usr/lib=" + usrLib.absolutePath + "  usr/bin=" + usrBin.absolutePath +
                "\n" + summarizeLog(log) +
                if (unresolved.isEmpty()) ""
                else "\n建链失败=" + unresolved.take(FAILED_HEAD_LINES).joinToString("  |  ") +
                    if (unresolved.size > FAILED_HEAD_LINES)
                        "  |  （共 " + unresolved.size + " 条，只记前 " + FAILED_HEAD_LINES + " 条）"
                    else "",
        )
        val declaredBins = declaredBins(ctx, "")
        val missingBins = declaredBins.filterNot { binInstalled.contains(it) }
        val items = listOf(
                lobos.kernel.spi.SystemDiagnostics.Item(
                    "usr-bin-entries",
                    missingBins.isEmpty(),
                    "usr/bin 里的命令入口（声明 ${declaredBins.size} · 已建 ${binInstalled.size}）",
                    if (missingBins.isEmpty()) "声明的命令全部建出"
                    else "声明了但没建出：" + missingBins.joinToString(", ") +
                        " —— 这些命令在真机上会是 command not found，而系统其余部分完全正常（无任何症状）",
                ),
        )
        val selfCheck = lobos.kernel.spi.KernelServices.diagnostics().selfCheck(items)
        lobos.kernel.spi.KernelServices.logger().log("selfcheck", missingBins.isEmpty(), selfCheck, null)
        if (missingBins.isEmpty()) {
            lobos.kernel.spi.KernelServices.logger().log("ldconfig",true,"selfcheck 通过：" + lobos.kernel.spi.KernelServices.diagnostics().selfCheckVerdict(items),null)
        } else {
            lobos.kernel.spi.KernelServices.logger().log("ldconfig",false,"selfcheck 未通过：" + lobos.kernel.spi.KernelServices.diagnostics().selfCheckVerdict(items),null)
        }
        if (unresolved.isNotEmpty()) {
            lobos.kernel.spi.KernelServices.logger().log("ldconfig",null,"ldconfig 有 " + unresolved.size + " 条未解决（详见 diag.jsonl 的 ldconfig 记录）",null)
            for (u in unresolved) lobos.kernel.spi.KernelServices.logger().log("ldconfig",null,"  " + u,null)
        }
        return linked
    }

    private fun linkTo(target: File, link: File): Boolean =
        SonameChain.put(link, target) >= 0
    fun landedIds(ctx: Context): List<String> {
        val out = sortedSetOf<String>()
        SystemDirs.lib().listFiles()?.forEach { out += it.name }
        SystemDirs.bin().listFiles()?.forEach { out += it.name }
        return out.toList()
    }

    fun entryLinkOwners(ctx: Context): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        val roots = listOf(SystemDirs.lib(), SystemDirs.bin())
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
            for (e in lobos.kernel.spi.KernelServices.programs().all()) {

                out += declaredBins(ctx, e.programId)
            }
            return out.distinct()
        }
        val opt = lobos.kernel.layout.SystemDirs.opt()
        val base = File(opt, pieceId)
        val verDir = base.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return emptyList()

        return lobos.kernel.spi.KernelServices.programs().declaredBins(pieceId)
            .filter { it.startsWith("bin/") }
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }
}
