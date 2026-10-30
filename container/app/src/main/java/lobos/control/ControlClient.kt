package lobos.control

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import org.json.JSONObject

/**
 * Formal LOB-OS control client.
 *
 * This library owns the JSON-RPC handshake, newline framing, token use, timeout, structured error
 * classification, and requestId correlation. Android LocalSocket is only the transport adapter.
 * It is not a probe and it does not invoke CapabilityBroker directly.
 */
class ControlClient(
    private val socketName: String,
    private val sessionToken: String,
    private val protocolVersion: Int = 1,
    private val timeoutMs: Long = 10_000L,
) {
    private fun connect(socket: LocalSocket) {
        try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: Throwable) {
            throw ControlClientException("bridge connect failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    data class Result(
        val ok: Boolean,
        val code: Int,
        val requestId: String,
        val value: JSONObject,
    ) {
        fun detail(): String = value.optString("detail", value.optString("error", ""))
    }

    fun rollback(id: String, base: String, requestId: String, dryRun: Boolean = false): Result {
        require(id.isNotBlank()) { "id 为空" }
        require(base == "apk") { "base 只接受 apk" }
        require(requestId.isNotBlank()) { "requestId 为空" }
        return LocalSocket().use { socket ->
            connect(socket)
            socket.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val out = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

            fun exchange(request: JSONObject): JSONObject {
                out.write(request.toString())
                out.write("\n")
                out.flush()
                val line = reader.readLine() ?: throw ControlClientException("bridge closed before response")
                return runCatching { JSONObject(line) }
                    .getOrElse { throw ControlClientException("invalid JSON-RPC response: ${it.message}") }
            }

            val handshake = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", "handshake-" + UUID.randomUUID().toString())
                put("method", "bridge.handshake")
                put("params", JSONObject().apply {
                    put("protocol", protocolVersion)
                    put("token", sessionToken)
                    put("requires", org.json.JSONArray().put("base"))
                })
            }
            val hs = exchange(handshake)
            if (hs.has("error")) return@use Result(false, hs.errorCode(), requestId, hs.errorValue())

            val request = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("method", "lobos.sys.native.rollback")
                put("params", JSONObject().apply {
                    put("id", id)
                    put("base", base)
                    put("requestId", requestId)
                    put("dryRun", dryRun)
                })
            }
            val response = exchange(request)
            if (response.has("error")) return@use Result(false, response.errorCode(), requestId, response.errorValue())
            val result = response.optJSONObject("result") ?: JSONObject()
            Result(result.optBoolean("ok", false), 0, requestId, result)
        }
    }
}

class ControlClientException(message: String) : IllegalStateException(message)

private fun JSONObject.errorCode(): Int = optJSONObject("error")?.optInt("code", -1) ?: -1

private fun JSONObject.errorValue(): JSONObject = optJSONObject("error") ?: JSONObject().apply {
    put("error", optString("message", "unknown bridge error"))
}
