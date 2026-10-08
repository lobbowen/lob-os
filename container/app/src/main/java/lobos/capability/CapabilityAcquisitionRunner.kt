package lobos.capability

import android.content.Context
import lobos.capability.AdbClientRunner
import lobos.lifecycle.AccessibilityServiceState
import lobos.permissions.PermissionCatalog
import lobos.permissions.PermissionCenter

data class AcquisitionResult(val ok: Boolean, val verified: Boolean, val detail: String)

object CapabilityAcquisitionRunner {

    private const val DEFAULT_TIMEOUT_MS = 20_000L

    @Synchronized
    fun run(
        ctx: Context,
        executor: String,
        capId: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): AcquisitionResult = when (executor) {
        CapabilityCatalog.EXEC_NOTIFICATION_LISTENER -> enableNotificationListener(ctx, timeoutMs)
        CapabilityCatalog.EXEC_ACCESSIBILITY -> bindAccessibilityServiceState(ctx, timeoutMs)
        CapabilityCatalog.EXEC_BATTERY_WHITELIST -> whitelistBattery(ctx, timeoutMs)
        CapabilityCatalog.EXEC_APPOPS_ALLOW -> setAppOps(ctx, capId, timeoutMs)
        CapabilityCatalog.EXEC_PM_GRANT -> grantRuntimePerm(ctx, capId, timeoutMs)
        CapabilityCatalog.EXEC_RECHECK -> recheckChannel(ctx)
        CapabilityCatalog.EXEC_RERUN_SELFCHECK -> rerunSelfCheck(ctx)
        CapabilityCatalog.EXEC_OEM_CONFIRM -> confirmOemGuards(ctx)
        else -> AcquisitionResult(false, false, "未知执行器：" + executor)
    }

    fun dispatch(ctx: Context, capId: String, acq: Acquisition): AcquisitionResult? {
        val executor = acq.target
        return when (acq.kind) {
            AcquireKind.AUTO, AcquireKind.SILENT_VIA_ADB ->
                if (executor == null) AcquisitionResult(false, false, "取法缺执行器")
                else run(ctx, executor, capId)
            else -> null
        }
    }

    @Synchronized
    private fun recheckChannel(ctx: Context): AcquisitionResult {
        AdbChannelComponent.reset(ctx, "能力获取流程要求重测通道")
        val p = AdbChannelComponent.refreshNow(ctx)
        return AcquisitionResult(
            ok = p.outcome == ChannelState.LIVE,
            verified = p.outcome == ChannelState.LIVE,
            detail = p.detail.ifBlank { p.outcome.name },
        )
    }

    private fun rerunSelfCheck(ctx: Context): AcquisitionResult {
        CapabilityEvidenceCollector.forgetKernelChecks()
        val items = CapabilityEvidenceCollector.collect(ctx).programChecks
        val bad = items.filter { it.ok != true }.map { it.id }
        return AcquisitionResult(
            ok = bad.isEmpty(),
            verified = bad.isEmpty(),
            detail = if (bad.isEmpty()) "自检 " + items.size + " 项通过" else "未通过/未知：" + bad.joinToString(),
        )
    }

    private fun confirmOemGuards(ctx: Context): AcquisitionResult {
        OemGuards.confirm(ctx)
        return AcquisitionResult(true, true, "已记录四项厂商开关回执（下一轮判据生效）")
    }

    private fun bindAccessibilityServiceState(ctx: Context, timeoutMs: Long): AcquisitionResult {
        val outcome = AccessibilityServiceState.ensureBound(ctx, timeoutMs)
        val listedNotBound = !outcome.bound && PermissionCenter(ctx).accessibilityEnabledInSettings()
        return AcquisitionResult(
            ok = outcome.issued,
            verified = outcome.bound,
            detail = "无障碍服务 " + outcome.state + "：" + outcome.detail +
                (if (listedNotBound) "（名单已登记，系统未绑定服务实例）" else ""),
        )
    }

