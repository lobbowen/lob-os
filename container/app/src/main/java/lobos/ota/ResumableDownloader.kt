package lobos.ota
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ResumableDownloader {

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val MAX_BYTES = 32L * 1024 * 1024
    const val DEFAULT_ATTEMPTS = 3

    enum class Result { DONE, PARTIAL, FAILED }

    const val PART_SUFFIX = ".part"

    fun isPartialFile(name: String): Boolean = name.endsWith(PART_SUFFIX)

    data class Outcome(val result: Result, val detail: String?, val partBytes: Long)

    fun download(
        url: String,
        dest: File,
        part: File,
        expectedSha256: String?,
        deadline: Long,
        attempts: Int = DEFAULT_ATTEMPTS,
        maxBytes: Long = MAX_BYTES,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        now: () -> Long = { System.currentTimeMillis() },
    ): Outcome {
        var lastErr: String? = null
        for (attempt in 1..attempts) {
            if (expired(deadline, now)) return partial(part, budgetDetail(part))
            val r = try {
                transferOnce(url, part, expectedSha256, deadline, maxBytes, connectTimeoutMs, readTimeoutMs, now)
            } catch (e: Throwable) {
                lastErr = e::class.java.simpleName + ": " + (e.message ?: "")
                Transfer.FAILED
            }
            when (r) {
                Transfer.DONE -> {
                    dest.delete()
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    return Outcome(Result.DONE, null, dest.length())
                }
                Transfer.PARTIAL -> return partial(part, budgetDetail(part))
                Transfer.FAILED -> if (attempt < attempts) {
                    try { sleep(delayFor(attempt)) } catch (_: InterruptedException) { }
                }
            }
        }
        return Outcome(Result.FAILED, lastErr ?: ("重试 " + attempts + " 次仍未完成，半包已保留"), part.length())
    }

    fun delayFor(attempt: Int): Long = minOf(500L shl (attempt - 1), 4_000L)

    private enum class Transfer { DONE, PARTIAL, FAILED }

    private fun partial(part: File, detail: String) = Outcome(Result.PARTIAL, detail, part.length())

    private fun budgetDetail(part: File) = "启动预算耗尽；已下 " + part.length() + " 字节已保留，下次启动继续"

    private fun expired(deadline: Long, now: () -> Long): Boolean = deadline > 0L && now() > deadline

    private fun transferOnce(
        url: String, part: File, expectedSha256: String?, deadline: Long,
        maxBytes: Long, connectTimeoutMs: Int, readTimeoutMs: Int, now: () -> Long,
    ): Transfer {
        val have = if (part.isFile) part.length() else 0L
        val conn = open(url, connectTimeoutMs, readTimeoutMs)
        if (have > 0L) conn.setRequestProperty("Range", "bytes=" + have + "-")
        return try {
            when (val code = conn.responseCode) {
                416 -> if (sha256Ok(part, expectedSha256)) Transfer.DONE
                       else { part.delete(); throw IllegalStateException("416 且 sha256 不符，已丢弃") }
                206 -> appendTo(conn, part, expectedSha256, deadline, maxBytes, now)
                in 200..299 -> {
                    part.delete()
                    appendTo(conn, part, expectedSha256, deadline, maxBytes, now)
                }
                else -> throw IllegalStateException("HTTP " + code)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun appendTo(
        conn: HttpURLConnection, part: File, expectedSha256: String?,
        deadline: Long, maxBytes: Long, now: () -> Long,
    ): Transfer {
        val total = totalBytes(conn)
        conn.inputStream.use { ins ->
            part.parentFile?.mkdirs()
            FileOutputStream(part, true).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (expired(deadline, now)) return Transfer.PARTIAL
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (part.length() > maxBytes) throw IllegalStateException("包超过上限 " + maxBytes + " 字节")
                }
                out.flush()
                try { out.fd.sync() } catch (_: Throwable) { }
            }
        }
        if (part.length() <= 0L) throw IllegalStateException("下载到 0 字节")
        if (total > 0L && part.length() < total) throw EOFException("提前结束 " + part.length() + "/" + total)
        if (!sha256Ok(part, expectedSha256)) {
            part.delete()
            throw IllegalStateException("sha256 校验未通过，已丢弃半包重下")
        }
        return Transfer.DONE
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Ok(file: File, expected: String?): Boolean {
        val e = expected?.trim()?.lowercase().orEmpty()
        if (e.isBlank()) return true
        return try { sha256(file).equals(e, ignoreCase = true) } catch (_: Throwable) { false }
    }

    private fun totalBytes(conn: HttpURLConnection): Long {
        conn.getHeaderField("Content-Range")?.substringAfterLast("/")?.trim()?.toLongOrNull()?.let { if (it > 0) return it }
        return conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
    }

    private fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "lobos-os-ota")
            setRequestProperty("Accept-Encoding", "identity")
        }
}
