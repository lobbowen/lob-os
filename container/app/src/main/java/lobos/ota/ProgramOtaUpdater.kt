package lobos.ota

import android.content.Context
import lobos.os.PowerLocks
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object ProgramOtaUpdater {

    private const val TAG = "ProgramOtaUpdater"
    private const val CONFIG_ASSET = "program-feed.json"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_ZIP_BYTES = 32L * 1024 * 1024

    data class Config(
        val baseUrl: String,
        val releaseTag: String,
        val channel: String,
        val manifestName: String,
        val autoCheck: Boolean,
        val startupBudgetMs: Long,
        val allowDowngrade: Boolean,
    ) {
    val manifestUrl: String get() = "$baseUrl/$releaseTag/$manifestName?t=${System.currentTimeMillis()}"
        fun zipUrl(version: String) = "$baseUrl/$releaseTag/program-$version.zip"
    }

    data class Outcome(
        val checked: Boolean,
        val available: Boolean,
        val updated: Boolean,
        val current: String?,
        val remote: String?,
        val detail: String,
        val upToDate: Boolean = false,
    )

    fun loadConfig(context: Context): Config? {
        val devCfg = File(context.filesDir, CONFIG_ASSET)
        if (devCfg.isFile) {
            val fromDevice = parseConfig(readTextOrNull(devCfg))
            if (fromDevice != null) {
                Log.i(TAG, "program-feed.json 取自设备侧覆盖：$devCfg（可随时暂停/改通道，无需重装 APK）")
                return fromDevice
            }
            Log.w(TAG, "设备侧 $CONFIG_ASSET 不可用/非法 —— 回退 APK 内 asset（绝不静默关掉 OTA）")
        }
        return parseConfig(
            try {
                context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            } catch (e: Throwable) {
                Log.w(TAG, "program-feed.json 不可用: ${e.message}")
                null
            }
        )
    }

    private fun readTextOrNull(f: File): String? = try { f.readText() } catch (e: Throwable) { null }

    private fun parseConfig(text: String?): Config? {
        if (text == null) return null
        return try {
            val o = JSONObject(text)
            val base = o.optString("baseUrl", "").trim().trimEnd('/')
            val channel = o.optString("channel", "stable").trim().ifBlank { "stable" }
            val override = o.optString("releaseTag", "").trim()
            val tag = if (override.isNotBlank()) override else "program-" + channel
            val name = o.optString("manifestName", "program-manifest.json").trim().ifBlank { "program-manifest.json" }
            val auto = o.optBoolean("autoCheck", true)
            val budget = o.optLong("startupBudgetMs", 12000L)
            run {
                  val allowDowngrade = o.optBoolean("allowDowngrade", false)
                  if (!base.startsWith("https://")) null
                  else Config(base, tag, channel, name, auto, budget, allowDowngrade)
              }
        } catch (e: Throwable) { null }
    }

    fun checkAndUpdate(
        context: Context,
        km: ProgramManager,
        checkOnly: Boolean = false,
        budgetMs: Long = 0L,
    ): Outcome {
        val net = PowerLocks.wifi(context)
        return try {
            checkAndUpdateNet(context, km, checkOnly, budgetMs)
        } finally {
            net.close()
        }
    }

    private fun checkAndUpdateNet(
        context: Context,
        km: ProgramManager,
        checkOnly: Boolean = false,
        budgetMs: Long = 0L,
    ): Outcome {
        val deadline = if (budgetMs > 0L) System.currentTimeMillis() + budgetMs else 0L
        fun left(): Long = if (deadline == 0L) Long.MAX_VALUE else deadline - System.currentTimeMillis()
        val cfg = loadConfig(context)
            ?: return Outcome(false, false, false, km.currentVersion(), null, "未配置 program-feed.json（远端 OTA 关闭）")

        val current = km.currentVersion()
        val manifestText = try {
            httpGetText(cfg.manifestUrl, MAX_MANIFEST_BYTES, left())
        } catch (e: Throwable) {
            return Outcome(true, false, false, current, null, "取 manifest 失败（离线或不可达）：${e::class.java.simpleName}: ${e.message}")
        }

        val manifest = try {
            JSONObject(manifestText)
        } catch (e: Throwable) {
            return Outcome(true, false, false, current, null, "manifest 不是合法 JSON：${e.message}")
        }

        val remote = manifest.optString("version", "").trim().ifBlank { null }
            ?: return Outcome(true, false, false, current, null, "manifest 缺 version 字段")

        val seq = manifest.optLong("sequence", 0L)
        val st = loadState(context)
        val verdict = OtaPolicy.evaluate(
            OtaPolicy.Input(
                remoteVersion = remote,
                currentVersion = current,
                floorVersion = km.floorVersion(),
                expiresEpochMs = manifest.optLong("expiresEpochMs", 0L),
                sequence = seq,
                lastSequence = st.optLong("lastSequence", 0L),
                rolloutPercent = manifest.optInt("rolloutPercent", 100),
                installId = installId(context),
                hasSha256 = manifest.optString("sha256", "").isNotBlank(),
                hasSignature = manifest.optString("signature", "").isNotBlank(),
                allowDowngrade = cfg.allowDowngrade,
                nowMs = System.currentTimeMillis(),
            ),
            checkOnly = checkOnly,
        )
        when (verdict) {
            is OtaPolicy.Verdict.Reject -> return Outcome(true, false, false, current, remote, verdict.message)
            is OtaPolicy.Verdict.UpToDate -> return Outcome(true, false, false, current, remote, verdict.message, upToDate = true)
            is OtaPolicy.Verdict.Available -> return Outcome(true, true, false, current, remote, verdict.message)
            is OtaPolicy.Verdict.Holdback -> return Outcome(true, true, false, current, remote, verdict.message)
            is OtaPolicy.Verdict.Downgrade -> lobos.os.Journal.append(
                context, "ota", null, "按显式策略降级（审计）",
                "允许降级：" + current + " → " + remote + "；策略=program-feed.json allowDowngrade=true",
            )
            OtaPolicy.Verdict.Install -> Unit
        }

        if (left() <= 0L) {
            return Outcome(true, true, false, current, remote, "启动预算已耗尽（${budgetMs}ms）：本次不下载，下次启动或手动触发重试")
        }
        val url = manifest.optString("url", "").trim().ifBlank { cfg.zipUrl(remote) }
        val tmp = File(context.cacheDir, "program-ota-$remote.zip")
        val part = File(context.cacheDir, tmp.name + ResumableDownloader.PART_SUFFIX)
        val manifestFile = File(context.cacheDir, "program-manifest-ota.json")
        val manifestStaged = runCatching {
            lobos.os.StateFiles.writeAtomic(manifestFile, manifestText)
        }.isSuccess
        if (!manifestStaged) {
            return Outcome(
                true, true, false, current, remote,
                "清单暂存失败：无法完成签名复检，本次拒绝安装（fail-closed）",
            )
        }
        val dl = ResumableDownloader.download(
            url = url,
            dest = tmp,
            part = part,
            expectedSha256 = manifest.optString("sha256", "").ifBlank { null },
            deadline = deadline,
            maxBytes = MAX_ZIP_BYTES,
        )
        if (dl.result != ResumableDownloader.Result.DONE) {
            return Outcome(true, true, false, current, remote, "下载未完成（$url）：${dl.detail ?: dl.result}")
        }

        val result = try {
            val installId = manifest?.optString("id", "")?.trim()?.takeIf { it.isNotBlank() }
                ?: manifest?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() } ?: ""
            ProgramInstaller.install(
                context, tmp, manifest, ProgramInstaller.Source.OTA, manifestFile = manifestFile, programId = installId,
            )
        } finally {
            tmp.delete()
            manifestFile.delete()
        }
        if (result.ok && seq > 0L) {
            st.put("pendingSequence", seq)
            saveState(context, st)
            lobos.os.Journal.append(
                context, "ota", null, "安装成功：序列号待健康提交",
                "sequence=" + seq + "（健康通过后才推进 lastSequence；失败则撤销，同版可重装）",
            )
        }
        return Outcome(
            checked = true,
            available = true,
            updated = result.ok,
            current = current,
            remote = result.version ?: remote,
            detail = result.toDiagnosticLine(),
        )
    }

    private fun stateFile(context: Context) = File(context.filesDir, "program-feed-state.json")

    fun promotePendingSequence(context: Context) {
        val st = loadState(context)
        val pending = st.optLong("pendingSequence", 0L)
        if (pending <= 0L) return
        st.put("lastSequence", pending)
        st.remove("pendingSequence")
        saveState(context, st)
        lobos.os.Journal.append(context, "ota", true, "健康通过：推进 feed 序列号", "lastSequence=" + pending)
    }

    fun dropPendingSequence(context: Context) {
        val st = loadState(context)
        val pending = st.optLong("pendingSequence", 0L)
        if (pending <= 0L) return
        st.remove("pendingSequence")
        saveState(context, st)
        lobos.os.Journal.append(
            context, "ota", false, "健康未通过：撤销待推进序列号",
            "pendingSequence=" + pending + " —— 回滚后同版仍可重装",
        )
    }


    private fun loadState(context: Context): JSONObject =
        try { JSONObject(stateFile(context).readText()) } catch (_: Throwable) { JSONObject() }

    private fun saveState(context: Context, o: JSONObject) {
        try { lobos.os.StateFiles.writeAtomic(stateFile(context), o.toString()) } catch (_: Throwable) { }
    }

    private fun installId(context: Context): String {
        val st = loadState(context)
        val id = st.optString("installId", "")
        if (id.isNotBlank()) return id
        val gen = java.util.UUID.randomUUID().toString()
        st.put("installId", gen)
        saveState(context, st)
        return gen
    }

    private fun open(url: String, budgetMs: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            val cap = if (budgetMs <= 0L) Long.MAX_VALUE else budgetMs.coerceAtLeast(1_000L)
            connectTimeout = minOf(CONNECT_TIMEOUT_MS.toLong(), cap).toInt()
            readTimeout = minOf(READ_TIMEOUT_MS.toLong(), cap).toInt()
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "lobos-os-ota")
        }

    private fun httpGetText(url: String, maxBytes: Int, budgetMs: Long): String {
        val conn = open(url, budgetMs)
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return conn.inputStream.use { ins ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val n = ins.read(buf, total, maxBytes - total)
                if (n <= 0) break
                total += n
            }
            String(buf, 0, total, Charsets.UTF_8)
        }
    }

}
