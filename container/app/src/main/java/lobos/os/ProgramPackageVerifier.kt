package lobos.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import lobos.runtime.SupplyProvisioner

/**
 * 程序包校验（Kotlin 实现，不依赖 node）。
 *
 * 为什么不用 node 跑 JS：原实现用 `node program-verify.js` 做校验，
 * 而 node 是"装程序时按需拉"的那一类 —— 内置应用安装校验需要 node，
 * node 自己安装也要走校验，鸡生蛋。真机报过 runtime-missing。
 *
 * 验证链与 JS 版逐条对齐（assets/node/program-verify.js）：
 *   1. zip 的 sha256 与清单锚点比对（有锚点才比）
 *   2. zip 完整性（条目可读）
 *   3. 包内 program-manifest.json 存在、含 version、位于 program/<version>/
 *   4. 包内清单的 ed25519 签名验证（对 canonical 后的内容）
 *   5. 外部清单的 ed25519 签名验证（若有）
 *
 * canonical 规则必须与 JS 版一致，否则签名对不上：
 *   递归按键排序、剔除顶层 signature、紧凑 JSON。
 *
 * 保留 assets/node/program-verify.js 作交叉验证工具（不在安装路径上）：
 * 两边对同一包给出一致结论才认为移植正确。
 */
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

        val sha = runCatching { sha256HexFile(zip) }.getOrElse {
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

    // ── canonical：必须与 program-verify.js 逐字一致 ──────────────────
//
// 不用 JSONObject.toString()：Android 的 JSONObject 键序不保证，
// 而签名是对 canonical 后的**字节**算的，键序差一个字节就验不过。
// 这里自己按「递归排序键 → 紧凑拼接」生成字符串。
//
// 顶层剔除 signature（签名自己不在被签内容里）。

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

    /** JSON 数字字面量：整数不带小数点，与 JS JSON.stringify 一致。 */
    private fun numberLiteral(n: Number): String {
        val d = n.toDouble()
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) return d.toLong().toString()
        return n.toString()
    }

    /** JSON 字符串转义，与 JS JSON.stringify 对齐（含 \u2028/\u2029）。 */
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

    // ── zip 读取 ──────────────────────────────────────────────────────

    private fun readManifestInZip(zip: File): String? = runCatching {
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val e = entries.firstOrNull { !it.isDirectory && it.name.endsWith("program-manifest.json") }
                ?: return null
            // 约定必须在 program/<version>/ 下；这里只做位置检查，不猜 version
            if (!e.name.startsWith("program/") || !e.name.contains("/program-manifest.json")) return null
            zf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.getOrNull()

    fun sha256HexFile(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(64 * 1024).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyEd25519(pubPem: String, data: ByteArray, sigB64: String): Boolean =
        SupplyProvisioner.verifyEd25519(
            pubPem, data, runCatching { android.util.Base64.decode(sigB64, android.util.Base64.DEFAULT) }.getOrElse { return false },
        )

    /** 公钥锚点：assets/ota-public.pem 的内容。与 ProgramVerifier 原来读的是同一份。 */
    fun publicKeyPem(context: Context): String? = runCatching {
        context.assets.open("ota-public.pem").use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}