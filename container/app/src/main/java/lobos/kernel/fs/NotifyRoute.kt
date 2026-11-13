package lobos.kernel.fs

import android.content.Context

object NotifyRoute {

    const val ROUTE = "notify-route"

    fun note(
        ctx: Context?,
        route: String,
        notificationId: Int,
        kind: String,
        detail: String = "",
    ) {
        if (ctx == null) return
        runCatching {
            lobos.services.log.RuntimeDiagnostics.append(
                ctx, ROUTE, true,
                "那发通知逐字[" + route + "]逐字发了",
                "id=" + notificationId +
                    " kind=" + kind +
                    (if (detail.isBlank()) "" else " " + detail),
            )
        }
    }
}
