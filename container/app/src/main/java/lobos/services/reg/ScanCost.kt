package lobos.services.reg

import android.content.Context
import java.io.File
import org.json.JSONObject

object ScanCost {

    private const val CONFIG_NAME = "scan-cost.json"

    const val DEFAULT_WITH_CONTENT_FINGERPRINT = true

    fun withContentFingerprint(ctx: Context): Boolean {
        val f = configFile(ctx)
        if (!f.isFile) return DEFAULT_WITH_CONTENT_FINGERPRINT
        return runCatching {
            val o = JSONObject(f.readText())
            val v = o.opt("skipContentFingerprint")

            if (v is Boolean) !v else DEFAULT_WITH_CONTENT_FINGERPRINT
        }.getOrDefault(DEFAULT_WITH_CONTENT_FINGERPRINT)
    }

    fun traverseFiles(ctx: Context): Boolean {
        val f = configFile(ctx)
        if (!f.isFile) return true
        return runCatching {
            val o = JSONObject(f.readText())
            val v = o.opt("skipTraversal")
            if (v is Boolean) !v else true
        }.getOrDefault(true)
    }

    fun configFile(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.etc(ctx), CONFIG_NAME)

    fun describe(ctx: Context): String {
        val on = withContentFingerprint(ctx)
        val walk = traverseFiles(ctx)
        val src = if (configFile(ctx).isFile) "设备侧 " + configFile(ctx).absolutePath else "默认（无设备侧配置）"
        return "内容指纹=" + (if (on) "算" else "跳过") +
            " 遍历=" + (if (walk) "做" else "不做") +
            " 目录视图=仍然扫目录" +
            " 来源=" + src
    }
}
