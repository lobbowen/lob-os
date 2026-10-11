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

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\r", "").replace("\n", "").trim()
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }
}
