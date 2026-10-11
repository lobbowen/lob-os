package lobos.services.reg

object UnitState {

    enum class Load(val label: String) {
        LOADED("loaded"),

        NOT_FOUND("not-found"),

        BAD_SETTING("bad-setting"),

        ERROR("error"),

        MASKED("masked"),
    }

    enum class Active(val label: String) {
        ACTIVE("active"),
        INACTIVE("inactive"),

        FAILED("failed"),

        ACTIVATING("activating"),
        DEACTIVATING("deactivating"),

        MAINTENANCE("maintenance"),

        RELOADING("reloading"),

        REFRESHING("refreshing"),
    }

    enum class Sub(val label: String) {
        NA(""),

        STARTING("start"),

        RUNNING("running"),

        DEGRADED("degraded"),

        STOPPING("stop"),

        FAILED("failed"),

        DEAD("dead"),
    }

    fun loadOf(entry: UnitEntry?): Load = when {
        entry == null -> Load.NOT_FOUND
        entry.invalid != null -> Load.BAD_SETTING
        else -> Load.LOADED
    }

    fun activeOf(
        entry: UnitEntry?,
        processAlive: Boolean,
        startRequested: Boolean = false,
        stopRequested: Boolean = false,
        reloadRequested: Boolean = false,
        maintenance: Boolean = false,
    ): Active = when {
        entry == null -> Active.INACTIVE

        entry.restarts >= entry.maxRestarts && entry.maxRestarts > 0 ->
            if (processAlive) Active.ACTIVE else Active.FAILED

        entry.invalid != null -> Active.FAILED

        !isRunnable(entry) -> Active.ACTIVE

        processAlive ->
            if (reloadRequested) Active.RELOADING else Active.ACTIVE

        startRequested -> Active.ACTIVATING
        stopRequested -> Active.DEACTIVATING
        entry.exitCode != null && entry.exitCode != 0 -> Active.FAILED
        maintenance -> Active.MAINTENANCE
        else -> Active.INACTIVE
    }

    fun isRunnable(entry: UnitEntry): Boolean = entry.role != "library" && entry.role != "headers"

    fun subOf(entry: UnitEntry?, processAlive: Boolean, healthy: Boolean): Sub = when {
        entry == null -> Sub.DEAD
        !isRunnable(entry) -> Sub.NA
        !processAlive -> if (entry.exitCode != null && entry.exitCode != 0) Sub.FAILED else Sub.DEAD
        healthy -> Sub.RUNNING
        else -> Sub.DEGRADED
    }

    fun isActive(active: Active): Boolean = active == Active.ACTIVE
}
