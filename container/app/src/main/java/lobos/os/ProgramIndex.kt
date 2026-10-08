package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 装好了的东西在系统里的状态 —— 只有两个值：
 *  这东西是「件」（系统文件，落在usr/lib/<id>/<版本>/）还是「程序」（落在 opt/<id>/<版本>/）。
 *
 * 此前是三值（INFRA / CAPABILITY / APPLICATION），但 INFRA 与 CAPABILITY 都是件，
 * 细分没有意义 —— **形态由 role 与落位决定，不由这个枚举决定**。
 */
enum class Level { PIECE, PROGRAM }

enum class Desired { RUNNING, STOPPED, FROZEN }

/**
 * 一件 —— 系统文件。「装了什么」全部由落位推导（见 [PieceScan]），
 * 这里只存推导不出但运行时要用的那几样。
 */
data class PieceEntry(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val stateDir: String,
    val sha256: String,
    /** 库的落位文件名（`libssl.so`），命令为空 */
    val libName: String,
    /** 可执行入口的相对路径（`bin/jq`） */
    val assetEntry: String,
    /** 形态：library · exec · shell · multi-command · headers（件自己声明，见 component-meta.json） */
    val role: String,
    /** 它依赖哪些我们提供的库（声明式；ELF 的 DT_NEEDED 由安装时解） */
    val requires: List<String>,
    /** 是不是系统必需的；不是必需的可被程序覆盖 */
    val required: Boolean,
    /** 校验没过的原因（装机阶段的结论，不是运行时状态） */
    val invalid: String?,
)

/** 一个程序 —— 装在 opt/，由内核起进程托管。 */
data class ProgramEntry(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val stateDir: String,
    val sha256: String,
    val assetEntry: String,
    val role: String,
    /** 常驻（进程退出即重启） */
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
)

/**
 * 索引条目 —— 「一件」或「一个程序」，**按形状分开，不是一张平表**。
 *
 * 此前是 27 个平铺字段的 data class，encode() 里还要判
 * 「如果是 APPLICATION 才写那 17 个字段」—— 两类东西挤在一张表里，
 * 于是每处读都要先判一次（`level == APPLICATION` 出现 9 处），
 * 加一种新形态的件还要改这个 data class。
 *
 * 现在：[piece] 与 [program] 各有各的字段，
 * `piece != null` 就是件、否则是程序 —— 一处判据，不用到处比枚举。
 */
