package lobos.runtime

import android.content.Context
import android.system.Os
import lobos.RuntimeDiagnostics
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

object SupplyProvisioner {

    private const val CHANNEL_ASSET = "supply/channel.json"
    private const val FETCH_TIMEOUT_MS = 30000
    internal const val MAX_FETCH_BYTES = 256 * 1024 * 1024
    internal const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

    fun etcDir(ctx: Context): File = lobos.os.SystemDirs.etc(ctx)

    fun versionDir(ctx: Context, id: String, version: String): File =
        File(pieceDir(ctx, id), version)

    fun pieceDir(ctx: Context, id: String): File = lobos.os.SystemDirs.pieceDir(ctx, id)

    fun entryLink(ctx: Context, name: String): File = lobos.os.SystemDirs.bin(ctx).let { File(it, name) }

    private fun selectedFile(ctx: Context): File = File(etcDir(ctx), "installed.json")

    fun selectedVersion(ctx: Context, id: String, fallback: String = ""): String {
        val f = selectedFile(ctx)
        if (!f.isFile) return fallback
        return runCatching {
            JSONObject(f.readText()).optString(id, "")
        }.getOrNull().orEmpty().ifBlank { fallback }
    }

    fun selectVersion(ctx: Context, id: String, version: String) {
        val f = selectedFile(ctx)
        val cur = runCatching {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        }.getOrNull() ?: JSONObject()
        cur.put(id, version)
        f.parentFile?.mkdirs()
        f.writeText(cur.toString())
    }

    internal fun manifestDir(ctx: Context): String? {
        val o = channelAnchor(ctx) ?: return null
        val base = o.optString("baseUrl", "").trimEnd('/')
        if (base.isEmpty()) return null
        return base + "/component-" + o.optString("channel", "canary")
    }

    internal fun manifestNameOf(ctx: Context, channel: String): String? {
        val o = channelAnchor(ctx) ?: return null
        val m = o.optJSONObject("manifests")?.optJSONObject(channel)
        if (m != null) return m.optString("name", "").ifBlank { null }
        if (channel == "component") return o.optString("manifestName", "").ifBlank { null }
        return null
    }

    internal fun manifestSigNameOf(ctx: Context, channel: String): String? {
        val o = channelAnchor(ctx) ?: return null
        val m = o.optJSONObject("manifests")?.optJSONObject(channel)
        if (m != null) return m.optString("sigName", "").ifBlank { null }
        if (channel == "component") return o.optString("sigName", "").ifBlank { null }
        return null
    }

    internal fun uncached(url: String): String =
        url + (if (url.indexOf('?') >= 0) "&" else "?") + "t=" + System.currentTimeMillis()

    internal fun httpGet(url: String, maxBytes: Int = MAX_FETCH_BYTES): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = FETCH_TIMEOUT_MS
        conn.readTimeout = FETCH_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP " + conn.responseCode + " " + url)
            val declared = conn.getHeaderFieldLong("Content-Length", -1L)
            if (declared > maxBytes.toLong()) {
                throw IllegalStateException("响应声明 " + declared + " 字节，超上限 " + maxBytes + "：" + url)
            }
            conn.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) {
                        throw IllegalStateException("响应超上限 " + maxBytes + " 字节：" + url)
                    }
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    internal fun channelAnchor(ctx: Context): JSONObject? = try {
        val t = ctx.assets.open(CHANNEL_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        JSONObject(t)
    } catch (e: Throwable) { null }

    internal fun anchorName(ctx: Context, field: String): String? =
        channelAnchor(ctx)?.optString(field, "")?.ifBlank { null }

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\r", "").replace("\n", "").trim()
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }

    internal fun verifyEd25519(pubPem: String, data: ByteArray, sig: ByteArray): Boolean {
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pemToDer(pubPem)))
            val v = Signature.getInstance("Ed25519")
            v.initVerify(key)
            v.update(data)
            v.verify(sig)
        } catch (e: Throwable) { false }
    }

    internal fun httpGetToFile(url: String, dest: File): Long {
        dest.parentFile?.mkdirs()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 300_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("件下载 HTTP " + code)
            }
            dest.outputStream().buffered().use { out ->
                conn.inputStream.use { it.copyTo(out, 64 * 1024) }
            }
            return dest.length()
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun sha256HexFile(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun unzipFromFile(zip: File, dest: File) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        java.util.zip.ZipFile(zip).use { zf ->
            for (e in java.util.Collections.list(zf.entries())) {
                val out = File(dest, e.name).canonicalFile
                if (out.path != destRoot.path && !out.path.startsWith(destRoot.path + File.separator)) {
                    throw IllegalArgumentException("包条目路径越界（疑似目录穿越）: " + e.name)
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins ->
                        out.outputStream().use { ins.copyTo(it, 64 * 1024) }
                    }
                    ExecBits.apply(out)
                }
            }
        }
    }

    internal fun unzipInto(zipBytes: ByteArray, dest: File) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(dest, e.name).canonicalFile
                if (out.path != destRoot.path && !out.path.startsWith(destRoot.path + File.separator)) {
                    throw IllegalArgumentException("包条目路径越界（疑似目录穿越）: " + e.name)
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    ExecBits.apply(out)
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
    }
}
