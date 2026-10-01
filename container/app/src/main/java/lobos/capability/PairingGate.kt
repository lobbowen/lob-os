package lobos.capability

object PairingGate {

    data class Decision(val gapCapId: String?, val notice: String, val jump: Acquisition?, val ready: Boolean)

    private val GATE_ORDER: List<String> =
        CapabilityCatalog.requiresInOrder(CapabilityCatalog.ADB_CREDENTIALS)

    fun decide(e: Evidence, v: Map<String, CapVerdict>): Decision {
        val gap = GATE_ORDER.firstOrNull { v[it]?.status != CapStatus.GRANTED }
        if (gap != null) {
            return Decision(
                gap,
                "还差一步：" + CapabilityCatalog.titleOf(gap) + " —— " + (v[gap]?.detail ?: "未达成"),
                firstAcquirer(gap, e),
                ready = false,
            )
        }
        return Decision(
            null,
            "环境就绪：在「无线调试」页点「与配对设备配对」，端口一出现就能输码",
            firstAcquirer(CapabilityCatalog.WIRELESS_DEBUG, e),
            ready = true,
        )
    }

    private fun firstAcquirer(id: String, e: Evidence): Acquisition? =
        CapabilityCatalog.byId(id)?.acquirer?.invoke(e)?.firstOrNull()
}
