package lobos.os
import android.content.Context
import java.io.File
import lobos.os.Desired
import lobos.os.Level
import lobos.os.ProgramIndex
import lobos.os.ProgramRegistry
import org.json.JSONObject

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
        storeRoot ?: File(lobos.os.SystemDirs.opt(context), programId)
    private val currentPointer = File(programRoot, "CURRENT")

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
                "若入口需要被 exec，它必须改走 jniLibs/nativeLibraryDir（exec_type）通道。"
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
            val spec = ProgramRegistry.spec(context, programId)
            val role = spec?.role?.takeIf { it.isNotBlank() } ?: "app"
            val resident = spec?.resident == true
            val existing = lobos.os.ProgramIndex.get(context, programId)
            val base = existing ?: lobos.os.ProgramIndex.empty(programId, lobos.os.Level.PROGRAM)
            lobos.os.ProgramIndex.upsert(
                context,
                base.edited(
                    version = version,
                    enabled = true,
                    desired = if (existing == null) lobos.os.Desired.RUNNING else base.desired,
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
                    invalid = spec?.invalid,
                ),
            )
            lobos.log.Journal.note(
                context, "registry", null, "指针切换即登记（不改动 desired，启停意图由安装与用户决定）",
                "id=" + programId + " version=" + version + " role=" + role + " resident=" + resident,
            )
        }
    }

    fun floorVersion(): String? = store.floorVersion()
    fun setFloor(version: String) = store.setFloor(version)
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
