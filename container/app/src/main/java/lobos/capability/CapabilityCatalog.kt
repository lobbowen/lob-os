package lobos.capability

import lobos.permissions.PermissionCatalog

object CapabilityCatalog {

    const val S0 = "S0"
    const val OX = "OX"
    const val S1 = "S1"
    const val S2 = "S2"
    const val S3 = "S3"

    const val DEV_OPTIONS = "dev-options"
    const val WIRELESS_DEBUG = "wireless-debug"
    const val ADB_CREDENTIALS = "adb-credentials"
    const val ADB_UI_AUTOMATION = "adb-ui-automation"
    const val ADB_CHANNEL = "adb-channel"
    const val RUNTIME = "runtime"
    const val PROGRAM_BUNDLE = "program-bundle"

    const val NAV_DEV_OPTIONS = "nav:dev-options"
    const val NAV_WIRELESS_DEBUG = "nav:wireless-debug"
    const val NAV_DIAGNOSTICS = "nav:diagnostics"

    const val NAV_OEM_CARD_LOCK = "nav:oem-card-lock"
    const val NAV_OEM_FULL_BG = "nav:oem-full-bg"
    const val NAV_OEM_FREEZE = "nav:oem-freeze"
    const val NAV_OEM_STARTUP = "nav:oem-startup"
    const val EXEC_OEM_CONFIRM = "oem-guards-confirm"

    enum class PermTierClass { APPOP, RUNTIME, SECURE_SETTINGS, IN_APP }

    const val EXEC_REPROBE = "adb-channel-reprobe"
    const val EXEC_RERUN_SELFCHECK = "program-selfcheck-rerun"
    const val EXEC_NOTIFICATION_LISTENER = "settings-put-notification-listener"
    const val EXEC_ACCESSIBILITY = "settings-put-accessibility-service"
    const val EXEC_BATTERY_WHITELIST = "dumpsys-battery-whitelist"

    const val EXEC_APPOPS_ALLOW = "appops-set-allow"

    const val EXEC_PM_GRANT = "pm-grant-runtime-permission"

