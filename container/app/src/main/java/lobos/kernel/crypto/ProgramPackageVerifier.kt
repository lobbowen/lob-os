package lobos.kernel.crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object ProgramPackageVerifier {

    data class Outcome(
        val ok: Boolean,
        val version: String?,
        val reason: String?,
        val detail: String,
        val sha256: String,
    )

    private const val MAX_UNZIP = 64L * 1024 * 1024

    fun verify(
        context: Context,
        zip: File,
        expectedSha256: String?,
        externalManifest: JSONObject?,
        pubPem: String,
    ): Outcome {
        if (!zip.isFile) return Outcome(false, null, "zip-missing", "候选包不存在: ${zip.absolutePath}", "")
        if (zip.length() == 0L) return Outcome(false, null, "zip-empty", "候选包是空文件", "")
        if (zip.length() > MAX_UNZIP) {
            return Outcome(false, null, "zip-too-large", "候选包 ${zip.length()} 字节，超上限 $MAX_UNZIP", "")
        }

        val sha = runCatching { Crypto.sha256HexFile(zip) }.getOrElse {
            return Outcome(false, null, "hash-failed", "算 sha256 失败：${it.message}", "")
        }
        if (!expectedSha256.isNullOrBlank() && !sha.equals(expectedSha256.trim(), ignoreCase = true)) {
            return Outcome(
                false, null, "sha256-mismatch",
                "锚点不符：期望 ${expectedSha256.trim().take(12)} 实际 ${sha.take(12)}", sha,
            )
        }

        val manifestText = runCatching { readManifestInZip(zip) }.getOrNull()
            ?: return Outcome(
                false, null, "no-program-manifest",
                "包内找不到 program-manifest.json，或它不在 program/<version>/ 下", sha,
            )
        val manifest = runCatching { JSONObject(manifestText) }.getOrNull()
            ?: return Outcome(false, null, "program-manifest-bad", "包内清单不是合法 JSON", sha)

        val version = manifest.optString("version", "").trim()
        if (version.isBlank()) return Outcome(false, null, "no-version", "包内清单缺 version", sha)

        val sig = manifest.optString("signature", "").trim()
        if (sig.isBlank()) {
            return Outcome(false, version, "signature-missing", "包内清单无 signature 字段", sha)
        }
        val ok = verifyEd25519(pubPem, canonical(manifest).toByteArray(), sig)
        if (!ok) {
            return Outcome(false, version, "signature-invalid", "包内清单 ed25519 验签未通过", sha)
        }

        if (externalManifest != null) {
            val extSig = externalManifest.optString("signature", "").trim()
            if (extSig.isBlank()) {
                return Outcome(false, version, "manifest-signature-missing", "外部清单无 signature", sha)
            }
            val extOk = verifyEd25519(pubPem, canonical(externalManifest).toByteArray(), extSig)
            if (!extOk) {
                return Outcome(
                    false, version, "manifest-signature-invalid",
                    "外部清单 ed25519 验签未通过", sha,
                )
            }
        }

        return Outcome(true, version, null, "包内与外部清单均验签通过", sha)
    }

    fun canonical(o: JSONObject): String = buildString {
        append('{')
        val keys = o.keys().asSequence().filter { it != "signature" }.sorted().toList()
        keys.forEachIndexed { i, k ->
            if (i > 0) append(',')
            append(quote(k))
            append(':')
            append(canonicalValue(o.get(k)))
        }
        append('}')
    }

    private fun canonicalValue(v: Any?): String = when (v) {
        null -> "null"
        is JSONObject -> canonical(v)
        is JSONArray -> buildString {
            append('[')
            for (i in 0 until v.length()) {
                if (i > 0) append(',')
                append(canonicalValue(v.get(i)))
            }
            append(']')
        }
        is Boolean -> v.toString()
        is Number -> numberLiteral(v)
        else -> quote(v.toString())
    }

    private fun numberLiteral(n: Number): String {
        val d = n.toDouble()
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) return d.toLong().toString()
        return n.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ' || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun readManifestInZip(zip: File): String? = runCatching {
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val e = entries.firstOrNull { !it.isDirectory && it.name.endsWith("program-manifest.json") }
                ?: return null
            if (!e.name.startsWith("program/") || !e.name.contains("/program-manifest.json")) return null
            zf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.getOrNull()

    fun sha256HexFile(f: File): String = Crypto.sha256HexFile(f)

    private fun verifyEd25519(pubPem: String, data: ByteArray, sigB64: String): Boolean =
        Crypto.verifyEd25519(
            pubPem, data, runCatching { android.util.Base64.decode(sigB64, android.util.Base64.DEFAULT) }.getOrElse { return false },
        )

    fun publicKeyPem(context: Context): String? = runCatching {
        context.assets.open("supply/component-public.pem").use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}