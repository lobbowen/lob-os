package lobos.services.supply

import android.content.Context
import lobos.services.log.RuntimeDiagnostics
import lobos.services.reg.ProgramIndex

object HealthCheck {
    enum class Action { NONE, RECOVERY, SPACE, COMPONENTS }

    data class Item(
        val title: String,
        val ok: Boolean,
        val detail: String,
        val action: Action,
        val actionLabel: String,
    )

    data class Result(val items: List<Item>, val passed: Int, val failed: Int) {
        val allOk: Boolean get() = failed == 0
        fun summary(): String = if (allOk) "全部通过" else failed.toString() + " 项需要处理"
    }

    fun run(ctx: Context): Result {
        val items = ArrayList<Item>()
        val ledger = InstallGuard.all(ctx)
        val index = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())

        val installedOk = index.isNotEmpty()
        items.add(Item(
            "组件就位",
            installedOk,
            if (installedOk) "已登记 " + index.size + " 个组件"
            else "还没有任何组件，系统无法提供服务",
            if (installedOk) Action.NONE else Action.COMPONENTS,
            if (installedOk) "" else "去安装",
        ))

        val noRecord = index.count { !ledger.containsKey(it.id) }
        items.add(Item(
            "安装记录",
            noRecord == 0,
            if (noRecord == 0) "每个组件都有安装记录"
            else noRecord.toString() + " 个组件缺少安装记录（重装一次即可补上）",
            if (noRecord == 0) Action.NONE else Action.COMPONENTS,
            if (noRecord == 0) "" else "查看组件",
        ))

        val free = InstallGuard.freeBytes(ctx.filesDir)
        val spaceOk = free < 0 || free >= 256L * 1024 * 1024
        items.add(Item(
            "可用空间",
            spaceOk,
            if (free < 0) "读不到可用空间" else "可用 " + InstallGuard.human(free),
            if (spaceOk) Action.NONE else Action.SPACE,
            if (spaceOk) "" else "去清理",
        ))

        val fails = runCatching {
            RuntimeDiagnostics.events(ctx, 200).count { it.level == RuntimeDiagnostics.Level.FAIL }
        }.getOrDefault(0)
        items.add(Item(
            "最近失败",
            fails == 0,
            if (fails == 0) "最近没有失败记录" else "最近有 " + fails + " 条失败记录",
            if (fails == 0) Action.NONE else Action.RECOVERY,
            if (fails == 0) "" else "查看失败",
        ))

        val reclaim = Cleanup.reclaimable(ctx)
        items.add(Item(
            "可清理",
            reclaim <= 0,
            if (reclaim <= 0) "没有可清理的内容" else "可清理 " + InstallGuard.human(reclaim),
            if (reclaim <= 0) Action.NONE else Action.SPACE,
            if (reclaim <= 0) "" else "去清理",
        ))

        val passed = items.count { it.ok }
        val failed = items.size - passed
        return Result(items, passed, failed)
    }
}