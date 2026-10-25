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

        //  ★ 缺陷名（C-102）**这一格挑错了同一件里的那一个** —— 而它治的是**对象那一层**。
        //    而原来这一支逐字是 `.filter { ProgramIndex.isPiece(ctx, it) }`，
        //    => 而 isPiece 逐字是「assetEntry 空 => PIECE」，=> 而它**只**收**纯库件**那一族
        //       （=> 而 crypto / curl / zlib 那一族）。
        //    => 而**程序形态**那一族（=> 而 busybox / bash / jq / openssl / ripgrep）**不在**里面，
        //    => 而而它们 bin/ 下那些命令入口**从来没被扫过**，
        //    => 而而 usr/bin 下的命令入口**一条也没建**
        //       （=> 而 07 今天核到设备侧逐字是「已建 0」，而那**不是**「没有命令入口」，
        //       => 而而那是「**这一支**没扫到那些件」）。
        //    => 而处置：这一支收**所有在册的件**（=> 而不按 Level 筛），
        //       => 而「这一件有没有 bin/ 下的命令入口」由**它自己**回答
        //       （=> 而下面那一支逐字是 File(root, "bin").listFiles()）。
        //    => 而按 C-135：这与「这一件是不是库件」是**两问**（=> 而那一问由 Level 那一族答）。
        val idx = lobos.services.reg.ProgramIndex.all(ctx)
            .sortedBy { it.id }
        val pieces = idx.map { File(opt, it.id) }
        var linked = 0
        val log = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val claimed = mutableMapOf<String, String>()
        // SONAME -> 提供它的那几处（件 id、那一处、字节摘要）。
        //    而它必须**先收集全部再决定**（S56）：
        //    原来这一处是「扫到一个建一个」，  而后建的覆盖先建的   由遍历顺序决定 
        val sonameCandidates = LinkedHashMap<String, MutableList<Triple<String, File, String>>>()
        val binInstalled = mutableListOf<String>()

        for (e in idx) {
            val p = File(opt, e.id)
            val ver = e.version.takeIf { it.isNotBlank() }
                ?: p.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.name
                ?: continue
            val root = File(p, ver)
            if (!root.isDirectory) continue

            // C-102: 原来这里只建一条「同名软链 -> SONAME 名字」，丢掉了 ldconfig(8) 的三段链形状
            // （libfoo.so -> libfoo.so.<major> -> libfoo.so.<full>），改为按 SonameChain 落三段链。
            //
            //  而「两件都提供同一个 SONAME 时链该指向哪一处」这一格（S56 裁定）：
            //    它不该由「谁先扫到」或「谁后扫到」决定 ——  而那正是原来这一处的行为：
            //    idx 是 sortedBy { it.id }， 而 lib/ 下逐个建链   **后建的那一条覆盖先建的**  
            //    而今天 crypto 输给 openssl **只因为 c < o**   改一个件名它就反过来  
            //    **「字母序」不是一条规则，只是遍历顺序的副产品**（ C-92）。
            //    而那一格该由「这一份是不是唯一的」决定（01 今天裁的），三种情形三条处置：
            //     一 只有一件提供那个 SONAME              -> 指向它
            //     二 多件提供、而字节**逐字相同**        -> 指向任一处，**并记「共 N 处」**
            //        （ 字节相同  指向哪一处今天无害，  而「无害」不该靠「恰好扫到了哪一个」）
            //     三 多件提供、而字节**不同**            -> **报歧义、不建链**
            //        （ 字节不同  链指哪一处会改变程序行为，  而让字母决定程序行为不可接受）
            //    而实现：**先收集**这一批每一件 lib/ 下每个文件的名字与字节摘要，
            //     **再按名字分组**， 然后按上面三条分别处置。
            File(root, "lib").listFiles()?.sortedBy { it.name }?.forEach { so ->
                if (!so.isFile) return@forEach
                sonameCandidates.computeIfAbsent(so.name) { ArrayList() }
                    .add(Triple(e.id, so, lobos.services.supply.SupplySha.sha256(so)))
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


        // SONAME -> 该指向哪一处（S56 裁定的三情形， 而 C-05：不能确定就报缺）
        //   一 只有一件提供那个 SONAME        -> 指向它
        //   二 多件提供、字节**逐字相同**     -> 指向其中一处，**并记「共 N 处」**
        //   三 多件提供、字节**不同**         -> **报歧义、不建链**
        //     （ 字节不同 => 链指哪一处会改变程序行为， 而让字母序决定它不可接受）
        for ((soname, cands) in sonameCandidates) {
            if (cands.size == 1) {
                val so = cands[0].second
                val landed = SonameChain.land(so, usrLib)
                linked += landed.created
                if (!landed.ok) unresolved += landed.detail
                log += landed.log
                log += "kind1 " + soname + " 只有一件提供（" + cands[0].first + "）=> 指向它"
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
                " 处提供（字节逐字相同）=> 指向 " + first.first +
                "；而「无害」不该靠「恰好扫到了哪一个」"
            log += "note " + soname + " 共 " + cands.size + " 处提供（字节逐字相同）=> 指向 " +
                first.first + "；而「无害」不该靠「恰好扫到了哪一个」"
        }            }
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
    /**
     * 「已声明的命令入口」—— 这一列答的是「**这一件在 usr/bin 下应该有哪几个名字**」。
     *
     * ★ 缺陷名（C-102）**这一格挑错了同一件里的那一个** —— 而它治的是**对象那一层**：
     *   而原来这一支逐字是 `if (!ProgramIndex.isPiece(ctx, e)) continue`，
     *   => 而 isPiece 逐字是「assetEntry 空 => PIECE」，=> 而它**只**收 Level.PIECE 那一族。
     *   => 而那一族的成员是「**纯库件**」（=> 而 crypto / curl / zlib 那一族，
     *      => 而它们**真的**只有 lib/ 下的字节、=> 而它们**没有** bin/ 目录）。
     *   => 而而 openssl 在 0a00489 那一笔之后 assetEntry 逐字是 bin/openssl（=> 而它非空），
     *      => 而它于是判 PROGRAM、=> 而它被 continue **跳过**了。
     *   => 而而那个「声明」那一列于是逐字是 0 —— => 而**那不是**「这一类被声明了 0 个」，
     *      => 而是「那一列按 isPiece 选、=> 而而选出来的那一族里**没有** bin/ 下的程序形态字节」。
     *   => 而那正是 C-112 那一族（「0 最容易被相信」那一族）：=> 而那个 0 是个**假零**。
     *
     * => 而处置：这一格**按那一件有没有 bin/ 目录**来选，=> 而**不**按 Level 那一族筛。
     *   => 而而「这一件有没有 bin/ 下的命令入口」这一问，=> 而它**该**由那一件自己回答
     *      （=> 而下面那一支逐字是 File(verDir, "bin").listFiles()）。
     *   => 而而「纯库件」那一族**自然**答空（=> 而它们没有 bin/ 目录），
     *      => 而而「程序形态」那一族答出它 bin/ 下的那些名字。
     *   => 而按 C-135：这与「这一件是不是库件」是**两问**（=> 而那一问由 Level 那一族答）。
     */
    fun declaredBins(ctx: Context, pieceId: String): List<String> {
        if (pieceId.isBlank()) {
            val out = mutableListOf<String>()
            for (e in lobos.services.reg.ProgramIndex.all(ctx)) {
                //  ★ 而这一处**不**按 Level 那一族筛（=> 而那正是那个假零的成因）。
                //    而那一件自己答「我有没有 bin/ 下的命令入口」——=> 而下面那一支逐字是
                //    File(verDir, "bin").listFiles()，=> 而纯库件（=> 而 crypto / curl / zlib 那一族）
                //    **自然**答空，=> 而程序形态那一族答出它 bin/ 下的那些名字。
                out += declaredBins(ctx, e.id)
            }
            return out.distinct()
        }
        val opt = lobos.kernel.layout.SystemDirs.opt(ctx)
        val base = File(opt, pieceId)
        val verDir = base.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return emptyList()
        //  ★ 缺陷名（C-102）**这一格把「本体那一份」也算成了命令名** —— 而它治的是**对象那一层**。
        //    而原来这一支逐字是 `File(verDir, "bin").listFiles()?.map { it.name }`，
        //    => 而它把 bin/ 下**每一项**都算成「usr/bin 下的命令入口」。
        //    => 而 busybox 那一件 bin/ 下有 19 项：本体那一份（bin/libbusybox.so）+ 18 条指向它的软链，
        //    => 而而**本体那一份不认自己的文件名**（=> 而 01 核到过），
        //    => 而而它**不该**出现在「usr/bin 里的命令入口」那一列里
        //    => 而而 07 今天核到设备侧逐字是「声明了但没建出：… libbusybox.so …」（=> 而那是**假零**那一族的反面）。
        //    => 而「哪几项是**命令名**」那一格**产物自带**：
        //       derivedLinks 那一格逐字记的是「哪些是派生物」（=> 而构建期 elf-form.sh 那一处写的），
        //       => 而 busybox 那一件逐字是那 18 个 applet 名字（cat、cp、df…vi），
        //       => 而而**本体那一份不在**里面。
        //    => 而处置：**先**取产物那一份的 derivedLinks（=> 而那**就是**作者声明的命令名清单）；
        //       而它**为空**时（=> 而 openssl 那一件：=> 而它那两项都是**实体**、=> 而而它们各自是命令），
        //       => 而才退回「bin/ 下那些项」那一支。
        val shape = lobos.services.supply.ProgramInstallPipeline.PieceShape.read(verDir)
        if (shape != null && shape.derivedLinks.isNotEmpty()) {
            return shape.derivedLinks
                .filter { it.startsWith("bin/") }
                .map { it.substringAfterLast('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
        //  ★ 缺陷名（C-102）**这一支读的是**落位目录**而不是**产物那一份**
        //    => 而而落位目录里**可能有**产物说没有的那几项（=> 而那是**遗留物**），
        //    => 而而它们**不该**算成「这一件对外承诺的命令名」。
        //    => 而真机读数（07，6c107c7 那一版，2026-10-25）：
        //      · 那一版产物里**没有** bin/c_rehash（=> 而本机核过产物那一份），
        //      · 而落位侧 piece-shape.json 的 files[] 逐字是 3 项（=> 而 c_rehash **不在**了），
        //      · 而设备上 opt/openssl/3.6.3/bin/c_rehash 那个字节**逐字还在**（=> 而那是遗留物），
        //      · 而「声明」那一列逐字仍是 23（=> 而就是这一支把它数进去了）。
        //    => 而处置：这一支改读**产物那一份**的 files[]（=> 而那是**这一件自己声明的**），
        //      => 而而落位目录那份**只**用来判「形态」（=> 而它不判「承诺了什么」）。
        if (shape != null) {
            return shape.files
                .filter { it.role == lobos.services.supply.ProgramInstallPipeline.ROLE_PROGRAM }
                .map { it.path.substringAfterLast('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
        return File(verDir, "bin").listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }
}
