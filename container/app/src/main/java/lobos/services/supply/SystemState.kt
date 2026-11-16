package lobos.services.supply

import android.content.Context
import lobos.services.log.RuntimeDiagnostics
import lobos.services.reg.ProgramIndex

object SystemState {
    enum class Tier { OK, ATTENTION, REPAIR }

    data class Report(
        val tier: Tier,
        val headline: String,
        val reasons: List<String>,
        val installedCount: Int,
        val noRecordCount: Int,
        val upgradableCount: Int,
        val failedRecent: Int,
        val freeBytes: Long,
        val usedBytes: Long,
        val reclaimableBytes: Long,
    ) {
        fun tierLabel(): String = when (tier) {
            Tier.OK -> "正常"
            Tier.ATTENTION -> "需注意"
            Tier.REPAIR -> "需修复"
        }
    }

    fun evaluate(ctx: Context): Report {
        val ledger = InstallGuard.all(ctx)
        val index = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
        val installed = index.size
        val noRecord = index.count { !ledger.containsKey(it.id) }
        val dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000
        val failedRecent = runCatching {
            RuntimeDiagnostics.events(ctx, 400)
                .count { it.level == RuntimeDiagnostics.Level.FAIL && it.atMs >= dayAgo }
        }.getOrDefault(0)
        val free = InstallGuard.freeBytes(ctx.filesDir)
        val used = ledger.values.sumOf { it.optLong("bytes", 0L) }
        val up = runCatching { CatalogClient.entries(ctx) }.getOrNull()?.let { arr ->
            var n = 0
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optBoolean("upgradable", false)) n++
            }
            n
        } ?: 0
        val reclaim = Cleanup.reclaimable(ctx)

        val reasons = ArrayList<String>()
        if (installed == 0) reasons.add("还没有安装任何组件")
        if (noRecord > 0) reasons.add(noRecord.toString() + " 个组件缺少安装记录")
        if (failedRecent > 0) reasons.add("最近 24 小时有 " + failedRecent + " 次失败")
        if (free in 1L..(256L * 1024 * 1024)) reasons.add("可用空间偏少：" + InstallGuard.human(free))
        if (reclaim > 0) reasons.add("可清理 " + InstallGuard.human(reclaim))

        val tier = when {
            installed == 0 -> Tier.REPAIR
            failedRecent > 0 || (free in 1L..(128L * 1024 * 1024)) -> Tier.REPAIR
            noRecord > 0 || up > 0 || reclaim > 0 -> Tier.ATTENTION
            else -> Tier.OK
        }
        val headline = when (tier) {
            Tier.OK -> "系统状态良好"
            Tier.ATTENTION -> "可以优化一下"
            Tier.REPAIR -> "需要处理一下"
        }
        if (reasons.isEmpty()) reasons.add("组件齐全、库链接可用、空间充足")
        return Report(tier, headline, reasons, installed, noRecord, up, failedRecent, free, used, reclaim)
    }
}