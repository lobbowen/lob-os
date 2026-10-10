package lobos.services.reg


/**
 * unit 状态 —— 照抄 `systemctl list-units` 的三列形状。
 *
 * 官方原文（systemctl(1) 的 Table 1 与 LOAD/ACTIVE/SUB 说明）：
 *
 *   LOAD   = Reflects whether the unit definition was properly loaded.
 *            one of: loaded · not-found · bad-setting · error · masked
 *   ACTIVE = The high-level unit activation state, i.e. generalization of SUB.
 *   SUB    = The low-level unit activation state, values depend on unit type.
 *
 * 我们此前那8 个自造状态（ABSENT BROKEN STOPPED STARTING RUNNING RESTARTING
 * UNHEALTHY QUARANTINED）里有 4 个 systemd 没有，systemd 的 maintenance/
 * reloading/refreshing 与整列 LOAD 我们也没有。现在按三列重建。
 *
 * 关键一条（Table 1 的 failed 定义）：
 *   failed = "Similar to inactive, but the unit failed in some way
 *             (process returned error code on exit, crashed, an operation
 *              timed out, **or after too many restarts**)"
 * —— 「重启太多次」落进 failed，这就是 NRestarts 的用处。
 */
object UnitState {

    // ── LOAD 列 ────────────────────────────────────────────────
    /** LOAD = whether the unit definition was properly loaded */
    enum class Load(val label: String) {
        /** LOADED —— 定义被正确读取 */
        LOADED("loaded"),

        /**
         * NOT_FOUND —— 没有这个 unit 文件。
         * systemd 里「没装」不属于 unit 状态（那个 unit 压根不在内存里）。
         * 我们需要一个值来说「没装」，但那是**注册表里没有这一条**，
         * 不是 LOAD 的某一档 —— 见 [UnitState.loadOf]。
         */
        NOT_FOUND("not-found"),

        /** BAD_SETTING —— 定义读了但某个必填设置解不开（对应我们的 invalid 非空） */
        BAD_SETTING("bad-setting"),

        /** ERROR —— 装载过程出错（读定义时抛异常那一类） */
        ERROR("error"),

        /** MASKED —— 被配置文件 mask 掉（`/dev/null -> unit`） */
        MASKED("masked"),
    }

    // ── ACTIVE 列 ──────────────────────────────────────────────
    /** ACTIVE = the high-level unit activation state */
    enum class Active(val label: String) {
        ACTIVE("active"),
        INACTIVE("inactive"),

        /**
         * FAILED —— "Similar to inactive, but the unit failed in some way
         * (process returned error code on exit, crashed, an operation timed out,
         *  **or after too many restarts**)"。
         *
         * ★ 「重启太多次」也落进这里 —— systemd 靠 NRestarts 判，
         *   我们的 restarts 现在在 UnitEntry 里（之前是局部变量，进程重启即丢）。
         */
        FAILED("failed"),

        ACTIVATING("activating"),
        DEACTIVATING("deactivating"),

        /** MAINTENANCE —— unit 是 inactive，且有维护操作在进行 */
        MAINTENANCE("maintenance"),

        /** RELOADING —— unit 是 active，正在重载配置 */
        RELOADING("reloading"),

        /** REFRESHING —— unit 是 active，正在它自己的 namespace 里激活新挂载 */
        REFRESHING("refreshing"),
    }

    // ── SUB 列 ────────────────────────────────────────────────
    /**
     * SUB = the low-level unit activation state，**values depend on unit type**。
     *
     * 我们只有一种 unit（件/程序按 role 区分），所以 SUB 只对「要跑进程的那种」
     * 有意义 —— 与 systemd 的 service unit 对应。
     */
    enum class Sub(val label: String) {
        /** 不适用（件没有进程） */
        NA(""),

        /** 进程起来了但还没就绪 —— 对应 Type=notify 之前的等待 */
        STARTING("start"),

