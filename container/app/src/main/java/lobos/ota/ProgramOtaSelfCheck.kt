package lobos.ota

import android.content.Context
import lobos.RuntimeDiagnostics
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object ProgramOtaSelfCheck {

    private const val MANIFEST_TIMEOUT_MS = 8_000

    fun run(ctx: Context): List<SelfCheckReport.Item> {
        val out = mutableListOf<SelfCheckReport.Item>()

        val cfg = try { ProgramOtaUpdater.loadConfig(ctx) } catch (_: Throwable) { null }
        out += SelfCheckReport.Item(
            "feed-config",
            cfg != null,
            "OTA 源配置",
            if (cfg == null) "读不到 assets/program-feed.json，或 baseUrl 非 https"
            else cfg.baseUrl + "   tag=" + cfg.releaseTag + "   autoCheck=" + cfg.autoCheck,
        )
        if (cfg == null) return out

        val reach = try {
            val t0 = System.currentTimeMillis()
            val conn = (URL(cfg.manifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = MANIFEST_TIMEOUT_MS
                readTimeout = MANIFEST_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "lobos-selfcheck")
            }
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            val reqid = conn.getHeaderField("X-Reqid") ?: "(无)"
            conn.disconnect()
            Triple(code, text, (System.currentTimeMillis() - t0) to reqid)
        } catch (e: Throwable) {
            Triple(-1, "", 0L to (e::class.java.simpleName + ": " + (e.message ?: "")))
        }
        out += SelfCheckReport.Item(
            "manifest-reachable",
            reach.first in 200..299,
            "manifest 可取",
            if (reach.first in 200..299) "HTTP " + reach.first + "  " + reach.third.first + "ms  X-Reqid=" + reach.third.second
            else "HTTP " + reach.first + "  " + reach.third.second,
        )
        val m: JSONObject? = try { JSONObject(reach.second) } catch (_: Throwable) { null }

        if (m == null) {
            out += SelfCheckReport.Item("manifest-fields", null, "manifest 字段齐全", "未取到 manifest，无法判定")
            out += SelfCheckReport.Item("manifest-fresh", null, "manifest 未过期", "未取到 manifest，无法判定")
        } else {
            val need = listOf("version", "sequence", "expiresEpochMs", "rolloutPercent", "sha256", "signature")
            val miss = need.filter { m.optString(it, "").isBlank() }
            out += SelfCheckReport.Item(
                "manifest-fields",
                miss.isEmpty(),
                "manifest 字段齐全",
                if (miss.isEmpty())
                    "version=" + m.optString("version") + "  sequence=" + m.optLong("sequence") +
                        "  rollout=" + m.optInt("rolloutPercent", -1) + "%"
                else "缺字段：" + miss.joinToString(", "),
            )
            val exp = m.optLong("expiresEpochMs", 0L)
            val now = System.currentTimeMillis()
            out += SelfCheckReport.Item(
                "manifest-fresh",
                exp > now,
                "manifest 未过期",
                if (exp <= 0L) "manifest 未声明有效期"
                else "剩余 " + ((exp - now) / 86_400_000L) + " 天",
            )
        }

        val ids = lobos.os.ProgramRegistry.listIds(ctx)
        var km: ProgramDir? = null
        var cur: String? = null
        var floor: String? = null
        var pend: ProgramOtaStateStore.Pending? = null
        if (ids.size == 1) {
            km = ProgramDir(ctx, ids[0])
            val store = ProgramOtaStateStore(km.programRootDir())
            cur = store.currentVersion()
            floor = store.floorVersion()
            pend = store.pending()
            out += SelfCheckReport.Item(
                "local-state",
                true,
                "本地内核状态",
                "CURRENT=" + (cur ?: "(无)") + "   FLOOR=" + (floor ?: "(无)") + "   PENDING=" + (pend?.version ?: "(无)"),
            )
        } else {
            out += SelfCheckReport.Item(
                "local-state",
                null,
                "本地状态段跳过：程序数=" + ids.size + "（本段只在唯一程序时可读）",
                ids.joinToString().ifBlank { "（无）" },
            )
        }

        if (m != null) {
            val st = try {
                val f = File(ctx.filesDir, "program-feed-state.json")
                if (f.isFile) JSONObject(f.readText()) else JSONObject()
            } catch (_: Throwable) { JSONObject() }
            val v = OtaPolicy.evaluate(
                OtaPolicy.Input(
                    remoteVersion = m.optString("version", ""),
                    currentVersion = cur,
                    floorVersion = floor,
                    expiresEpochMs = m.optLong("expiresEpochMs", 0L),
                    sequence = m.optLong("sequence", 0L),
                    lastSequence = st.optLong("lastSequence", 0L),
                    rolloutPercent = m.optInt("rolloutPercent", 100),
                    installId = st.optString("installId", "selfcheck"),
                    nowMs = System.currentTimeMillis(),
                    hasSha256 = m.optString("sha256", "").isNotBlank(),
                    hasSignature = m.optString("signature", "").isNotBlank(),
                ),
            )
            val desc = when (v) {
                is OtaPolicy.Verdict.Reject -> "拒绝（" + v.code + "）：" + v.message
                is OtaPolicy.Verdict.UpToDate -> "已是最新：" + v.message
                is OtaPolicy.Verdict.Available -> "有更新：" + v.message
                is OtaPolicy.Verdict.Holdback -> "灰度未命中（正常）：" + v.message
                is OtaPolicy.Verdict.Downgrade -> "按显式策略降级：" + v.message
                OtaPolicy.Verdict.Install -> "可安装"
            }
            out += SelfCheckReport.Item("policy", true, "OTA 裁定", desc)
        }

        val parts = try {
            (ctx.cacheDir.listFiles() ?: emptyArray()).filter { ResumableDownloader.isPartialFile(it.name) }
                .map { it.name to it.length() }
        } catch (_: Throwable) { emptyList() }
        val staging = try {
            (km?.programRootDir()?.listFiles() ?: emptyArray()).filter { ProgramDir.isStagingDir(it.name) }.map { it.name }
        } catch (_: Throwable) { emptyList() }
        out += SelfCheckReport.partialItem(parts, staging)

        return out
    }

    fun runAndFormat(ctx: Context): String {
        val items = run(ctx)
        val text = SelfCheckReport.format(items)
        try {
            RuntimeDiagnostics.append(ctx, "selfcheck", SelfCheckReport.overallOk(items), "设备端自检", text)
        } catch (_: Throwable) { }
        return text
    }
}
