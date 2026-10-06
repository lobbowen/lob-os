package lobos.log

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

    val ALL: List<String> = listOf(
        ACQ, ADB, ADB_CHANNEL, APPMGR, BOOT, CAPABILITY, CATALOG, COMPAT,
        DEEPLINK, DIAG, DOZE, INDEX, INSTANCE, MDNS, OS_PHASE, OTA, PACKAGE,
        PAIR, PERM, PERMISSION_LEDGER, PORTS, PROGRAM, PROGRAMS, QUICKAPP,
        REGISTRY, RESIDENCY, SESSION, SETTINGS, STATE, SUPERVISOR_POOL, SVC,
    )
}