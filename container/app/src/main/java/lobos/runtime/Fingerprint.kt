package lobos.runtime

import java.io.File
import java.security.MessageDigest

/**
 * 内容指纹 —— 当一件没有声明版本时，落位目录名用它。
 *
 * 为什么要兜底：落位目录名就是版本（`usr/lib/<id>/<版本>/`），
 * 而版本要由构建期写定。万一某件漏了声明版本，目录名不能空 ——
 * 用它文件内容的 sha256 前 12 位，客观可验、变了就会变。
 */
object Fingerprint {

    fun of(f: File): String = runCatching {
        if (!f.isFile) return@runCatching ""
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }.getOrDefault("")
}
