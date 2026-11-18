package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.services.reg.ProgramManager
import lobos.services.reg.ProgramIndex

data class RuntimeBinding(
    val constraint: String,
    val resolved: String,
    val source: String,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("constraint", constraint)
        put("resolved", resolved)
        put("source", source)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject?): RuntimeBinding? = o?.let {
            RuntimeBinding(
                constraint = it.optString("constraint", ""),
                resolved = it.optString("resolved", ""),
                source = it.optString("source", ""),
            )
        }.takeIf { it?.resolved?.isNotBlank() == true }
    }
}

object RuntimeResolver {

    private fun userOverrides(ctx: Context): org.json.JSONObject {
        val f = File(ctx.filesDir, "var/lib/runtime-overrides.json")
        return runCatching { org.json.JSONObject(f.readText()) }.getOrDefault(org.json.JSONObject())
    }

    fun overrideFor(ctx: Context, appId: String, runtime: String): String? =
        userOverrides(ctx).optJSONObject("apps")?.optJSONObject(appId)?.optString(runtime, "")?.takeIf { it.isNotBlank() }

    fun parseRequire(raw: String): Pair<String, String>? {
        val s = raw.trim()
        if (s.isBlank()) return null
        val idx = s.indexOfFirst { it == '>' || it == '<' || it == '=' }
        return if (idx <= 0) Pair(s, "") else Pair(s.substring(0, idx).trim(), s.substring(idx).trim())
    }

    fun installedVersions(ctx: Context, runtime: String): List<String> = runCatching {
        ProgramDir(ctx, runtime, ProgramManager.stateDirOf(ctx, runtime)).installedVersions()
    }.getOrDefault(emptyList())

    private fun compareVersion(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").substringBefore("-").substringBefore("+").split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().removePrefix("v").substringBefore("-").substringBefore("+").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size, 3)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    fun resolve(ctx: Context, appId: String, requires: List<String>): Map<String, RuntimeBinding> {
        val out = LinkedHashMap<String, RuntimeBinding>()
        for (raw in requires) {
            val (name, range) = parseRequire(raw) ?: continue
            if (name.isBlank()) continue
            val override = overrideFor(ctx, appId, name)
            if (override != null) {
                out[name] = RuntimeBinding(range, override, "override")
                continue
            }
            val installed = installedVersions(ctx, name)
            val compatible = installed.filter { range.isBlank() || VersionRange.satisfies(it, range) }
            if (compatible.isNotEmpty()) {
                val best = compatible.maxWithOrNull(::compareVersion)!!
                out[name] = RuntimeBinding(range, best, "resolved")
            }
        }
        return out
    }

    fun resolveOne(ctx: Context, appId: String, runtime: String, range: String): RuntimeBinding? =
        resolve(ctx, appId, listOf(if (range.isBlank()) runtime else runtime + range))[runtime]
}
