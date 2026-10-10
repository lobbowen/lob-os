package lobos.services.supply
object OtaPolicy {

    data class Input(
        val remoteVersion: String,
        val currentVersion: String?,
        val floorVersion: String?,
        val expiresEpochMs: Long,
        val sequence: Long,
        val lastSequence: Long,
        val rolloutPercent: Int,
        val installId: String,
        val nowMs: Long,
        val hasSha256: Boolean = true,
        val hasSignature: Boolean = true,
        val allowDowngrade: Boolean = false,
    )

    sealed class Verdict {
        data class Reject(val code: String, val message: String) : Verdict()
        data class UpToDate(val message: String) : Verdict()
        data class Holdback(val message: String) : Verdict()
        data class Available(val message: String) : Verdict()
        data class Downgrade(val message: String) : Verdict()
        object Install : Verdict()
    }

    fun bucketOf(installId: String, version: String): Int =
        Math.floorMod((installId + ":" + version).hashCode(), 100)

    fun evaluate(i: Input, checkOnly: Boolean = false): Verdict {
        if (i.expiresEpochMs <= 0L) {
            return Verdict.Reject(
                "manifest-missing-expires",
                "manifest 缺 expiresEpochMs —— 必填字段缺失即拒绝安装，不许静默跳过",
            )
        }
        if (i.nowMs > i.expiresEpochMs) {
            return Verdict.Reject(
                "manifest-expired",
                "manifest 已过期（expiresEpochMs=" + i.expiresEpochMs + "）—— 检查发布流水线是否仍在签发",
            )
        }
        if (!i.hasSha256) {
            return Verdict.Reject("manifest-missing-sha256", "manifest 缺 sha256 —— 必填字段缺失即拒绝安装")
        }
        if (!i.hasSignature) {
            return Verdict.Reject("manifest-missing-signature", "manifest 缺 signature —— 必填字段缺失即拒绝安装")
        }
        if (ProgramOtaVersions.isBelowFloor(i.remoteVersion, i.floorVersion)) {
            return Verdict.Reject(
                "version-below-floor",
                "远端 " + i.remoteVersion + " 低于版本下限 " + i.floorVersion + " —— 拒绝（防回退）",
            )
        }
        if (i.sequence < 1L) {
            return Verdict.Reject("manifest-sequence-missing", "manifest 缺 sequence（或为 0）—— 必填字段缺失即拒绝安装")
        }
        if (i.sequence in 1..i.lastSequence) {
            return Verdict.Reject(
                "manifest-replay",
                "manifest sequence=" + i.sequence + " 不高于已提交 " + i.lastSequence + " —— 疑似重放，拒绝",
            )
        }
        val newer = ProgramOtaVersions.isNewer(i.remoteVersion, i.currentVersion)
        val same = i.currentVersion != null && i.remoteVersion == i.currentVersion
        if (!newer && !same) {
            if (!i.allowDowngrade) {
                return Verdict.Reject(
                    "downgrade-not-allowed",
                    "远端 " + i.remoteVersion + " 低于本地 " + i.currentVersion +
                        " —— 降级需显式策略（supply/channel.json manifests.program.allowDowngrade=true），已拒绝并留审计",
                )
            }
            if (checkOnly) {
                return Verdict.Available("发现降级目标 " + i.remoteVersion + "（策略允许，checkOnly：未安装）")
            }
            return Verdict.Downgrade(
                "按显式策略允许降级：" + i.currentVersion + " → " + i.remoteVersion + "（留审计）",
            )
        }
        if (same) {
            return Verdict.UpToDate("已是最新（本地 " + i.currentVersion + "，远端 " + i.remoteVersion + "）")
        }
        if (checkOnly) {
            return Verdict.Available("发现新版本 " + i.remoteVersion + "（checkOnly：未安装）")
        }
        val rollout = i.rolloutPercent.coerceIn(0, 100)
        if (rollout < 100) {
            val bucket = bucketOf(i.installId, i.remoteVersion)
            if (bucket >= rollout) {
                return Verdict.Holdback("灰度未命中（bucket=" + bucket + " >= rolloutPercent=" + rollout + "）—— 下次启动再试")
            }
        }
        return Verdict.Install
    }
}
