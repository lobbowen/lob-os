package lobos.setup

object PostPairingAutoFlow {

    fun ready(e: Evidence): Boolean =
        e.credentials == CredentialsState.PAIRED && e.channelLive()

    fun plan(e: Evidence): List<String> {
        if (!ready(e)) return emptyList()
        val verdicts = CapabilityCatalog.evaluate(e)
        return verdicts.keys.sorted().filter { id ->
            val v = verdicts[id]
            if (v?.status != CapStatus.ACTION) return@filter false
            if (e.granted(id)) return@filter false
            val acq = CapabilityCatalog.byId(id)?.acquirer(e)?.firstOrNull()
            acq != null && acq.kind == AcquireKind.SILENT_VIA_ADB
        }
    }
}