    val ALL: List<Capability> = listOf(
        Capability(
            id = DEV_OPTIONS, title = "开发者选项", segment = OX,
            judge = { e ->
                if (e.devOptionsOn) CapVerdict(CapStatus.GRANTED, "已开启")
                else CapVerdict(CapStatus.ACTION, "未开启：顶部总开关先打开")
            },
            acquirer = { listOf(Acquisition(AcquireKind.USER_TAP, "去开发者选项页", NAV_DEV_OPTIONS)) },
        ),
        Capability(
            id = WIRELESS_DEBUG, title = "无线调试", segment = OX, requires = setOf(DEV_OPTIONS),
            judge = { e ->
                if (e.wirelessDebugOn) CapVerdict(CapStatus.GRANTED, "已开启")
                else CapVerdict(CapStatus.ACTION, "未开启：同一页里的「无线调试」开关")
            },
            acquirer = { listOf(Acquisition(AcquireKind.USER_TAP, "打开无线调试页", NAV_WIRELESS_DEBUG)) },
        ),
        perm(
            PermissionCatalog.POST_NOTIFICATIONS, "通知发送", PermTierClass.RUNTIME,
            segment = S0,
        ),
        Capability(
            id = ADB_CREDENTIALS, title = "ADB 配对凭据", segment = OX,
            requires = setOf(DEV_OPTIONS, WIRELESS_DEBUG, PermissionCatalog.POST_NOTIFICATIONS),
            judge = { e ->
                when {
                    e.credentials == CredentialsState.PAIRED ->
                        CapVerdict(CapStatus.GRANTED, "身份与配对记录在册")
                    e.pairAttempt != null && !e.pairAttempt.ok ->
                        CapVerdict(CapStatus.FAILED, e.pairAttempt.reason)
                    else -> CapVerdict(CapStatus.ACTION, "无线配对（一次 6 位码）")
                }
            },
            acquirer = { listOf(Acquisition(AcquireKind.USER_CODE, "开始配对")) },
            bridgeToken = "adb_shell",
        ),
        Capability(
            id = ADB_CHANNEL, title = "ADB 通道", segment = OX, requires = setOf(ADB_CREDENTIALS),
            optional = true,
            judge = { e ->
                val age = e.nowMs - e.channel.atMs
                when {
                    e.channelLive() -> CapVerdict(CapStatus.GRANTED, e.channel.detail)
                    e.channel.outcome == ProbeOutcome.NEVER_RUN ->
                        CapVerdict(CapStatus.ACTION, "通道未校验（探针待跑）")
                    e.channel.outcome == ProbeOutcome.DEAD ->
                        CapVerdict(CapStatus.FAILED, "通道不通：" + e.channel.detail)
                    else -> CapVerdict(
                        CapStatus.FAILED,
                        "通道读数已过期 ${age}ms（>${e.channelTtlMs}ms），重测中",
                    )
                }
            },
            acquirer = { listOf(Acquisition(AcquireKind.AUTO, "重测通道", EXEC_REPROBE)) },
        ),
        perm(
            PermissionCatalog.MANAGE_EXTERNAL_STORAGE, "全部文件访问", PermTierClass.APPOP,
            bridgeToken = "manage_external_storage",
        ),
        perm(PermissionCatalog.REQUEST_INSTALL_PACKAGES, "安装未知应用", PermTierClass.APPOP),
        perm(PermissionCatalog.SYSTEM_ALERT_WINDOW, "悬浮窗", PermTierClass.APPOP),
        perm(
            PermissionCatalog.BATTERY_OPTIMIZATION, "电池优化豁免", PermTierClass.APPOP,
            note = "不豁免则 Doze 下被限流，存活率显著下降",
        ),
        perm(
            PermissionCatalog.NOTIFICATION_ACCESS, "通知读取", PermTierClass.SECURE_SETTINGS,
            note = "系统通知读取（程序可投递状态通知）",
            bridgeToken = "notification_access",
        ),
        perm(
            PermissionCatalog.ACCESSIBILITY, "无障碍服务（UI 自动化执行体）", PermTierClass.SECURE_SETTINGS,
            note = "只作 UI 自动化执行体，不承担保活；保活靠前台服务与闹钟",
        ),
        Capability(
            id = ADB_UI_AUTOMATION, title = "UI 自动化（随 ADB 组件）", segment = OX,
            requires = setOf(ADB_CHANNEL), optional = true, bridgeToken = "accessibility",
            judge = { e ->
                val svc = e.granted(PermissionCatalog.ACCESSIBILITY)
                when {
                    e.granted(ADB_CHANNEL) && svc -> CapVerdict(CapStatus.GRANTED, "随 ADB 组件已开启")
                    svc -> CapVerdict(CapStatus.BLOCKED, "无障碍已开，但 ADB 组件未开：自动化不开")
                    else -> CapVerdict(CapStatus.ACTION, "开启后随 ADB 组件一并打开无障碍")
                }
            },
            acquirer = { e ->
                listOf(
                    Acquisition(AcquireKind.SILENT_VIA_ADB, "经 ADB 静默开启无障碍（随 ADB 组件）", EXEC_ACCESSIBILITY),
                )
            },
        ),
        perm(
            PermissionCatalog.MEDIAPROJECTION, "屏幕捕获授权", PermTierClass.IN_APP,
            optional = true, note = "每次会话授权，物理不可预置",
            bridgeToken = "mediaprojection",
        ),
        Capability(
            id = RUNTIME, title = "运行时", segment = S3,
            judge = { e ->
                if (e.controlPlaneUp) CapVerdict(CapStatus.GRANTED, "控制面在线")
                else CapVerdict(CapStatus.ACTION, "控制面未响应")
            },
            acquirer = { listOf(Acquisition(AcquireKind.USER_TAP, "看运行时启动日志", NAV_DIAGNOSTICS)) },
        ),
        Capability(
            id = PROGRAM_BUNDLE, title = "Program 包", segment = S3, requires = setOf(RUNTIME),
            judge = { e ->
                val failed = e.programChecks.filter { it.ok == false }.map { it.id }
                val unknown = e.programChecks.filter { it.ok == null }.map { it.id}
                when {
                    e.programChecks.isEmpty() -> CapVerdict(CapStatus.ACTION, "Program 自检待跑")
                    failed.isNotEmpty() -> CapVerdict(CapStatus.FAILED, "自检失败：" + failed.joinToString())
                    unknown.isNotEmpty() -> CapVerdict(CapStatus.ACTION, "自检有未知项：" + unknown.joinToString())
                    else -> CapVerdict(CapStatus.GRANTED, "自检 ${e.programChecks.size} 项通过")
                }
            },
            acquirer = { listOf(Acquisition(AcquireKind.AUTO, "重跑自检", EXEC_RERUN_SELFCHECK)) },
        ),
    )

