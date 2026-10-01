package lobos.lifecycle

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import lobos.RuntimeDiagnostics
import lobos.bridge.AdbClientRunner
import lobos.capability.CapabilityCriteria
import lobos.permissions.PermissionCatalog

enum class AnchorState { BOUND, UNBOUND, UNKNOWN }

data class BindOutcome(
    val state: AnchorState,
    val bound: Boolean,
    val issued: Boolean,
    val detail: String,
)

object AccessibilityAnchor {

    private const val TAG = "AccessibilityAnchor"

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

    fun state(ctx: Context): AnchorState {
        val c = component(ctx) ?: return AnchorState.UNKNOWN
        return try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val match = enabled.any { info ->
                val si = info.resolveInfo?.serviceInfo ?: return@any false
                si.packageName == c.packageName && si.name == c.className
            }
            if (match) AnchorState.BOUND else AnchorState.UNBOUND
        } catch (_: Throwable) {
            AnchorState.UNKNOWN
        }
    }

    fun isBound(ctx: Context): Boolean = state(ctx) == AnchorState.BOUND

    fun ensureBound(ctx: Context, timeoutMs: Long): BindOutcome {
        if (isBound(ctx)) return BindOutcome(AnchorState.BOUND, true, false, "锚在位，无需动作")

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val c = component(ctx)
            ?: return BindOutcome(AnchorState.UNKNOWN, false, false, "组件名解析不出，不能下发")
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
        var how = if (wrote) "本地写 secure 设置" else "本地无 WRITE_SECURE_SETTINGS，走 ADB 通道"
        var issued = wrote

        if (!wrote) {
            val out = adbPut(ctx, PermissionCatalog.SECURE_KEY_ACCESSIBILITY, "'" + joined + "'", deadline)
            issued = out?.ok == true
            how = when {
                out == null -> how + "失败：保护激活预算（" + timeoutMs + "ms）已用尽"
                out.ok -> how + "：写入服务名单" + (if (needMaster) "、总开关" else "")
                else -> how + "失败：" + (out.error ?: out.raw.take(120))
            }
            if (needMaster && out?.ok == true) {
                val master = adbPut(ctx, PermissionCatalog.SECURE_KEY_ACCESSIBILITY_ENABLED, "1", deadline)
                if (master?.ok != true) how += "；总开关未置上（" + (master?.error ?: "预算用尽") + "）"
            }
        } else if (needMaster) {
            how += "，总开关一并置 1"
        }

        val st = state(ctx)
        val bound = st == AnchorState.BOUND
        RuntimeDiagnostics.append(
            ctx, "accessibility", bound,
            if (bound) "锚已挂上（闸门开着）" else "挂锚未成：" + st,
            how + "；timeout=" + timeoutMs + "ms",
        )
        return BindOutcome(st, bound, issued, how + "；state=" + st)
    }

    private fun adbPut(ctx: Context, key: String, value: String, deadlineMs: Long): AdbClientRunner.AdbOutcome? {
        val left = deadlineMs - SystemClock.elapsedRealtime()
        if (left <= 0L) return null
        return try {
            AdbClientRunner.shell(
                ctx, "settings put secure " + key + " " + value, null, null, left,
            )
        } catch (_: Throwable) {
            null
        }
    }
}
