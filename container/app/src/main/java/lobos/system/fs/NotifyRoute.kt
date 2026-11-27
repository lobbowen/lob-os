package lobos.system.fs


object NotifyRoute {

    const val ROUTE = "notify-route"

    fun note(
        enabled: Boolean,
        route: String,
        notificationId: Int,
        kind: String,
        detail: String = "",
    ) {
        if (!enabled) return
        runCatching {
            lobos.system.core.KernelServices.logger().log(ROUTE, true,
                "那发通知[" + route + "]发了",
                "id=" + notificationId +
                    " kind=" + kind +
                    (if (detail.isBlank()) "" else " " + detail),
            )
        }
    }
}
