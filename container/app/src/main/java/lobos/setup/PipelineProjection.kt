package lobos.setup

import lobos.capability.CapabilityCatalog
import lobos.capability.CapStatus
import lobos.capability.CapVerdict
import lobos.capability.Evidence
import lobos.capability.Acquisition
import lobos.capability.Capability

enum class StepStatus { DONE, ACTION, BLOCKED, FAILED }

data class PipelineStep(
    val id: String,
    val title: String,
    val status: StepStatus,
    val detail: String,
    val pending: Acquisition? = null,
    val pendingCapId: String? = null,
)

object PipelineProjection {

    const val S4 = "S4"

    private val GATING = listOf(
        CapabilityCatalog.RUNTIME,
        CapabilityCatalog.PROGRAM_BUNDLE,
    )

    fun workbenchReady(verdicts: Map<String, CapVerdict>): Boolean =
        GATING.all { verdicts[it]?.status == CapStatus.GRANTED }


    private fun rank(s: CapStatus): Int = when (s) {
        CapStatus.FAILED -> 0
        CapStatus.ACTION -> 1
        CapStatus.BLOCKED -> 2
        CapStatus.GRANTED -> 3
    }

    private fun toStep(s: CapStatus): StepStatus = when (s) {
        CapStatus.GRANTED -> StepStatus.DONE
        CapStatus.ACTION -> StepStatus.ACTION
        CapStatus.BLOCKED -> StepStatus.BLOCKED
        CapStatus.FAILED -> StepStatus.FAILED
    }

      /**
       * 投影成步骤列表 —— **逐项列出，按真实状态排序**。
       *
       * 此前按 S0~S3/OX 分档、每档出一条（档位那套是我加的，Linux 没有：
       * 那里只有「有没有成」，没有「这项属于第几档」）。现在每项能力一条，
       * 顺序由状态决定（失败 → 待办 → 等待 → 已就位）。
       */
      fun project(e: Evidence, verdicts: Map<String, CapVerdict>): List<PipelineStep> {
          val rows = CapabilityCatalog.ALL
              .filter { verdicts[it.id] != null }
              .sortedBy { rank(verdicts[it.id]!!.status) }
              .map { c ->
                  val v = verdicts[c.id]!!
                  PipelineStep(
                      id = c.id,
                      title = c.title,
                      status = toStep(v.status),
                      // 状态名保留在标题里，别丢 —— 原来它错传到了 detail 位
                      detail = v.detail.ifBlank { v.status.name },
                  )
              }
          return rows + workbenchRow(e, verdicts)
      }

    private fun workbenchRow(e: Evidence, verdicts: Map<String, CapVerdict>): PipelineStep {
        val gating = GATING.mapNotNull { id ->
            val c = CapabilityCatalog.byId(id) ?: return@mapNotNull null
            val v = verdicts[id] ?: return@mapNotNull null
            c to v
        }
        val gaps = gating.filter { it.second.status != CapStatus.GRANTED }
        if (gaps.isEmpty()) {
            return PipelineStep(S4, "工作台", StepStatus.DONE, "可进入控制面板")
        }
        val worst = gaps.minByOrNull { rank(it.second.status) }!!
        val blockedOnly = gaps.all { it.second.status == CapStatus.BLOCKED }
        return PipelineStep(
            id = S4,
            title = "工作台",
            status = if (blockedOnly) StepStatus.BLOCKED else StepStatus.ACTION,
            detail = "缺 " + gaps.joinToString("、") { it.first.title } +
                "（共 " + gaps.size + " 项）",
            pending = worst.first.acquirer.invoke(e).firstOrNull(),
            pendingCapId = worst.first.id,
        )
    }
}
