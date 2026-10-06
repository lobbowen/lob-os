package lobos.setup

import lobos.capability.CapabilityCatalog
import lobos.capability.CapStatus
import lobos.capability.CapVerdict
import lobos.capability.Evidence
import lobos.capability.Acquisition
import lobos.capability.Capability

enum class StageStatus { DONE, CURRENT, NEXT, BLOCKED, FAILED }

data class FlowStage(
    val id: String,
    val title: String,
    val why: String,
    val status: StageStatus,
    val detail: String = "",
    val action: Acquisition? = null,
    val actionCapId: String? = null,
    val extra: Acquisition? = null,
)

object OnboardingFlow {

    const val F1 = "F1"
    const val F2 = "F2"
    const val F3 = "F3"
    const val F4 = "F4"

    private val RUNTIME_ENV = listOf(CapabilityCatalog.RUNTIME, CapabilityCatalog.PROGRAM_BUNDLE)

    private val F1_OWNS =
        (CapabilityCatalog.requiresInOrder(CapabilityCatalog.ADB_CREDENTIALS) +
            CapabilityCatalog.ADB_CREDENTIALS +
            CapabilityCatalog.ADB_UI_AUTOMATION).toSet()

    private val F3_OWNS = RUNTIME_ENV.toSet()

    private val ENTRY_ORDER = RUNTIME_ENV

    fun readyToEnter(verdicts: Map<String, CapVerdict>): Boolean =
        PipelineProjection.workbenchReady(verdicts)

    val SKELETON: List<FlowStage> = listOf(
        FlowStage(F1, "可选组件：无线配对（一次 6 位码）", WHY_PAIR, StageStatus.NEXT),
        FlowStage(F3, "进入工作台", WHY_ENTER, StageStatus.NEXT),
    )

    fun stages(e: Evidence, v: Map<String, CapVerdict>): List<FlowStage> {
        val cred = v[CapabilityCatalog.ADB_CREDENTIALS]
        val ready = readyToEnter(v)
        val entryGap = ENTRY_ORDER.firstOrNull { v[it]?.status != CapStatus.GRANTED }

        val rows = listOf(
            Row(CapabilityCatalog.ADB_CREDENTIALS, F1_OWNS, cred?.status == CapStatus.GRANTED, false,
                cred?.detail ?: "", cred),
            Row(entryGap ?: CapabilityCatalog.RUNTIME, F3_OWNS, ready, true,
                if (ready) "通道与控制面就绪"
                else ENTRY_ORDER.filter { v[it]?.status != CapStatus.GRANTED }
                    .joinToString("；") { "${CapabilityCatalog.titleOf(it)}：${v[it]?.detail ?: ""}" },
                entryGap?.let { v[it] }),
        )

        val current = rows.indexOfFirst { !it.done && it.blocking }
            .let { if (it >= 0) it else rows.indexOfFirst { !it.done } }
        return SKELETON.mapIndexed { i, base ->
            val r = rows[i]
            val status = when {
                r.done -> StageStatus.DONE
                i == current -> when (r.verdict?.status) {
                    CapStatus.FAILED -> StageStatus.FAILED
                    CapStatus.BLOCKED -> StageStatus.BLOCKED
                    else -> StageStatus.CURRENT
                }
                r.verdict?.status == CapStatus.BLOCKED -> StageStatus.BLOCKED
                else -> StageStatus.NEXT
            }
            base.copy(
                status = status,
                detail = r.detail,
                action = if (i == current) firstAction(r.capId, e) else null,
                actionCapId = if (i == current) r.capId else null,
            )
        }
    }

    fun debts(v: Map<String, CapVerdict>): List<Capability> {
        val cred = v[CapabilityCatalog.ADB_CREDENTIALS]
        val claimed = mutableSetOf<String>()
        if (cred?.status != CapStatus.GRANTED) claimed += F1_OWNS
        if (!readyToEnter(v)) claimed += F3_OWNS
        return CapabilityCatalog.ALL.filter {
            !it.optional && it.id !in claimed && v[it.id]?.status != CapStatus.GRANTED
        }
    }

    private data class Row(
        val capId: String?,
        val owns: Set<String>,
        val done: Boolean,
        val blocking: Boolean,
        val detail: String,
        val verdict: CapVerdict?,
    )

    private fun firstAction(capId: String?, e: Evidence): Acquisition? =
        capId?.let { CapabilityCatalog.byId(it) }?.acquirer?.invoke(e)?.firstOrNull()

    private const val WHY_PAIR = "点一下就现场核对开发者环境并跳到无线调试页；端口只在册时才算数"
    private const val WHY_ENTER = "通道+控制面+内核包就绪就进面板，其余权限不挡门；通道每次现问 mDNS"
}
