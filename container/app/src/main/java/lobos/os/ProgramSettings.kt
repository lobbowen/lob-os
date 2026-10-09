package lobos.os

import android.content.Context
import java.io.File
import lobos.log.Journal
import org.json.JSONObject

object ProgramSettings {

    private fun file(ctx: Context) = File(SystemDirs.libvar(ctx), "program-settings.json")

    @Synchronized
    fun read(ctx: Context): JSONObject = runCatching {
        val f = file(ctx)
        if (!f.exists()) JSONObject() else JSONObject(f.readText())
    }.getOrDefault(JSONObject())

    val ALLOWED_KEYS: Set<String> = setOf("args", "env")

    data class PatchResult(val ok: Boolean, val rejected: List<String>, val merged: JSONObject)

    @Synchronized
    fun patch(ctx: Context, id: String, patch: JSONObject): PatchResult {
        val rejected = mutableListOf<String>()
        patch.keys().forEach { k -> if (!ALLOWED_KEYS.contains(k)) rejected.add(k) }
        val envKeys = patch.optJSONObject("env")?.keys()?.asSequence()?.toList() ?: emptyList()
        val reservedEnv = envKeys.filter { lobos.os.RuntimeEnvironment.RESERVED_ENV.contains(it) }
        rejected.addAll(reservedEnv.map { "env." + it })
        if (rejected.isNotEmpty()) {
            lobos.log.Journal.note(
                ctx, "settings", false, "程序设置被拒（键不在白名单或属保留变量）",
                "id=" + id + " 拒绝=" + rejected.joinToString(","),
            )
            return PatchResult(false, rejected, read(ctx).optJSONObject(id) ?: JSONObject())
        }
        val all = read(ctx)
        val cur = all.optJSONObject(id) ?: JSONObject()
        patch.keys().forEach { k -> cur.put(k, patch.get(k)) }
        all.put(id, cur)
        val wrote = runCatching {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            lobos.os.StateFiles.writeAtomic(f, all.toString())
        }.isSuccess
        if (!wrote) {
            lobos.log.Journal.note(ctx, "settings", false, "程序设置落盘失败", "id=" + id)
            return PatchResult(false, listOf("persist-failed"), cur)
        }
        return PatchResult(true, emptyList(), cur)
    }
}
