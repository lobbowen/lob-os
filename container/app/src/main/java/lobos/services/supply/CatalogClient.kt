package lobos.services.supply

// OTA 的定位（改这块之前先读这段）：
//
//   OTA **只是下载器**。它不自己找地址、不自己拉清单、不在启动时自动铺件。
//   它等控制面板在第二阶段给到**具体的拉取动作指令和地址**，才去下载。
//
//   因此：程序启动路径上**不允许**出现 refresh() 之类的自动调用。
//   曾经有一处 supplyOnStartup() 在 onCreate 里起守护线程去下清单 ——
//   那既违背这个定位，又把真因盖住了：真因是 provision 返回空，
//   而那条「清单下载失败」的日志让人误以为是供给的问题。
//
//   桥方法侧的入口保留（控制面板显式调用才触发）：
//     os.catalog.list      refresh 默认 false —— 只读本地
//     os.catalog.refresh   显式刷


import android.content.Context
import lobos.services.log.RuntimeDiagnostics
import lobos.services.log.Journal
import lobos.kernel.layout.PrefixProvisioner
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles
import lobos.services.reg.ProgramManager

object CatalogClient {

    const val CACHE_NAME = "catalog.json"
    const val TTL_MS = 6 * 60 * 60 * 1000L

    fun cacheFile(ctx: Context): File = File(SystemDirs.libvar(ctx), CACHE_NAME)

    @Synchronized
    fun cached(ctx: Context): JSONObject? = runCatching {
        val f = cacheFile(ctx)
        if (!f.isFile) null else JSONObject(f.readText())
    }.getOrNull()

    fun fetchedAt(ctx: Context): Long = cached(ctx)?.optLong("fetchedAt", 0L) ?: 0L

    fun cachedExpiry(ctx: Context): Long {
        val body = cached(ctx)?.optString("body", "") ?: return 0L
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return 0L
        return o.optLong("expiresEpochMs", 0L)
    }

    fun entries(ctx: Context): JSONArray {
        val body = cached(ctx)?.optString("body", "") ?: return JSONArray()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return JSONArray()
        val out = JSONArray()
        val unified = o.optJSONArray("packages")
        if (unified != null) {
            for (i in 0 until unified.length()) {
                val e = unified.optJSONObject(i) ?: continue
                out.put(normalize(e))
            }
            return out
        }
        val tools = o.optJSONArray("tools")
        if (tools != null) {
            for (i in 0 until tools.length()) {
                val e = tools.optJSONObject(i) ?: continue
                if (!e.has("kind")) e.put("kind", "toolset")
                if (!e.has("layout")) e.put("layout", "toolset")
                out.put(normalize(e))
            }
        }
        val products = o.optJSONArray("products")
        if (products != null) {
            for (i in 0 until products.length()) {
                val e = products.optJSONObject(i) ?: continue
                if (!e.has("kind")) e.put("kind", "product")
                out.put(normalize(e))
            }
        }
        return out
    }

    private fun normalize(e: JSONObject): JSONObject {
        if (e.optString("kind", "").isBlank()) e.put("kind", "component")
        if (e.optString("name", "").isBlank()) e.put("name", e.optString("id", ""))
        if (!e.has("versions")) {
            val one = JSONObject()
            val v = e.optString("version", "")
            if (v.isNotBlank()) one.put("version", v)
            one.put("url", e.optString("url", ""))
            one.put("sha256", e.optString("sha256", ""))
            one.put("entry", e.optString("entry", ""))
            one.put("default", true)
            e.put("versions", JSONArray().put(one))
        }
        return e
    }

