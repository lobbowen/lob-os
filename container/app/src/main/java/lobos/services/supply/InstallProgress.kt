package lobos.services.supply

import android.content.Context
import org.json.JSONObject

object InstallProgress {
    enum class Stage {
        IDLE, QUEUED, DOWNLOAD, VERIFY, LAND, LINK, DONE, FAILED
    }

    data class Snapshot(
        val id: String,
        val stage: Stage,
        val detail: String,
        val at: Long,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot("", Stage.IDLE, "", 0L)

    fun current(): Snapshot = snapshot

    fun stage(id: String, stage: Stage, detail: String = "") {
        snapshot = Snapshot(id, stage, detail, System.currentTimeMillis())
    }

    fun done(id: String, version: String) {
        snapshot = Snapshot(id, Stage.DONE, "v" + version, System.currentTimeMillis())
    }

    fun failed(id: String, reason: String) {
        snapshot = Snapshot(id, Stage.FAILED, reason, System.currentTimeMillis())
    }

    fun label(stage: Stage): String = when (stage) {
        Stage.IDLE -> "空闲"
        Stage.QUEUED -> "排队"
        Stage.DOWNLOAD -> "下载"
        Stage.VERIFY -> "校验"
        Stage.LAND -> "落位"
        Stage.LINK -> "接入"
        Stage.DONE -> "完成"
        Stage.FAILED -> "失败"
    }

    fun recent(ctx: Context): List<JSONObject> = InstallGuard.all(ctx).values.toList()
}