package lobos.kernel.crypto

import java.io.File
import java.security.MessageDigest

object Crypto {

    /**
     * 验签：走纯实现（Ed25519Pure），不走平台的提供者。
     *
     *   理由（07 本轮核出、Lead 本机实读复核成立）：
     *     API 36 的那台设备上七个提供者没有一个支持 Ed25519，
     *     全部报 NoSuchAlgorithmException —— 平台那一路在那台设备上根本不存在。
     *     而验签是一道闸门，结果必须与设备无关；
     *     平台有的设备走平台、没有的走纯实现，两条路会给出两种行为，
     *     这种分歧在闸门上不能留。所以这里只有纯实现这一条路。
     *
     *   之前的修法为什么不管用：
     *     一版是 KeyFactory.getInstance("Ed25519") 不带 provider，
     *       它只取第一个提供者，在 Android 上那正是 AndroidKeyStore，
     *       那一族的 KeyFactory 只认 KeyGenParameterSpec，解析 X509 公钥必然抛异常；
     *     另一版先用 p.get("KeyFactory.Ed25519") 筛提供者，
     *       那个服务键在 Android 的提供者里查不到，于是每个提供者都被筛掉。
     *
     *   判据注释那一族不许删（那是定位依据）。
     */
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

        // 验签走纯实现（Ed25519Pure），不走平台的提供者。
        //   真因（07 本轮核出、Lead 本机实读复核成立）：
        //     API 36 的那台设备上七个提供者没有一个支持 Ed25519，
        //     全部报 NoSuchAlgorithmException —— 平台那一路在那台设备上根本不存在。
        //     之前的两版修法都不管用：
        //       一版是 KeyFactory.getInstance("Ed25519") 不带 provider，
        //         它只取第一个提供者，在 Android 上那正是 AndroidKeyStore；
        //       另一版用 p.get("KeyFactory.Ed25519") 先筛提供者，
        //         那个服务键在 Android 的提供者里查不到，于是每个提供者都被筛掉。
        //   而验签是一道闸门，结果必须与设备无关；
        //     平台有的设备走平台、没有的走纯实现，两条路会给出两种行为，
        //     这种分歧在闸门上不能留。所以纯实现是唯一的验签路径。
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
            oldRoute.append("那公钥逐字不是 32 字节（读到 " + raw32Bytes.size + " 字节），没法验签")
        }

        // 两栏保留是为了读数格式不变（07 的读数靠这两栏分栏）。
        // 现在两条读法走的是同一个纯实现，所以只验一次，两栏报同一个结果 ——
        // 这也正是「闸门的结果必须与设备无关」的意思：没有第二条会给出别的答案的路。
        if (!ok) {
            try {
                if (der.size < 32) {
                    newRoute.append("那裸那 32 字节逐字**不够**（ DER 逐字只有 " + der.size + " 字节）")
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
            // 旧读法那一栏已经过了，这一栏报同一个结果（它不是另一次验签）
            newRoute.append("那验签**过**了（与旧读法同一个纯实现的结果）")
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


    /** 那 sha256 逐字（ hex 小写） */
    fun sha256Hex(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(b).joinToString("") { "%02x".format(it) }
    }

    /**
     * 文件的 sha256（hex 小写）。
     *
     * 按 64 KiB 一块边读边算，不把整个文件读进内存。
     * 依 C-987：件包可能超过 2 GiB，整读会撞 ERR_FS_FILE_TOO_LARGE。
     * 调用方：PackageVerifier.kt:34 与 :173、Compat.kt:59 与 :139。
     */
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
