package lobos.lifecycle

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import lobos.RuntimeDiagnostics
import lobos.capability.CapabilityCriteria
import lobos.permissions.PermissionCatalog

enum class ServiceState { BOUND, UNBOUND, UNKNOWN }

data class ToggleOutcome(
    val state: ServiceState,
    val bound: Boolean,
    val issued: Boolean,
    val detail: String,
)

object AccessibilityServiceState {

    const val ENABLE_BUDGET_MS = 5_000L

    private const val TAG = "AccessibilityServiceState"

    private fun componentString(ctx: Context): String =
        CapabilityCriteria.names(ctx).accessibilityComponent

    private fun component(ctx: Context): ComponentName? = ComponentName.unflattenFromString(componentString(ctx))

    private fun enabledList(ctx: Context): List<String> = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun masterSwitch(ctx: Context): String = try {
        Settings.Secure.getString(ctx.contentResolver, PermissionCatalog.SECURE_KEY_ACCESSIBILITY_ENABLED) ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun state(ctx: Context): ServiceState {
        val c = component(ctx) ?: return ServiceState.UNKNOWN
        return try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val match = enabled.any { info ->
                val si = info.resolveInfo?.serviceInfo ?: return@any false
                si.packageName == c.packageName && si.name == c.className
            }
            if (match) ServiceState.BOUND else ServiceState.UNBOUND
        } catch (_: Throwable) {
            ServiceState.UNKNOWN
        }
    }

    fun isBound(ctx: Context): Boolean = state(ctx) == ServiceState.BOUND

    fun ensureBound(ctx: Context, timeoutMs: Long): ToggleOutcome {
        if (isBound(ctx)) return ToggleOutcome(ServiceState.BOUND, true, false, "无障碍服务已就位，无需动作")

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val c = component(ctx)
            ?: return ToggleOutcome(ServiceState.UNKNOWN, false, false, "组件名解析不出，不能下发")
        val flat = c.flattenToString()
        val cur = enabledList(ctx)
        val without = cur.filter { it != flat }.joinToString(":")
        val joined = (cur.filter { it != flat } + flat).joinToString(":")
        val needMaster = masterSwitch(ctx) != "1"

        val wrote = try {
            if (cur.contains(flat)) {
                Settings.Secure.putString(
                    ctx.contentResolver, PermissionCatalog.SECURE_KEY_ACCESSIBILITY, without,
                )
            }
            Settings.Secure.putString(
                ctx.contentResolver, PermissionCatalog.SECURE_KEY_ACCESSIBILITY, joined,
            )
            if (needMaster) {
                Settings.Secure.putString(
                    ctx.contentResolver, PermissionCatalog.SECURE_KEY_ACCESSIBILITY_ENABLED, "1",
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
        val how = if (wrote) "本机写 secure 设置成功"
            else "本机无 WRITE_SECURE_SETTINGS：请到系统「无障碍」页手动开启（本设计不借 ADB 静默改系统设置）"
        val issued = wrote
        val st = state(ctx)
        val bound = st == ServiceState.BOUND
        RuntimeDiagnostics.append(
            ctx, "accessibility", bound,
            if (bound) "无障碍服务已就位（自动化可用）" else "无障碍服务未就位：" + st,
            how + "；timeout=" + timeoutMs + "ms",
        )
        return ToggleOutcome(st, bound, issued, how + "；state=" + st)
    }

    fun disable(ctx: Context): ToggleOutcome {
        val c = component(ctx)
            ?: return ToggleOutcome(ServiceState.UNKNOWN, false, false, "组件名解析不出，不能下发")
        val flat = c.flattenToString()
        val cur = enabledList(ctx)
        if (!cur.contains(flat)) {
            return ToggleOutcome(state(ctx), false, false, "本服务本就不在启用列表里，无需动作")
        }
        val without = cur.filter { it != flat }.joinToString(":")
        val empty = cur.filter { it != flat }.isEmpty()
        val wrote = try {
            Settings.Secure.putString(
                ctx.contentResolver, PermissionCatalog.SECURE_KEY_ACCESSIBILITY, without,
            )
            if (empty) {
                Settings.Secure.putString(
                    ctx.contentResolver, PermissionCatalog.SECURE_KEY_ACCESSIBILITY_ENABLED, "0",
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
        val how = if (wrote) "本机写 secure 设置成功"
        else "本机无 WRITE_SECURE_SETTINGS：请到系统「无障碍」页手动关闭"
        val st = state(ctx)
        RuntimeDiagnostics.append(
            ctx, "accessibility", st == ServiceState.UNBOUND,
            if (st == ServiceState.UNBOUND) "无障碍服务已停用" else "无障碍服务停用未生效：" + st,
            how,
        )
        return ToggleOutcome(st, st == ServiceState.BOUND, wrote, how + "；state=" + st)
    }
}
