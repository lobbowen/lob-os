package lobos.services.reg

import android.content.Context
import java.io.File
import lobos.services.supervise.SupervisorPolicy
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles
import lobos.services.supervise.Backoff

enum class Level { PIECE, PROGRAM }

enum class Desired { RUNNING, STOPPED, FROZEN }

data class UnitEntry(
        val id: String,
        val version: String,
        val stateDir: String,
        val sha256: String,
        val assetEntry: String,
        val libName: String = "",
        val files: List<FileRec> = emptyList(),

        val requires: List<String> = emptyList(),
        val after: List<String> = emptyList(),
        val conflicts: List<String> = emptyList(),

        val resident: Boolean = false,
        val restart: Restart = Restart.ON_FAILURE,
        val maxRestarts: Int = 5,
        val backoffMs: List<Long> = emptyList(),

        val httpPort: Int = 0,
        val httpHealth: String = "",
        val capabilities: List<String> = emptyList(),

        val env: Map<String, String> = emptyMap(),

        val uiPackage: String = "",
        val uiName: String = "",
        val uiIcon: String = "",
        val onUiClosed: String = "",

        val enabled: Boolean = true,
        val role: String = "",
        val required: Boolean = false,
        val invalid: String? = null,

        val pid: Int = 0,
        val starttime: Long = 0L,
        val exitCode: Int? = null,
        val exitedAt: Long = 0L,
        val restarts: Int = 0,
        val lastFailure: String = "",
        val desired: Desired = Desired.STOPPED,
    ) {
        data class FileRec(val path: String, val sha256: String)

        val isPiece: Boolean get() = role.isNotEmpty() && role != "app"

        val level: Level get() = if (isPiece) Level.PIECE else Level.PROGRAM

    data class BaseItem(
        val id: String,
        val kind: String,
        val version: String,
        val sha256: String,
        val today: String,
        val intended: String,
        val optional: Boolean,
    )
    }


object ProgramIndex {

    private fun dir(ctx: Context): File = SystemDirs.libvar(ctx)
    private const val FILE = "program-index.json"
    private const val SCHEMA = 1

    val DEFAULT_BACKOFF: List<Long> = (0 until lobos.services.supervise.SupervisorPolicy.BACKOFF_STEPS).map {
        Backoff.exponential(it, lobos.services.supervise.SupervisorPolicy.BACKOFF_BASE_MS, lobos.services.supervise.SupervisorPolicy.BACKOFF_MAX_MS)
    }

    fun levelOf(raw: String): Level =
        if (raw.trim().uppercase() == "PIECE") Level.PIECE else Level.PROGRAM

    fun root(ctx: Context): File = dir(ctx)

    fun file(ctx: Context): File = File(dir(ctx), FILE)

