package lobos.capability

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.Locale
import lobos.bridge.AdbClientRunner
import lobos.os.Backoff
import lobos.os.StateFiles
import org.json.JSONObject
import lobos.os.Journal

object AdbChannelComponent {

    const val ID = "adb-channel"

    const val MAX_ATTEMPTS = 6
    const val BACKOFF_BASE_MS = 15_000L
    const val BACKOFF_MAX_MS = 10 * 60_000L

    const val HALF_OPEN_MS = 5 * 60_000L

    const val ENDPOINT_TTL_MS = 30_000L

    enum class State { UNPAIRED, ONLINE, BACKOFF, QUARANTINED }

    data class Snapshot(
        val state: State,
        val detail: String,
        val port: Int,
        val attempts: Int,
        val lastOnlineAt: Long,
        val nextAttemptAt: Long,
        val updatedAt: Long,
    )

    @Volatile private var snapshot = Snapshot(State.UNPAIRED, "未探测", 0, 0, 0L, 0L, 0L)
    private var attempts = 0
    private var nextAttemptAt = 0L
    private var lastOnlineAt = 0L
    private var lastPort = 0
    private var restored = false

    private const val PROBE_CMD = "id"
    private const val PROBE_MARK = "uid=2000"
    private const val SHELL_TIMEOUT_MS = 8_000L
    private const val CHANNEL_TIMEOUT_MS = 2_000L
    private const val RETRY_COOLDOWN_MS = 5_000L
    private const val REPROBE_MS = 60_000L

    @Volatile private var probeCache: ChannelProbe = ChannelProbe(ProbeOutcome.NEVER_RUN)
    @Volatile private var lastVerifyMs = 0L

    private fun invalidateProbe() {
        probeCache = ChannelProbe(ProbeOutcome.NEVER_RUN)
        lastVerifyMs = 0L
    }

    @Synchronized
    private fun probeNow(ctx: Context, nowMs: Long): ChannelProbe {
        val prev = probeCache
        val age = nowMs - prev.atMs
        if (prev.outcome == ProbeOutcome.DEAD && age >= 0 && age < RETRY_COOLDOWN_MS) return prev
        if (CapabilityCriteria.credentialsState(ctx) != CredentialsState.PAIRED) {
            return ChannelProbe(ProbeOutcome.NEVER_RUN, nowMs, "凭据未在册")
        }
        val channel = AdbClientRunner.channel(ctx, CHANNEL_TIMEOUT_MS)
        val ready = channel.json?.optBoolean("ready", false) == true
        val verifyAge = nowMs - lastVerifyMs
        if (prev.outcome == ProbeOutcome.LIVE && lastVerifyMs > 0 && verifyAge >= 0 && verifyAge < REPROBE_MS) {
            val refreshed = if (ready) {
                val h = channel.json?.optString("host", "") ?: ""
                val p = channel.json?.optInt("port", 0) ?: 0
                ChannelProbe(
                    ProbeOutcome.LIVE, nowMs,
                    if (h.isNotBlank() && p > 0) "常驻通道在线 @" + h + ":" + p else "常驻通道在线",
                )
            } else {
                ChannelProbe(ProbeOutcome.DEAD, nowMs, "常驻通道已断开")
            }
            probeCache = refreshed
            return refreshed
        }
        val outcome = AdbClientRunner.shell(ctx, PROBE_CMD, null, null, SHELL_TIMEOUT_MS)
        val stdout = outcome.json?.optString("out", "") ?: ""
        val probed = when {
            outcome.ok && PROBE_MARK in stdout -> {
                lastVerifyMs = nowMs
                ChannelProbe(ProbeOutcome.LIVE, nowMs, "shell 在线（" + PROBE_MARK + " 已验）")
            }
            outcome.ok -> ChannelProbe(ProbeOutcome.DEAD, nowMs, "已连通但拿不到 shell uid：" + stdout.take(80))
            else -> ChannelProbe(ProbeOutcome.DEAD, nowMs, outcome.error ?: ("shell 失败 exit=" + outcome.exitCode))
        }
        probeCache = probed
        return probed
    }
    fun state(): Snapshot = snapshot

    fun isOnline(): Boolean = snapshot.state == State.ONLINE

    fun stateName(): String = snapshot.state.name.lowercase(Locale.US)

    fun asChannelProbe(): ChannelProbe {
        val s = snapshot
        return ChannelProbe(probeOutcome(), if (s.updatedAt > 0L) s.updatedAt else 0L, s.detail)
    }

    fun refreshNow(ctx: Context): ChannelProbe {
        tick(ctx)
        return asChannelProbe()
    }

    fun probeOutcome(): ProbeOutcome = when (snapshot.state) {
        State.ONLINE -> ProbeOutcome.LIVE
        State.UNPAIRED -> ProbeOutcome.NEVER_RUN
        State.BACKOFF, State.QUARANTINED -> ProbeOutcome.DEAD
    }

    private fun file(ctx: Context): File {
        val d = File(ctx.filesDir, "os")
        d.mkdirs()
        return File(d, "adb-channel.json")
    }

    private fun restore(ctx: Context) {
        if (restored) return
        restored = true
        val o = StateFiles.readJson(file(ctx)) ?: return
        lastPort = o.optInt("port", 0)
        lastOnlineAt = o.optLong("lastOnlineAt", 0L)
        attempts = o.optInt("attempts", 0)
        val persistedState = o.optString("state", "")
        val updatedAt = o.optLong("updatedAt", 0L)
        if (persistedState == State.QUARANTINED.name.lowercase(Locale.US) &&
            System.currentTimeMillis() - updatedAt > HALF_OPEN_MS
        ) {
            attempts = 0
            nextAttemptAt = 0L
            Journal.note(ctx, "adb-channel", null, "隔离半开：允许重试通道", "静态 " + (System.currentTimeMillis() - updatedAt) + "ms")
        } else if (persistedState == State.QUARANTINED.name.lowercase(Locale.US)) {
            snapshot = snapshot.copy(state = State.QUARANTINED, attempts = attempts)
        } else if (persistedState == State.ONLINE.name.lowercase(Locale.US)) {
            snapshot = snapshot.copy(state = State.BACKOFF, attempts = attempts)
        }
    }

