package lobos.ota

object ProgramOtaResolution {

    enum class State {
        ABSENT,
        INCOMPLETE,
        READY,
    }

    data class Resolved(
        val state: State,
        val version: String?,
        val ok: Boolean,
        val title: String,
        val detail: String,
    )

    fun resolve(currentVersion: String?, entryPath: String?, entryExists: Boolean): Resolved {
        val v = currentVersion?.trim()?.ifBlank { null }
        if (v == null) {
            return Resolved(
                state = State.ABSENT,
                version = null,
                ok = false,
                title = "尚无内核包（OTA 尚未安装成功）",
                detail = "任一程序的 CURRENT 指针都缺失；程序需经 OTA 安装，本次不启动运行时。" +
                    "随包 assets/node/server.js 只是探针，不在启动链上（只由诊断页显式驱动）。",
            )
        }
        if (!entryExists) {
            return Resolved(
                state = State.INCOMPLETE,
                version = v,
                ok = false,
                title = "内核不完整（CURRENT=" + v + "，但入口缺失）",
                detail = "CURRENT 已指向 " + v + "，却找不到入口" +
                    (entryPath?.let { "（" + it + "）" } ?: "") +
                    "；本次不启动运行时。**归因提示**：说明安装确实发生过，" +
                    "应查那一次的 install/verify 日志（可能只落地了一半），而不是查网络与 feed。",
            )
        }
        return Resolved(
            state = State.READY,
            version = v,
            ok = true,
            title = "内核版本=" + v,
            detail = "入口=" + (entryPath ?: "(未知)"),
        )
    }
}
