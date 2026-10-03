package lobos.bridge

object ApiSpec {

    const val TRANSPORT = "lobos-bridge/1"
    const val ENVELOPE = "jsonrpc-2.0"

    const val NAMESPACE_DEV = "lobos.dev"
    const val NAMESPACE_SYS = "lobos.sys"

    const val GROUP_DEV = "lobos:dev"
    const val GROUP_SYS = "lobos:sys"

    const val TIER_DEV = "dev"
    const val TIER_SYS = "sys"

    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 0

    const val IDEMPOTENT = "idempotent"
    const val MUTATING = "mutating"
    const val READONLY = "readonly"

    const val SCOPE_SELF = "self"
    const val SCOPE_PROGRAM = "program"
    const val SCOPE_SYSTEM = "system"

    val DEV_NAMESPACES = listOf(
        "runtime", "packages", "catalog", "facilities", "instances",
        "journal", "diagnostics", "ports", "state", "processes", "programs",
    )

    val SYS_NAMESPACES = listOf(
        "capability", "permissions", "accessibility", "adb", "automation",
        "notifications", "screenshot", "power", "shell", "fs", "ui", "device",
    )

    val IDEMPOTENCE = mapOf(
        "lobos.sys.accessibility.enable" to IDEMPOTENT,
        "lobos.sys.accessibility.state" to READONLY,
        "lobos.sys.accessibility.disable" to IDEMPOTENT,
        "lobos.sys.accessibility.actions" to READONLY,
        "lobos.sys.manifest.spec" to READONLY,
        "lobos.sys.manifest.validate" to READONLY,
        "lobos.sys.permissions.ledger" to READONLY,
        "lobos.sys.permissions.roles" to READONLY,
    )

    val SCOPES = mapOf(
        "lobos.sys.api" to SCOPE_SYSTEM,
        "lobos.sys.capability.invoke" to SCOPE_SYSTEM,
        "lobos.sys.shell.exec" to SCOPE_SYSTEM,
        "lobos.sys.fs.write" to SCOPE_SYSTEM,
        "lobos.sys.device.listInstalled" to SCOPE_SYSTEM,
        "lobos.sys.device.launch" to SCOPE_SYSTEM,
        "lobos.sys.device.openUrl" to SCOPE_SYSTEM,
        "lobos.sys.accessibility.state" to SCOPE_SYSTEM,
        "lobos.sys.accessibility.enable" to SCOPE_SYSTEM,
        "lobos.sys.accessibility.disable" to SCOPE_SYSTEM,
        "lobos.sys.accessibility.actions" to SCOPE_SYSTEM,
        "lobos.sys.permissions.ledger" to SCOPE_SYSTEM,
        "lobos.sys.permissions.roles" to SCOPE_SYSTEM,
        "lobos.sys.host.status" to SCOPE_SYSTEM,
    )

    val CANONICAL = mapOf(
        "app.listInstalled" to "lobos.sys.device.listInstalled",
        "app.launch" to "lobos.sys.device.launch",
        "app.openUrl" to "lobos.sys.device.openUrl",
        "notif.post" to "lobos.sys.notifications.post",
        "notif.read" to "lobos.sys.notifications.list",
        "ui.tap" to "lobos.sys.ui.tap",
        "ui.swipe" to "lobos.sys.ui.swipe",
        "ui.inputText" to "lobos.sys.ui.inputText",
        "ui.getUiTree" to "lobos.sys.ui.tree",
        "ui.screenshot" to "lobos.sys.screenshot.capture",
        "ui.waitFor" to "lobos.sys.ui.waitFor",
        "ui.globalAction" to "lobos.sys.ui.globalAction",
        "ui.events" to "lobos.sys.ui.events",
        "ui.dropEvents" to "lobos.sys.ui.dropEvents",
        "shell.status" to "lobos.sys.adb.status",
        "shell.pair" to "lobos.sys.adb.pair",
        "shell.forget" to "lobos.sys.adb.forget",
        "shell.exec" to "lobos.sys.shell.exec",
        "fs.read" to "lobos.sys.fs.read",
        "fs.write" to "lobos.sys.fs.write",
        "fs.list" to "lobos.sys.fs.list",
        "fs.mkdir" to "lobos.sys.fs.mkdir",
        "build.programInstall" to "lobos.dev.packages.install",
        "build.programStatus" to "lobos.dev.packages.status",
        "build.apk" to "lobos.dev.programs.apk",
        "build.status" to "lobos.dev.programs.status",
        "capability.invoke" to "lobos.sys.capability.invoke",
        "sys.info" to "lobos.sys.info",
        "sys.api" to "lobos.sys.api",
        "os.manifest.spec" to "lobos.sys.manifest.spec",
        "os.manifest.validate" to "lobos.sys.manifest.validate",
        "os.accessibility.state" to "lobos.sys.accessibility.state",
        "os.permissions.ledger" to "lobos.sys.permissions.ledger",
        "os.permissions.roles" to "lobos.sys.permissions.roles",
        "os.host.status" to "lobos.sys.host.status",
        "os.accessibility.enable" to "lobos.sys.accessibility.enable",
        "os.accessibility.disable" to "lobos.sys.accessibility.disable",
        "os.accessibility.actions" to "lobos.sys.accessibility.actions",
        "sys.nativeAssets" to "lobos.sys.device.nativeAssets",
        "os.nativeAssets.status" to "lobos.sys.device.nativeAssets",
        
        "sys.nativeAssets" to "lobos.sys.device.nativeAssets",
    )