    private fun perm(
        id: String,
        title: String,
        tier: PermTierClass,
        segment: String = S2,
        optional: Boolean = false,
        note: String = "",
        bridgeToken: String? = null,
    ): Capability {
        require(PermissionCatalog.byId(id) != null) { "$id 不在 PermissionCatalog.ALL 里，判据无从取数" }
        return Capability(
            id = id, title = title, segment = segment, optional = optional,
            bridgeToken = bridgeToken,
            judge = { e ->
                if (e.granted(id)) CapVerdict(CapStatus.GRANTED, "已授权")
                else {
                    val attempt = e.permissionAttempts[id]
                    val why = if (note.isEmpty()) "未授权" else "未授权（$note）"
                    CapVerdict(
                        CapStatus.ACTION,
                        if (attempt == null) "$why｜adb 未试" else "$why｜" + attempt.outcome.human,
                    )
                }
            },
            acquirer = { e -> permAcquirers(id, tier, e) },
        )
    }

    private fun permAcquirers(id: String, tier: PermTierClass, e: Evidence): List<Acquisition> {
        val tap = when (tier) {
            PermTierClass.RUNTIME -> Acquisition(AcquireKind.RUNTIME_DIALOG, "系统弹窗授权", id)
            PermTierClass.IN_APP -> Acquisition(AcquireKind.USER_TAP, "去诊断页授权", NAV_DIAGNOSTICS)
            PermTierClass.SECURE_SETTINGS -> Acquisition(AcquireKind.USER_TAP, "去系统授权页", id)
            PermTierClass.APPOP -> Acquisition(AcquireKind.USER_TAP, "去系统授权页", id)
        }
        val silent = silentAcquisition(id, tier) ?: return listOf(tap)
        if (!e.channelLive()) return listOf(tap)
        return when (e.attemptOutcome(id)) {
            null, AttemptOutcome.SILENT_OK -> listOf(silent)
            AttemptOutcome.NEEDS_TAP, AttemptOutcome.UNSUPPORTED -> listOf(tap)
        }
    }

    private fun silentAcquisition(id: String, tier: PermTierClass): Acquisition? = when (tier) {
        PermTierClass.SECURE_SETTINGS -> when (id) {
            PermissionCatalog.NOTIFICATION_ACCESS ->
                Acquisition(AcquireKind.SILENT_VIA_ADB, "经 ADB 静默开启", EXEC_NOTIFICATION_LISTENER)
            PermissionCatalog.ACCESSIBILITY ->
                Acquisition(AcquireKind.SILENT_VIA_ADB, "经 ADB 静默开启", EXEC_ACCESSIBILITY)
            else -> null
        }
        PermTierClass.RUNTIME ->
            if (PermissionCatalog.byId(id)?.permission.isNullOrBlank()) null
            else Acquisition(AcquireKind.SILENT_VIA_ADB, "经 ADB 试授这项运行时权限", EXEC_PM_GRANT)
        PermTierClass.APPOP -> when (id) {
            PermissionCatalog.BATTERY_OPTIMIZATION ->
                Acquisition(AcquireKind.SILENT_VIA_ADB, "经 ADB 加入 Doze 白名单", EXEC_BATTERY_WHITELIST)
            else -> if (PermissionCatalog.byId(id)?.appOpsOp == null) null
            else Acquisition(AcquireKind.SILENT_VIA_ADB, "经 ADB 试开这项 AppOps", EXEC_APPOPS_ALLOW)
        }
        PermTierClass.IN_APP -> null
    }

