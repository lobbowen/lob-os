package lobos.quickapp

import org.json.JSONObject

object LobosBridge {

    private const val PONG = "pong"

    fun handle(event: String, data: JSONObject?, reply: (JSONObject) -> Unit) {
        when (event) {
            "ping" -> reply(JSONObject().apply { put("ok", true); put("echo", PONG) })
            "capabilities" -> reply(capabilities())
            else -> reply(JSONObject().apply { put("ok", false); put("error", "unknown event: $event") })
        }
    }

    private fun capabilities(): JSONObject = JSONObject().apply {
        put("ok", true)
        put("runtime", "lobos")
        put("hostApis", listOf("ping", "capabilities"))
    }
}
