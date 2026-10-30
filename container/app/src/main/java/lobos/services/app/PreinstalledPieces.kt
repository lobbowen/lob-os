package lobos.services.app

import android.content.Context
import java.io.File
import lobos.kernel.layout.SystemDirs
import lobos.services.log.RuntimeDiagnostics
import lobos.services.supply.ProgramInstallPipeline

object PreinstalledPieces {

    private const val ASSET_DIR = "pieces"

    private fun trace(ctx: Context, stage: String, detail: String) {
        lobos.services.log.Journal.note(ctx, "prefix-trace", null, stage, detail)
    }

    /**
     * 那 APK 里那**几件**的名字（=> 而那**件**名逐字**去**那 .zip **那个**后缀**）。
     *   => 而它逐字**给**那**逐件**做**那一族**用**（=> 而而那**它**逐字**不**自己**装**）。
     */
    fun pieceIds(ctx: Context): List<String> =
        runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".zip") }
            .map { it.substringBeforeLast('.') }
            .sorted()

    /**
     * 装**那一个**件（=> 而那**件**名逐字**给**进来）。
     *   => 而而那**它**逐字**记**那**一笔**（=> 而而那**它**逐字**是**那**逐件**那一族**的**一**件**）。
     */
    fun installOneById(ctx: Context, id: String): Boolean {
        trace(ctx, "装 " + id, "开始")
        val r = try {
            installOne(ctx, id)
        } catch (e: Throwable) {
            trace(ctx, "装 " + id, "抛了 " + e.javaClass.name + ": " + (e.message ?: ""))
            false
        }
        //  缺陷名（C-102）**那「；成」**那一行**逐字**写**在**那 installOne **返**回**之后**、=> 而而那**它**逐字**晚**。
        //    => 而那**成因**：=> 而那**调用方**（=> 而那 installOneById **:40** 那一行）逐字**等**那 installOne **返**回**才**写**，
        //      => 而而那**进程**逐字**在**那**注册**之后**、**在**那**调用方**写**那一行**之前**、逐字**被**杀**了**。
        //    => 而而那**后果**逐字 = 而那 journal 里**没有**那「；成」**那一行**（=> 而而那**它**逐字**看**不出**那**一件**到底**装没装**上**）。
        //    => 而那**处置**：=> 而那**进度**逐字**在**那**关键**步骤**上**就**记**（=> 而而那**它**逐字**在**那**注册**之前**），
        //      => 而而那**它**逐字**不**是**那**结果**那一族**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**记**的是**什么**」与「那**它**记**在**哪**」）。
        trace(ctx, "装 " + id, if (r) "成" else "败")
        return r
    }

    fun installAll(ctx: Context): List<String> {
        trace(ctx, "进入", "thread=" + Thread.currentThread().name)
        val names = runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .also { trace(ctx, "assets.list", "结果 " + (it.getOrNull()?.size ?: -1) + " 项，异常=" + (it.exceptionOrNull()?.javaClass?.simpleName ?: "无")) }
            .getOrDefault(emptyList())
        trace(ctx, "assets 列表", names.joinToString(",").take(300))
        if (names.isEmpty()) {
            RuntimeDiagnostics.append(ctx, "prefix", true, "APK 里没有预装件")
            return emptyList()
        }

        val done = mutableListOf<String>()
        var failed = 0

        for (n in names) {
            if (!n.endsWith(".zip")) continue
            val id = n.substringBeforeLast('.')
            //  => 而那**逐件**那一族逐字**走**那 installOneById（=> 而而那**它**逐字**记**那**一笔**、=> 而而那**它**逐字**是**那**一**件**的事**）。
            val r = installOneById(ctx, id)
            if (r) done += id else failed++
        }

        RuntimeDiagnostics.append(
            ctx, "prefix", failed == 0,
            if (failed == 0) "预装件全部就位（${done.size} 件）"
            else "预装件 $failed 个没装上",
            "已装=" + done.joinToString(",") +
                if (failed > 0) "  失败=" + failed else "",
        )
        runCatching { lobos.kernel.layout.LdConfig.run(ctx) }
            .onFailure {
                RuntimeDiagnostics.append(
                    ctx, "envprobe", false, "环境探针失败",
                    it::class.java.simpleName + ": " + it.message,
                )
            }
        return done
    }

    private fun fail(ctx: Context, id: String, why: String): Boolean {
        RuntimeDiagnostics.append(ctx, "prefix", false, "预装件 $id", why)
        return false
    }

    private data class DerivedCheck(
        val total: Int,
        val okCount: Int,
        val rebuilt: Int,
        val detail: String,
    )

    /** 校验这一件的派生物软链，有一条不对就当场重算它。
     *
     *  缺陷（C-102） 落位检查只验原物在不在、派生物指向哪不管
     *   软链是派生物而落位跳过已存在项，派生物因此永不刷新
     *   link 成功不等于目标可达
     *
     *  判据：每一条「指向这一件」的软链，目标必须存在，且必须落在落位目录里。
     *  真机读数（07，ls -l，**旧版落位那一版**：5a265b1）：libbusybox.so / liblobosjq.so / libssl.so 的目标是
     *  files/data/user/0/lobos.os/files/opt/<id>/<ver>/<ver>/<entry> —— 前缀两遍、版本段两遍，
     *  而 linkcrypto/libcurl/libz 那三行是干净的。跨多版装机不变，因为这一支只验原物。
     *
     *   缺陷名（C-102）**派生物校验也按目录名分家**：原来那一行是
     *    if (entry.startsWith("lib/")) libDir else binDir —— 又是「看目录名」。
     *   现在目录由**形态**决定（[role] 那一格，产物带来的）。
     */
    private fun refreshDerivedLinks(
        ctx: Context,
        id: String,
        version: String,
    ): DerivedCheck {
        val verDir = File(SystemDirs.pieceDir(ctx, id), version)
        // 族二（这一件是程序还是库）：派生物**按产物里实际存在的每一种形态各建各的那一条**。
        //    而这一格**不再按「一个 primary + 一个 role」算**——
        //      那样算出来的 `dirs` 由那一个 role 二选一， 而 mixed 那一件两种形态都有，
        //      于是另一种形态该建的那一条**永远不在检查范围内**。
        //    而**这一格也不接受「按 entry 猜」**： 形态是构建期算好的（04 今天那张表第二行），
        //      运行期只读产物那一份（ 读不到就报缺，  不按某一支兜底）。
        //   缺陷名（C-102）**mixed 那一件被按单一形态看待**：
        //     原来 primary / landedRel / dirs 三处都只按**一个** role 算，
        //     而 dirs 由那个 role 二选一（ROLE_PROGRAM => 只有 binDir、ROLE_LIBRARY => 只有 libDir）
        val shapeHere = ProgramInstallPipeline.PieceShape.read(verDir)
        val targets = ArrayList<Triple<String, String, String>>()   // (落位后那一份的相对路径, 该建的目录, 形态)
        if (shapeHere != null) {
            for (r in listOf(ProgramInstallPipeline.ROLE_LIBRARY, ProgramInstallPipeline.ROLE_PROGRAM)) {
                val items = shapeHere.byRole(r)
                if (items.isEmpty()) continue
                val dir = ProgramInstallPipeline.PieceShape.dirForRole(r) ?: continue
                val name = items.first().path.substringAfterLast('/')
                targets.add(Triple(dir + "/" + name, when (r) {
                    ProgramInstallPipeline.ROLE_PROGRAM -> lobos.kernel.layout.PrefixProvisioner.binDir(ctx).absolutePath
                    else -> lobos.kernel.layout.PrefixProvisioner.libDir(ctx).absolutePath
                }, r))
            }
        }
        if (targets.isEmpty()) {
            return DerivedCheck(
                0, 0, 0,
                "落位目录里没有形态说明（" + ProgramInstallPipeline.SHAPE_NAME +
                    "）—— 派生物该建在哪由产物那一份决定（ 而不按某一支兜底）",
            )
        }
        var total = 0
        var ok = 0
        var rebuilt = 0
        val bad = mutableListOf<String>()
        for ((landedRel, dirAbs, role) in targets) {
            val dir = File(dirAbs)
            val name = landedRel.substringAfterLast('/')
            val landed = File(verDir, landedRel).canonicalFile.path
            val link = File(dir, name)
            //  记录那一件的入口来源 = APK 原件（=> 而那来源这一格是「回到 APK 原件」可判的事实）。
            //   缺陷名（C-102）**只记了程序形态那一条**：这一件若同时有库形态与程序形态
            //   （mixed），=> 而那**两个角色各建一条派生物软链**，=> 而而原来这一处
            //   按 targets 逐条写同一件的那一格，=> 而**后一条把前一条盖掉了**
            //   （=> 而那盖掉的是「库那条入口指针」）。=> 而回退要落回的是**入口那一格**
            //   （=> 而那是程序形态那一条），=> 而所以现在**按角色记**：
            //   每个角色各记一格（角色 + 那一条入口指针路径 + 那一份字节路径）。
            //   缺陷名（C-102）**库形态那一格只记了字节、没记那条指针**：
            //   而库形态的入口指针（usr/lib/<名>）恰恰是**回退要落回的那一条**，
            //   => 而那一格没记 => 而「回到 APK 原件」对纯库件无从判。
            //   现在：两个角色**都**按「角色 + 入口指针路径 + 那一份字节路径」记。
            runCatching {
                lobos.services.supply.PieceOriginStore.recordApkSlot(
                    ctx, id, version, role, link.absolutePath, File(verDir, landedRel).absolutePath,
                )
            }
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && !link.exists()) {
                // 这一条**该有而没有**（ 而 total 只数已存在的，  所以那一格看不见它）
                //   显式记一条：「派生物缺失」——  而不靠 total=0 让人去猜 
                bad += dir.name + "/" + name + " 缺失（该有而没有）"
                continue
            }
            total++
            val okNow = runCatching {
                // readSymbolicLink() 在 java.nio.file.Path 上，不在 java.io.File 上。
                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (okNow) { ok++; continue }
            //  指向**落位后那一份**（ 而 entry 是清单里那一条  对纯库件是空  指向目录）
            val fresh = File(verDir, landedRel)
            val made = runCatching {
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                android.system.Os.symlink(fresh.absolutePath, link.absolutePath)
                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (made) { ok++; rebuilt++ } else {
                bad += dir.name + "/" + name + " 重算后仍不通"
            }
        }
        return DerivedCheck(total, ok, rebuilt, bad.joinToString("；"))
    }

    private fun installOne(ctx: Context, id: String): Boolean {
        val pkg = extract(ctx, id) ?: return fail(ctx, id, "assets 里取不出 pieces/$id.zip")

        val unpacked = File(ctx.cacheDir, "preinst/u/$id").also { it.deleteRecursively(); it.mkdirs() }
        runCatching { lobos.services.supply.SupplyProvisioner.unzipFromFile(pkg, unpacked, ctx) }
            .onFailure { return fail(ctx, id, "解包失败: " + it.message) }

        val meta = runCatching {
            val f = File(unpacked, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) return fail(ctx, id, "包里没有 component-meta.json（解包后）")
            org.json.JSONObject(f.readText())
        }.getOrNull() ?: return fail(ctx, id, "component-meta.json 解析失败")

        val version = meta.optString("version", "").ifBlank { return fail(ctx, id, "说明里没有 version") }
        // entry **允许为空** —— 那是「这一件没有程序那一侧」（纯库件：crypto/curl/zlib）。
        //  缺陷名（C-102）纯库件被当成「说明里没有 entry」而报缺：
        //   原来这一行是 ifBlank { return fail(… "说明里没有 entry") }，
        //   而那把「没有入口」当成了「清单坏了」 纯库件一步都装不上。
        //   而按 C-05「没有」就是没有：纯库件**本来就该没有入口**（07 今天的复算表：
        //   crypto/curl/zlib 复算 role=library 且 bin/ 目录不存在）。
        val entry = meta.optString("entry", "").trim()

        // 形态只从**产物自带的** piece-shape.json 读。读不到  报缺，
        // 不探测那份字节、不猜、不兜底（用户 2026-10-20 三行规则第三条）。
        val shape = ProgramInstallPipeline.PieceShape.read(unpacked)
            ?: return fail(ctx, id, "包里没有 piece-shape.json（产物没带形态）—— 报缺，不探测字节")
        if (entry.isNotBlank() && !File(unpacked, entry).isFile) return fail(ctx, id, "包里没有 entry 指的文件 " + entry)

        // 落位后入口在哪：由形态决定（库进库目录、程序进程序目录），不看目录名。
        // 纯库件没有入口  landedEntry 为空  它只落库那一份（dirForRole 那把尺）。
        //  编译错误（run 39415331935 第21步）：entryRole 原来声明在 if 块里，
        //   而下面三处（refreshDerivedLinks / ManifestWriter.write / register）要它  提到外层。
        // 族二（这一件是程序还是库）：构建期按形态算出，运行期**只读那一格**（04 今天那张表第二行）。
        //   缺陷名（C-102）**这一格有两把尺**：
        //     原来空 entry 时这一处**自己答 ROLE_LIBRARY** —— 而那是运行期在猜形态。
        //     而形态是构建期算好的（scripts/toolchain/elf-form.sh 照那一把尺），随产物落进
        //     piece-shape.json 的 files[].role —— 运行期只该读它。
        //   真机读数（01 今天核过，2026-10-20）：busybox 那个落位目录里 lib/ 是空的、
        //     而 bin/ 下 19 个实体 => **形态与角色在那一份上已经对不上了**。
        //   => 现在：只从产物那一份取；取不到就报缺（C-05），不自己答一个。
        //
        // ★ 缺陷名（C-102）**这一格取错了输入** —— 而 04 今天裁的判据方向是：
        //   「**按 entry 那一项的形态**」，=> 而**不是**「先找库形态的落位项」。
        //
        // 而改前的形态（=> 而本机逐处核到了它怎么把 openssl 那一件判错）：
        //   val entryRole = shape.byRole(ROLE_LIBRARY).firstOrNull()?.role   （=> 而那**先**找库形态）
        //   => 而 openssl 那一件**有** lib/libcrypto.so 与 lib/libssl.so => => 而它**找到了**
        //   => => 而 entryRole = **library**（=> 而**不是**按 entry 那一项的形态，
        //   => 而 entry 那一项逐字是 bin/openssl、=> 而它在产物那一份里的 role 逐字是 **program**）
        //   => => 而落位侧那份 program-manifest.json 的 role 逐字就是 library
        //   => => 而 :541 那一行 `assetEntry = if (isProgram) entry else ""` 把它清空
        //   => => 而索引里 openssl 那一行 assetEntry 逐字是空、role 逐字是 library
        //   => => 而 levelOf 判 PIECE => => 而它进不了监督名单（SupervisorPool.kt:136 那一道）
        //   => => 而它那一件自己的 bin/openssl 也因此**进不了** usr/bin。
        //
        // => 而那一族的判别形式（可机械判）：「**这一件有没有程序形态的字节**」
        //   => 而 openssl **有**（=> 而 bin/openssl 在产物那一份的 programs 那一列里）。
        //
        // => 而按 C-103 那一格该问的是「**这一件有没有可调起的入口**」
        //   => 而那**正是** entry 那一格（=> 而它非空就答它）。
        //
        // => 而「entry 为空」那一支**仍**取库形态那一项
        //   （=> 而那是纯库件那一族：=> 而它们**真的**只有库形态的字节，=> 而而它们**该**是 library）。
        val entryRole: String = if (entry.isNotBlank()) {
            shape.roleOf(entry)
                ?: return fail(ctx, id, "入口 " + entry + " 的形态不在产物的形态说明里 —— 报缺，不猜")
        } else {
            shape.byRole(ProgramInstallPipeline.ROLE_LIBRARY)
                .firstOrNull()?.role
                ?: return fail(
                    ctx, id,
                    "产物没有带库形态的落位项，而这一件又没有入口 —— 形态是构建期算好的，" +
                        "运行期只读那一格；读不到就报缺，不自己答一个",
                )
        }
        val landedEntry = if (entry.isBlank()) "" else {
            ProgramInstallPipeline.PieceShape.landedRel(shape, entry)
                ?: return fail(ctx, id, "入口 " + entry + " 的形态认不出（role=" + entryRole + "）—— 报缺，不猜")
        }

        val verDir = File(SystemDirs.pieceDir(ctx, id), lobos.services.reg.ProgramIndex.safeSegment(version) ?: return fail(ctx, id, "版本号非法 " + version))
        //  纯库件没有入口  这一支的判据不能是「verDir/<entry> 在不在」
        //   （空 entry 时 File(verDir, "") 是目录  恒真  误短路）。
        //    纯库件验的是「它那一份库字节在不在 lib/ 下」——按形态（role=library）判。
        //  **「已落位」的判据是「是不是同一份」，不是「在不在」** ——
        //   缺陷名（C-102）**旧字节永远换不掉**：
        //     原来这里判的是「落位目录里那份东西在不在」—— 而「在」只说明**有个东西在那儿**，
        //     不说明它是**这一份**。而落位目录是 opt/<id>/<version> ——
        //     **版本号没变而内容变了**（指纹变了） 同一目录  旧字节留着  新的没落下来。
        //    真机读数（01 今天逐件核的，2026-10-20）：
        //     真机 busybox 那一件的落位目录 mtime = 04:49（唯一旧的一件），
        //     它的字节 sha = 87e32d67… 而新包那份是 4ce4a418…（我今天核过新包的 files[].sha256）
        //      **同一份吗？不是**  那一格今天判「不一致」 走重装  新字节落下来。
        //    现在：逐项比对**产物自带的 files[].sha256**与落位目录里那份的 sha256。
        //     而 piece-shape.json 是产物自带的（构建期算的），落位目录里那份的 sha 现算。
        //  纯库件（没有入口）那一格也用**同一把尺**（是不是同一份）：
        //   原来它判的是「lib/ 下有没有文件」—— 而那同样只说明「有个东西在那儿」。
        val landedPresent = if (landedEntry.isBlank()) {
            val libDir = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY) ?: "lib")
            libDir.isDirectory && libDir.listFiles()?.any { it.isFile } == true
        } else {
            File(verDir, landedEntry).isFile
        }
        // 族三（是不是我们要的那一代）：逐项比「落位上那份字节的 sha256」与「产物 files[].sha256」。
        //   缺陷名（C-102）**「跳过当成相同」在比代这一格上把「不知道」当成了「是」**：
        //     原来这一支有两处 `return@all true`：
        //       · fs.sha256.isBlank()          => 产物没给摘要，而这一格答「相同」
        //       · dst.canonicalFile 取不到       => 路径算不出来，而这一格也答「相同」
        //     而 §1.55：缺字段要报缺、不拿缺省当值。
        //      而后果是可核的：产物少给一条摘要 => 那一项永远被判「相同」=>
        //        那一项**永远不会被换掉**（ 这就是「旧字节换不掉」那一族里的一格）
        //   判别形式：那一项缺摘要时，这一格答的是「相同」还是「无法判定」；
        //     答「相同」=> 这一格把「缺证据」当成了「证据支持」。
        //   => 现在：缺摘要或路径算不出来都答「无法判定」，而「无法判定」**不判已落位到最新**
        //     （=> 走重装，而重装会重新落位并重写这一格）。
        val unverifiable = mutableListOf<String>()
        //  ★ 缺陷名（C-102）**这一格只比「产物说有的那几项」** —— => 而而**产物说没有的那几项**
        //    **从来没被比过** => => 而落位目录里**多出来**的那些项**永远留着**。
        //    => 而那正是 07 今天在设备侧核到的那一格：
        //      · 6c107c7 那一版产物里**没有** bin/c_rehash（=> 而本机核过产物那一份），
        //      · 而这一趟 journal 逐字有「OK 解包 bin/openssl」两行、=> 而**含 c_rehash 的解包行数 = 0**
        //        （=> 而这一版压根没解包它），
        //      · 而设备上 opt/openssl/3.6.3/bin/c_rehash 那个字节**逐字还在**、
        //        而 mtime 逐字是 2026-10-24 06:31（=> 而那是 84df912 那一版落下的遗留物），
        //      · 而这一版**既没再落它、也没删它**。
        //    => 而成因：那一段比的是**逐项 sha256**（=> 而它只遍历 shape.files），
        //      => 而而**多出来的**那几项**不在** shape.files 里、=> 而它们**不被比**、
        //      => 而而「同内容不重写」那一短路就**成立**（=> 而 landedUpToDate 为真），
        //      => 而而 install **压根没跑** => => 而那几项遗留物**永远删不掉**。
        //    => 而处置：这一格还要核「落位目录里**有没有**产物说没有的那几项」；
        //      => 而有 => 而那一格**不**判已落位到最新 => => 而走重装 => => 而 install 跑
        //      => => 而 install 那一族逐字是「整目录换上去」（=> 而旧那一份被 renameTo 挪走、
        //         => 而然后 deleteRecursively）=> => 而那一项**自然**没有���。
        val staleInLanding = mutableListOf<String>()
        run {
            val declared = shape.files.map { fs ->
                val d = ProgramInstallPipeline.PieceShape.dirForRole(fs.role) ?: fs.dir
                d + "/" + fs.path.substringAfterLast('/')
            }.toSet()
            for (roleDir in listOf(
                ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_PROGRAM),
                ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY),
            )) {
                val rd = roleDir ?: continue
                val dir = File(verDir, rd)
                val kids = dir.listFiles() ?: continue
                for (k in kids) {
                    val rel = rd + "/" + k.name
                    if (!declared.contains(rel)) staleInLanding += rel
                }
            }
        }
        if (staleInLanding.isNotEmpty()) {
            lobos.services.log.Journal.note(
                ctx, "prefix-trace", false,
                "落位目录里有产物说没有的那几项 " + id,
                staleInLanding.joinToString("，") +
                    "（=> 而那一版产物里没有它们，=> 而它们是上一版落下的遗留物，" +
                    "=> 而按「产物说没有的就不该在」这一条，=> 而走重装把它们换掉）",
            )
        }
        val sameBytes = shape.files.all { fs ->
            if (fs.sha256.isBlank()) {
                unverifiable += fs.path + "（产物没带摘要）"
                return@all false
            }
            val dst = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(fs.role) ?: fs.dir)
                .let { File(it, fs.path.substringAfterLast('/')) }
            val real = runCatching { dst.canonicalFile }.getOrNull()
            if (real == null) {
                unverifiable += fs.path + "（落位路径算不出来）"
                return@all false
            }
            if (!real.isFile) return@all false
            sha256Of(real) == fs.sha256
        }
        // 格三（是不是我们要的那一代）：读产物自带的那一格构建标识。
        //   C-104（加了之后有没有消费者）：而 01 今天核到**今天零个消费者**
        //   （落位侧只读 component-meta.json 的 version 与 entry 两格）——
        //   而「加了字段」与「加了读者」必须同一处落地，否则那一问的答案就是「是」。
        //    而它要答的是：「落位上那份是不是**这一次构建**造出来的那一份」。
        //    而它与 sameBytes 是两问（04 今天 §1.93 与本节）：
        //     · sameBytes 答「落位上那份 == 产物里那份」（内容）
        //     · 这一格答「产物里那份 == 这一次构建的那一份」（代）
        //    而落位目录里没有这一格（它只存在包里那份 component-meta.json 里）
        //    而落位目录那份清单是 installComponent 落位后写的 ——  而它写的时候
        //    有没有把构建标识一起写进去，决定这一格能不能答。
        //  处置：**只报，不拦**（03 今天给的两条我判甲，理由是判据那一层）。
        //   甲 只报：写一条诊断，不动落位。后果是那一件是旧代时系统继续用旧字节跑。
        //   乙 拦住：判它不是这一代就不落。后果是「拿不到」与「不是」在读侧不可分
        //     —— 而本地件（buildSha=local）与旧件（落位目录里那份没有那一格）
        //     **都读不到**构建标识  而这一族按「拿不到要答不知道」
        //     **而「不知道」不能拦住落位**。
        //    所以：landedUpToDate **只看内容**（sameBytes），构建标识那一格只报。
        //  => 而「落位目录里有没有产物说没有的那几项」也要算进去（=> 而那一格见上面那一段）
        //     => 而有 => 而那一格**不**判已落位到最新 => => 而走重装 => => 而 install 跑
        val landedUpToDate = landedPresent && sameBytes && staleInLanding.isEmpty()
        if (unverifiable.isNotEmpty()) {
            lobos.services.log.Journal.note(
                ctx, "prefix-trace", false, "无法判定是不是那一代 " + id,
                unverifiable.joinToString("，") +
                    "（按 §1.55：缺证据不答「相同」=> 这一格不判已落位到最新 => 走重装）",
            )
        }
        if (verDir.isDirectory && landedUpToDate) {
            // 缺陷（C-102） 落位检查只验原物在不在、派生物指向哪不管
            //         软链是派生物而落位跳过已存在项，派生物因此永不刷新
            //  这一支原来只验 verDir/<entry>（原物）在，就 register 并 return true；
            //   而 usr/bin 与 usr/lib 下指向这一件的软链是**派生物**，坏链就永远留在这儿。
            //   真机读数（07，**旧版落位那一版**：5a265b1）：libbusybox.so / liblobosjq.so / libssl.so 三条坏的，跨多版装机不变。
            //    而那三条坏链的名字里有 liblobosjq.so —— 而今天 jq 那一件的 lib/ 下不再有那一份
            //   （03 今天那一问：零人 NEEDED 它  产物里就不该有） 那一版的名字不再成立。
            //  return 之前先校验派生物；有一条不对就重算它。
            //   缺陷名（C-102）**短路那一支按目录名验原物**：原来验的是 verDir/<entry>，
            //    而 entry 是清单里那一条（lib/ 那份） 这一支的判据是「目录名对不对」，
            //    不是「形态对不对」。现在验的是**落位后那一条**（形态决定的）。
                    // 格一那一族的收尾：**快路径也要刷新落位目录那两份产物自带的清单**。
        //   缺陷名（C-102）**只刷了其中一份，而建链读的是另一份**：
        //     · 产物自带两份：component-meta.json（META_NAME）与 piece-shape.json（SHAPE_NAME）
        //     · 而 refreshDerivedLinks 读的是 **piece-shape.json**（PieceShape.read(verDir)）
        //     · 而原来这一处只刷 component-meta.json   建链读到的是**上一次重装带过来的**那一份 
        //     ·  而 62e0c7c 只做了一半 ——  而那一半就是「六件报缺」那一格的原因 
        //    而真机读数（07，2026-10-21，run 39483058890 / head=fec29ac）：
        //     八件的 component-meta.json 全部刷新（mtime 03:39/03:40，全部带 buildSha）
        //     而 piece-shape.json **只有 busybox 与 ripgrep 两件有** —— 
        //     而那两件正是**今天被重装过**的那两件   ** 这是「那个动作的副作用」，
        //      而不是「那六件坏了」**（04 今天第二段那条判别）
        //    现在：两份都刷，**而且这份在 refreshDerivedLinks 之前**（  而顺序是这一格的关键：
        //      建链读的那一份要在刷新之后才拿得到   顺序要反过来 ）
        //    而 piece-shape.json **逐字复制产物那一份** —— 
        //      而它不是登记（  登记是 manifest 与 index， 而它只描述「这一件是什么」）
        //      而它是**构建期算好的那一格**（C-16：  运行期只读不判断）   所以照抄 
            val landedMeta = runCatching {
                val j = org.json.JSONObject(meta.toString())
                j.put("entry", landedEntry)
                j
            }.getOrNull()
        //  ★ 缺陷名（C-102）**落位侧按哪一把尺落的权限** —— 而**这一支**没有落权限。
        //   而慢路径那一支有（=> 而 ProgramInstallPipeline 逐字是「权限按**产物声明的 mode** 落
        //   （运行期不判形态、不替产物决定权限）」，=> 而它逐项 chmod）。
        //   而预装件走的是**快路径**（=> 而这一支），=> 而这一支**原来一个 chmod 都没有**。
        //   => 而那后果是：解包出来的那个字节**带着 zip 里的权限**落下去，
        //      => 而而 zip 里那一项**没有**被执行位时（=> 而 c_rehash 那一项逐字是 -rw-------），
        //      => 而而它在盘上**不可执行**，=> 而而它也**建不出** usr/bin 下的那一条软链。
        //   => 而处置：这一支也按**产物声明的 mode** 逐项落（=> 而与慢路径同一把尺、同一句话）。
        //   => 而**不**在这里判形态（=> 而形态是构建期算好的、=> 而那一格在 shape.files[].mode 里）。
        val modesLanded = runCatching {
            var n = 0
            for (fs in shape.files) {
                if (fs.mode.isBlank()) continue
                val dst = File(
                    verDir,
                    ProgramInstallPipeline.PieceShape.dirForRole(fs.role) ?: fs.dir,
                ).let { File(it, fs.path.substringAfterLast('/')) }
                if (!dst.isFile) continue
                val m = runCatching { fs.mode.toInt(8) }.getOrNull() ?: continue
                runCatching { android.system.Os.chmod(dst.absolutePath, m) }
                n++
            }
            n
        }.getOrDefault(0)
        if (modesLanded == 0 && shape.files.isNotEmpty()) {
            trace(ctx, "落权限", id + " 一项也没落（产物没带 mode）—— 而运行期不替产物决定权限")
        }
    val manifestWritten = runCatching {
                lobos.services.supply.ManifestWriter.write(
                    verDir, landedMeta, version, entryRole
                )
            }
        val shapeRefreshed = runCatching {
            val src = File(unpacked, ProgramInstallPipeline.SHAPE_NAME)
            if (!src.isFile) {
                false
            } else {
                val dst = File(verDir, ProgramInstallPipeline.SHAPE_NAME)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                true
            }
        }.getOrDefault(false)
        if (!shapeRefreshed) {
            trace(ctx, "刷新 " + ProgramInstallPipeline.SHAPE_NAME,
                id + " 失败（快路径）：产物那一份不在 —— 而建链会报缺，不猜")
        }
        val metaRefreshed = runCatching {
            val j = landedMeta ?: return@runCatching false
            val dst = File(verDir, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            dst.parentFile?.mkdirs()
            dst.writeText(j.toString(1) + "\n")
            true
        }.getOrDefault(false)
        if (!metaRefreshed) {
            trace(ctx, "刷新 " + lobos.kernel.layout.PrefixProvisioner.META_NAME,
                id + " 失败（快路径）：落位目录那份仍是旧的那一份")
        }
val derived = refreshDerivedLinks(ctx, id, version)
            trace(ctx, "校验派生物软链", id + " 共=" + derived.total + " 对=" + derived.okCount +
                " 重算=" + derived.rebuilt + " " + derived.detail)
            runCatching { pkg.deleteRecursively() }
        runCatching { unpacked.deleteRecursively() }


            trace(ctx, "写 program-manifest.json", id + " 成=" + manifestWritten.isSuccess +
                " 目标=" + verDir.absolutePath + "/" + lobos.services.supply.ProgramDir.MANIFEST_NAME +
                (if (manifestWritten.isSuccess) "" else " 失败=" + manifestWritten.exceptionOrNull()))
            //  => 而那**进度**逐字**在**那**注册**之前**就**记**（=> 而而那**它**逐字**是**那**关键**步骤**上的**那一个**）。
            //    => 而那**它**逐字**不**是**那「；成」**那一族**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**记**的是**什么**」与「那**它**是**不是**那**结果**」）。
            trace(ctx, "装 " + id, "登记前")
            register(ctx, id, version, relStateDir(id), landedEntry, entryRole, verDir, landedMeta)
            return true
        }
        val r = ProgramInstallPipeline.install(
            ctx,
            ProgramInstallPipeline.Spec(
                from = ProgramInstallPipeline.From.APP,
                programId = id,
                zip = pkg,
                shape = ProgramInstallPipeline.Shape.COMPONENT,
                expectedVersion = version,
                expectedEntryRel = entry,
            ),
        )
        runCatching { pkg.deleteRecursively() }
        runCatching { unpacked.deleteRecursively() }
        if (!r.ok) {
            RuntimeDiagnostics.append(
                ctx, "prefix", false, "预装件 $id 装不上",
                (r.reason ?: "") + " — " + r.detail,
            )
            return false
        // 格三（是不是我们要的那一代）：比「**install 之后**落位目录那份 vs 产物那份」。
        //   缺陷名（C-102）**检查跑在 install 之前，读到的是上一次留下的旧文件**：
        //     原来这一段在 install 调用之前，而 install 在本函数后面
        //      它读到的落位目录那份**还没被换成包里那一份**
        //     => 八件全报「不是这一次构建的那一份」。
        //    真机读数（07，2026-10-21）： 那 8 条全在同一个 prefix-trace 轮次里（ (b) 不成立），
        //      而 APK 里那个 zip 与 Release 上那一份 cmp 逐字相同（ (a) 也不成立）
        //       那一格的真根因是**行序**  这一段挪到 install 之后。
        //    而这一格**不参与**「已落位到最新」那个判断（04 今天第七条：
        //      构建标识报不一致 = 观察点，不拦；逐项摘要报不一致 = 闸门，拦）
        //       它在这里只写一条诊断。
        //    而**三种状态不可互相替代**（01 今天 S53，C-05）：
        //       那一格写着值 → 可答；  那一格**不存在** → 报缺（ 而它**不是**「= APK 那一代」）；
        //       写着值而 ≠ APK 那一格 → 不同代。 而**答不出「是」时要答「缺」或「不同」，不许答「是」**。
        val pkgBuild = runCatching {
            val f = File(unpacked, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) "" else org.json.JSONObject(f.readText()).optString("buildSha", "").trim()
        }.getOrDefault("")
        val landedBuild = runCatching {
            val f = File(verDir, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) "" else org.json.JSONObject(f.readText()).optString("buildSha", "").trim()
        }.getOrDefault("")
        // 01 今天 S53：三态不可互相替代（C-05）——而**三态判别要求两处都先确认存在**：
        //    两处都存在而相等  => 同代
        //    **有一处不存在**  => 报缺（ 而它**不是**「= APK 那一代」， 也不是「= 未知的那一代」）
        //    两处都存在而不等  => 不同代
        //  而「凡那一格参与比较，先确认它在每一件上都存在；有一件缺  那一格整体报缺」
        //  而「local」那个占位**也要报缺**（04 今天那条总判据）：
        //   它是「本地造的那一份」，而它与「= APK 那一格」在读侧**不可区分**
        //    而结论需要一个具体值（「是不是这一次构建的」）  那一格必须报缺，不许填 
        //    而它与 APK 那一格都是 40 位时两者逐字相同  「恰好同相」 
        //    换一个输入就不同相了（ APK 那一格不是 local）   不能拿它判「同代」。
        val pkgIsLocal = pkgBuild.equals("local", ignoreCase = true)
        val landedIsLocal = landedBuild.equals("local", ignoreCase = true)
        when {
            pkgBuild.isBlank() || landedBuild.isBlank() || pkgIsLocal || landedIsLocal -> {
                val missing = listOfNotNull(
                    "产物那份".takeIf { pkgBuild.isBlank() || pkgIsLocal },
                    "落位目录那份".takeIf { landedBuild.isBlank() || landedIsLocal },
                ).joinToString(" 与 ")
                lobos.services.log.Journal.note(
                    ctx, "prefix-trace", null, "报缺：无法判定是不是这一代 " + id,
                    missing + "没有 buildSha 那一格 —— 而「缺」既不是「= APK 那一代」， 也不是「= 未知的那一代」",
                )
            }
            pkgBuild != landedBuild -> {
                lobos.services.log.Journal.note(
                    ctx, "prefix-trace", false, "不同代：不是这一次构建的那一份 " + id,
                    "产物 buildSha=" + pkgBuild + "、落位 buildSha=" + landedBuild +
                        " —— 只报，不拦落位（构建标识是观察点，逐项摘要才是闸门）",
                )
            }
        }

        }
        return true
    }

    /** 一个文件的 sha256（小写十六进制）；读不到就空串。 */
    private fun sha256Of(f: File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** 纯库件那一份库字节的名字 —— 从落位目录 lib/ 下取（**按形态**：库形态的那一份）。
     *   库件没有入口（清单里 entry 空） 「库在哪里」那一格记的就是这个名字。
     *   而它不按目录名挑：lib/ 下若有多个 .so，取**主名**那一个（名字恰以 .so 结尾）——
     *    主名是「这一件的那一份」的标记（与构建侧同一把尺）。 */
    private fun libFileNameOf(verDir: File): String {
        val libDir = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY) ?: "lib")
        val files = libDir.listFiles()?.filter { it.isFile } ?: return ""
        val primary = files.firstOrNull { it.name.endsWith(".so") }
        return (primary ?: files.firstOrNull())?.name ?: ""
    }

    /** 落位目录的**相对**路径（opt/<id>）—— 注册表里那一格是相对路径，
     *  ProgramManager.stateDirOf 拼的是 File(ctx.filesDir, stateDir)。 */
    private fun relStateDir(id: String): String =
        lobos.kernel.layout.SystemDirs.relPieceDir(id)

    private fun extract(ctx: Context, id: String): File? = runCatching {
        val dst = File(ctx.cacheDir, "preinst/$id.zip")
        dst.parentFile?.mkdirs()
        ctx.assets.open("$ASSET_DIR/$id.zip").use { ins ->
            dst.outputStream().use { ins.copyTo(it) }
        }
        dst.takeIf { it.length() > 0 }
    }.getOrNull()

    /** 这一件是程序还是库 —— **只读产物的形态那一格**（[role]），不猜、不探测。
     *  （原来的这一格读的是 meta 的 `kind` —— 那是清单里我们自己写的字面量，
     *    而形态的真源是产物自带的 piece-shape.json。） */
    private fun levelOf(role: String): lobos.services.reg.Level =
        if (role == ProgramInstallPipeline.ROLE_PROGRAM) lobos.services.reg.Level.PROGRAM
        else lobos.services.reg.Level.PIECE

    /** 登记这一件。**注册表只记「库在哪里」与「程序在哪里」。**
     *
     *   缺陷名（C-102）**库那份也被记成入口**：原来这一行无条件
     *    `assetEntry = entry`（清单里那一条，不分形态） lib/ 下那份也成了「入口」，
     *    于是「入口格有值」被当成了「这一件是程序」——
     *    今天五件失败的成因：入口填到了 lib 那一份。
     *   现在**入口格只对程序形态填**；库形态那一格空着（= 只有落位目录）。
     *
     *   缺陷名（C-102）**role 由运行期现读字节填**：原来这一行是
     *    `role = PieceScan.roleFromDisk(ctx, id)`（walk 落位目录读 ELF 头）。
     *    形态是**生产时就知道的事** —— 现在从产物带来的那一格（[role]）读。
     *
     *   role 回答的是「它是什么」；「它是不是底座解释器」是另一个问题（§10.1:138），
     *    由 [ShellEntry] 的判据回答，不靠这个字段。
     */
    private fun register(
        ctx: Context,
        id: String,
        version: String,
        stateDir: String,
        entry: String,
        role: String,
        //  编译错误（run 39415331935 第21步）：verDir 未定义 —— register 是私有函数，
        //   而「那份库字节的名字」要从**落位目录**里取（按形态） 把落位目录传进来。
        verDir: File,
        meta: org.json.JSONObject?,
    ) {
        val prev = lobos.services.reg.ProgramIndex.get(ctx, id)
        val isProgram = role == ProgramInstallPipeline.ROLE_PROGRAM
        runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                ctx,
                (prev ?: lobos.services.reg.ProgramIndex.empty(id, levelOf(role))).copy(
                    id = id,
                    version = version,
                    // 落位目录记**相对**路径（opt/<id>）——
                    // ProgramManager.stateDirOf 拼的是 File(ctx.filesDir, stateDir)，
                    // 绝对路径会被拼成 filesDir + "/data/..." （那就找不到了）。
                    stateDir = stateDir,
                    enabled = true,
                    desired = lobos.services.reg.Desired.RUNNING,
                    //  库件没有入口（entry 空） libName 取**那份库字节**的名字。
                    //   而那一份从落位目录 lib/ 下按**形态**取（role=library），不按目录名、不按 entry。
                    libName = if (entry.isNotBlank()) entry.substringAfterLast("/") else libFileNameOf(verDir),
                    assetEntry = if (isProgram) entry else "",
                    role = role,
                    required = meta?.optBoolean("essential", false) ?: false,
                    capabilities = emptyList(),
                    requires = (meta?.optJSONArray("requires") ?: org.json.JSONArray()).let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    },
                ),
            )
        }.onFailure {
            RuntimeDiagnostics.append(
                ctx, "prefix", false, "预装件 $id 登记失败",
                it::class.java.simpleName + ": " + it.message,
            )
        }
    }
}
