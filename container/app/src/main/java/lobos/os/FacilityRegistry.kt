package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object FacilityRegistry {

    enum class Kind { RUNTIME, COMPONENT, CHANNEL }

    data class Facility(
        val name: String,
        val kind: Kind,
        val version: String,
        val enabled: Boolean,
        val deps: List<String>,
        val stateDir: String,
        val source: String,
        val sha256: String,
        val tier: String,
        val libName: String,
    )

    private const val SEED_ASSET = "supply/seed.json"

    const val TIER_BASE = "base"
    const val TIER_OPTIONAL = "optional"

    fun root(ctx: Context): File = File(ctx.filesDir, "sys")
    private fun file(ctx: Context): File = File(root(ctx), "registry.json")

    fun fileFor(ctx: Context): File = file(ctx)

    fun kindDir(kind: Kind): String = when (kind) {
        Kind.RUNTIME -> "runtimes"
        Kind.CHANNEL -> "channels"
        Kind.COMPONENT -> "components"
    }

    fun kindOf(raw: String): Kind = when (raw.trim().uppercase()) {
        "RUNTIME" -> Kind.RUNTIME
        "CHANNEL" -> Kind.CHANNEL
        else -> Kind.COMPONENT
    }

    fun defaultStateDir(name: String, kind: Kind): String = "sys/" + kindDir(kind) + "/" + name

    fun safeSegment(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 64) return null
        if (v == "." || v == "..") return null
        return v.takeIf { it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' } }
    }

    fun dirFor(ctx: Context, name: String): File {
        val seg = safeSegment(name) ?: return File(ctx.filesDir, TIER_OPTIONAL)
        val known = all(ctx).firstOrNull { it.name == seg }
        if (known != null && known.stateDir.isNotBlank()) return File(ctx.filesDir, known.stateDir)
        val kind = kindOf(CatalogClient.entryFor(ctx, seg)?.optString("kind", "") ?: "")
        return File(ctx.filesDir, defaultStateDir(seg, kind))
    }

    fun seed(ctx: Context): List<Facility> = runCatching {
        val text = ctx.assets.open(SEED_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONObject(text).optJSONArray("bundled") ?: return emptyList()
        val out = mutableListOf<Facility>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optString("name", "")
            if (name.isBlank()) continue
            val kind = kindOf(e.optString("kind", ""))
            out.add(
                Facility(
                    name = name,
                    kind = kind,
                    version = "",
                    enabled = true,
                    deps = e.optJSONArray("deps")?.let { d -> (0 until d.length()).map { d.optString(it) } } ?: emptyList(),
                    stateDir = defaultStateDir(name, kind),
                    source = "apk",
                    sha256 = "",
                    tier = TIER_BASE,
                    libName = e.optString("libName", ""),
                ),
            )
        }
        out
    }.getOrDefault(emptyList())

    @Synchronized
    fun upsert(
        ctx: Context,
        name: String,
        kind: Kind,
        version: String,
        enabled: Boolean,
        deps: List<String>,
        sha256: String,
        source: String,
        tier: String = TIER_OPTIONAL,
        libName: String = "",
    ) {
        val f = file(ctx)
        val o = StateFiles.readJson(f) ?: JSONObject()
        val arr = o.optJSONArray("facilities") ?: JSONArray()
        val out = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("name") == name) {
                e.put("kind", kind.name)
                e.put("version", version)
                e.put("enabled", enabled)
                e.put("deps", JSONArray(deps))
                e.put("sha256", sha256)
                e.put("source", source)
                e.put("tier", tier)
                e.put("libName", libName)
                e.put("stateDir", defaultStateDir(name, kind))
                hit = true
            }
            out.put(e)
        }
        if (!hit) {
            out.put(JSONObject().apply {
                put("name", name)
                put("kind", kind.name)
                put("version", version)
                put("enabled", enabled)
                put("deps", JSONArray(deps))
                put("sha256", sha256)
                put("source", source)
                put("tier", tier)
                put("libName", libName)
                put("stateDir", defaultStateDir(name, kind))
            })
        }
        f.parentFile?.mkdirs()
        StateFiles.writeJson(f, JSONObject().apply { put("facilities", out) })
    }

    @Synchronized
    fun remove(ctx: Context, name: String): Boolean {
        val f = file(ctx)
        val o = StateFiles.readJson(f) ?: return false
        val arr = o.optJSONArray("facilities") ?: return false
        val out = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("name") == name) { hit = true } else { out.put(e) }
        }
        if (!hit) return false
        return StateFiles.writeJson(f, JSONObject().apply { put("facilities", out) })
    }

    @Synchronized
    fun setEnabled(ctx: Context, name: String, enabled: Boolean): Boolean {
        val f = file(ctx)
        val o = StateFiles.readJson(f) ?: return false
        val arr = o.optJSONArray("facilities") ?: return false
        var hit = false
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("name") == name) {
                e.put("enabled", enabled)
                hit = true
            }
        }
        if (!hit) return false
        return StateFiles.writeJson(f, o)
    }

    @Synchronized
    fun setVersion(ctx: Context, name: String, version: String) {
        val f = file(ctx)
        val o = StateFiles.readJson(f) ?: return
        val arr = o.optJSONArray("facilities") ?: return
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("name") == name) e.put("version", version)
        }
        StateFiles.writeJson(f, o)
    }

    @Synchronized
    fun ensure(ctx: Context) {
        val f = file(ctx)
        if (f.isFile) return
        val seeded = seed(ctx)
        f.parentFile?.mkdirs()
        StateFiles.writeJson(f, JSONObject().apply { put("facilities", encode(seeded)) })
        Journal.note(
            ctx, "facility", null, "登记表初始化（来源：assets/supply/seed.json）",
            "内置=" + seeded.joinToString(",") { it.name },
        )
    }

    @Synchronized
    fun all(ctx: Context): List<Facility> {
        ensure(ctx)
        val o = StateFiles.readJson(file(ctx)) ?: return seed(ctx)
        val arr = o.optJSONArray("facilities") ?: return seed(ctx)
        val out = mutableListOf<Facility>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optString("name", "")
            if (name.isBlank()) continue
            val kind = kindOf(e.optString("kind", ""))
            out.add(
                Facility(
                    name = name,
                    kind = kind,
                    version = e.optString("version", ""),
                    enabled = e.optBoolean("enabled", true),
                    deps = e.optJSONArray("deps")?.let { d -> (0 until d.length()).map { d.optString(it) } } ?: emptyList(),
                    stateDir = e.optString("stateDir", defaultStateDir(name, kind)),
                    source = e.optString("source", "apk"),
                    sha256 = e.optString("sha256", ""),
                    tier = e.optString("tier", TIER_BASE),
                    libName = e.optString("libName", ""),
                ),
            )
        }
        return out
    }

    fun enabled(ctx: Context): Set<String> = all(ctx).filter { it.enabled }.map { it.name }.toSet()

    fun isBase(ctx: Context, name: String): Boolean =
        all(ctx).firstOrNull { it.name == name }?.tier == TIER_BASE

    private fun encode(list: List<Facility>): JSONArray = JSONArray().apply {
        for (f in list) {
            put(JSONObject().apply {
                put("name", f.name)
                put("kind", f.kind.name)
                put("version", f.version)
                put("enabled", f.enabled)
                put("deps", JSONArray(f.deps))
                put("stateDir", f.stateDir)
                put("source", f.source)
                put("sha256", f.sha256)
                put("tier", f.tier)
                put("libName", f.libName)
            })
        }
    }
}