    private fun enableNotificationListener(ctx: Context, timeoutMs: Long): AcquisitionResult {
        val center = PermissionCenter(ctx)
        val names = CapabilityCriteria.names(ctx)
        val ours = names.notificationListenerComponent
        if (ours.isBlank()) return AcquisitionResult(false, false, "组件名未解析，不能下发")
        val current = center.notificationListenersValue()
        val merged = (current.split(":").filter { it.isNotBlank() && it != ours } + ours).joinToString(":")
        val outcome = AdbClientRunner.shell(
            ctx,
            "settings put secure " + PermissionCatalog.SECURE_KEY_NOTIFICATION_LISTENER + " " + merged,
            null, null, timeoutMs,
        )
        val readBack = center.notificationListenerEnabled()
        return when {
            readBack -> AcquisitionResult(true, true, "系统已记录勾选" +
                (if (center.notificationListenerBound()) "，服务已绑定" else "，等待系统绑定服务"))
            !outcome.ok -> AcquisitionResult(false, false, "下发失败：" +
                (outcome.error ?: commandText(outcome).take(160)))
            else -> AcquisitionResult(true, false, "命令已下发，回读未见生效：" + commandText(outcome).take(160))
        }
    }

    private fun whitelistBattery(ctx: Context, timeoutMs: Long): AcquisitionResult {
        val outcome = AdbClientRunner.shell(
            ctx, "dumpsys deviceidle whitelist +" + ctx.packageName, null, null, timeoutMs,
        )
        val readBack = PermissionCenter(ctx).batteryExempt()
        return when {
            readBack -> AcquisitionResult(true, true, "已加入 Doze 白名单")
            !outcome.ok -> AcquisitionResult(false, false, "下发失败：" +
                (outcome.error ?: commandText(outcome).take(160)))
            else -> AcquisitionResult(true, false, "命令已下发，回读未见生效：" + commandText(outcome).take(160))
        }
    }

    private fun setAppOps(ctx: Context, capId: String?, timeoutMs: Long): AcquisitionResult {
        val spec = capId?.let { PermissionCatalog.byId(it) }
            ?: return AcquisitionResult(false, false, "取法缺能力 id 或 id 不在判据表：" + (capId ?: "无"))
        val op = spec.appOpsOp
        if (op.isNullOrBlank()) {
            return AcquisitionResult(false, false, "判据表未登记 AppOps 操作名：" + spec.id)
        }
        val outcome = AdbClientRunner.shell(
            ctx, "appops set " + ctx.packageName + " " + op + " allow", null, null, timeoutMs,
        )
        val readBack = PermissionCenter(ctx).isGranted(spec)
        return when {
            readBack -> AcquisitionResult(true, true, op + " 已置为 allow")
            !outcome.ok -> AcquisitionResult(false, false, "下发失败：" +
                (outcome.error ?: commandText(outcome).take(160)))
            else -> AcquisitionResult(true, false, "命令已下发，回读未见生效：" + commandText(outcome).take(160))
        }
    }

    private fun grantRuntimePerm(ctx: Context, capId: String?, timeoutMs: Long): AcquisitionResult {
        val spec = capId?.let { PermissionCatalog.byId(it) }
            ?: return AcquisitionResult(false, false, "取法缺能力 id 或 id 不在判据表：" + (capId ?: "无"))
        val permName = spec.permission
        if (permName.isNullOrBlank()) {
            return AcquisitionResult(false, false, "判据表未登记 Android 权限名：" + spec.id)
        }
        val outcome = AdbClientRunner.shell(
            ctx, "pm grant " + ctx.packageName + " " + permName, null, null, timeoutMs,
        )
        val readBack = PermissionCenter(ctx).isGranted(spec)
        return when {
            readBack -> AcquisitionResult(true, true, permName + " 已授予")
            !outcome.ok -> AcquisitionResult(false, false, "下发失败：" +
                (outcome.error ?: commandText(outcome).take(160)))
            else -> AcquisitionResult(true, false, "命令已下发，回读未见生效：" + commandText(outcome).take(160))
        }
    }

    private fun commandText(outcome: AdbClientRunner.AdbOutcome): String {
        val out = outcome.json?.optString("out", "").orEmpty()
        val logs = outcome.json?.optString("logs", "").orEmpty()
        return (out + "\n" + logs + "\n" + outcome.raw).lineSequence()
            .firstOrNull { "Exception" in it || "error" in it.lowercase() }
            ?: (outcome.error ?: out.trim().ifBlank { "无输出" })
    }
}
