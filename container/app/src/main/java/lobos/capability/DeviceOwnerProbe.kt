package lobos.capability

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager

object DeviceOwnerProbe {

    data class Result(
        val isDeviceOwner: Boolean,
        val adminComponentDeclared: Boolean,
        val detail: String,
    ) {
    }

    fun measure(ctx: Context): Result {
        val pkg = runCatching { ctx.packageName }.getOrDefault("")
        val declared = adminComponentDeclared(ctx)
        val owner = runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(pkg)
        }.getOrDefault(false)
        val detail = when {
            owner -> "isDeviceOwnerApp=true"
            declared -> "isDeviceOwnerApp=false（本包已声明 admin 组件，可被置为设备所有者）"
            else -> "isDeviceOwnerApp=false（本包未声明 admin 组件：apk+do 档在本构建下不可达）"
        }
        return Result(owner, declared, detail)
    }

    fun adminComponentDeclared(ctx: Context): Boolean = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_RECEIVERS)
        val receivers = info.receivers ?: return@runCatching false
        receivers.any { r -> r.permission == android.Manifest.permission.BIND_DEVICE_ADMIN }
    }.getOrDefault(false)
}
