package lobos.kernel.crypto

import java.io.File
import java.security.MessageDigest

object Crypto {

    fun verifyEd25519(pubPem: String, data: ByteArray, sig: ByteArray): Boolean {
        return try {
            val der = pemToDer(pubPem)
            if (der.size < 32) false
            else {
                val raw = der.copyOfRange(der.size - 32, der.size)
                Ed25519Pure.verify(raw, data, sig)
            }
        } catch (_: Throwable) { false }
    }

    data class VerifyFacts(

        val raw32Hex: String,

        val sha256Raw32: String,

        val sha256Der: String,

        val derBytes: Int,

        val derDecoded: Boolean,

        val bodyBytes: Int,

        val bodySha256: String,

        val sigBytes: Int,

        val error: String,

        val ok: Boolean,
    ) {

        fun oneLine(): String =
            "ok=" + ok +
                " pem.raw32=" + raw32Hex +
                " pem.sha256Raw32=" + sha256Raw32 +
                " pem.sha256Der=" + sha256Der +
                " pem.derBytes=" + derBytes +
                " pem.derDecoded=" + derDecoded +
                " body.bytes=" + bodyBytes +
                " body.sha256=" + bodySha256 +
                " sig.bytes=" + sigBytes +
                " error=" + (error.ifBlank { "(无)" })
    }

    fun verifyEd25519WithFacts(pubPem: String, data: ByteArray, sig: ByteArray): VerifyFacts {
        var raw32 = ""
        var shaRaw = ""
        var shaDer = ""
        var derLen = 0
        var derOk = false
        var ok = false

        val oldRoute = StringBuilder()

        val newRoute = StringBuilder()

        var der: ByteArray = ByteArray(0)
        try {
            der = pemToDer(pubPem)
            derLen = der.size
            derOk = true
            raw32 = if (der.size >= 32) der.copyOfRange(der.size - 32, der.size).joinToString("") { "%02x".format(it) } else ""
            shaRaw = sha256Hex(raw32.toByteArray(Charsets.UTF_8))
            shaDer = sha256Hex(der)
        } catch (t: Throwable) {

            oldRoute.append("pem 那解码就**失败**了：" + t.javaClass.name + ": " + (t.message ?: ""))
        }

        val raw32Bytes = if (der.size >= 32) der.copyOfRange(der.size - 32, der.size) else ByteArray(0)
        if (raw32Bytes.size == 32) {
            try {
                val ok1 = Ed25519Pure.verify(raw32Bytes, data, sig)
                oldRoute.append(
                    if (ok1) "那验签**过**了"
                    else "那验签**不过**（签名配不上那公钥）",
                )
                if (ok1) ok = true
            } catch (t: Throwable) {
                oldRoute.append(t.javaClass.name + ": " + (t.message ?: ""))
            }
        } else {
            oldRoute.append("那公钥不是 32 字节（读到 " + raw32Bytes.size + " 字节），没法验签")
        }

        if (!ok) {
            try {
                if (der.size < 32) {
                    newRoute.append("那裸那 32 字节**不够**（DER 只有 " + der.size + " 字节）")
                } else {
                    val raw = der.copyOfRange(der.size - 32, der.size)
                    val ok2 = Ed25519Pure.verify(raw, data, sig)
                    newRoute.append(
                        if (ok2) "那验签**过**了"
                        else "那验签**不过**（签名配不上那公钥）",
                    )
                    if (ok2) ok = true
                }
            } catch (t: Throwable) {
                newRoute.append(t.javaClass.name + ": " + (t.message ?: ""))
            }
        } else {

            newRoute.append("那验签**过**了（与旧读法同一个纯实现的结果）")
        }

        val err = buildString {
            append("旧读法[").append(oldRoute).append("]")
            append(" 新读法[").append(newRoute).append("]")
        }
        return VerifyFacts(
            raw32Hex = raw32,
            sha256Raw32 = shaRaw,
            sha256Der = shaDer,
            derBytes = derLen,
            derDecoded = derOk,
            bodyBytes = data.size,
            bodySha256 = sha256Hex(data),
            sigBytes = sig.size,
            error = err,
            ok = ok,
        )
    }

    fun sha256Hex(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(b).joinToString("") { "%02x".format(it) }
    }

    fun sha256HexFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
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

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\r", "").replace("\n", "").trim()
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }
}
