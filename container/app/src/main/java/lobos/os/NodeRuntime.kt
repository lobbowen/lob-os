package lobos.os

import android.content.Context
import java.io.File
import lobos.native.NativeAssetRegistry

object NodeRuntime {

    fun path(ctx: Context): File? =
        FacilityManager.nodeBin(ctx)
            ?: NativeAssetRegistry.resolve(ctx, NativeAssetRegistry.NODE).takeIf { it.isFile }

    fun missing(ctx: Context): String =
        "node 运行时未安装：请在控制面板的系统组件里安装 node（商店里的运行时包）"
}