    fun entryFor(ctx: Context, name: String): JSONObject? {
        val arr = entries(ctx)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("name") == name) return e
        }
        return null
    }

    @Synchronized
    fun refresh(ctx: Context, force: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val fresh = fetchedAt(ctx)
        if (!force && fresh > 0L && now - fresh < TTL_MS) {
            val cachedExpiry = cachedExpiry(ctx)
            if (cachedExpiry in 1 until now) {
                return fail(ctx, "缓存的清单已过期（过期于 " + cachedExpiry + "），需要发布新一轮才能继续安装")
            }
            return JSONObject().apply {
                put("ok", true)
                put("cached", true)
                put("fetchedAt", fresh)
                put("count", entries(ctx).length())
            }
        }
        val base = SupplyProvisioner.manifestDir(ctx)
            ?: return fail(ctx, "通道锚读不到：assets/supply/channel.json")
        val name = SupplyProvisioner.manifestNameOf(ctx, "component")
            ?: return fail(ctx, "通道锚没有 manifests.component.name")
        val sigName = SupplyProvisioner.manifestSigNameOf(ctx, "component") ?: (name + ".sig")
        val pubPem = runCatching {
            ctx.assets.open("supply/component-public.pem").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return fail(ctx, "信任根读不到：assets/supply/component-public.pem")
        val body = runCatching {
            SupplyProvisioner.httpGet(SupplyProvisioner.uncached(base + "/" + name), SupplyProvisioner.MAX_MANIFEST_BYTES)
        }.getOrNull()
            ?: return fail(ctx, "清单下载失败：" + base + "/" + name)
        val sig = runCatching {
            android.util.Base64.decode(
                SupplyProvisioner.httpGet(SupplyProvisioner.uncached(base + "/" + sigName), SupplyProvisioner.MAX_MANIFEST_BYTES).toString(Charsets.UTF_8).trim(),
                android.util.Base64.DEFAULT,
            )
        }.getOrNull() ?: return fail(ctx, "签名下载失败：" + base + "/" + sigName)
        if (!SupplyProvisioner.verifyEd25519(pubPem, body, sig)) return fail(ctx, "清单验签不通过（拒用）")
        val text = body.toString(Charsets.UTF_8)
        val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return fail(ctx, "清单不是合法 JSON")
        val expires = parsed.optLong("expiresEpochMs", 0L)
        if (expires <= 0L) return fail(ctx, "清单缺 expiresEpochMs（必填字段缺失即拒绝）")
        if (now > expires) {
            return fail(
                ctx,
                "清单已过期（过期于 " + expires + "），需要发布新一轮才能继续安装",
            )
        }
        val f = cacheFile(ctx)
        f.parentFile?.mkdirs()
        StateFiles.writeJson(f, JSONObject().apply {
            put("fetchedAt", now)
            put("baseUrl", base)
            put("channel", parsed.optString("channel", ""))
            put("revision", parsed.optLong("revision", 0L))
            put("manifestName", name)
            put("body", text)
        })
        Journal.note(
            ctx, "catalog", null, "目录已刷新（签名校验通过）",
            "channel=" + parsed.optString("channel", "") + " 包=" + entries(ctx).length(),
        )
        return JSONObject().apply {
            put("ok", true)
            put("cached", false)
            put("fetchedAt", now)
            put("count", entries(ctx).length())
            put("channel", parsed.optString("channel", ""))
            put("revision", parsed.optLong("revision", 0L))
        }
    }

    fun list(ctx: Context): JSONObject {
        val out = JSONArray()
        val arr = entries(ctx)
        var upgradable = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optString("name", "")
            if (name.isBlank()) continue
            val wantSha = e.optString("sha256", "")
            val marker = File(SupplyProvisioner.etcDir(ctx), "." + name + ".ok")
            val haveSha = if (marker.isFile) {
                runCatching { marker.readText().trim() }.getOrDefault("")
            } else {
                ""
            }
            val facilityVersion = runCatching { ProgramManager.currentVersion(ctx, name) }.getOrNull() ?: ""
            val entryRel = e.optString("entry", "bin/" + name)
            val bin = File(PrefixProvisioner.binDir(ctx), entryRel.substringAfterLast("/"))
            val installed = haveSha.isNotBlank() || facilityVersion.isNotBlank() || bin.isFile
            val up = installed && wantSha.isNotBlank() && haveSha.isNotBlank() && haveSha != wantSha
            if (up) upgradable += 1
            out.put(JSONObject().apply {
                put("name", name)
                put("kind", e.optString("kind", "component"))
                put("layout", e.optString("layout", if (e.has("aliases")) "toolset" else "single"))
                put("version", e.optString("version", ""))
                put("sha256", wantSha)
                put("size", e.optLong("size", 0L))
                put("entry", entryRel)
                put("deps", e.optJSONArray("deps") ?: JSONArray())
                put("requires", e.optJSONObject("requires") ?: JSONObject.NULL)
                put("installed", installed)
                put("installedSha", haveSha)
                put("installedVersion", facilityVersion)
                put("upgradable", up)
                put("entryOk", bin.isFile)
            })
        }
        val at = fetchedAt(ctx)
        return JSONObject().apply {
            put("fetchedAt", at)
            put("stale", at == 0L || System.currentTimeMillis() - at > TTL_MS)
            put("count", out.length())
            put("upgradable", upgradable)
            put("packages", out)
        }
    }

    private fun fail(ctx: Context, why: String): JSONObject {
        RuntimeDiagnostics.append(ctx, "catalog", false, "目录刷新失败", why)
        return JSONObject().apply {
            put("ok", false)
            put("detail", why)
            put("fetchedAt", fetchedAt(ctx))
        }
    }
}
