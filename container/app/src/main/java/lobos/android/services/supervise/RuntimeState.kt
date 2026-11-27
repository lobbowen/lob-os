package lobos.android.services.supervise

object RuntimeState {
    enum class Phase {
        UNKNOWN,
        STOPPED,
        BOOTING,
        RUNNING,
        FAILED,
    }

    @Volatile
    var phase: Phase = Phase.UNKNOWN
        private set

    @Volatile
    var detail: String = ""
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    fun mark(p: Phase, d: String = "") {
        phase = p
        detail = d
        updatedAt = System.currentTimeMillis()
    }

    fun label(): String = when (phase) {
        Phase.UNKNOWN -> "未知"
        Phase.STOPPED -> "已停止"
        Phase.BOOTING -> "启动中"
        Phase.RUNNING -> "运行中"
        Phase.FAILED -> "异常"
    }
}