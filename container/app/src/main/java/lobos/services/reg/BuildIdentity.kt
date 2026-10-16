package lobos.services.reg

import android.content.Context
import java.io.File
import org.json.JSONObject
import lobos.kernel.fs.StateFiles
import lobos.kernel.layout.SystemDirs

object BuildIdentity {

    const val FILE = "build-identity.json"

    fun file(ctx: Context): File = File(SystemDirs.libvar(ctx), FILE)

    fun write(ctx: Context): Boolean {
        val o = JSONObject().apply {
            put("schema", 1)
            put("gitSha", lobos.BuildConfig.GIT_SHA)
            put("runId", lobos.BuildConfig.RUN_ID)
            put("versionName", lobos.BuildConfig.VERSION_NAME)
            put("versionCode", lobos.BuildConfig.VERSION_CODE)
            put("bridgeProtocol", lobos.BuildConfig.BRIDGE_PROTOCOL)
            put("identifiable", lobos.BuildConfig.GIT_SHA != UNKNOWN)
            put("writtenBy", "lobos-os")
        }
        return runCatching {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            StateFiles.writeAtomic(f, o.toString(2))
            true
        }.getOrDefault(false)
    }

    fun read(ctx: Context): JSONObject? =
        runCatching { JSONObject(file(ctx).readText()) }.getOrNull()

    fun identifiable(ctx: Context): Boolean =
        read(ctx)?.optBoolean("identifiable", false) ?: false

    private const val UNKNOWN = "unknown"
}