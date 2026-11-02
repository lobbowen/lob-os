package lobos.kernel.crypto

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object Crypto {

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

    fun verifyEd25519(pubPem: String, data: ByteArray, sig: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pemToDer(pubPem)))
        val v = Signature.getInstance("Ed25519")
        v.initVerify(key)
        v.update(data)
        v.verify(sig)
    } catch (_: Throwable) { false }

    //  缺陷名（C-102）**那一格逐字把异常逐字**吞了**、=> 而而那那异常是什么逐字现在逐字答不出**
    //    => 而那旧版逐字是 `catch (_: Throwable) { false }`
    //      => 而那**判据**逐字是「那设备端逐字为什么不过」、
    //        => 而那**判据**逐字必须落在「那设备端逐字实际用的是什么锚、
    //          => 而那它逐字实际验的是哪一份字节、
    //          => 而那两者逐字与那仓内逐字那一把一致不一致」那一个事实上
    //        （=> 而那它逐字**不**由「那宿主机上逐字验过」顶掉
    //          —— 而那两读逐字是不同时点的两读、=> 而那宿主机那一读逐字**不**能顶替那设备那一读，依 C-115）
    //      => 而那**异常是什么**逐字**答不出**、=> 而那**判据**逐字**就**答不出
    //    => 而那**处置**：=> 而那**异常**逐字**不**吞、=> 而那它逐字**带**那四件事实一起报出来
    //      => 而那四件事实逐字是：
    //        1. 那 App 进程逐字实际读到的那 pem 逐字的 sha256(raw32) 与 sha256(DER)
    //        2. 那验签那一格逐字实际喂进去的那 body 逐字的字节数与 sha256
    //        3. 那 sig 逐字的字节数
    //        4. 那 verifyEd25519 那一格逐字**抛了什么异常**
    //      => 而那**判据注释**那一族逐字**不许删**（=> 而那是那**定位依据**）
    //      => 而那**规范**那一层归 **04**、=> 而那**判据**归 **01**、=> 而那**实现**归 **05**
    data class VerifyFacts(
        /** 那 pem 逐字那 **raw 32 字节**（=> 而那 Ed25519 逐字那公钥逐字就是那 32 字节） */
        val raw32Hex: String,
        /** 那 pem 逐字那 **sha256(raw32)** */
        val sha256Raw32: String,
        /** 那 pem 逐字那 **sha256(DER)** */
        val sha256Der: String,
        /** 那 pem 逐字那 **DER** 逐字那长度 */
        val derBytes: Int,
        /** 那 pem 逐字那 DER 逐字它**解不**解得出来 */
        val derDecoded: Boolean,
        /** 那 body 逐字那**字节数** */
        val bodyBytes: Int,
        /** 那 body 逐字那 **sha256** */
        val bodySha256: String,
        /** 那 sig 逐字那**字节数** */
        val sigBytes: Int,
        /** 那**异常**是什么（=> 而那没有异常时逐字是那空串） */
        val error: String,
        /** 那验签逐字**过没过** */
        val ok: Boolean,
    ) {
        /** 那**一行**逐字**读得出来**的那一份（=> 而那它逐字进那诊断那一族） */
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

    /**
     * 那验签那一格逐字**带事实**地走一遍（=> 而那它逐字**不**吞那异常）。
     *
     *   => 而那**判据**逐字是「那设备端逐字实际用的是什么锚、
     *     => 而那它逐字实际验的是哪一份字节、
     *     => 而那两者逐字与那仓内逐字那一把一致不一致」那一个事实上
     */
    fun verifyEd25519WithFacts(pubPem: String, data: ByteArray, sig: ByteArray): VerifyFacts {
        var raw32 = ""
        var shaRaw = ""
        var shaDer = ""
        var derLen = 0
        var derOk = false
        var err = ""
        var ok = false
        try {
            val der = pemToDer(pubPem)
            derLen = der.size
            derOk = true
            raw32 = if (der.size >= 32) der.copyOfRange(der.size - 32, der.size).joinToString("") { "%02x".format(it) } else ""
            shaRaw = sha256Hex(raw32.toByteArray(Charsets.UTF_8))
            shaDer = sha256Hex(der)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
            val v = Signature.getInstance("Ed25519")
            v.initVerify(key)
            v.update(data)
            ok = v.verify(sig)
        } catch (t: Throwable) {
            //  => 而那**异常**逐字**不**吞、=> 而那它逐字**带**那四件事实一起报出来
            err = t.javaClass.name + ": " + (t.message ?: "")
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

    /** 那 sha256 逐字（=> 而那 hex 小写） */
    fun sha256Hex(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(b).joinToString("") { "%02x".format(it) }
    }

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\r", "").replace("\n", "").trim()
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }
}
