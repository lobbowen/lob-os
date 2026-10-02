package lobos.bridge

object ApiSurface {

    const val LOBOS_DEV = "lobos.dev"
    const val LOBOS_SYS = "lobos.sys"

    const val DEV_GROUP = "lobos:dev"
    const val SYS_GROUP = "lobos:sys"

    const val DEV_SCOPE = "app"
    const val SYS_SCOPE = "app"

    private val DEV_PREFIXES = listOf(
        "os.runtime.",
        "os.packages.",
        "os.catalog.",
        "os.facilities.",
        "os.instances.",
        "os.journal.",
        "os.diag.",
        "os.ports.",
        "os.env.",
        "os.compat.",
        "os.registry.",
        "os.storage.",
        "os.fs.",
        "os.programs.",
        "os.processes.",
    )

    private val SYS_PREFIXES = listOf(
        "os.capability.",
        "os.permissions.",
        "os.anchor.",
        "os.adb.",
        "os.accessibility.",
        "os.notifications.",
        "os.automation.",
        "os.screenshot.",
        "os.power.",
    )

    private val SYS_ONLY = setOf(
        "shell.exec",
        "shell.readOutput",
        "shell.start",
        "shell.stop",
        "sys.getprop",
        "sys.env",
        "ui.tap",
        "ui.swipe",
        "ui.inputText",
        "ui.getUiTree",
        "ui.waitFor",
        "ui.screenshot",
        "notif.post",
        "notif.list",
        "build.programInstall",
    )

    private val DEV_EXCLUDED = setOf(
        "os.env.status",
        "os.compat.status",
        "os.fs.read",
        "os.fs.write",
        "os.fs.delete",
        "os.fs.list",
    )

    fun layerOf(method: String): String {
        if (SYS_ONLY.contains(method)) return SYS_SCOPE
        if (DEV_EXCLUDED.contains(method)) return SYS_SCOPE
        for (p in SYS_PREFIXES) if (method.startsWith(p)) return SYS_SCOPE
        for (p in DEV_PREFIXES) if (method.startsWith(p)) return DEV_SCOPE
        return DEV_SCOPE
    }

    fun groupFor(method: String): String =
        if (layerOf(method) == SYS_SCOPE) SYS_GROUP else DEV_GROUP

    fun requireLayer(method: String, requested: String) {
        val actual = layerOf(method)
        if (actual != requested) {
            throw IllegalArgumentException(
                "方法 $method 属于 $actual 层，不能按 $requested 层调用",
            )
        }
    }

    fun surface(): String =
        "lobos.dev：程序运行时与包管理（面向开发者）；lobos.sys：系统能力与设置（面向宿主与面板）"
}