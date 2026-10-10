package lobos.services.supply

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import lobos.kernel.layout.SystemDirs

object RegistryStore {

    private fun file(ctx: Context) = File(SystemDirs.libvar(ctx), "registry.json")

    @Synchronized
    private fun read(ctx: Context): JSONObject = runCatching {
        val f = file(ctx)
        if (!f.exists()) JSONObject() else JSONObject(f.readText())
    }.getOrDefault(JSONObject())

    @Synchronized
    fun info(ctx: Context): JSONObject {
        val o = read(ctx)
        return JSONObject().apply {
            put("origin", o.optString("origin", ""))
            put("updatedAt", o.optLong("updatedAt", 0L))
                put("programs", JSONObject.NULL)
        }
    }

    @Synchronized
    fun setOrigin(ctx: Context, origin: String?): JSONObject {
        val o = read(ctx)
        o.put("origin", origin)
        o.put("updatedAt", System.currentTimeMillis())
        runCatching {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            lobos.kernel.fs.StateFiles.writeAtomic(f, o.toString())
        }
        return info(ctx)
    }

}
