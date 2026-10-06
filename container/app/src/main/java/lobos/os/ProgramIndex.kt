package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class Level { INFRA, CAPABILITY, CHANNEL, APPLICATION }

enum class Category { RUNTIME, TOOLCHAIN, LIBRARY, APPLICATION, NONE }

enum class Desired { RUNNING, STOPPED, FROZEN }

data class IndexEntry(
    val id: String,
    val level: Level,
    val category: Category,
    val origin: String,
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

    private const val DIR = "os"
    private const val FILE = "program-index.json"
    private const val SCHEMA = 1

    val DEFAULT_BACKOFF: List<Long> = (0 until lobos.runtime.SupervisorPolicy.BACKOFF_STEPS).map {
        Backoff.exponential(it, lobos.runtime.SupervisorPolicy.BACKOFF_BASE_MS, lobos.runtime.SupervisorPolicy.BACKOFF_MAX_MS)
    }

    fun levelOf(raw: String): Level = when (raw.trim().uppercase()) {
        "INFRA" -> Level.INFRA
        "RUNTIME", "COMPONENT", "CAPABILITY" -> Level.CAPABILITY
        "CHANNEL" -> Level.CHANNEL
        else -> Level.APPLICATION
    }

    fun categoryOf(raw: String): Category = when (raw.trim().uppercase()) {
        "RUNTIME" -> Category.RUNTIME
        "TOOLCHAIN", "COMPONENT" -> Category.TOOLCHAIN
        "LIBRARY" -> Category.LIBRARY
        "APPLICATION" -> Category.APPLICATION
        else -> Category.NONE
    }

    fun file(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        d.mkdirs()
        return File(d, FILE)
    }

    fun root(ctx: Context): File = File(ctx.filesDir, "sys")

    /**
     * 一个「段名」是否可以安全地作为文件名/目录名。
     *
     * 要挡的是**路径穿越**（`..`、`/`）与**命令解析上的坑**（空格），
     * 不是「看起来不常规的字符」。白名单按这个目的列，不多挡。
     *
     * 为什么放行 `+`：**clang++ 是真命令**，不是怪名字。
     * llvmtoolchain 一件里有八个工具，clang++ 是其中之一 ——
     * 挡掉它就等于「装上了 clang 但编不了 C++」，而这种缺失很安静：
     * 判据只跑 `clang --version` 与编一个 .c，看不出 C++ 编不了。
     * 实测 `+` 在设备文件系统上建链完全正常（真建过 clang++/a-b/a.b 等）。
     *
     * 仍然拒绝：空格与制表符（shell 里要引号，`tar x` 之类的地方会碎成两个参数）、
     * `/` 等分隔符（路径穿越）、`.`/`..`、超长名。
     */
    fun safeSegment(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 64) return null
        if (v == "." || v == "..") return null
        // 名字里只要有字母数字与 . _ - + 之外的字符就拒 —— 空格/制表符/引号/
        // 分隔符自然都被这条白名单挡住，不必单开一个空白检查
        // （本机无编译器，少依赖一个没验过的 API 更稳）。
        return v.takeIf {
            it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == '+' }
        }
    }

    fun empty(id: String, level: Level): IndexEntry = IndexEntry(
        id = id,
        level = level,
        category = if (level == Level.APPLICATION) Category.APPLICATION else Category.NONE,
        origin = "unknown",
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
        put("origin", e.origin)
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
            origin = o.optString("origin", "unknown"),
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
