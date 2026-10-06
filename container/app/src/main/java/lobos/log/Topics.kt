package lobos.log

/**
 * 日志类别。
 *
 * 判据来源：这些值原先是 89 个调用点里的裸字符串，拼错要到运行时才发现
 * （比如把 "package" 写成 "pakcage"，日志静默少一类，没有任何报错）。
 * 收成常量后编译器能查出来。
 *
 * 命名说明：不叫 `Category`，会与 `os.Category`（程序分类
 * RUNTIME/TOOLCHAIN/LIBRARY/APPLICATION）混淆。
 *
 * 生成方式：从全仓 `Journal.append/note(ctx, "…")` 实跑提取。
 * 新增类别时同步加到这里，别再写裸字符串。
 */
object Topics {

    const val ACQ = "acq"
    const val ADB = "adb"
    const val ADB_CHANNEL = "adb-channel"
    const val APPMGR = "appmgr"
    const val BOOT = "boot"
    const val CAPABILITY = "capability"
    const val CATALOG = "catalog"
    const val COMPAT = "compat"
    const val DEEPLINK = "deeplink"
    const val DIAG = "diag:"
    const val DOZE = "doze"
    const val INDEX = "index"
    const val INSTANCE = "instance"
    const val MDNS = "mdns"
    const val OS_PHASE = "os-phase"
    const val OTA = "ota"
    const val PACKAGE = "package"
    const val PAIR = "pair"
    const val PERM = "perm"
    const val PERMISSION_LEDGER = "permission-ledger"
    const val PORTS = "ports"
    const val PROGRAM = "program"
    const val PROGRAMS = "programs"
    const val QUICKAPP = "quickapp"
    const val REGISTRY = "registry"
    const val RESIDENCY = "residency"
    const val SESSION = "session"
    const val SETTINGS = "settings"
    const val STATE = "state"
    const val SUPERVISOR_POOL = "supervisor-pool"
    const val SVC = "svc"

    /** 全部已知类别。给导出层与自检用。 */
    val ALL: List<String> = listOf(
        ACQ, ADB, ADB_CHANNEL, APPMGR, BOOT, CAPABILITY, CATALOG, COMPAT,
        DEEPLINK, DIAG, DOZE, INDEX, INSTANCE, MDNS, OS_PHASE, OTA, PACKAGE,
        PAIR, PERM, PERMISSION_LEDGER, PORTS, PROGRAM, PROGRAMS, QUICKAPP,
        REGISTRY, RESIDENCY, SESSION, SETTINGS, STATE, SUPERVISOR_POOL, SVC,
    )
}