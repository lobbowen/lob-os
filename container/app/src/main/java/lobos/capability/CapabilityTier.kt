package lobos.capability

import org.json.JSONArray
import org.json.JSONObject

object CapabilityTier {

    enum class Tier(val label: String) {
        APK("apk：基础档，只有本应用自身权限"),
        APK_ADB("apk+adb：已配对 ADB 通道在线（可写 secure settings，锚可由内核重挂）"),
        APK_DO("apk+do：Device Owner（可选档；可用 API 需逐条验证）"),
    }

    data class Verdict(
        val tier: Tier,
        val basis: List<String>,
        val unproven: List<String>,
        val silentGrantVerified: Boolean,
        val deviceOwner: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("tier", tier.name.lowercase())
            put("label", tier.label)
            put("basis", JSONArray(basis))
            put("unproven", JSONArray(unproven))
            put("silentGrantVerified", silentGrantVerified)
            put("deviceOwner", deviceOwner)
        }
    }

    fun of(
        channelLive: Boolean,
        deviceOwner: Boolean,
        silentGrantVerified: Boolean = false,
        notes: List<String> = emptyList(),
    ): Verdict {
        val basis = mutableListOf<String>()
        val unproven = notes.toMutableList()
        if (silentGrantVerified && channelLive) {
            basis.add("静默授权已实测：权限台账里有系统回读确认的成功记录（SILENT_OK）")
        } else if (channelLive) {
            unproven.add("尚无静默授权实测：台账里没有系统回读确认的成功记录")
        }
        if (deviceOwner) {
            basis.add("实测：本应用已是设备所有者")
            return Verdict(Tier.APK_DO, basis, unproven, silentGrantVerified, true)
        }
        if (channelLive) {
            basis.add("ADB 通道已实测在线（未过期）")
            return Verdict(Tier.APK_ADB, basis, unproven, silentGrantVerified, false)
        }
        basis.add("仅本应用：未实测到在线 ADB 通道，也未实测到设备所有者")
        return Verdict(Tier.APK, basis, unproven, silentGrantVerified, false)
    }

    fun fromEvidence(ev: Evidence, owner: DeviceOwnerProbe.Result): Verdict {
        val notes = mutableListOf<String>()
        val live = ev.channelLive()
        val silent = ev.permissionAttempts.values.any { it.outcome == AttemptOutcome.SILENT_OK }
        if (!live) {
            when (ev.channel.outcome) {
                ProbeOutcome.NEVER_RUN -> notes.add("ADB 通道从未实测（never_run）⇒ 不计入 adb 档")
                ProbeOutcome.DEAD -> notes.add("ADB 通道实测为死：" + ev.channel.detail)
                ProbeOutcome.LIVE -> notes.add("ADB 通道探针已过期（TTL " + ev.channelTtlMs + "ms）⇒ 需重测才算数")
            }
            if (ev.wirelessDebugOn) notes.add("无线调试开关是开的，但未实测到在线通道 ⇒ 不计入 adb 档（配置不等于能力）")
            if (ev.credentials == CredentialsState.PAIRED) notes.add("已有配对凭据，但未实测到在线通道 ⇒ 不计入 adb 档")
        }
        if (!owner.isDeviceOwner) notes.add(owner.detail)
        return of(live, owner.isDeviceOwner, silent, notes)
    }
}
