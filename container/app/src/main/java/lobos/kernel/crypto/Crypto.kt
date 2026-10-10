package lobos.kernel.crypto

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 内核只提供两种事实：一份字节的摘要，以及一份签名的真假。
 * 它不知道被摘要的是什么、被签的是哪份清单 —— 那是上层的事。
 */
object Crypto {

    /** 文件的 SHA-256，小写十六进制。流式读，不把整个文件装进内存。 */
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

    /** PEM 公钥验 Ed25519 签名。密钥格式、算法不支持或签名不符，一律 false。 */
    fun verifyEd25519(pubPem: String, data: ByteArray, sig: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pemToDer(pubPem)))
        val v = Signature.getInstance("Ed25519")
        v.initVerify(key)
        v.update(data)
        v.verify(sig)
    } catch (_: Throwable) { false }

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\r", "").replace("\n", "").trim()
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }
}
