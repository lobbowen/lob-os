package lobos.capability

import android.content.Context
import lobos.ota.ProgramOtaSelfCheck
import lobos.permissions.PermissionCatalog
import lobos.permissions.PermissionCenter
import lobos.runtime.GuestAdapter
import java.net.HttpURLConnection
import java.net.URL

object CapabilityEvidenceCollector {

    private const val PROGRAM_CHECK_TTL_MS = 5 * 60_000L

    private var programChecksAt = 0L
    private var programChecks: List<CheckItem> = emptyList()

    @Synchronized
    fun collect(ctx: Context, nowMs: Long = System.currentTimeMillis()): Evidence {
        val center = PermissionCenter(ctx)
        val grants = PermissionCatalog.ALL.filter { center.isGranted(it) }.map { it.id }.toSet()
        val up = controlPlaneUp()
        return Evidence(
            nowMs = nowMs,
            devOptionsOn = CapabilityCriteria.devOptionsOn(ctx),
            wirelessDebugOn = CapabilityCriteria.wirelessDebugOn(ctx),
            credentials = CapabilityCriteria.credentialsState(ctx),
            channel = AdbChannelComponent.asChannelProbe(),
            grants = grants,
            permissionAttempts = PermissionLedger.readAll(ctx),
            controlPlaneUp = up,
            programChecks = programChecks(ctx, nowMs, up),
            oemGuards = OemGuards.confirmedAll(ctx),
            pairAttempt = AttemptStore.lastPair,
            names = CapabilityCriteria.names(ctx),
        )
    }

    fun forgetKernelChecks() {
        programChecksAt = 0L
        programChecks = emptyList()
    }

    fun systemReads(ctx: Context, nowMs: Long = System.currentTimeMillis()): Evidence {
        val center = PermissionCenter(ctx)
        return Evidence(
            nowMs = nowMs,
            devOptionsOn = CapabilityCriteria.devOptionsOn(ctx),
            wirelessDebugOn = CapabilityCriteria.wirelessDebugOn(ctx),
            credentials = CapabilityCriteria.credentialsState(ctx),
            channel = AdbChannelComponent.asChannelProbe(),
            grants = PermissionCatalog.ALL.filter { center.isGranted(it) }.map { it.id }.toSet(),
            permissionAttempts = PermissionLedger.readAll(ctx),
            pairAttempt = AttemptStore.lastPair,
            oemGuards = OemGuards.confirmedAll(ctx),
            names = CapabilityCriteria.names(ctx),
        )
    }

    private fun programChecks(ctx: Context, nowMs: Long, controlPlaneUp: Boolean): List<CheckItem> {
        if (!controlPlaneUp) {
            programChecksAt = 0L
            programChecks = emptyList()
            return emptyList()
        }
        if (programChecks.isEmpty() || nowMs - programChecksAt >= PROGRAM_CHECK_TTL_MS) {
            programChecks = runCatching {
                ProgramOtaSelfCheck.run(ctx).map { CheckItem(it.id, it.ok, it.detail) }
            }.getOrDefault(emptyList())
            programChecksAt = nowMs
        }
        return programChecks
    }

    fun controlPlaneUp(): Boolean = runCatching {
        val snap = lobos.os.ResidencyStatus.snapshot()
        val at = snap.optLong("updatedAt", 0L)
        at > 0L && System.currentTimeMillis() - at < 60_000L
    }.getOrDefault(false)

}
