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
            trace(ctx, "装 " + id, "开始")
            val r = try {
                installOne(ctx, id)
            } catch (e: Throwable) {
                trace(ctx, "装 " + id, "抛了 " + e.javaClass.name + ": " + (e.message ?: ""))
                false
            }
            trace(ctx, "装 " + id, if (r) "成" else "败")
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
        entry: String,
        role: String,
    ): DerivedCheck {
        val verDir = File(SystemDirs.pieceDir(ctx, id), version)
        //  纯库件没有入口（entry 空） 派生物软链的主体是**那份库字节**（按形态取）。
        //   原来这里两行是 File(verDir, entry) 与 entry.substringAfterLast("/") ——
        //   而 entry 为空时前者是目录、后者是空串  这一支会炸或建出无名软链。
        val primary = if (entry.isNotBlank()) entry.substringAfterLast("/") else libFileNameOf(verDir)
        if (primary.isBlank()) {
            return DerivedCheck(0, 0, 0, "没有入口也没有库字节 —— 不猜它该建在哪")
        }
        //  **落位后那一份的完整相对路径**（ 形态决定目录  它可能不在 entry 那一层）
        //   缺陷名（C-102）**重建时指向了错的那一份**：
        //     原来这一支是 File(verDir, primary) 与 File(verDir, entry) ——
        //      而 primary 是**文件名**（不含目录） File(verDir, primary) 落在 verDir 根下，
        //       而真正那份在 verDir/<形态决定的目录>/<名>  landed 指向一个**不存在**的路径；
        //        而 entry 对**纯库件**是空串  File(verDir, "") = **目录** 
        //         重建出「指向目录」的软链  那一支必然「重算后仍不通」。
        //    真机读数（07，2026-10-20）：七件每一件都是「共=1 对=0 重算=0 …重算后仍不通」。
        //    现在：落位后那一份 = verDir/<形态决定的目录>/<名>（按 role 取目录），
        //     而重建时指向**它**。
        val landedRel = File(
            ProgramInstallPipeline.PieceShape.dirForRole(role) ?: "lib",
            primary,
        ).path
        val landed = File(verDir, landedRel).canonicalFile.path
        val dirs = when (role) {
            ProgramInstallPipeline.ROLE_PROGRAM -> listOf(lobos.kernel.layout.PrefixProvisioner.binDir(ctx))
            ProgramInstallPipeline.ROLE_LIBRARY -> listOf(lobos.kernel.layout.PrefixProvisioner.libDir(ctx))
            else -> emptyList()
        }
        if (dirs.isEmpty()) {
            return DerivedCheck(0, 0, 0, "入口形态认不出（role=" + role + "）—— 不猜它该建在哪")
        }
        var total = 0
        var ok = 0
        var rebuilt = 0
        val bad = mutableListOf<String>()
        for (dir in dirs) {
            val link = File(dir, primary)
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && !link.exists()) continue
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
                bad += dir.name + "/" + primary + " 重算后仍不通"
            }
        }
        return DerivedCheck(total, ok, rebuilt, bad.joinToString("；"))
    }

    private fun installOne(ctx: Context, id: String): Boolean {
        val pkg = extract(ctx, id) ?: return fail(ctx, id, "assets 里取不出 pieces/$id.zip")

        val unpacked = File(ctx.cacheDir, "preinst/u/$id").also { it.deleteRecursively(); it.mkdirs() }
        runCatching { lobos.services.supply.SupplyProvisioner.unzipFromFile(pkg, unpacked) }
            .onFailure { return fail(ctx, id, "解包失败: " + it.message) }

        val meta = runCatching {
            val f = File(unpacked, "component-meta.json")
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
        val entryRole: String = if (entry.isBlank()) {
            // 纯库件没有入口 —— 而它的 role 是**库**（那一份字节落 lib/）。
            //  而这一格不能空：refreshDerivedLinks 与 ManifestWriter.write 都要它
            //   （role 决定派生物建在哪个目录） 空 role 会让那一支早退（派生物不建）。
            ProgramInstallPipeline.ROLE_LIBRARY
        } else {
            shape.roleOf(entry)
                ?: return fail(ctx, id, "入口 " + entry + " 的形态不在产物的形态说明里 —— 报缺，不猜")
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
        val sameBytes = shape.files.all { fs ->
            if (fs.sha256.isBlank()) return@all true
            val dst = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(fs.role) ?: fs.dir)
                .let { File(it, fs.path.substringAfterLast('/')) }
            val real = runCatching { dst.canonicalFile }.getOrNull() ?: return@all true
            !real.isFile || sha256Of(real) == fs.sha256
        }
        val landedUpToDate = landedPresent && sameBytes
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
            val derived = refreshDerivedLinks(ctx, id, version, landedEntry, entryRole)
            trace(ctx, "校验派生物软链", id + " 共=" + derived.total + " 对=" + derived.okCount +
                " 重算=" + derived.rebuilt + " " + derived.detail)
            runCatching { pkg.deleteRecursively() }
        runCatching { unpacked.deleteRecursively() }
            val landedMeta = runCatching {
                val j = org.json.JSONObject(meta.toString())
                j.put("entry", landedEntry)
                j
            }.getOrNull()
            val manifestWritten = runCatching {
                lobos.services.supply.ManifestWriter.write(
                    verDir, landedMeta, version, entryRole
                )
            }
            trace(ctx, "写 program-manifest.json", id + " 成=" + manifestWritten.isSuccess +
                " 目标=" + verDir.absolutePath + "/" + lobos.services.supply.ProgramDir.MANIFEST_NAME +
                (if (manifestWritten.isSuccess) "" else " 失败=" + manifestWritten.exceptionOrNull()))
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
        lobos.kernel.layout.SystemDirs.REL_OPT + "/" + id

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