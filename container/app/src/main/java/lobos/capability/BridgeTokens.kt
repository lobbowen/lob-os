package lobos.capability

object BridgeTokens {

    const val BASE = "base"

    const val PROGRAM_UPDATE = "program_update"

    fun from(e: Evidence): Set<String> {
        val caps = mutableSetOf(BASE, PROGRAM_UPDATE)
        CapabilityCatalog.ALL.forEach { c ->
            val token = c.bridgeToken ?: return@forEach
            if (CapabilityCatalog.rawJudge(c.id, e)?.status == CapStatus.GRANTED) caps += token
        }
        return caps
    }
}
