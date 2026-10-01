package lobos.capability

import lobos.permissions.PermissionCatalog
import lobos.setup.PostPairingAutoFlow

object PermissionSprint {

    val REQUIRED: List<String> = CapabilityCatalog
        .requiresInOrder(CapabilityCatalog.ADB_CREDENTIALS)
        .filter { PermissionCatalog.byId(it) != null }

    fun residue(e: Evidence): List<String> {
        if (!PostPairingAutoFlow.ready(e)) return emptyList()
        val anchors = CapabilityCatalog.ALL.filter { it.keepAliveAnchor }.map { it.id }.toSet()
        val candidates = CapabilityCatalog.ALL.filter {
            PermissionCatalog.byId(it.id) != null && !it.optional && it.id !in REQUIRED &&
                e.attemptOutcome(it.id) in setOf(AttemptOutcome.NEEDS_TAP, AttemptOutcome.UNSUPPORTED)
        }.map { it.id }
        return candidates.filter { anchors.contains(it) } + candidates.filter { !anchors.contains(it) }
    }

    fun pending(e: Evidence, asked: Set<String> = emptySet()): List<String> =
        (REQUIRED + residue(e)).distinct().filter { !e.granted(it) && it !in asked }

    fun next(e: Evidence, asked: Set<String>): Pair<String, Acquisition>? {
        val id = pending(e, asked).firstOrNull() ?: return null
        val acq = CapabilityCatalog.byId(id)?.acquirer?.invoke(e)?.firstOrNull() ?: return null
        return id to acq
    }
}
