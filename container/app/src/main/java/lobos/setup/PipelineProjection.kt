package lobos.setup

import lobos.capability.CapabilityCatalog.S0
import lobos.capability.CapabilityCatalog.S1
import lobos.capability.CapabilityCatalog.S2
import lobos.capability.CapabilityCatalog.S3
import lobos.capability.CapabilityCatalog.OX
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

    private val OPTIONAL = listOf(
        CapabilityCatalog.ADB_CHANNEL,
        CapabilityCatalog.ADB_UI_AUTOMATION,
    )

    fun workbenchReady(verdicts: Map<String, CapVerdict>): Boolean =
        GATING.all { verdicts[it]?.status == CapStatus.GRANTED }

    private val TITLES = mapOf(
        S0 to "系统能力（可选）",
        S1 to "能力与权限集",
        S2 to "状态与权限",
        S3 to "运行时+内核",
        CapabilityCatalog.OX to "可选组件（ADB）",
    )

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

    fun project(e: Evidence, verdicts: Map<String, CapVerdict>): List<PipelineStep> {
        val rows = listOf(S0, S1, S2, S3).map { seg ->
            val caps = CapabilityCatalog.ALL.filter { it.segment == seg }
            val scoped = if (seg == S1) caps else caps.filter { !it.optional }
            val ranked = scoped.map { it to verdicts[it.id] }
                .filter { it.second != null }
                .map { it.first to it.second!! }
            if (ranked.isEmpty()) PipelineStep(seg, TITLES.getValue(seg), StepStatus.DONE, "本段无待办")
            else worstOf(seg, TITLES.getValue(seg), ranked, e)
        }
        return rows + workbenchRow(e, verdicts)
    }

    private fun worstOf(
        seg: String,
        title: String,
        ranked: List<Pair<Capability, CapVerdict>>,
        e: Evidence,
    ): PipelineStep {
        val best = ranked.minByOrNull { rank(it.second.status) }!!
        val pendingCount = ranked.count { it.second.status != CapStatus.GRANTED }
        val detail = if (seg == S2 && pendingCount > 1) {
            "${best.first.title}：${best.second.detail}（另有 ${pendingCount - 1} 项待办）"
        } else {
            best.second.detail.ifBlank { best.first.title }
        }
        return PipelineStep(
            id = seg,
            title = title,
            status = toStep(best.second.status),
            detail = detail,
            pending = CapabilityCatalog.byId(best.first.id)?.acquirer?.invoke(e)?.firstOrNull(),
            pendingCapId = best.first.id,
        )
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
