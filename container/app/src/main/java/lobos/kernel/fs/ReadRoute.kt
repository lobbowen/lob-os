package lobos.kernel.fs

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ReadRoute {

    const val DEFAULT_MAX_BYTES = 1024L * 1024L

    const val ROUTE = "read-route"

    fun readAll(
        enabled: Boolean,
        route: String,
        file: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): ByteArray {
        val size = file.length()
        if (size > maxBytes) {
            note(enabled, route, file, size, maxBytes, false)
            throw IllegalStateException(
                "那一份太大：" + file.absolutePath + "  = " + size +
                    " 字节、读法[" + route + "]只许 " + maxBytes +
                    "（按 C-05 报缺、**不**整读进那堆）"
            )
        }
        val bytes = file.inputStream().use { it.readBytes() }
        note(enabled, route, file, bytes.size.toLong(), maxBytes, true)
        return bytes
    }

    fun note(
        enabled: Boolean,
        route: String,
        file: File,
        size: Long,
        maxBytes: Long,
        ok: Boolean,
    ) {

        if (ok) remember(route, file.absolutePath, size)
        if (!enabled) return
        runCatching {
            lobos.kernel.spi.KernelServices.logger().log(ROUTE, ok,
                "那读法[" + route + "]读了那一份",
                "file=" + file.absolutePath +
                    " bytes=" + size +
                    " limit=" + maxBytes +
                    (if (ok) "" else "（**超限** **没有**被读）"),
            )
        }
    }

    private data class Live(
        val route: String,
        val file: String,
        val size: Long,
        val atMs: Long,
    )

    private val live = LinkedHashMap<String, Live>()

    private fun remember(route: String, file: String, size: Long) {
        synchronized(live) {
            live[route + "|" + file] = Live(route, file, size, System.currentTimeMillis())
        }
    }

    fun liveReads(): JSONArray {
        val out = JSONArray()
        val now = System.currentTimeMillis()
        synchronized(live) {
            for (l in live.values) {
                out.put(JSONObject().apply {
                    put("route", l.route)
                    put("file", l.file)
                    put("bytes", l.size)
                    put("ageMs", now - l.atMs)
                })
            }
        }
        return out
    }
}
