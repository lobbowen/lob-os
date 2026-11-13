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

    /* *
     * APK 里那几件的名字（去掉 .zip 后缀），给逐件那一族用（它自己不装）。
     */
    fun pieceIds(ctx: Context): List<String> =
        runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".zip") }
            .map { it.substringBeforeLast('.') }
            .sorted()

    /* *
     * 装那一个件（件名给进来），并记那一笔（它是逐件那一族的一件）。
     */
    fun installOneById(ctx: Context, id: String): Boolean {
        trace(ctx, "装 " + id, "开始")
        val r = try {
            installOne(ctx, id)
        } catch (e: Throwable) {
            trace(ctx, "装 " + id, "抛了 " + e.javaClass.name + ": " + (e.message ?: ""))
            false
        }
        // 缺陷名 C-102：那「；成」那一行写在 `installOne` 返回之后、太晚。
        // 进程在注册之后、在调用方写那一行之前被杀，journal 里就没有「；成」（看不出那一件装没装上）。
        // 处置：进度在关键步骤上就记（在注册之前），不是等结果。
        trace(ctx, "装 " + id, if (r) "成" else "败")
        return r
    }

    fun installAll(ctx: Context): List<String> {
        trace(ctx, "进入", "thread=" + Thread.currentThread().name)
        val names = runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .also { trace(ctx, "assets.list", "结果 " + (it.getOrNull()?.size ?: -1) + "项，异常=" + (it.exceptionOrNull()?.javaClass?.simpleName ?: "无")) }
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
            // 逐件那一族走 `installOneById`（它记那一笔）。
            val r = installOneById(ctx, id)
            if (r) done += id else failed++
        }

        RuntimeDiagnostics.append(
            ctx, "prefix", failed == 0,
            if (failed == 0) "预装件全部就位（${done.size} 件）"
            else "预装件 $failed 个没装上",
            "已装=" + done.joinToString(",") +
                if (failed > 0) "失败=" + failed else "",
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

    /* * 校验这一件的派生物软链，有一条不对就当场重算它。
     * 缺陷 C-102：落位检查只验原物在不在、派生物指向哪不管（软链是派生物而落位跳过已存在项，永不刷新），
     * 而 link 成功不等于目标可达。判据：每一条「指向这一件」的软链，目标必须存在、且落在落位目录里。
     * 真机读数（07，ls -l，旧版落位 5a265b1）：libbusybox.so / liblobosjq.so / libssl.so 的目标是
     * `files/data/user/0/lobos.os/files/opt/<id>/<ver>/<ver>/<entry>`（前缀两遍、版本段两遍），linkcrypto/libcurl/libz 干净。
     * 旧版还按目录名分家（`if (entry.startsWith("lib/")) libDir else binDir`），现在由形态决定（`[role]`）。 */
    private fun refreshDerivedLinks(
        ctx: Context,
        id: String,
        version: String,
    ): DerivedCheck {
        val verDir = File(SystemDirs.pieceDir(ctx, id), version)
        // 族二（这一件是程序还是库）：派生物按产物里实际存在的每一种形态各建各的那一条。
        // 不再按「一个 primary + 一个 role」算——那样 `dirs` 由那一个 role 二选一，而 mixed 那一件两种形态都有，
        // 另一种形态该建的那条永远不在检查范围内。也不接受「按 entry 猜」：形态是构建期算好的（04 那张表第二行），
        // 运行期只读产物那一份（读不到就报缺、不按某一支兜底）。
        // 缺陷 C-102：旧版把 mixed 那一件按单一形态看待（primary / landedRel / dirs 三处都只按一个 role 算，
        // 而 dirs 由那个 role 二选一：ROLE_PROGRAM 只有 binDir、ROLE_LIBRARY 只有 libDir）。
        val shapeHere = ProgramInstallPipeline.PieceShape.read(verDir)
        val targets = ArrayList<Triple<String, String, String>>()   // (落位后那一份的相对路径, 该建的目录, 形态)
        if (shapeHere != null) {
            for (r in listOf(ProgramInstallPipeline.ROLE_LIBRARY, ProgramInstallPipeline.ROLE_PROGRAM)) {
                val items = shapeHere.byRole(r)
                if (items.isEmpty()) continue
                val dir = ProgramInstallPipeline.PieceShape.dirForRole(r) ?: continue
                val abs = when (r) {
                    ProgramInstallPipeline.ROLE_PROGRAM -> lobos.kernel.layout.PrefixProvisioner.binDir(ctx).absolutePath
                    else -> lobos.kernel.layout.PrefixProvisioner.libDir(ctx).absolutePath
                }
                // 缺陷名 C-102：这一格只取首项，而一件可能有好几条链。旧版 `val name = items.first().path.substringAfterLast('/')`
                // （首项是 LC_ALL=C sort 的副产品、是实体名那层）；01 裁的 S604-01 §1.4 第 3 条：openssl 有两条链
                // （libcrypto.so 与 libssl.so），「这一格不是「取第一个」」。该取指针层（软链那层）全部、再按 `chain[0]` 择一，
                // 与 PieceEntryPointer 同一套口径（C-104 判别源唯一、不各写一套）。
                val picked = ProgramInstallPipeline.PieceShape.pointerLayerOf(shapeHere, r, verDir)
                val chosen = if (picked.isNotEmpty()) {
                    picked
                } else {
                    // 择一不唯一或取不出时（ 是库形态那一族）
                    // **报缺**（ 按 C-05 不猜、 不按首项兜底）。
                    emptyList()
                }
                if (chosen.isEmpty()) {
                    // 程序形态只有一条入口（不参与择一）；库形态择一不出来时不建那一条（下面报缺）。
                    if (r == ProgramInstallPipeline.ROLE_PROGRAM && items.size == 1) {
                        val name = items.first().path.substringAfterLast('/')
                        targets.add(Triple(dir + "/" + name, abs, r))
                    }
                    continue
                }
                for (item in chosen) {
                    val name = item.path.substringAfterLast('/')
                    targets.add(Triple(dir + "/" + name, abs, r))
                }
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
            // 记录那一件的入口来源 = APK 原件（来源这一格是「回到 APK 原件」可判的事实）。
            // 缺陷名 C-102：旧版只记了程序形态那一条——mixed 那件两个角色各建一条派生物软链，原来按 targets 逐条写同一件的
            // 那一格，后一条把前一条盖掉了（盖掉的是库那条入口指针）；而库形态的入口指针（usr/lib/<名>）恰恰是回退要落回
            // 的那一条，一格没记「回到 APK 原件」对纯库件无从判。现在两个角色都按「角色 + 入口指针路径 + 字节路径」记。
            runCatching {
                lobos.services.supply.PieceOriginStore.recordApkSlot(
                    ctx, id, version, role, link.absolutePath, File(verDir, landedRel).absolutePath,
                )
            }
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && !link.exists()) {
                // 这一条**该有而没有**（ 而 total 只数已存在的， 所以那一格看不见它）
                // 显式记一条：「派生物缺失」—— 而不靠 total=0 让人去猜 
                bad += dir.name + "/" + name + "缺失（该有而没有）"
                continue
            }
            total++
            val okNow = runCatching {
                // readSymbolicLink() 在 java.nio.file.Path 上，不在 java.io.File 上。
                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (okNow) { ok++; continue }
            // 指向**落位后那一份**（ 而 entry 是清单里那一条 对纯库件是空 指向目录）
            val fresh = File(verDir, landedRel)
            val made = runCatching {
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                android.system.Os.symlink(fresh.absolutePath, link.absolutePath)
                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (made) { ok++; rebuilt++ } else {
                bad += dir.name + "/" + name + "重算后仍不通"
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
        // entry 允许为空——那是「这一件没有程序那一侧」（纯库件：crypto/curl/zlib）。
        // 缺陷名 C-102：旧版 `ifBlank { return fail(… "说明里没有 entry") }` 把「没有入口」当成了「清单坏了」，
        // 纯库件一步都装不上。按 C-05「没有」就是没有：纯库件本来就该没有入口（07 复算表：crypto/curl/zlib
        // 复算 role=library 且 bin/ 目录不存在）。
        val entry = meta.optString("entry", "").trim()

        // 形态只从**产物自带的** piece-shape.json 读。读不到 报缺，
        // 不探测那份字节、不猜、不兜底（用户 2026-10-20 三行规则第三条）。
        val shape = ProgramInstallPipeline.PieceShape.read(unpacked)
            ?: return fail(ctx, id, "包里没有 piece-shape.json（产物没带形态）—— 报缺，不探测字节")
        if (entry.isNotBlank() && !File(unpacked, entry).isFile) return fail(ctx, id, "包里没有 entry 指的文件 " + entry)

        // 落位后入口在哪由形态决定（不看目录名）；纯库件没有入口，landedEntry 为空。entryRole 原在 if 块里、三处
        // （refreshDerivedLinks / ManifestWriter.write / register）要它，已提到外层（run 39415331935 第21步）。
        // 族二（是程序还是库）：形态构建期算出，运行期只读那一格（04 那张表第二行）。缺陷 C-102：旧版空 entry 时自己答 ROLE_LIBRARY
        // （猜形态）；真机读数（01，2026-10-20）busybox 落位目录 lib/ 空、bin/ 下 19 个实体，形态与角色对不上。
        // 缺陷 C-102（取错输入）：旧版先找库形态（openssl 的 libcrypto.so 与 libssl.so 由此被找到，而 entry 是 bin/openssl/program），
        // `:541` 因此清空 assetEntry、levelOf 判 PIECE 不进监督名单（`SupervisorPool.kt:136`）；按 C-103 该问 entry 那格。
        val entryRole: String = if (entry.isNotBlank()) {
            // 缺陷名 C-102：这一格从 `files[]` 取、必然 'program'。旧版 `shape.roleOf(entry)`，而 `files[]` 的 `role` 是形态那把尺
            // （program / library / unknown），件整体的系统角色（exec / shell / library / …）不在那一栏里。
            // 后果（07 核出）：`component-meta.json` 里 bash 是 role='shell'，而 `piece-shape.json` 的 files 1 条是 role='program'
            // （busybox 30 条、roles ['program']），落位侧 `program-manifest.json` 是 'program'，注册表也是，`role='shell'`
            // 永远答不出。处置（Lead 采纳的甲）：系统角色走产物自带的 `systemRole`（构建期从 `component-meta.json` 派生、
            // 一份真源）；读不到时报缺（C-05，不猜填、不按形态那把尺兜底）。
            shape.systemRole.takeIf { it.isNotBlank() && it != "unknown" }
                ?: return fail(
                    ctx, id,
                    "产物那一份形态说明里没有那系统角色 —— 报缺，不猜" +
                        "（它逐字该从那 component-meta.json 派生、按 C-05 不猜填）",
                )
        } else {
            // 缺陷名 C-102：这一格取库形态那项的首项，而一件可能有好几条链。旧版 `shape.byRole(ROLE_LIBRARY).firstOrNull()?.role`
            // （首项是 LC_ALL=C sort 的副产品、是实体名那层）；01 裁的 S604-01 §1.4 第 3 条：openssl 有两条链（libcrypto.so
            // 与 libssl.so），「这一格不是「取第一个」」。取哪几条：调唯一那套口径（`pointerLayerOf`），择一不出来时报缺
            // （C-05，不猜、不按首项兜底）。
            val verDirForRole = File(
                SystemDirs.pieceDir(ctx, id),
                lobos.services.reg.ProgramIndex.safeSegment(version)
                    ?: return fail(ctx, id, "版本号非法 " + version),
            )
            val libLayer = ProgramInstallPipeline.PieceShape.pointerLayerOf(
                shape, ProgramInstallPipeline.ROLE_LIBRARY, verDirForRole,
            )
            libLayer.firstOrNull()?.role
                ?: return fail(
                    ctx, id,
                    "产物没有带库形态的落位项，而这一件又没有入口 —— 形态是构建期算好的，" +
                        "运行期只读那一格；读不到就报缺，不自己答一个",
                )
        }
        val landedEntry = if (entry.isBlank()) "" else {
            ProgramInstallPipeline.PieceShape.landedRel(shape, entry)
                ?: return fail(ctx, id, "入口 " + entry + "的形态认不出（role=" + entryRole + "）—— 报缺，不猜")
        }

        val verDir = File(SystemDirs.pieceDir(ctx, id), lobos.services.reg.ProgramIndex.safeSegment(version) ?: return fail(ctx, id, "版本号非法 " + version))
        // 纯库件没有入口，这一支的判据不能是「verDir/<entry> 在不在」（空 entry 时 `File(verDir, "")` 是目录、恒真、误短路），
        // 它验的是「那份库字节在不在 lib/ 下」（按形态 role=library 判）。
        // 「已落位」的判据是「是不是同一份」而非「在不在」——缺陷 C-102（旧字节永远换不掉）：落位目录是 opt/<id>/<version>，
        // 版本号没变而内容变了时同一目录、旧字节留着。真机读数（01，2026-10-20）：busybox 落位目录 mtime=04:49（唯一旧的一件），
        // 它字节 sha=87e32d67… 而新包那份 4ce4a418…，判「不一致」走重装。现在逐项比 `files[].sha256` 与盘上那份现算的 sha。
        val landedPresent = if (landedEntry.isBlank()) {
            val libDir = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY) ?: "lib")
            libDir.isDirectory && libDir.listFiles()?.any { it.isFile } == true
        } else {
            File(verDir, landedEntry).isFile
        }
        // 族三（是不是我们要的那一代）：逐项比「落位上那份字节的 sha256」与「产物 files[].sha256」。
        // 缺陷名 C-102：「跳过当成相同」在比代这一格上把「不知道」当成了「是」——旧版有两处 `return@all true`：
        // fs.sha256.isBlank()（产物没给摘要）与 dst.canonicalFile 取不到（路径算不出来），都答「相同」。
        // 而 §1.55：缺字段要报缺、不拿缺省当值。后果可核：产物少给一条摘要，那一项永远被判「相同」、永远不会被换掉
        // （这就是「旧字节换不掉」里的一格）。判别形式：那一项缺摘要时答「相同」还是「无法判定」——答「相同」就是把
        // 缺证据当成了证据支持。现在缺摘要或路径算不出来都答「无法判定」，而不判已落位到最新（走重装）。
        val unverifiable = mutableListOf<String>()
        // 缺陷名 C-102：这一格只比「产物说有的那几项」，产物说没有的那几项从来没被比过，落位目录里多出来的那些永远留着。
        // 正是 07 在设备侧核到的：6c107c7 那一版产物里没有 bin/c_rehash（本机核过），journal 里「OK 解包 bin/openssl」
        // 两行、含 c_rehash 的解包行数 = 0，而设备上 opt/openssl/3.6.3/bin/c_rehash 那个字节还在、mtime 2026-10-24 06:31
        // （84df912 那版落下的遗留物），这一版既没再落它、也没删它。成因：那一段只遍历 `shape.files` 比逐项 sha256，
        // 多出来的不在里面、不比，「同内容不重写」的短路就成立（landedUpToDate 为真），install 压根没跑。处置：还要核
        // 「落位目录里有没有产物说没有的那几项」，有就不判已落位到最新、走重装（install 是整目录换上去，旧的自然没有）。
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
                    "（一版产物里没有它们，它们是上一版落下的遗留物，" +
                    "按「产物说没有的就不该在」这一条，走重装把它们换掉）",
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
        // 格三（是不是我们要的那一代）：读产物自带的那一格构建标识。C-104：01 核到今天零个消费者（落位侧只读 component-meta.json
        // 的 version 与 entry 两格），而「加了字段」与「加了读者」必须同一处落地。它答「落位上那份是不是这一次构建造出来的」，
        // 与 sameBytes 是两问（04 §1.93：内容 vs 代）；这一格只存在包里那份 component-meta.json 里。
        // 处置：只报、不拦（03 两条判甲）。甲只报=写诊断不动落位（旧代继续用旧字节）；乙拦住=判它不是这一代就不落，而「拿不到」
        // 与「不是」在读侧不可分（buildSha=local 与旧件都读不到），拿不到要答不知道、不知道不能拦住落位。所以 landedUpToDate
        // 只看内容（sameBytes），构建标识那格只报。「落位目录里有没有产物说没有的那几项」也算进去（见上一段）。
        val landedUpToDate = landedPresent && sameBytes && staleInLanding.isEmpty()
        if (unverifiable.isNotEmpty()) {
            lobos.services.log.Journal.note(
                ctx, "prefix-trace", false, "无法判定是不是那一代 " + id,
                unverifiable.joinToString("，") +
                    "（按 §1.55：缺证据不答「相同」这一格不判已落位到最新 走重装）",
            )
        }
        if (verDir.isDirectory && landedUpToDate) {
            // 缺陷 C-102：落位检查只验原物在不在、派生物指向哪不管——软链是派生物而落位跳过已存在项，永不刷新（原支只验 verDir/<entry>
            // 在就 register 并 return true；07 旧版落位 5a265b1：libbusybox.so / liblobosjq.so / libssl.so 三条坏的、跨多版装机不变）。
            // 格一那一族的收尾：快路径也要刷新两份产物清单。缺陷 C-102：旧版只刷 component-meta.json，而 `refreshDerivedLinks` 读
            // piece-shape.json（62e0c7c 只做了一半，就是「六件报缺」那格；07，2026-10-21，run 39483058890 / head=fec29ac：八件的
            // component-meta.json 全刷新，piece-shape.json 只有 busybox 与 ripgrep 有）。现在两份都刷、这份在 `refreshDerivedLinks`
            // 之前（顺序是关键），piece-shape.json 直接复制产物那份（C-16：运行期只读不判断）。
            val landedMeta = runCatching {
                val j = org.json.JSONObject(meta.toString())
                j.put("entry", landedEntry)
                j
            }.getOrNull()
        // 缺陷名 C-102：落位侧按哪一把尺落的权限——而这一支没有落权限。慢路径那一支有（ProgramInstallPipeline
        // 「权限按产物声明的 mode 落，运行期不判形态、不替产物决定权限」，逐项 chmod），而预装件走的是快路径（这一支），
        // 这一支原来一个 chmod 都没有。后果：解包出来的字节带着 zip 里的权限落下去，zip 里那一项没有被执行位时
        // （c_rehash 是 -rw-------），它在盘上不可执行，也建不出 usr/bin 下那条软链。处置：这一支也按产物声明的 mode 逐项落
        // （与慢路径同一把尺）；不在这里判形态（形态是构建期算好的，在 `shape.files[].mode` 里）。
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
            trace(ctx, "落权限", id + "一项也没落（产物没带 mode）—— 而运行期不替产物决定权限")
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
                id + "失败（快路径）：产物那一份不在 —— 而建链会报缺，不猜")
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
                id + "失败（快路径）：落位目录那份仍是旧的那一份")
        }
val derived = refreshDerivedLinks(ctx, id, version)
            trace(ctx, "校验派生物软链", id + "共=" + derived.total + "对=" + derived.okCount +
                "重算=" + derived.rebuilt + "" + derived.detail)
            runCatching { pkg.deleteRecursively() }
        runCatching { unpacked.deleteRecursively() }


            trace(ctx, "写 program-manifest.json", id + "成=" + manifestWritten.isSuccess +
                "目标=" + verDir.absolutePath + "/" + lobos.services.supply.ProgramDir.MANIFEST_NAME +
                (if (manifestWritten.isSuccess) "" else "失败=" + manifestWritten.exceptionOrNull()))
            // 进度在注册之前就记（它是关键步骤上的那一个，不是等「；成」那个结果）。
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
                (r.reason ?: "") + "— " + r.detail,
            )
            return false
        // 格三（是不是我们要的那一代）：比「install 之后落位目录那份 vs 产物那份」。缺陷 C-102：旧版检查跑在 install 之前，
        // 读到的是上次留下的旧文件，于是八件全报「不是这一次构建的那一份」。真机读数（07，2026-10-21）：那 8 条全在同一个
        // prefix-trace 轮次里（(b) 不成立）、APK 里那个 zip 与 Release 上那份 cmp 相同（(a) 也不成立），真根因是行序，
        // 这一段已挪到 install 之后。这一格不参与「已落位到最新」（04 第七条：构建标识报不一致=观察点不拦，逐项摘要=闸门拦）。
        // 三种状态不可互相替代（01 S53，C-05）：写着值→可答；那格不存在→报缺（不是「= APK 那一代」）；写着值而 ≠ APK→不同代。
        // 答不出「是」时要答「缺」或「不同」，不许答「是」。
        val pkgBuild = runCatching {
            val f = File(unpacked, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) "" else org.json.JSONObject(f.readText()).optString("buildSha", "").trim()
        }.getOrDefault("")
        val landedBuild = runCatching {
            val f = File(verDir, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) "" else org.json.JSONObject(f.readText()).optString("buildSha", "").trim()
        }.getOrDefault("")
        // 01 S53：三态不可互相替代（C-05）——三态判别要求两处都先确认存在：两处都存在而相等 = 同代；有一处不存在 = 报缺
        // （不是「= APK 那一代」，也不是「未知的那一代」）；两处都存在而不等 = 不同代。
        // 「凡那一格参与比较，先确认它在每一件上都存在；有一件缺，那一格整体报缺」；「local」那个占位也要报缺
        // （04 那条总判据）：它是「本地造的那一份」，与「= APK 那一格」在读侧不可区分，而结论需要一个具体值，那一格必须报缺、
        // 不许填。它与 APK 那一格都是 40 位时两者相同只是「恰好同相」，换一个输入就不同相了，不能拿它判「同代」。
        val pkgIsLocal = pkgBuild.equals("local", ignoreCase = true)
        val landedIsLocal = landedBuild.equals("local", ignoreCase = true)
        when {
            pkgBuild.isBlank() || landedBuild.isBlank() || pkgIsLocal || landedIsLocal -> {
                val missing = listOfNotNull(
                    "产物那份".takeIf { pkgBuild.isBlank() || pkgIsLocal },
                    "落位目录那份".takeIf { landedBuild.isBlank() || landedIsLocal },
                ).joinToString("与 ")
                lobos.services.log.Journal.note(
                    ctx, "prefix-trace", null, "报缺：无法判定是不是这一代 " + id,
                    missing + "没有 buildSha 那一格 —— 而「缺」既不是「= APK 那一代」， 也不是「= 未知的那一代」",
                )
            }
            pkgBuild != landedBuild -> {
                lobos.services.log.Journal.note(
                    ctx, "prefix-trace", false, "不同代：不是这一次构建的那一份 " + id,
                    "产物 buildSha=" + pkgBuild + "、落位 buildSha=" + landedBuild +
                        "—— 只报，不拦落位（构建标识是观察点，逐项摘要才是闸门）",
                )
            }
        }

        }
        return true
    }

    /* * 一个文件的 sha256（小写十六进制）；读不到就空串。 */
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

    /* * 纯库件那份库字节的名字：从落位目录 lib/ 下按形态取（库形态那一份）。库件没有入口（entry 空），「库在哪里」那格
     * 记的就是这个名字。不按目录名挑：lib/ 下若有多个 .so，取主名那一个（名字恰以 .so 结尾，是「这一件的那一份」的标记，
     * 与构建侧同一把尺）。 */
    private fun libFileNameOf(verDir: File): String {
        val libDir = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY) ?: "lib")
        val files = libDir.listFiles()?.filter { it.isFile } ?: return ""
        val primary = files.firstOrNull { it.name.endsWith(".so") }
        return (primary ?: files.firstOrNull())?.name ?: ""
    }

    /* * 落位目录的**相对**路径（opt/<id>）—— 注册表里那一格是相对路径，
     * ProgramManager.stateDirOf 拼的是 File(ctx.filesDir, stateDir)。 */
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

    /* * 这一件是程序还是库 —— **只读产物的形态那一格**（[role]），不猜、不探测。
     * （原来的这一格读的是 meta 的 `kind` —— 那是清单里我们自己写的字面量，
     * 而形态的真源是产物自带的 piece-shape.json。） */
    private fun levelOf(role: String): lobos.services.reg.Level =
        if (role == ProgramInstallPipeline.ROLE_PROGRAM) lobos.services.reg.Level.PROGRAM
        else lobos.services.reg.Level.PIECE

    /* * 登记这一件。注册表只记「库在哪里」与「程序在哪里」。
     * 缺陷 C-102：库那份也被记成入口——原来 `assetEntry = entry` 无条件，lib/ 下那份也成了入口，「入口格有值」被当成「这一件是
     * 程序」（今天五件失败的成因）；现在入口格只对程序形态填，库形态空着（只有落位目录）。
     * 缺陷 C-102：role 由运行期现读字节填——原来 `role = PieceScan.roleFromDisk(ctx, id)`（walk 落位目录读 ELF 头），而形态是
     * 生产时就知道的事，现在从产物带来的那一格（`[role]`）读。role 答「它是什么」；「是不是底座解释器」是另一问（§10.1:138），
     * 由 `[ShellEntry]` 的判据回答。 */
    private fun register(
        ctx: Context,
        id: String,
        version: String,
        stateDir: String,
        entry: String,
        role: String,
        // 编译错误（run 39415331935 第21步）：verDir 未定义 —— register 是私有函数，
        // 而「那份库字节的名字」要从**落位目录**里取（按形态） 把落位目录传进来。
        verDir: File,
        meta: org.json.JSONObject?,
    ) {
        val prev = lobos.services.reg.ProgramIndex.get(ctx, id)
        val isProgram = role == ProgramInstallPipeline.ROLE_PROGRAM
        // 库那一档的判据是「它真的没有程序入口」，不是「它不是 program」。
        val isLibrary = role == ProgramInstallPipeline.ROLE_LIBRARY
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
                    // 库件没有入口（entry 空），libName 取那份库字节的名字，从落位目录 lib/ 下按形态取（role=library）。
                    libName = if (entry.isNotBlank()) entry.substringAfterLast("/") else libFileNameOf(verDir),
                    // 库件没有入口（entry 空），libName 取那份库字节的名字，从落位目录 lib/ 下按形态取（role=library）。
                    // 缺陷 C-102：入口格只对 role='program' 填，而 role='shell' 也有入口——旧版 `assetEntry = if (isProgram) entry
                    // else ""`（isProgram 是 `role == ROLE_PROGRAM`，Lead 实读 `PreinstalledPieces.kt:732`）把 shell 也清空了：注册表里
                    // bash/busybox 的 assetEntry=''，`ShellEntry.kt:11`-`:13` 的判据让 resolve 返回 null，`sys.tty.exec` 抛「底座没有
                    // 命令解释器」——而落位那面全对（usr/bin 两个软链都在、entry 都在、清单 role='shell'），差的只是那个 assetEntry。
                    // 处置：入口格答「那一件有没有程序入口」，不由角色决定——library 仍清空、shell 不清空；两把尺不合并（C-105）。
                    assetEntry = if (isLibrary) "" else entry,
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