    init {
        val seen = HashSet<String>()
        ALL.forEachIndexed { i, c ->
            require(seen.add(c.id)) { "能力 id 重复: ${c.id}" }
            c.requires.forEach { r ->
                val idx = ALL.indexOfFirst { it.id == r }
                require(idx in 0 until i) { "能力 ${c.id} 的前置 $r 必须是更早声明的项（拓扑序被破坏）" }
            }
            ALL.filter { it.optional }.forEach { o ->
                require(o.id !in c.requires) { "optional 能力 ${o.id} 不得作为 ${c.id} 的前置" }
            }
        }
        val tokens = ALL.mapNotNull { it.bridgeToken }
        require(tokens.size == tokens.toSet().size) {
            "桥令牌重复：" + tokens.groupBy { it }.filter { it.value.size > 1 }.keys
        }
    }

    val OEM_GUARDS: List<Capability> = listOf(
        Capability(
            id = OemGuards.STARTUP_MANAGER, title = "自启动管理", segment = S3,
            judge = { e ->
            if (e.oemGuards.contains(OemGuards.STARTUP_MANAGER)) CapVerdict(CapStatus.GRANTED, "已确认")
            else CapVerdict(CapStatus.ACTION, "厂商开关无公开读接口：拨完请点「我已完成」")
            },
            acquirer = { listOf(
            Acquisition(AcquireKind.USER_TAP, "去启动管理页", NAV_OEM_STARTUP),
            Acquisition(AcquireKind.AUTO, "我已完成", EXEC_OEM_CONFIRM),
            ) },
            ),
            Capability(
            id = OemGuards.CARD_LOCK, title = "卡片锁/后台弹窗", segment = S3,
            judge = { e ->
            if (e.oemGuards.contains(OemGuards.CARD_LOCK)) CapVerdict(CapStatus.GRANTED, "已确认")
            else CapVerdict(CapStatus.ACTION, "厂商开关无公开读接口：拨完请点「我已完成」")
            },
            acquirer = { listOf(
            Acquisition(AcquireKind.USER_TAP, "去权限管理页", NAV_OEM_CARD_LOCK),
            Acquisition(AcquireKind.AUTO, "我已完成", EXEC_OEM_CONFIRM),
            ) },
            ),
            Capability(
            id = OemGuards.FULL_BACKGROUND, title = "完全后台运行", segment = S3,
            judge = { e ->
            if (e.oemGuards.contains(OemGuards.FULL_BACKGROUND)) CapVerdict(CapStatus.GRANTED, "已确认")
            else CapVerdict(CapStatus.ACTION, "厂商开关无公开读接口：拨完请点「我已完成」")
            },
            acquirer = { listOf(
            Acquisition(AcquireKind.USER_TAP, "去省电管理页", NAV_OEM_FULL_BG),
            Acquisition(AcquireKind.AUTO, "我已完成", EXEC_OEM_CONFIRM),
            ) },
            ),
            Capability(
            id = OemGuards.FREEZE_WHITELIST, title = "速冻白名单", segment = S3,
            judge = { e ->
            if (e.oemGuards.contains(OemGuards.FREEZE_WHITELIST)) CapVerdict(CapStatus.GRANTED, "已确认")
            else CapVerdict(CapStatus.ACTION, "厂商开关无公开读接口：拨完请点「我已完成」")
            },
            acquirer = { listOf(
            Acquisition(AcquireKind.USER_TAP, "去白名单页", NAV_OEM_FREEZE),
            Acquisition(AcquireKind.AUTO, "我已完成", EXEC_OEM_CONFIRM),
            ) },
            ),
    )

    fun evaluate(e: Evidence): Map<String, CapVerdict> {
        val out = LinkedHashMap<String, CapVerdict>()
        for (c in ALL + OEM_GUARDS) {
            val verdict = c.judge(e)
            if (verdict.status == CapStatus.GRANTED) { out[c.id] = verdict; continue }
            val waiting = c.requires.firstOrNull { req ->
                val v = out[req]
                v != null && v.status != CapStatus.GRANTED
            }
            out[c.id] = if (waiting != null) CapVerdict(CapStatus.BLOCKED, "等待 " + titleOf(waiting)) else verdict
        }
        return out
    }

    fun rawJudge(id: String, e: Evidence): CapVerdict? = byId(id)?.judge?.invoke(e)

    fun byId(id: String): Capability? = ALL.firstOrNull { it.id == id }

    fun titleOf(id: String): String = byId(id)?.title ?: id

    fun requiresInOrder(id: String): List<String> {
        val req = byId(id)?.requires ?: return emptyList()
        return ALL.filter { req.contains(it.id) }.map { it.id }
    }
}
