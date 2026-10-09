package lobos.os

import android.content.Context
import java.io.File
import lobos.runtime.SupervisorPolicy
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
 * 一个单元 —— 件与程序是同一种东西，只是配置不同。
 *
 * 照抄 systemd.unit(5)：「Units are named as their configuration files」——
 * unit 是一种实体，`*.service` 与 `*.mount` 只是配置不同。
 * 此前我们分成 [PieceEntry] 与 [ProgramEntry] 两段，字段重复了 10 个
 *（id/version/enabled/stateDir/sha256/assetEntry/role/requires/invalid），
 * 于是每处读都要先判「这是件还是程序」。
 *
 * 现在一种记录。字段按 systemd.service(5) 的分类排列：
 *   身份 · 依赖 · 进程 · 健康 · 环境 · UI · 开关与标记
 */
data class UnitEntry(
        // ── 身份 ────────────────────────────────────────────
        /** id 就是落位的目录名/程序目录名 —— 与配置文件同名，不另编 */
        val id: String,
        val version: String,
        /** 落位根：件是 `usr/lib/<id>/<版本>/` · 程序是 `opt/<id>/<版本>/` */
        val stateDir: String,
        /** 整件字节身份（覆盖 files 全部内容的哈希） */
        val sha256: String,
        /** 入口相对路径：`bin/jq` · `lib/libssl.so` */
        val assetEntry: String,
        /** 库的落位文件名（`libssl.so`），非库为空 */
        val libName: String = "",
        /**
         * 这一件铺了哪些文件 + 每个文件的校验值
         * —— dpkg 的 `db-fsys:Files` 与 `.deb` 的 `md5sums`。
         * 没有它就答不出：删这个件该删哪些文件 · 某个文件被换过没有
         * （`dpkg -V` 拿的就是这份与实际文件比对）。
         * 路径相对落位根，换存储位置不用重记。
         */
        val files: List<FileRec> = emptyList(),

        // ── 依赖 ────────────────────────────────────────────
        /**
         * `Requires=` —— 需求依赖：这些单元必须同时就位。
         *
         * systemd.unit(5)：「ordering and requirement dependencies are ORTHOGONAL」
         * —— 排序（After=/Before=）与需求（Requires=/Conflicts=）是两类，
         * 混在一个字段里就分不清「启动顺序」与「缺了就不行」。
         * 排序那一半见 [after]。
         */
        val requires: List<String> = emptyList(),
        /** `After=` —— 排序依赖：这些单元先就位，本单元才起 */
        val after: List<String> = emptyList(),
        /** `Conflicts=` —— 互斥：这些单元在位时本单元必须停 */
        val conflicts: List<String> = emptyList(),

        // ── 进程（systemd.service 的 Type= 与 ExecStart=）────────
        /** `Type=simple` 恒有进程 · `Type=oneshot` 跑完就完 —— 件没有进程 */
        val resident: Boolean = false,
        /** `Restart=` —— no · on-failure · always */
        val restart: Restart = Restart.ON_FAILURE,
        /** `StartLimitBurst=` —— 窗口内最多重启几次 */
        val maxRestarts: Int = 5,
        /** `RestartSec=` 的步进 */
        val backoffMs: List<Long> = emptyList(),

        // ── 健康（WatchdogSec=）────────────────────────────
        /** `WatchdogSec=` 的探测端口，0 = 不探测 */
        val httpPort: Int = 0,
        val httpHealth: String = "",
        /** 对外提供的具名能力（systemd 无此字段 —— 快应用特有） */
        val capabilities: List<String> = emptyList(),

        // ── 环境（Environment=）────────────────────────────
        /** `Environment=` */
        val env: Map<String, String> = emptyMap(),

        // ── UI（快应用特有）────────────────────────────────
        val uiPackage: String = "",
        val uiName: String = "",
        val uiIcon: String = "",
        val onUiClosed: String = "",

        // ── 开关与标记 ─────────────────────────────────────
        val enabled: Boolean = true,
        /** 形态：library · exec · shell · multi-command · headers · app（落位形状决定） */
        val role: String = "",
        /** `Essential=yes` —— 系统必需的，缺了系统起不来 */
        val required: Boolean = false,
        /** 校验没过的原因（装机阶段的结论，不是运行时状态） */
        val invalid: String? = null,

        // ── 运行态（systemctl show 的MainPID 那一类）────────────
        /**
         * 运行态与配置在**同一个属性空间**——
         * systemctl(1)：「the properties shown by the command are generally
         * more low-level, normalized versions of the original configuration
         * settings AND EXPOSE RUNTIME STATE IN ADDITION TO CONFIGURATION.
         * For example, properties shown for service units include the
         * service's current main process identifier as "MainPID"」。
         *
         * ★ 这一段以前不存在 —— pid 在 ProcessLedger、退出码没记、
         *   重启计数是局部变量（进程重启即归零）。现在都在这儿。
         */
        val pid: Int = 0,
        /** /proc/<pid>/stat 的 starttime —— 防 pid 复用（Linux 也这么做） */
        val starttime: Long = 0L,
        /** `ExecMainStatus=` —— 上一次退出的码 */
        val exitCode: Int? = null,
        /** 上一次退出的时刻 */
        val exitedAt: Long = 0L,
        /** `NRestarts=` —— 累计重启次数（systemd 是持久的，我们以前是局部变量） */
        val restarts: Int = 0,
        /** `Result=` —— 最后一次为什么挂 */
        val lastFailure: String = "",
        /** `DesiredState=` —— 想怎么对它（RUNNING/STOPPED/FROZEN） */
        val desired: Desired = Desired.STOPPED,
    ) {
        /** 一个文件：相对落位根的路径 + sha256 */
        data class FileRec(val path: String, val sha256: String)

        /** 是件还是程序 —— 由落位形状决定，不由字段决定 */
        val isPiece: Boolean get() = role.isNotEmpty() && role != "app"

        /**
         * 这是件还是程序 —— [isPiece] 的枚举形态。
         *
         * 此前它在 IndexEntry 上（那个转发属性里），第 1 层合成 UnitEntry 时漏了搬过来，
         * 波及 8 处调用点（ProgramManager / ProgramStatus / SupervisorPool /
         * QuickAppRegistry / PackageInstaller / ProgramIndex.byLevel）。
         */
        val level: Level get() = if (isPiece) Level.PIECE else Level.PROGRAM
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

    /**
     * 路径片段消毒 —— 把 raw 变成能安全落进 usr/lib/<id>/ 的那一段。
     *
     * 挡 . 与 ..、限长 64、限字符集（字母数字与 . _ - +）。
     * 不合格返回 null，调用方据此拒绝这个 id/版本。
     */
    /** 注册表根目录 —— 程序/件的状态都落在这下面 */
    fun root(ctx: Context): File = dir(ctx)

    /** 注册表文件本身。改动它就是改数据源，不该有别处 */
    fun file(ctx: Context): File = File(dir(ctx), FILE)

    fun safeSegment(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 64) return null
        if (v == "." || v == "..") return null
        return v.takeIf {
            it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == '+' }
        }
    }

    /**
     * 一条「还没登记」的空记录 —— upsert 前的基底。
     *
     * 第二参数用 Level 而不是裸 Boolean：Level 才是仓库里表示
     * 「这是件还是程序」的那一个（byLevel / level == Level.PIECE 都在用）。
     * 此前这里签名是 isPiece: Boolean，而 5 个调用点里 5 个传的已经是 Level
     * —— 两种表示混着，编译报「actual type is Level, but Boolean was expected」。
     */
    fun empty(id: String, level: Level): UnitEntry =
        UnitEntry(
            id = id,
            version = "",        // 还没装 —— version 由落位扫描填
            stateDir = "",        // 同上
            assetEntry = "",      // 同上
            sha256 = "",          // 同上
            role = if (level == Level.PIECE) "" else "app",
        )

    /**
     * 编码 —— 一种 unit 一种形状。
     *
     * systemd 也只有一种 unit 文件：Type= 那一行区分 service/mount，
     * 不是一个文件一种 schema。我们此前的 "kind" 字段是那层间接的产物。
     *
     * `role` 就是那个区分字段：library · exec · shell · headers · app。
     */
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

    /** 解码 —— encode 的逆运算。字段与 encode 一一对应，没有第二种形状 */
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
                // keys() 返回 MutableSet<String>（Kotlin 侧带上了元素类型），
                // names() 在 Kotlin 眼里是 Array<Any!>，associate 出来就成了 Map<Any!, String!>
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
    /**
     * 这是不是一件 —— **看落位形状决定出来的 role**，不看它在记录的哪一段。
     *
     * 此前判据是「条目里 piece 字段非空」，那是我们有两种记录时的产物；
     * systemd 只有一种 unit，Type= 那一行就区分了 service/mount
     * （我们对应的是 role：library · exec · shell · headers · app）。
     */
    fun isPiece(e: UnitEntry): Boolean = e.role.isNotEmpty() && e.role != "app"

    /** 某个 id 是不是一件 —— 查注册表，不扫盘 */
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

    @Synchronized
    fun replaceAll(ctx: Context, entries: List<UnitEntry>) = persist(ctx, entries)

    private fun persist(ctx: Context, entries: List<UnitEntry>) {
        val arr = JSONArray()
        entries.sortedBy { it.id }.forEach { arr.put(encode(it)) }
        StateFiles.writeJson(file(ctx), JSONObject().apply {
            put("schema", SCHEMA)
            put("entries", arr)
        })
    }
}

/**
 * 改字段 —— 参数全为 null 表示「不改」。
 *
 * 改完返回新的 [UnitEntry]。落盘由调用方做（ProgramIndex.mutate）。
 */
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
