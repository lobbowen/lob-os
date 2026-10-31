package lobos.services.supply
import android.content.Context
import java.io.File
import lobos.services.log.Journal
import lobos.services.reg.ProgramRegistry
import org.json.JSONObject
import lobos.services.reg.edited

class ProgramDir(
    private val context: Context,
    val programId: String = "",
    storeRoot: java.io.File? = null,
) {


    init {
        if (programId.isBlank()) {
            throw IllegalArgumentException("ProgramDir 需要显式程序 id：宿主没有\"主程序\"概念")
        }
    }

    data class ProgramManifest(
        val name: String,
        val version: String,
        val abi: String,
        val engines: JSONObject?,
        val entry: String,
        val requires: List<String>,
        val signature: String?
    )

    private val programRoot =
        storeRoot ?: File(lobos.kernel.layout.SystemDirs.opt(context), programId)

    fun currentVersion(): String? = store.currentVersion()

    fun installedVersions(): List<String> {
        if (!programRoot.exists()) return emptyList()
        return programRoot.list()?.filter {
            it != "CURRENT" && !isStagingDir(it) && !isReplacedDir(it) && File(programRoot, it).isDirectory
        }?.sorted() ?: emptyList()
    }

    fun programDir(version: String): File = File(programRoot, version)

    fun programRootDir(): File = programRoot

    fun quickAppDir(): File = File(programRoot, "quickapp")

    fun pruneOldVersions(keepExtra: Int = 1): Triple<List<String>, List<String>, Long> {
        val names = try { programRoot.list()?.sorted() ?: emptyList() } catch (_: Throwable) { emptyList() }
        val versions = names.filter { !isStagingDir(it) && !isReplacedDir(it) && File(programRoot, it).isDirectory }
        val pinned = linkedSetOf<String>()
        currentVersion()?.let { pinned.add(it) }
        store.floorVersion()?.let { pinned.add(it) }
        pending()?.from?.let { pinned.add(it) }
        val spare = versions.filterNot { it in pinned }.sortedDescending().take(keepExtra.coerceAtLeast(0))
        val droppable = versions.filterNot { it in pinned || it in spare }
        var freed = 0L
        val gone = mutableListOf<String>()
        for (v in droppable) {
            val d = File(programRoot, v)
            val size = runCatching { d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
            if (d.deleteRecursively()) { gone.add(v); freed += size }
        }
        return Triple(gone, droppable.filterNot { gone.contains(it) }, freed)
    }

    fun sweepStaleStaging(): Pair<List<String>, List<String>> {
        val names = try { programRoot.list()?.sorted() ?: emptyList() } catch (_: Throwable) { emptyList() }
        val cur = currentVersion()
        val restored = mutableListOf<String>()
        if (cur != null) {
            val orphan = names.filter { isReplacedDir(it) && it.startsWith(cur + REPLACED_INFIX) }
            for (a in orphan) {
                val dest = File(programRoot, cur)
                if (!dest.exists() && File(programRoot, a).renameTo(dest)) restored.add(a)
            }
        }
        val stale = names.filter { it !in restored && (isStagingDir(it) || isReplacedDir(it)) }
        val gone = stale.filter { File(programRoot, it).deleteRecursively() }
        return (gone + restored) to stale.filterNot { gone.contains(it) }
    }

    companion object {
        const val MANIFEST_NAME = "program-manifest.json"

        private const val ENTRY_ABSENT = ".entry-absent"

        private const val STAGING_INFIX = ".tmp-"

        const val REPLACED_INFIX = ".replaced-"

        private val STAGING_RE: Regex by lazy { Regex(".+" + Regex.escape(STAGING_INFIX) + "\\d+-\\d+") }

        fun stagingDirName(
            version: String,
            pid: Int = android.os.Process.myPid(),
            atMs: Long = System.currentTimeMillis(),
        ): String = version + STAGING_INFIX + pid + "-" + atMs

        fun isStagingDir(name: String): Boolean = STAGING_RE.matches(name)

        fun isReplacedDir(name: String): Boolean = name.contains(REPLACED_INFIX)
    }

    fun entryPath(version: String): File {
        val declared = readProgramManifest(version)?.entry?.trim().orEmpty()
        return if (declared.isBlank()) File(programDir(version), ENTRY_ABSENT) else File(programDir(version), declared)
    }

    fun assertNotDirectlyExecutable(version: String) {
        val entry = entryPath(version).canonicalFile
        val filesRoot = context.filesDir.canonicalFile
        check(entry.startsWith(filesRoot)) {
            "控制面板入口应位于 filesDir（app_data_file，W^X 禁 exec）内，但它跑到了 ${entry.parent}。" +
                "此断言失败意味着 OTA 的落盘布局被破坏 —— " +
                "若入口需要被 exec，它必须走我们自己落位的形状（usr/lib/<id>/<版本>/bin/，usr/bin 建软链）。jniLibs/nativeLibraryDir 只是取源，那两个目录不可写（实测属主是 system）。"
        }
    }
    fun rawManifest(version: String): JSONObject? {
        val p = File(programDir(version), MANIFEST_NAME)
        if (!p.isFile) return null
        return runCatching { JSONObject(p.readText()) }.getOrNull()
    }

    fun readProgramManifest(version: String): ProgramManifest? = readProgramManifest(version, programRoot)

    fun readProgramManifest(version: String, root: File): ProgramManifest? {
        val p = File(File(root, version), MANIFEST_NAME)
        if (!p.exists()) return null
        return try {
            val json = JSONObject(p.readText())
            ProgramManifest(
                name = json.optString("name", "lobos-os"),
                version = json.optString("version", version),
                abi = json.optString("abi", ""),
                engines = json.optJSONObject("engines"),
                entry = json.optString("entry", ""),
                requires = json.optJSONArray("requires")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                signature = json.optString("signature", "").ifBlank { null }
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun unzipInto(zip: File, dest: File) {
        unzip(zip, dest)
    }

    fun setCurrentVersion(version: String) = store.setCurrentVersion(version)

    private val store by lazy { ProgramOtaStateStore(programRoot) { v -> registerCurrent(v) } }

    private fun registerCurrent(version: String) {
        runCatching {
            val landed = PieceScan.isLandedPiece(context, programId)
            val meta = if (landed) PieceScan.pieceMeta(context, programId) else null
            val spec = if (landed) null else ProgramRegistry.spec(context, programId)
            val role = if (landed) {
                PieceScan.roleFromDisk(context, programId)
            } else {
                spec?.role?.takeIf { it.isNotBlank() } ?: "app"
            }
            //  ★ 缺陷名（C-102）**同一件事在两条路径上有两个默认值，而落位那一条取到了 false 那个**
            //    —— 而「装上来就常驻」（=> 而 `lifecycle.resident` 那一格）今天**缺**。
            //
            //  而**这一格该由谁写**（=> 而 01 那一件 S116 第二节的三问，=> 而而 04 那一裁核为成立）：
            //    · G1 **设备上被读的那一份**是**落位侧那一份**
            //      （=> 而 ProgramRegistry.kt:100 逐字是 `val life = json.optJSONObject("lifecycle")`，
            //        => 而那个 json 读的是 ProgramDir.MANIFEST_NAME = program-manifest.json）；
            //    · G2 而**构建期那一份**（component-meta.json）**不是**同一份；
            //    · G3 而这一格**该落在 G1 答的那一份**（=> 而落位侧那一份）
            //      => 而它今天**缺**（=> 而 ManifestWriter.build 那一串 m.put 里没有 lifecycle 那一格）。
            //
            //  => 而**缺了它为什么取到 false**：=> 而 `spec` 在落位那一支是 **null**
            //    （=> 而上面 :163 那一行逐字 `val spec = if (landed) null else …`），
            //    => 而 **`null?.resident == true` 是 false**
            //    => 而而那正是 journal 逐字「resident=false」那一条。
            //
            //  => 而**读那一侧**（=> 而 ProgramRegistry.kt:146 那一行）的缺省是 **true**，
            //    => 而那与这一侧**不一致**（=> 而按 C-115 两条并列留档）。
            //  => 而按 01 那一件 S116 第一节的裁定「装上来就常驻」= **true**，
            //    => 而这一格**必须显式写**、=> 而而**不许靠缺省**。
            //
            //  => 而**落位侧那一份**现在写了 lifecycle 那一格（=> 而 ManifestWriter.build 那一处），
            //    => 而而这一处就**从落位侧那一份读**（=> 而不是从那个 null 的 spec 算）。
            val resident = if (landed) {
                // 落位那一支：从**落位侧那一份**（program-manifest.json）读 lifecycle.resident
                //  （=> 而那一份由 ManifestWriter 写，=> 而它今天显式写了那一格）。
                //  （=> 而读不到就答 true，=> 而那与 ProgramRegistry.kt:146 的缺省一致）。
                val life = rawManifest(version)?.optJSONObject("lifecycle")
                life?.optBoolean("resident", true) ?: true
            } else {
                spec?.resident == true
            }
            val existing = lobos.services.reg.ProgramIndex.get(context, programId)
            val base = existing ?: lobos.services.reg.ProgramIndex.empty(programId, lobos.services.reg.Level.PROGRAM)
            lobos.services.reg.ProgramIndex.upsert(
                context,
                base.edited(
                    version = version,
                    enabled = true,
                    desired = if (existing == null) lobos.services.reg.Desired.RUNNING else base.desired,
                    role = role,
                    resident = resident,
                    restart = spec?.restart ?: base.restart,
                    maxRestarts = spec?.maxRestarts ?: base.maxRestarts,
                    backoffMs = spec?.backoffMs ?: base.backoffMs,
                    capabilities = spec?.capabilities ?: base.capabilities,
                    requires = spec?.requires ?: base.requires,
                    env = spec?.env ?: base.env,
                    httpPort = spec?.http?.port ?: base.httpPort,
                    httpHealth = spec?.http?.health ?: base.httpHealth,
                    invalid = if (landed) null else spec?.invalid,
                ),
            )
            if (landed) lobos.services.reg.ProgramIndex.clearInvalid(context, programId)
            lobos.services.log.Journal.note(
                context, "registry", null, "指针切换即登记（不改动 desired，启停意图由安装与用户决定）",
                "id=" + programId + " version=" + version + " role=" + role + " resident=" + resident,
            )
        }
    }

    fun floorVersion(): String? = store.floorVersion()
    fun setFloor(version: String) = store.setFloor(version)

    fun lowerFloor(version: String): Boolean = store.lowerFloor(version)
    fun isBelowFloor(version: String): Boolean = store.isBelowFloor(version)

    data class Pending(val version: String, val from: String?)
    fun markPending(version: String, from: String?) = store.markPending(version, from)
    fun pending(): Pending? = store.pending()?.let { Pending(it.version, it.from) }
    fun clearPending() = store.clearPending()
    fun rollbackTo(from: String): Boolean = store.rollbackTo(from)

    fun integrityChecks(): List<String> {
        val out = mutableListOf<String>()
        val cur = currentVersion()
        if (cur == null) {
            out += "CURRENT 指针缺失"
        } else {
            if (!File(programRoot, cur).isDirectory) out += "CURRENT=$cur 但目录不存在"
            if (!entryPath(cur).exists()) out += "CURRENT=$cur 但入口缺失（清单未声明 entry 或文件不存在）"
            if (readProgramManifest(cur) == null) out += "CURRENT=$cur 但 program-manifest.json 缺失/不可解析"
        }
        return out
    }

    private fun unzip(zip: File, dest: File) = ProgramArchive.unzip(zip, dest)

}