data class IndexEntry(
    val piece: PieceEntry?,
    val program: ProgramEntry?,
) {
    val id: String get() = piece?.id ?: program!!.id
    val level: Level get() = if (piece != null) Level.PIECE else Level.PROGRAM
    val version: String get() = piece?.version ?: program!!.version
    val enabled: Boolean get() = piece?.enabled ?: program!!.enabled
    val stateDir: String get() = piece?.stateDir ?: program!!.stateDir
    val sha256: String get() = piece?.sha256 ?: program!!.sha256
    val assetEntry: String get() = piece?.assetEntry ?: program!!.assetEntry
    val role: String get() = piece?.role ?: program!!.role
    val requires: List<String> get() = piece?.requires ?: program!!.requires
    val invalid: String? get() = piece?.invalid ?: program!!.invalid

    // 程序专有字段（件没有这些，调用方要能一眼看出）
    val desired: Desired get() = program?.desired ?: Desired.STOPPED
    val resident: Boolean get() = program?.resident ?: false
    val libName: String get() = piece?.libName ?: ""
    val required: Boolean get() = piece?.required ?: false

    /** 件由系统提供，程序可以被卸 —— 与 Level 无关，由形状决定 */
    val removable: Boolean get() = program != null
    val managed: Boolean get() = program != null

    /**
     * 改字段 —— 委托给对应那一种条目，调用点不必先判「这是件还是程序」。
     *
     * 参数全为 null 表示「不改」，于是每个调用点只写自己要改的那几个。
     * 改不属于当前形状的字段（比如给件设 desired）**抛错**，不静默丢掉 ——
     * 静默丢会让人以为改上了，那比报错难查得多。
     */
    fun edited(
        enabled: Boolean? = null,
        version: String? = null,
        stateDir: String? = null,
        sha256: String? = null,
        libName: String? = null,
        assetEntry: String? = null,
        role: String? = null,
        requires: List<String>? = null,
        required: Boolean? = null,
        resident: Boolean? = null,
        restart: Restart? = null,
        maxRestarts: Int? = null,
        backoffMs: List<Long>? = null,
        capabilities: List<String>? = null,
        env: Map<String, String>? = null,
        httpPort: Int? = null,
        httpHealth: String? = null,
        desired: Desired? = null,
        uiPackage: String? = null,
        uiName: String? = null,
        uiIcon: String? = null,
        onUiClosed: String? = null,
        invalid: String? = null,
    ): IndexEntry {
        val p = piece
        if (p != null) {
            require(resident == null && restart == null && maxRestarts == null &&
                backoffMs == null && capabilities == null && env == null &&
                httpPort == null && httpHealth == null && desired == null &&
                uiPackage == null && uiName == null && uiIcon == null && onUiClosed == null
            ) { "这是件（${p.id}），程序专有字段不该设给它 —— 那是补丁，不是机制" }
            return IndexEntry(
                PieceEntry(
                    p.id,
                    version ?: p.version,
                    enabled ?: p.enabled,
                    stateDir ?: p.stateDir,
                    sha256 ?: p.sha256,
                    libName ?: p.libName,
                    assetEntry ?: p.assetEntry,
                    role ?: p.role,
                    requires ?: p.requires,
                    required ?: p.required,
                    invalid ?: p.invalid,
                ),
                null,
            )
        }
        val q = program!!
        require(libName == null && required == null) {
            "这是程序（${q.id}），件专有字段不该设给它"
        }
        return IndexEntry(
            null,
            ProgramEntry(
                q.id,
                version ?: q.version,
                enabled ?: q.enabled,
                stateDir ?: q.stateDir,
                sha256 ?: q.sha256,
                assetEntry ?: q.assetEntry,
                role ?: q.role,
                resident ?: q.resident,
                restart ?: q.restart,
                maxRestarts ?: q.maxRestarts,
                backoffMs ?: q.backoffMs,
                capabilities ?: q.capabilities,
                requires ?: q.requires,
                env ?: q.env,
                httpPort ?: q.httpPort,
                httpHealth ?: q.httpHealth,
                desired ?: q.desired,
                uiPackage ?: q.uiPackage,
                uiName ?: q.uiName,
                uiIcon ?: q.uiIcon,
                onUiClosed ?: q.onUiClosed,
                invalid ?: q.invalid,
            ),
        )
    }
}

object ProgramIndex {

    private fun dir(ctx: Context): File = SystemDirs.libvar(ctx)
    private const val FILE = "program-index.json"
    private const val SCHEMA = 1

    val DEFAULT_BACKOFF: List<Long> = (0 until lobos.runtime.SupervisorPolicy.BACKOFF_STEPS).map {
        Backoff.exponential(it, lobos.runtime.SupervisorPolicy.BACKOFF_BASE_MS, lobos.runtime.SupervisorPolicy.BACKOFF_MAX_MS)
    }

    /** 只两个值：件还是程序。形态（library/exec/…）由 role 与落位决定，不由这个枚举决定 */
    fun levelOf(raw: String): Level =
        if (raw.trim().uppercase() == "PIECE") Level.PIECE else Level.PROGRAM

    /** 一个空条目 —— 按形状给对应那一种，不存在「27 个字段都填一遍」 */
    fun empty(id: String, level: Level): IndexEntry =
        if (level == Level.PIECE) {
            piece = PieceEntry(
                id = id, version = "", enabled = true, stateDir = "",
                sha256 = "", libName = "", assetEntry = "", role = "",
                requires = emptyList(), required = false, invalid = null,
            ),
            program = null
        } else {
            piece = null
            program = ProgramEntry(
                id = id, version = "", enabled = true, stateDir = "",
                sha256 = "", assetEntry = "", role = "app",
                resident = true, restart = Restart.ON_FAILURE, maxRestarts = 5,
                backoffMs = DEFAULT_BACKOFF, capabilities = emptyList(),
                requires = emptyList(), env = emptyMap(), httpPort = 0,
                httpHealth = "", desired = Desired.STOPPED,
                uiPackage = "", uiName = "", uiIcon = "", onUiClosed = "",
                invalid = null,
            ),
        }