    fun safeSegment(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 64) return null
        if (v == "." || v == "..") return null
        return v.takeIf {
            it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == '+' }
        }
    }

    fun empty(id: String, level: Level): UnitEntry =
        UnitEntry(
            id = id,
            version = "",        // 还没装 —— version 由落位扫描填
            stateDir = "",        // 同上
            assetEntry = "",      // 同上
            sha256 = "",          // 同上
            role = if (level == Level.PIECE) "" else "app",
        )

    fun encode(e: UnitEntry): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("id", e.id)
        put("version", e.version)
        put("stateDir", e.stateDir)
        put("sha256", e.sha256)
        put("assetEntry", e.assetEntry)
        put("libName", e.libName)
        put("role", e.role)
        put("enabled", e.enabled)
        put("required", e.required)
        e.invalid?.let { put("invalid", it) }

        put("files", JSONArray().apply {
            e.files.forEach { fr ->
                put(JSONObject().apply { put("path", fr.path); put("sha256", fr.sha256) })
            }
        })

        put("requires", JSONArray(e.requires))
        put("after", JSONArray(e.after))
        put("conflicts", JSONArray(e.conflicts))

        put("resident", e.resident)
        put("restart", e.restart.name)
        put("maxRestarts", e.maxRestarts)
        put("backoffMs", JSONArray(e.backoffMs))

        put("httpPort", e.httpPort)
        put("httpHealth", e.httpHealth)
        put("capabilities", JSONArray(e.capabilities))
        put("env", JSONObject(e.env.toMap()).toString())

        put("uiPackage", e.uiPackage)
        put("uiName", e.uiName)
        put("uiIcon", e.uiIcon)
        put("onUiClosed", e.onUiClosed)

        put("desired", e.desired.name)
        put("pid", e.pid)
        put("starttime", e.starttime)
        e.exitCode?.let { put("exitCode", it) }
        put("exitedAt", e.exitedAt)
        put("restarts", e.restarts)
        put("lastFailure", e.lastFailure)
    }

    fun decode(o: JSONObject): UnitEntry {
        fun strArray(k: String): List<String> =
            o.optJSONArray(k)?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
        fun longs(k: String): List<Long> =
            o.optJSONArray(k)?.let { a ->
                (0 until a.length()).map { a.optLong(it) }
            } ?: emptyList()
        return UnitEntry(
            id = o.optString("id", ""),
            version = o.optString("version", ""),
            stateDir = o.optString("stateDir", ""),
            sha256 = o.optString("sha256", ""),
            assetEntry = o.optString("assetEntry", ""),
            libName = o.optString("libName", ""),
            role = o.optString("role", ""),
            enabled = o.optBoolean("enabled", true),
            required = o.optBoolean("required", false),
            invalid = if (o.has("invalid")) o.optString("invalid", "") else null,
            files = o.optJSONArray("files")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val it = a.optJSONObject(i) ?: return@mapNotNull null
                    val path = it.optString("path", "")
                    if (path.isEmpty()) null
                    else UnitEntry.FileRec(path, it.optString("sha256", ""))
                }
            } ?: emptyList(),
            requires = strArray("requires"),
            after = strArray("after"),
            conflicts = strArray("conflicts"),
            resident = o.optBoolean("resident", false),
            restart = runCatching { Restart.valueOf(o.optString("restart", "ON_FAILURE")) }
                .getOrDefault(Restart.ON_FAILURE),
            maxRestarts = o.optInt("maxRestarts", 5),
            backoffMs = longs("backoffMs"),
            httpPort = o.optInt("httpPort", 0),
            httpHealth = o.optString("httpHealth", ""),
            capabilities = strArray("capabilities"),
            env = o.optJSONObject("env")?.let { m ->
                m.keys().asSequence().associateWith { k -> m.optString(k, "") }
            } ?: emptyMap(),
            uiPackage = o.optString("uiPackage", ""),
            uiName = o.optString("uiName", ""),
            uiIcon = o.optString("uiIcon", ""),
            onUiClosed = o.optString("onUiClosed", ""),
            desired = runCatching { Desired.valueOf(o.optString("desired", "STOPPED")) }
                .getOrDefault(Desired.STOPPED),
            pid = o.optInt("pid", 0),
            starttime = o.optLong("starttime", 0L),
            exitCode = if (o.has("exitCode")) o.optInt("exitCode", 0) else null,
            exitedAt = o.optLong("exitedAt", 0L),
            restarts = o.optInt("restarts", 0),
            lastFailure = o.optString("lastFailure", ""),
        )
    }

    @Synchronized
    fun all(ctx: Context): List<UnitEntry> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        if (o.optInt("schema", 0) != SCHEMA) return emptyList()
        val arr = o.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { decode(it) } }
    }

    @Synchronized
    fun isPiece(e: UnitEntry): Boolean = e.role.isNotEmpty() && e.role != "app"

    fun isPiece(ctx: Context, id: String): Boolean =
        get(ctx, id)?.let { isPiece(it) } ?: false

    fun get(ctx: Context, id: String): UnitEntry? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun byLevel(ctx: Context, level: Level): List<UnitEntry> = all(ctx).filter { it.level == level }

    @Synchronized
    fun upsert(ctx: Context, entry: UnitEntry) {
        persist(ctx, all(ctx).filterNot { it.id == entry.id } + entry)
    }

    @Synchronized
    fun remove(ctx: Context, id: String): Boolean {
        val cur = all(ctx)
        if (cur.none { it.id == id }) return false
        persist(ctx, cur.filterNot { it.id == id })
        PortBroker.release(ctx, id)
        return true
    }

    @Synchronized
    fun mutate(ctx: Context, id: String, block: (UnitEntry) -> UnitEntry): Boolean {
        val cur = get(ctx, id) ?: return false
        upsert(ctx, block(cur))
        return true
    }

    fun clearInvalid(ctx: Context, id: String): Boolean {
        val cur = get(ctx, id) ?: return false
        if (cur.invalid == null) return false
        upsert(ctx, cur.copy(invalid = null))
        return true
    }
    @Synchronized
    fun replaceAll(ctx: Context, entries: List<UnitEntry>) = persist(ctx, entries)

    private fun persist(ctx: Context, entries: List<UnitEntry>) {
        val arr = JSONArray()
        entries.sortedBy { it.id }.forEach { arr.put(encode(it)) }
        StateFiles.writeJson(file(ctx), JSONObject().apply {
            put("schema", SCHEMA)
            put("entries", arr)
            put("base", JSONArray().also { a -> baseOf(ctx).forEach { a.put(it) } })
        })
    }

    private fun encodeBase(i: UnitEntry.BaseItem): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("kind", i.kind)
        put("version", i.version)
        put("sha256", i.sha256)
        put("today", i.today)
        put("intended", i.intended)
        put("optional", i.optional)
    }

    fun baseOf(ctx: Context): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (e in all(ctx).sortedBy { it.id }) {
            if (!isPiece(e)) continue
            out.add(
                encodeBase(
                    UnitEntry.BaseItem(
                        id = e.id,
                        kind = "命令",
                        version = e.version,
                        sha256 = e.sha256,
                        today = "ota",
                        intended = "ota",
                        optional = false,
                    ),
                ),
            )
        }
        val identity = lobos.services.reg.BuildIdentity.read(ctx)
        val apkSha = identity?.optString("gitSha", "") ?: ""
        val comps: List<Pair<String, Boolean>> = listOf(
            lobos.kernel.compat.Compat.LIB_POSIX to false,
            lobos.kernel.compat.Compat.LIB_PTYSESSION to true,
            lobos.kernel.compat.Compat.LIB_FLOCK to true,
        )
        for ((name, opt) in comps) {
            val present = lobos.kernel.compat.Compat.lib(ctx, name)?.isFile == true
            out.add(
                encodeBase(
                    UnitEntry.BaseItem(
                        id = name,
                        kind = "组件",
                        version = "随 APK",
                        sha256 = apkSha,
                        today = "apk",
                        intended = "ota",
                        optional = opt,
                    ),
                ),
            )
            if (!present) {
                out.last().put("present", false)
            }
        }
        return out
    }

    fun base(ctx: Context): List<UnitEntry.BaseItem> {
        val stored = file(ctx).takeIf { it.isFile }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?.optJSONArray("base")
        if (stored != null && stored.length() > 0) {
            return (0 until stored.length()).mapNotNull { i ->
                stored.optJSONObject(i)?.let { b -> decodeBase(b) }
            }
        }
        return baseOf(ctx).mapNotNull { decodeBase(it) }
    }

    private fun decodeBase(b: JSONObject): UnitEntry.BaseItem = UnitEntry.BaseItem(
        id = b.optString("id", ""),
        kind = b.optString("kind", ""),
        version = b.optString("version", ""),
        sha256 = b.optString("sha256", ""),
        today = b.optString("today", ""),
        intended = b.optString("intended", ""),
        optional = b.optBoolean("optional", false),
    )
}

