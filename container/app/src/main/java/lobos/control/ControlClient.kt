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

    fun rollback(id: String, base: String, requestId: String, dryRun: Boolean = false): Result =
        call("lobos.sys.native.rollback", requestId, JSONObject().apply {
            put("id", id)
            put("base", base)
            put("dryRun", dryRun)
        }, id, base)

    fun update(manifestUrl: String, requestId: String, dryRun: Boolean = true): Result =
        call("lobos.sys.native.update", requestId, JSONObject().apply {
            put("manifestUrl", manifestUrl)
            put("dryRun", dryRun)
        }, "native", "ota")

    /**
     * 跑一条命令（ 它在 LOB-OS 自己那一份 Linux 里跑、 env 逐字是 LOB-OS 自己那套）。
     *
     *  缺陷名（C-102）**那个「跑一条用户指定的命令」的入口逐字没有、 「起程序」那一族逐字只到那件自己**
     *   旧版逐字**只有** [rollback] 与 [update] 两个方法、 它们逐字**不**起程序。
     *   **后果**： 「在 LOB-OS 自己那一份起程序」那一格逐字**从外部**够不到
     *     （ 个手填 URL 的入口逐字是设备 UI 那一面、 它逐字**不**是一个客户端）。
     *   **处置**： 这一族逐字**该有**（ 它逐字是产品那一面、 它逐字**不是**探针）
     *     **形状**逐字由 01 裁（ S615-01 §3.3）：
     *       · 那方法逐字是 [exec]
     *       · 那 handshake 的 `requires` 逐字**要能带** `lobos:sys`
     *         （ `sys.tty.exec` 逐字要 `ApiSpec.GROUP_SYS`、 CapabilityBroker.kt:1157
     *         逐字 `MethodDef(listOf(ApiSpec.GROUP_SYS), true)`）
     *       · 而那凭据逐字是那 `installerToken`（ CapabilityBroker.kt:94）、
     *         它逐字落在 `files/run/sessions.json`、 `SystemFilesProvider` 逐字是只读出口。
     *
     *  @param cmd 那要跑的命令串（ 它逐字**在** LOB-OS 自己那一份跑）
     *  @param requestId 那请求号（ 它逐字用来对账）
     *  @param timeoutMs 那超时（ 逐字默认 10000）
     *  @param noPty 那要不要走无 PTY 那一路（ 逐字默认 false、 true 逐字强制无 PTY）
     */
    fun exec(
        cmd: String,
        requestId: String,
        timeoutMs: Long = 10_000L,
        noPty: Boolean = false,
    ): Result {
        require(cmd.isNotBlank()) { "cmd 为空" }
        return call(
            "sys.tty.exec",
            requestId,
            JSONObject().apply {
                put("cmd", cmd)
                put("timeoutMs", timeoutMs)
                put("noPty", noPty)
            },
            "shell",
            "exec",
            requires = listOf(BRIDGE_BASE, GROUP_SYS),
        )
    }

    private fun call(
        method: String,
        requestId: String,
        params: JSONObject,
        id: String,
        base: String,
        requires: List<String> = listOf(BRIDGE_BASE),
    ): Result {
        require(id.isNotBlank()) { "id 为空" }
        require(requestId.isNotBlank()) { "requestId 为空" }
        return LocalSocket().use { socket ->
            connect(socket)
            socket.soTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val out = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            fun exchange(request: JSONObject): JSONObject {
                out.write(request.toString()); out.write("\n"); out.flush()
                val line = reader.readLine() ?: throw ControlClientException("bridge closed before response")
                return runCatching { JSONObject(line) }
                    .getOrElse { throw ControlClientException("invalid JSON-RPC response: ${it.message}") }
            }
            val handshake = JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", "handshake-" + UUID.randomUUID())
                put("method", "bridge.handshake")
                put("params", JSONObject().apply {
                    put("protocol", protocolVersion); put("token", sessionToken)
                    //  requires 逐字**能带** `lobos:sys`（ 它逐字是那 exec 那一族要的）
                    //     旧版逐字**只**带 "base"、 它逐字**拿不到** GROUP_SYS。
                    val req = org.json.JSONArray()
                    for (r in requires) req.put(r)
                    put("requires", req)
                })
            }
            val hs = exchange(handshake)
            if (hs.has("error")) return@use Result(false, hs.errorCode(), requestId, hs.errorValue())
            val request = JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", requestId); put("method", method)
                put("params", JSONObject(params.toString()).put("requestId", requestId))
            }
            val response = exchange(request)
            if (response.has("error")) return@use Result(false, response.errorCode(), requestId, response.errorValue())
            val result = response.optJSONObject("result") ?: JSONObject()
            Result(result.optBoolean("ok", false), 0, requestId, result)
        }
    }
}

class ControlClientException(message: String) : IllegalStateException(message)

/** 那 bridge 的基础能力组（ 它是每一个桥会话逐字都有的那一组）。 */
private const val BRIDGE_BASE = "base"

/** 那系统能力组（ 它逐字是 `sys.tty.exec` 那一族要的那一组、 ApiSpec.kt:12 逐字
 *  `const val GROUP_SYS = "lobos:sys"`）。 */
private const val GROUP_SYS = "lobos:sys"

private fun JSONObject.errorCode(): Int = optJSONObject("error")?.optInt("code", -1) ?: -1

private fun JSONObject.errorValue(): JSONObject = optJSONObject("error") ?: JSONObject().apply {
    put("error", optString("message", "unknown bridge error"))
}