    fun encode(e: IndexEntry): JSONObject {
        val p = e.piece
        if (p != null) return JSONObject().apply {
            put("kind", "piece")
            put("id", p.id)
            put("version", p.version)
            put("enabled", p.enabled)
            put("stateDir", p.stateDir)
            put("sha256", p.sha256)
            put("libName", p.libName)
            put("assetEntry", p.assetEntry)
            put("role", p.role)
            put("requires", JSONArray(p.requires))
            put("required", p.required)
            p.invalid?.let { put("invalid", it) }
        }
        val q = e.program!!
        return JSONObject().apply {
            put("kind", "program")
            put("id", q.id)
            put("version", q.version)
            put("enabled", q.enabled)
            put("stateDir", q.stateDir)
            put("sha256", q.sha256)
            put("assetEntry", q.assetEntry)
            put("role", q.role)
            put("resident", q.resident)
            put("restart", q.restart.name)
            put("maxRestarts", q.maxRestarts)
            put("backoffMs", JSONArray(q.backoffMs))
            put("capabilities", JSONArray(q.capabilities))
            put("requires", JSONArray(q.requires))
            put("env", JSONObject(q.env))
            put("httpPort", q.httpPort)
            put("httpHealth", q.httpHealth)
            put("desired", q.desired.name)
            put("uiPackage", q.uiPackage)
            put("uiName", q.uiName)
            put("uiIcon", q.uiIcon)
            put("onUiClosed", q.onUiClosed)
            q.invalid?.let { put("invalid", it) }
        }
    }

    fun decode(o: JSONObject): IndexEntry? {
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        fun strArray(k: String): List<String> =
            o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val inv = o.optString("invalid").takeIf { it.isNotBlank() }
        // kind 决定用哪个形状读；缺省按 program（与旧格式的默认一致）
        if (o.optString("kind", "program") == "piece") {
            return IndexEntry(
                piece = PieceEntry(
                    id = id, version = o.optString("version", ""),
                    enabled = o.optBoolean("enabled", true),
                    stateDir = o.optString("stateDir", ""),
                    sha256 = o.optString("sha256", ""),
                    libName = o.optString("libName", ""),
                    assetEntry = o.optString("assetEntry", ""),
                    role = o.optString("role", ""),
                    requires = strArray("requires"),
                    required = o.optBoolean("required", false),
                    invalid = inv,
                ),
                program = null,
            )
        }
        val envObj = o.optJSONObject("env")
        val env: Map<String, String> = if (envObj == null) emptyMap() else buildMap {
            val names = envObj.names() ?: return@buildMap
            for (i in 0 until names.length()) {
                put(names.optString(i), envObj.optString(names.optString(i)))
            }
        }
        return IndexEntry(
            piece = null,
            program = ProgramEntry(
                id = id, version = o.optString("version", ""),
                enabled = o.optBoolean("enabled", true),
                stateDir = o.optString("stateDir", ""),
                sha256 = o.optString("sha256", ""),
                assetEntry = o.optString("assetEntry", ""),
                role = o.optString("role", "app"),
                resident = o.optBoolean("resident", true),
                restart = when (o.optString("restart", "ON_FAILURE")) {
                    "ALWAYS" -> Restart.ALWAYS
                    "NEVER" -> Restart.NEVER
                    else -> Restart.ON_FAILURE
                },
                maxRestarts = o.optInt("maxRestarts", 5),
                backoffMs = o.optJSONArray("backoffMs")?.let { a -> (0 until a.length()).map { a.optLong(it) } }
                    ?.filter { it > 0 }?.takeIf { it.isNotEmpty() } ?: DEFAULT_BACKOFF,
                capabilities = strArray("capabilities"),
                requires = strArray("requires"),
                env = env, httpPort = o.optInt("httpPort", 0),
                httpHealth = o.optString("httpHealth", ""),
                desired = runCatching { Desired.valueOf(o.optString("desired", "STOPPED")) }.getOrDefault(Desired.STOPPED),
                uiPackage = o.optString("uiPackage", ""),
                uiName = o.optString("uiName", ""),
                uiIcon = o.optString("uiIcon", ""),
                onUiClosed = o.optString("onUiClosed", ""),
                invalid = inv,
            ),
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
