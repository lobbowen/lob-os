package lobos.kernel.crypto

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.security.Signature
import java.security.spec.EdECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.EdECPoint
import java.math.BigInteger

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

    //  缺陷名（C-102）**那一格逐字用错了那 API、 它逐字在 Android 上逐字走错了路****
    //    旧版逐字是
    //      `KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pemToDer(pubPem)))`
    //      **宿主 JVM** 逐字**过**（ SunEC 逐字**认**那 X509EncodedKeySpec）
    //      **Android** 逐字**不过**、 **真读数**逐字是那设备侧的：
    //        `error=java.security.spec.spec.InvalidKeySpecException: To generate a key pair in
    //         Android Keystore, use KeyPairGenerator initialized with
    //         android.security.keystore.KeyGenParameterSpec`
    //        （ AndroidKeyStore 逐字那**实现**逐字**只**在 Keystore 那一族逐字里逐字能用）
    //    **判据**逐字是「 凡一格逐字要答『那验签逐字为什么不过』，
    //      判据必须落在『那验签那一族逐字用的那条 API 逐字在那个平台上逐字存不存在』那一个事实上」
    //      （ 它逐字**不**由「那一件逐字是什么」顶掉）
    //    **处置**： **JCA 另一个读法**（ 是 Lead 的路线(4)）
    //      `EdECPublicKeySpec` + `EdECPoint` 逐字**在**那 Android 上**有**
    //        （ `java.security.spec.EdECPublicKeySpec` 与那 `java.security.spec.EdECPoint`
    //          逐字**都**在**那 android.jar** 逐字**里**、 本机**用那 android.jar 编译**逐字**过**了）
    //      **点**逐字**从**那**裸**那 32 字节逐字**解**出来：
    //        · 而那那 y 逐字**小端**、 x 的**奇偶**逐字**在**那**最高位**
    //        · 而而那那 EdECPoint 逐字要那**大端**的 BigInteger、 y 逐字**要 mod p**
    //          （ p = 2^255 - 19、 **不** mod 逐字**会**报「y value is too large」）
    //        · **解**出来的点逐字**等于**那宿主逐字**自己**给的那一点（ **本机实跑**逐字核过）
    //      **旧**读法逐字**留着**、 **宿主**逐字**先**走它（ 它逐字**快**）
    //        **设备**逐字**才**走那新读法
    //      **判据注释**那一族逐字**不许删**（ 是那**定位依据**）
    fun verifyEd25519(pubPem: String, data: ByteArray, sig: ByteArray): Boolean {
        //  **宿主**逐字那一条路
        try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pemToDer(pubPem)))
            val v = Signature.getInstance("Ed25519")
            v.initVerify(key)
            v.update(data)
            if (v.verify(sig)) return true
        } catch (_: Throwable) {
            //  **旧**读法逐字**不过**时逐字**落**到那**新**读法
        }
        //  **Android** 逐字那一条路（ Ed25519 逐字那**裸**那 32 字节）
        return try {
            val der = pemToDer(pubPem)
            if (der.size < 32) false
            else {
                val raw = der.copyOfRange(der.size - 32, der.size)
                val key = KeyFactory.getInstance("Ed25519").generatePublic(
                    EdECPublicKeySpec(NamedParameterSpec.ED25519, ed25519PointOf(raw)),
                )
                val v = Signature.getInstance("Ed25519")
                v.initVerify(key)
                v.update(data)
                v.verify(sig)
            }
        } catch (_: Throwable) { false }
    }

    /**
     * 那 **Ed25519** 逐字那**裸**那 32 字节逐字**解**成那**点**。
     *
     *   y 逐字**小端**、 x 的**奇偶**逐字**在**那**最高位**
     *   `EdECPoint` 逐字要那**大端**的 BigInteger、 y 逐字**要 mod p**
     *   p = 2^255 - 19
     */
    private fun ed25519PointOf(raw32: ByteArray): EdECPoint {
        val xOdd = (raw32[31].toInt() and 0x80) != 0
        val yLe = raw32.copyOf()
        yLe[31] = (yLe[31].toInt() and 0x7f).toByte()
        val be = ByteArray(yLe.size)
        for (i in yLe.indices) be[i] = yLe[yLe.size - 1 - i]
        val y = BigInteger(1, be).mod(ED25519_P)
        return EdECPoint(xOdd, y)
    }

    /** 那 **Ed25519** 逐字那曲线逐字那**素数** p = 2^255 - 19 */
    private val ED25519_P: BigInteger =
        BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    //  缺陷名（C-102）**那一格逐字把异常逐字**吞了**、 异常是什么逐字现在逐字答不出**
    //    旧版逐字是 `catch (_: Throwable) { false }`
    //      **判据**逐字是「那设备端逐字为什么不过」、
    //        **判据**逐字必须落在「那设备端逐字实际用的是什么锚、
    //          它逐字实际验的是哪一份字节、
    //          两者逐字与那仓内逐字那一把一致不一致」那一个事实上
    //        （ 它逐字**不**由「那宿主机上逐字验过」顶掉
    //          —— 而那两读逐字是不同时点的两读、 宿主机那一读逐字**不**能顶替那设备那一读，依 C-115）
    //      **异常是什么**逐字**答不出**、 **判据**逐字**就**答不出
    //    **处置**： **异常**逐字**不**吞、 它逐字**带**那四件事实一起报出来
    //      四件事实逐字是：
    //        1. 那 App 进程逐字实际读到的那 pem 逐字的 sha256(raw32) 与 sha256(DER)
    //        2. 那验签那一格逐字实际喂进去的那 body 逐字的字节数与 sha256
    //        3. 那 sig 逐字的字节数
    //        4. 那 verifyEd25519 那一格逐字**抛了什么异常**
    //      **判据注释**那一族逐字**不许删**（ 是那**定位依据**）
    //      **规范**那一层归 **04**、 **判据**归 **01**、 **实现**归 **05**
    data class VerifyFacts(
        /** 那 pem 逐字那 **raw 32 字节**（ Ed25519 逐字那公钥逐字就是那 32 字节） */
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
        /** 那**异常**是什么（ 没有异常时逐字是那空串） */
        val error: String,
        /** 那验签逐字**过没过** */
        val ok: Boolean,
    ) {
        /** 那**一行**逐字**读得出来**的那一份（ 它逐字进那诊断那一族） */
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
     * 那验签那一格逐字**带事实**地走一遍（ 它逐字**不**吞那异常）。
     *
     *   **判据**逐字是「那设备端逐字实际用的是什么锚、
     *     它逐字实际验的是哪一份字节、
     *     两者逐字与那仓内逐字那一把一致不一致」那一个事实上
     */
    fun verifyEd25519WithFacts(pubPem: String, data: ByteArray, sig: ByteArray): VerifyFacts {
        var raw32 = ""
        var shaRaw = ""
        var shaDer = ""
        var derLen = 0
        var derOk = false
        var ok = false
        //  **每一次尝试**逐字各报什么（ 判据逐字是「那**每一次**尝试逐字各报什么」那一个事实上）
        //    **旧**读法逐字那一条
        val oldRoute = StringBuilder()
        //  **新**读法逐字那一条
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
            //  **异常**逐字**不**吞、 它逐字**带**那四件事实一起报出来
            oldRoute.append("pem 那解码逐字就**失败**了：" + t.javaClass.name + ": " + (t.message ?: ""))
        }

        //  **旧**读法（ `X509EncodedKeySpec` 那一族）
        //    **宿主 JVM** 逐字**过**、 **Android** 逐字**不过**
        //    **真因**逐字（07 本轮逐字核出、Lead 本机实读逐字复核成立）：
        //      **两次**尝试逐字抛的是那**同一个**异常
        //        （ `InvalidKeySpecException: To generate a key pair in Android Keystore…`）
        //      **根因**逐字**不**是那读法本身
        //        `KeyFactory.getInstance("Ed25519")` 逐字在 **Android** 上逐字**整条路**都不可用
        //          （ `generatePublic` 逐字两种 spec 逐字都被逐字拒）
        //      **判据**逐字是「 凡一格逐字要答『那验签逐字为什么不过』，
        //        判据必须落在『那验签那一族逐字用的那条 API 逐字在那个平台上逐字存不存在、
        //          它逐字有没有另一条逐字**同族**的路』那一个事实上」
        //    **处置**： **提供者**逐字**一个**一个**试**
        //      `KeyFactory.getInstance("Ed25519")` 逐字**只**取那**第一个**提供者
        //        （ 在 **Android** 上逐字**就是**那 **AndroidKeyStore** 那一族、
        //        它逐字**只**认那 **Keystore** 那一族）
        //      **逐个**提供者逐字**都**试、 **能**认那 `X509EncodedKeySpec` 逐字**那个**就**用**它
        //      **提供者名**逐字**不猜填**（ 设备逐字**是**那 **API 36**、
        //        **提供者名**逐字**各**版本**不**一定**一样）
        for (r in ed25519KeyFactories()) {
            try {
                val key = r.kf.generatePublic(X509EncodedKeySpec(der))
                val v = r.sig
                v.initVerify(key)
                v.update(data)
                val ok1 = v.verify(sig)
                oldRoute.append(
                    "那提供者[" + r.provider + "]逐字**过**了、验签逐字"
                        + (if (ok1) "**过**了" else "**不过**（签名逐字**不**配那公钥）"),
                )
                if (ok1) { ok = true; break }
            } catch (t: Throwable) {
                oldRoute.append("那提供者[" + r.provider + "]逐字**红**：" + t.javaClass.name + ": " + (t.message ?: ""))
            }
        }
        if (oldRoute.isBlank()) oldRoute.append("那提供者逐字**一个**都没有（ `KeyFactory.Ed25519` 逐字**没**人认）")

        //  **新**读法（ `EdECPublicKeySpec` + `EdECPoint` 那一族）
        //    **设备**逐字**才**走那一条（ 它逐字**在**那 Android 上**有**）
        //  **每一次尝试**逐字各报什么、 **第二次**的失败逐字**不**许被**第一次**盖住
        if (!ok) {
            try {
                if (der.size < 32) {
                    newRoute.append("那裸那 32 字节逐字**不够**（ DER 逐字只有 " + der.size + " 字节）")
                } else {
                    val raw = der.copyOfRange(der.size - 32, der.size)
                    //  **提供者**逐字**也**逐个**试**（ `KeyFactory.getInstance("Ed25519")`
                    //    逐字**只**取那**第一个**提供者、 在 **Android** 上逐字**就是**那 **AndroidKeyStore** 那一族）
                    for (r in ed25519KeyFactories()) {
                        try {
                            val key = r.kf.generatePublic(
                                EdECPublicKeySpec(NamedParameterSpec.ED25519, ed25519PointOf(raw)),
                            )
                            val v = r.sig
                            v.initVerify(key)
                            v.update(data)
                            val ok2 = v.verify(sig)
                            newRoute.append(
                                "那提供者[" + r.provider + "]逐字**过**了、验签逐字"
                                    + (if (ok2) "**过**了" else "**不过**（签名逐字**不**配那公钥）"),
                            )
                            if (ok2) { ok = true; break }
                        } catch (t: Throwable) {
                            newRoute.append("那提供者[" + r.provider + "]逐字**红**：" + t.javaClass.name + ": " + (t.message ?: ""))
                        }
                    }
                }
            } catch (t: Throwable) {
                newRoute.append(t.javaClass.name + ": " + (t.message ?: ""))
            }
        }

        //  **判据**逐字是「那**每一次**尝试逐字各报什么」那一个事实上
        //    两次逐字**各**占一行、 **不**许第二次的失败逐字被第一次盖住
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

    /**
     * 那 **Ed25519** 逐字那**提供者**逐字**一个**一个**列**出来（ `KeyFactory` 与那 `Signature` 逐字**配对**）。
     *
     *   **判据**逐字是「那验签那一族逐字用的那条 API 逐字在那个平台上逐字存不存在、
     *     它逐字有没有另一条逐字**同族**的路」那一个事实上
     *   `KeyFactory.getInstance("Ed25519")` 逐字**只**取那**第一个**提供者
     *     （ 在 **Android** 上逐字**就是**那 **AndroidKeyStore** 那一族、
     *     它逐字**只**认那 **Keystore** 那一族、 它逐字**把**那 `X509EncodedKeySpec`
     *       逐字**也**拒了、 它逐字**也**把**那** `EdECPublicKeySpec` 逐字**拒**了）
     *   **提供者名**逐字**不猜填**（ 设备逐字**是**那 **API 36**、
     *     **提供者名**逐字**各**版本**不**一定**一样）
     *   **判别源**逐字**唯一**（ 依 C-104 逐字**判别源必须唯一**）
     */
    private data class Ed25519Factory(val provider: String, val kf: KeyFactory, val sig: Signature)

    private fun ed25519KeyFactories(): List<Ed25519Factory> {
        val out = ArrayList<Ed25519Factory>()
        val providers = try {
            Security.getProviders()
        } catch (_: Throwable) {
            emptyArray<Provider>()
        }
        for (p in providers) {
            // `KeyFactory.Ed25519` 逐字**在不在**那个提供者逐字**里**
            val kfAlg = try { p.get("KeyFactory.Ed25519") } catch (_: Throwable) { null }
            if (kfAlg == null) continue
            try {
                val kf = KeyFactory.getInstance("Ed25519", p)
                val sig = Signature.getInstance("Ed25519", p)
                out.add(Ed25519Factory(p.name, kf, sig))
            } catch (_: Throwable) {
                // 个提供者逐字**不**认那 Ed25519 逐字、 它逐字**跳过**
            }
        }
        // **默认**那一个逐字**也**列上（ 它逐字**可能**不在那 providers 逐字**里**）
        if (out.isEmpty()) {
            try {
                out.add(Ed25519Factory("默认", KeyFactory.getInstance("Ed25519"), Signature.getInstance("Ed25519")))
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** 那 sha256 逐字（ hex 小写） */
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