    private fun persist(ctx: Context) {
        val s = snapshot
        runCatching {
            StateFiles.writeJson(file(ctx), JSONObject().apply {
                put("id", ID)
                put("state", s.state.name.lowercase(Locale.US))
                put("port", s.port)
                put("attempts", s.attempts)
                put("lastOnlineAt", s.lastOnlineAt)
                put("detail", s.detail)
                put("updatedAt", s.updatedAt)
            })
        }
    }

    @Synchronized
    fun reset(ctx: Context, why: String) {
        invalidateProbe()
        attempts = 0
        nextAttemptAt = 0L
        val s = snapshot
        snapshot = s.copy(state = State.UNPAIRED, attempts = 0, nextAttemptAt = 0L, updatedAt = System.currentTimeMillis())
        persist(ctx)
        lobos.os.Journal.note(ctx, "adb-channel", null, "通道监督重置（内核动作）", why)
    }

    @Synchronized
    fun stop(ctx: Context) {
        val s = snapshot
        snapshot = s.copy(state = State.UNPAIRED, detail = "监督已停", updatedAt = System.currentTimeMillis())
        persist(ctx)
    }

    @Synchronized
    fun tick(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        restore(ctx)
        if (CapabilityCriteria.credentialsState(ctx) != CredentialsState.PAIRED) {
            attempts = 0
            nextAttemptAt = 0L
            set(ctx, State.UNPAIRED, "凭据未在册：需走无线调试配对（配对信息持久化在 files/adb）", lastPort)
            return
        }
        if (snapshot.state == State.QUARANTINED) return
        val now = SystemClock.elapsedRealtime()
        if (nextAttemptAt > 0L && now < nextAttemptAt) return
        runCatching { lobos.bridge.ConnectEndpointResolver.invalidateStale(nowMs, ENDPOINT_TTL_MS) }
        val probe = probeNow(ctx, nowMs)
        when (probe.outcome) {
            ProbeOutcome.LIVE -> {
                val channel = runCatching { AdbClientRunner.channel(ctx, 2_000L) }.getOrNull()
                val port = channel?.json?.optInt("port", 0) ?: 0
                val host = channel?.json?.optString("host", "") ?: ""
                val changed = lastPort > 0 && port > 0 && port != lastPort
                if (port > 0) lastPort = port
                attempts = 0
                nextAttemptAt = 0L
                lastOnlineAt = System.currentTimeMillis()
                val where = if (host.isNotBlank() && port > 0) "在线 @" + host + ":" + port else "在线"
                set(ctx, State.ONLINE, where + (if (changed) "（端口变化，已按新端口重挂）" else ""), lastPort)
                if (changed) {
                    lobos.os.Journal.note(ctx, "adb-channel", null, "通道端口变化，已重挂", "port=" + port)
                }
            }
            ProbeOutcome.DEAD -> selfHeal(ctx, probe.detail.ifBlank { "通道断开" })
            ProbeOutcome.NEVER_RUN -> {
                attempts = 0
                nextAttemptAt = 0L
                set(ctx, State.UNPAIRED, probe.detail.ifBlank { "通道未就绪" }, lastPort)
            }
        }
    }

    private fun selfHeal(ctx: Context, detail: String) {
        attempts += 1
        if (attempts > MAX_ATTEMPTS) {
            set(ctx, State.QUARANTINED, "重连达上限（" + attempts + "）：停止打通道，等待内核重置或重新配对", lastPort)
            lobos.os.Journal.note(
                ctx, "adb-channel", false, "通道自愈放弃（转隔离）",
                "attempts=" + attempts + " detail=" + detail,
            )
            return
        }
        val backoff = Backoff.exponential(attempts, BACKOFF_BASE_MS, BACKOFF_MAX_MS)
        nextAttemptAt = SystemClock.elapsedRealtime() + backoff
        val issued = runCatching {
            AdbClientRunner.channel(ctx, 2_000L)
            AdbClientRunner.shell(ctx, "id", null, lastPort.takeIf { it > 0 }, 8_000L).ok
        }.getOrDefault(false)
        set(
            ctx, State.BACKOFF,
            detail + "；第 " + attempts + " 次自愈" + (if (issued) "已下发" else "未生效") + "，退避 " + backoff + "ms",
            lastPort,
        )
        lobos.os.Journal.note(
            ctx, "adb-channel", false, "通道自愈尝试",
            "attempt=" + attempts + " detail=" + detail + " backoffMs=" + backoff + " issued=" + issued,
        )
    }

    @Synchronized
    private fun set(ctx: Context, st: State, detail: String, port: Int) {
        snapshot = Snapshot(st, detail, port, attempts, lastOnlineAt, nextAttemptAt, System.currentTimeMillis())
        persist(ctx)
    }

    fun status(): JSONObject = JSONObject().apply {
        val s = snapshot
        put("id", ID)
        put("kind", "system-component")
        put("state", s.state.name.lowercase(Locale.US))
        put("online", s.state == State.ONLINE)
        put("detail", s.detail)
        put("port", s.port)
        put("attempts", s.attempts)
        put("lastOnlineAt", s.lastOnlineAt)
        put("nextAttemptAt", s.nextAttemptAt)
        put("updatedAt", s.updatedAt)
    }
}