    fun canonical(method: String): String = CANONICAL[method] ?: method

    fun tierOf(method: String): String =
        if (canonical(method).startsWith(NAMESPACE_SYS + ".")) TIER_SYS else TIER_DEV

    fun groupOf(method: String): String = if (tierOf(method) == TIER_SYS) GROUP_SYS else GROUP_DEV

    fun idempotenceOf(method: String): String = IDEMPOTENCE[canonical(method)] ?: MUTATING

    fun scopeOf(method: String): String = SCOPES[canonical(method)] ?: SCOPE_PROGRAM

    fun isDeprecated(method: String): Boolean = CANONICAL.containsKey(method) && canonical(method) != method

    fun deprecatedInFavorOf(method: String): String? =
        if (isDeprecated(method)) CANONICAL[method] else null

    fun deprecationNotice(method: String): String? {
        val to = deprecatedInFavorOf(method) ?: return null
        return "方法 $method 已更名，标准名 $to；$to 将在协议 2 移除本名"
    }

    fun layerOf(method: String): String = if (tierOf(method) == TIER_SYS) NAMESPACE_SYS else NAMESPACE_DEV

    fun surface(): String = buildString {
        append("命名空间：").append(NAMESPACE_DEV).append("（开发者 API，程序运行时与包管理）／")
        append(NAMESPACE_SYS).append("（系统 API，能力·权限·设置与宿主设施）。\n")
        append("方法名：lobos.<层>.<域>.<动作>，全部小驼峰；旧短名保留为别名并标注弃用。\n")
        append("能力组：").append(GROUP_DEV).append("（可自动授予）／").append(GROUP_SYS)
        append("（需经 os.permissions 授权后授予）。\n")
        append("作用域：").append(SCOPE_SELF).append("／").append(SCOPE_PROGRAM)
        append("／").append(SCOPE_SYSTEM).append("（越界即 CODE_POLICY_DENIED）。\n")
        append("副作用标注：").append(READONLY).append("／").append(IDEMPOTENT)
        append("／").append(MUTATING).append("。\n")
        append("信封：").append(ENVELOPE).append("；传输：").append(TRANSPORT)
        append("；版本：").append(VERSION_MAJOR).append('.').append(VERSION_MINOR).append('。')
    }

    fun toJson(methods: List<String>): org.json.JSONObject = org.json.JSONObject().apply {
        put("transport", TRANSPORT)
        put("envelope", ENVELOPE)
        put("version", VERSION_MAJOR.toString() + "." + VERSION_MINOR)
        put("namespaces", org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("name", NAMESPACE_DEV)
                put("group", GROUP_DEV)
                put("domains", org.json.JSONArray(DEV_NAMESPACES))
            })
            put(org.json.JSONObject().apply {
                put("name", NAMESPACE_SYS)
                put("group", GROUP_SYS)
                put("domains", org.json.JSONArray(SYS_NAMESPACES))
            })
        })
        put("tiers", org.json.JSONArray().apply { put(TIER_DEV); put(TIER_SYS) })
        put("idempotence", org.json.JSONArray().apply { put(READONLY); put(IDEMPOTENT); put(MUTATING) })
        put("scopes", org.json.JSONArray().apply { put(SCOPE_SELF); put(SCOPE_PROGRAM); put(SCOPE_SYSTEM) })
        put("errorCodes", ERROR_CODES)
        put("rules", org.json.JSONArray().apply {
            put("方法名 lobos.<层>.<域>.<动作>，小驼峰；未带命名空间前缀的短名视为弃用别名")
            put("握手须携带 LOBOS_SESSION_TOKEN；会话一次性，不可重放")
            put("能力组在握手时由服务端按实测证据授予，客户端不可自述")
            put("缺能力组返 CODE_CAPABILITY_MISSING；参数非法返 CODE_INVALID_PARAM")
            put("同名方法不得跨层：lobos.sys.* 不对程序开放，仅宿主与面板可用")
        })
        put("methods", org.json.JSONArray().apply {
            for (m in methods.sorted()) {
                put(org.json.JSONObject().apply {
                    put("name", m)
                    put("canonical", canonical(m))
                    put("tier", tierOf(m))
                    put("layer", layerOf(m))
                    put("group", groupOf(m))
                    put("idempotence", idempotenceOf(m))
                    put("scope", scopeOf(m))
                    val to = deprecatedInFavorOf(m)
                    if (to != null) {
                        put("deprecated", true)
                        put("useInstead", to)
                    }
                })
            }
        })
    }

    private val ERROR_CODES: org.json.JSONObject = org.json.JSONObject().apply {
        put("CODE_METHOD_NOT_FOUND", -32601)
        put("CODE_INVALID_PARAM", -32602)
        put("CODE_INTERNAL", -32603)
        put("CODE_CAPABILITY_MISSING", -32001)
        put("CODE_NOT_IMPLEMENTED", -32002)
        put("CODE_SESSION_MISSING", -32004)
        put("CODE_POLICY_DENIED", -32005)
        put("CODE_PROTOCOL_UNSUPPORTED", -32006)
        put("CODE_VERSION_MISSING", -32007)
        put("CODE_SCOPE_DENIED", -32008)
        put("CODE_RATE_LIMITED", -32009)
        put("CODE_TIMEOUT", -32010)
    }
}