        /** 就绪并在运行 */
        RUNNING("running"),

        /** 起来了但探活不过 —— systemd 的 WatchdogSec 失败会走到 FAILED，
         *  这里是我们「在跑但不健康」那一档 */
        DEGRADED("degraded"),

        /** 优雅退出中 */
        STOPPING("stop"),

        /** 退出码非零 */
        FAILED("failed"),

        /** 没起来 */
        DEAD("dead"),
    }

    // ── 三列的取值 ────────────────────────────────────────────

    /**
     * LOAD 列怎么算。
     *
     * systemd：「unit definition was properly loaded」——
     * 那是**读定义文件**的结果。我们没有 unit 文件（配置在注册表里），
     * 所以对应物是「注册表里有没有这一条」+「这一条的设置解不解得开」。
     */
    fun loadOf(entry: UnitEntry?): Load = when {
        entry == null -> Load.NOT_FOUND
        entry.invalid != null -> Load.BAD_SETTING
        else -> Load.LOADED
    }

    /**
     * ACTIVE 列怎么算。
     *
     * 判据全是**注册表与账本里的事实**（UnitEntry 的运行态字段），
     * 不额外存一个状态 —— 状态是算出来的，不是记下来的。
     * 这与 systemd 一致：ActiveState 由进程在不在、desired 是什么、
     * 重启次数够不够决定。
     *
     * @param entry       注册表里那一条（null = 没装）
     * @param processAlive 账本里的 pid 还活着吗（ProcessLedger 判，含 starttime 防复用）
     * @param startRequested 已经请求了起、还在启动途中
     * @param stopRequested 已经请求了停、还在退出途中
     */
    fun activeOf(
        entry: UnitEntry?,
        processAlive: Boolean,
        startRequested: Boolean = false,
        stopRequested: Boolean = false,
        reloadRequested: Boolean = false,
        maintenance: Boolean = false,
    ): Active = when {
        entry == null -> Active.INACTIVE

        // 「重启太多次」→ failed（Table 1 的 failed 明写这一条）
        entry.restarts >= entry.maxRestarts && entry.maxRestarts > 0 ->
            if (processAlive) Active.ACTIVE else Active.FAILED

        entry.invalid != null -> Active.FAILED

        // 件没有进程 —— 有没有进程就两个态
        !isRunnable(entry) -> Active.ACTIVE

        // 进程还在：正在重载就是 RELOADING，否则就是 ACTIVE
        processAlive ->
            if (reloadRequested) Active.RELOADING else Active.ACTIVE

        // 进程不在：按「请求过什么」和上次怎么退的判（Table 1 的 inactive/failed）
        startRequested -> Active.ACTIVATING
        stopRequested -> Active.DEACTIVATING
        entry.exitCode != null && entry.exitCode != 0 -> Active.FAILED
        maintenance -> Active.MAINTENANCE
        else -> Active.INACTIVE
    }

    /** 这个 unit 有进程可跑吗 —— 件没有（systemd 的 Type=oneshot 那一类） */
    fun isRunnable(entry: UnitEntry): Boolean = entry.role != "library" && entry.role != "headers"

    /**
     * SUB 列怎么算 —— 只对有进程的那种有意义。
     *
     * 「健康」在我们这儿是 httpHealth 的探活结果（systemd 的 WatchdogSec）。
     */
    fun subOf(entry: UnitEntry?, processAlive: Boolean, healthy: Boolean): Sub = when {
        entry == null -> Sub.DEAD
        !isRunnable(entry) -> Sub.NA
        !processAlive -> if (entry.exitCode != null && entry.exitCode != 0) Sub.FAILED else Sub.DEAD
        healthy -> Sub.RUNNING
        else -> Sub.DEGRADED
    }

    /**
     * is-active 的判据 —— systemctl(1)：「Returns an exit code 0 if at least one
     * is active」。
     */
    fun isActive(active: Active): Boolean = active == Active.ACTIVE
}
