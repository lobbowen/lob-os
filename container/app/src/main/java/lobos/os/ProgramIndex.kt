package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class Level { INFRA, CAPABILITY, APPLICATION }

enum class Desired { RUNNING, STOPPED, FROZEN }

data class IndexEntry(
    val id: String,
    val level: Level,
    val asApplication: Boolean,
    val version: String,
    val enabled: Boolean,
    val stateDir: String,
    val deps: List<String>,
    val sha256: String,
    val tier: String,
    val libName: String,
    val assetEntry: String,
    val role: String,
    val resident: Boolean,
    val restart: Restart,
    val maxRestarts: Int,
    val backoffMs: List<Long>,
    val capabilities: List<String>,
    val requires: List<String>,
    val env: Map<String, String>,
    val httpPort: Int,
    val httpHealth: String,
    val desired: Desired,
    val uiPackage: String,
    val uiName: String,
    val uiIcon: String,
    val onUiClosed: String,
    val invalid: String?,
) {
    val managed: Boolean get() = level == Level.APPLICATION
    val removable: Boolean get() = level != Level.INFRA
}

object ProgramIndex {

    private fun dir(ctx: Context): File = SystemDirs.libvar(ctx)
    private const val FILE = "program-index.json"
    private const val SCHEMA = 1

    val DEFAULT_BACKOFF: List<Long> = (0 until lobos.runtime.SupervisorPolicy.BACKOFF_STEPS).map {
        Backoff.exponential(it, lobos.runtime.SupervisorPolicy.BACKOFF_BASE_MS, lobos.runtime.SupervisorPolicy.BACKOFF_MAX_MS)
    }

    fun levelOf(raw: String): Level = when (raw.trim().uppercase()) {
        "INFRA" -> Level.INFRA
        "RUNTIME", "COMPONENT", "CAPABILITY" -> Level.CAPABILITY
        else -> Level.APPLICATION
    }

    fun file(ctx: Context): File {
        val d = dir(ctx)
        d.mkdirs()
        return File(d, FILE)
    }

    fun root(ctx: Context): File = SystemDirs.libvar(ctx)

    fun safeSegment(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 64) return null
        if (v == "." || v == "..") return null
        return v.takeIf {
            it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == '+' }
        }
    }

    fun empty(id: String, level: Level): IndexEntry = IndexEntry(
        id = id,
        level = level,
        asApplication = level == Level.APPLICATION,
        version = "",
        enabled = true,
        stateDir = "",
        deps = emptyList(),
        sha256 = "",
        tier = "optional",
        libName = "",
        assetEntry = "",
        role = if (level == Level.APPLICATION) "app" else "",
        resident = true,
        restart = Restart.ON_FAILURE,
        maxRestarts = 5,
        backoffMs = DEFAULT_BACKOFF,
        capabilities = emptyList(),
        requires = emptyList(),
        env = emptyMap(),
        httpPort = 0,
        httpHealth = "",
        desired = Desired.STOPPED,
        uiPackage = "",
        uiName = "",
        uiIcon = "",
        onUiClosed = "",
        invalid = null,
    )

    fun encode(e: IndexEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("level", e.level.name)
        put("category", e.category.name)
        put("asApplication", e.asApplication)
        put("version", e.version)
        put("enabled", e.enabled)
        put("stateDir", e.stateDir)
        put("deps", JSONArray(e.deps))
        put("sha256", e.sha256)
        put("tier", e.tier)
        put("libName", e.libName)
        put("assetEntry", e.assetEntry)
        if (e.level == Level.APPLICATION) {
            put("role", e.role)
            put("resident", e.resident)
            put("restart", e.restart.name)
            put("maxRestarts", e.maxRestarts)
            put("backoffMs", JSONArray(e.backoffMs))
            put("capabilities", JSONArray(e.capabilities))
            put("requires", JSONArray(e.requires))
            put("env", JSONObject(e.env))
            put("httpPort", e.httpPort)
            put("httpHealth", e.httpHealth)
            put("desired", e.desired.name)
            put("uiPackage", e.uiPackage)
            put("uiName", e.uiName)
            put("uiIcon", e.uiIcon)
            put("onUiClosed", e.onUiClosed)
        }
        e.invalid?.let { put("invalid", it) }
    }

    fun decode(o: JSONObject): IndexEntry? {
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        val level = levelOf(o.optString("level", "APPLICATION"))
        val envObj = o.optJSONObject("env")
        val env: Map<String, String> = if (envObj == null) emptyMap() else buildMap {
            val names = envObj.names() ?: return@buildMap
            for (i in 0 until names.length()) {
                val k = names.optString(i)
                put(k, envObj.optString(k))
            }
        }
        return IndexEntry(
            id = id,
            level = level,
            category = categoryOf(o.optString("category", "NONE")),
            asApplication = o.optBoolean("asApplication", level == Level.APPLICATION),
            version = o.optString("version", ""),
            enabled = o.optBoolean("enabled", true),
            stateDir = o.optString("stateDir", ""),
            deps = o.optJSONArray("deps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            sha256 = o.optString("sha256", ""),
            tier = o.optString("tier", "optional"),
            libName = o.optString("libName", ""),
            assetEntry = o.optString("assetEntry", ""),
            role = o.optString("role", if (level == Level.APPLICATION) "app" else ""),
            resident = o.optBoolean("resident", true),
            restart = when (o.optString("restart", "ON_FAILURE")) {
                "ALWAYS" -> Restart.ALWAYS
                "NEVER" -> Restart.NEVER
                else -> Restart.ON_FAILURE
            },
            maxRestarts = o.optInt("maxRestarts", 5),
            backoffMs = o.optJSONArray("backoffMs")?.let { a -> (0 until a.length()).map { a.optLong(it) } }
                ?.filter { it > 0 }?.takeIf { it.isNotEmpty() } ?: DEFAULT_BACKOFF,
            capabilities = o.optJSONArray("capabilities")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            requires = o.optJSONArray("requires")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            env = env,
            httpPort = o.optInt("httpPort", 0),
            httpHealth = o.optString("httpHealth", ""),
            desired = runCatching { Desired.valueOf(o.optString("desired", "STOPPED")) }.getOrDefault(Desired.STOPPED),
            uiPackage = o.optString("uiPackage", ""),
            uiName = o.optString("uiName", ""),
            uiIcon = o.optString("uiIcon", ""),
            onUiClosed = o.optString("onUiClosed", ""),
            invalid = o.optString("invalid").takeIf { it.isNotBlank() },
        )
    }

    @Synchronized
    fun all(ctx: Context): List<IndexEntry> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        if (o.optInt("schema", 0) != SCHEMA) return emptyList()
        val arr = o.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { decode(it) } }
    }

    @Synchronized
    fun get(ctx: Context, id: String): IndexEntry? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun byLevel(ctx: Context, level: Level): List<IndexEntry> = all(ctx).filter { it.level == level }

    @Synchronized
    fun upsert(ctx: Context, entry: IndexEntry) {
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
    fun mutate(ctx: Context, id: String, block: (IndexEntry) -> IndexEntry): Boolean {
        val cur = get(ctx, id) ?: return false
        upsert(ctx, block(cur))
        return true
    }

    @Synchronized
    fun replaceAll(ctx: Context, entries: List<IndexEntry>) = persist(ctx, entries)

    private fun persist(ctx: Context, entries: List<IndexEntry>) {
        val arr = JSONArray()
        entries.sortedBy { it.id }.forEach { arr.put(encode(it)) }
        StateFiles.writeJson(file(ctx), JSONObject().apply {
            put("schema", SCHEMA)
            put("entries", arr)
        })
    }
}
