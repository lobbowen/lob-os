package lobos.capability

enum class ProbeOutcome {
    LIVE,
    DEAD,
    NEVER_RUN,
}

data class ChannelProbe(
    val outcome: ProbeOutcome,
    val atMs: Long = 0L,
    val detail: String = "",
)

enum class CredentialsState { NO_KEY, PAIRED }

data class PairAttempt(val atMs: Long, val ok: Boolean, val reason: String = "")

enum class AttemptOutcome(val human: String) {
    SILENT_OK("adb 已开"),
    NEEDS_TAP("adb 下发未见生效"),
    UNSUPPORTED("adb 被系统拒绝"),
}

data class SilentAttempt(val outcome: AttemptOutcome, val atMs: Long, val detail: String)

object AttemptOutcomeRule {

    private val REFUSAL_MARKS = listOf(
        "SecurityException", "Security exception", "Permission Denial", "not allowed",
        "Unknown operation", "does not exist",
    )

    fun of(issued: Boolean, verified: Boolean, systemText: String): AttemptOutcome? {
        if (!issued) return null
        if (verified) return AttemptOutcome.SILENT_OK
        val refused = REFUSAL_MARKS.any { systemText.contains(it, ignoreCase = true) }
        return if (refused) AttemptOutcome.UNSUPPORTED else AttemptOutcome.NEEDS_TAP
    }
}

data class CheckItem(val id: String, val ok: Boolean?, val detail: String = "")

data class DeviceNames(
    val packageName: String = "",
    val accessibilityComponent: String = "",
    val notificationListenerComponent: String = "",
)

data class Evidence(
    val nowMs: Long = 0L,
    val devOptionsOn: Boolean = false,
    val wirelessDebugOn: Boolean = false,
    val credentials: CredentialsState = CredentialsState.NO_KEY,
    val channel: ChannelProbe = ChannelProbe(ProbeOutcome.NEVER_RUN),
    val grants: Set<String> = emptySet(),
    val permissionAttempts: Map<String, SilentAttempt> = emptyMap(),
    val controlPlaneUp: Boolean = false,
    val programChecks: List<CheckItem> = emptyList(),

    val oemGuards: Set<String> = emptySet(),
    val pairAttempt: PairAttempt? = null,
    val names: DeviceNames = DeviceNames(),
    val channelTtlMs: Long = CHANNEL_TTL_MS,
) {
    companion object {
        const val CHANNEL_TTL_MS = 30_000L
    }

    fun granted(id: String): Boolean = grants.contains(id)

    fun attemptOutcome(id: String): AttemptOutcome? = permissionAttempts[id]?.outcome

    fun channelLive(): Boolean {
        if (channel.outcome != ProbeOutcome.LIVE) return false
        val age = nowMs - channel.atMs
        return age >= 0 && age < channelTtlMs
    }
}