fun UnitEntry.edited(
    enabled: Boolean? = null,
    version: String? = null,
    stateDir: String? = null,
    sha256: String? = null,
    libName: String? = null,
    assetEntry: String? = null,
    role: String? = null,
    requires: List<String>? = null,
    after: List<String>? = null,
    conflicts: List<String>? = null,
    required: Boolean? = null,
    files: List<UnitEntry.FileRec>? = null,
    resident: Boolean? = null,
    restart: Restart? = null,
    maxRestarts: Int? = null,
    backoffMs: List<Long>? = null,
    httpPort: Int? = null,
    httpHealth: String? = null,
    uiPackage: String? = null,
    uiName: String? = null,
    uiIcon: String? = null,
    onUiClosed: String? = null,
    capabilities: List<String>? = null,
    env: Map<String, String>? = null,
    desired: Desired? = null,
    invalid: String? = null,
    pid: Int? = null,
    starttime: Long? = null,
    exitCode: Int? = null,
    exitedAt: Long? = null,
    restarts: Int? = null,
    lastFailure: String? = null,
): UnitEntry = copy(
    enabled = enabled ?: this.enabled,
    version = version ?: this.version,
    stateDir = stateDir ?: this.stateDir,
    sha256 = sha256 ?: this.sha256,
    libName = libName ?: this.libName,
    assetEntry = assetEntry ?: this.assetEntry,
    role = role ?: this.role,
    requires = requires ?: this.requires,
    after = after ?: this.after,
    conflicts = conflicts ?: this.conflicts,
    required = required ?: this.required,
    files = files ?: this.files,
    resident = resident ?: this.resident,
    restart = restart ?: this.restart,
    maxRestarts = maxRestarts ?: this.maxRestarts,
    backoffMs = backoffMs ?: this.backoffMs,
    httpPort = httpPort ?: this.httpPort,
    httpHealth = httpHealth ?: this.httpHealth,
    uiPackage = uiPackage ?: this.uiPackage,
    uiName = uiName ?: this.uiName,
    uiIcon = uiIcon ?: this.uiIcon,
    onUiClosed = onUiClosed ?: this.onUiClosed,
    capabilities = capabilities ?: this.capabilities,
    env = env ?: this.env,
    desired = desired ?: this.desired,
    invalid = if (invalid != null) invalid else this.invalid,
    pid = pid ?: this.pid,
    starttime = starttime ?: this.starttime,
    exitCode = if (exitCode != null) exitCode else this.exitCode,
    exitedAt = exitedAt ?: this.exitedAt,
    restarts = restarts ?: this.restarts,
    lastFailure = lastFailure ?: this.lastFailure,
)
