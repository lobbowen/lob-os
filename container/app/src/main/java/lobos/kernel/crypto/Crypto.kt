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

    /**
     * Android 自带的那个 KeyStore 提供者。
     * 它的 KeyFactory 只用于按 KeyGenParameterSpec 生成密钥，
     * 不用于解析 X509 公钥做验签，所以挑工厂时在挑选阶段就排除它。
     */
    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** Ed25519 在不同实现上的算法拼写，两种都试。 */
    private val ED25519_ALGORITHM_SPELLINGS = arrayOf("Ed25519", "EdDSA")

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

        // 旧读法（X509EncodedKeySpec 那一族）
        //   宿主 JVM 上它能过，Android 上它过不了。
        //   真因（07 本轮核出、Lead 本机实读复核成立）：
        //     两次尝试抛的是同一个异常
        //       （InvalidKeySpecException: To generate a key pair in Android Keystore…）
        //     根因不是读法本身：KeyFactory.getInstance("Ed25519") 在 Android 上
        //     整条路都不可用 —— generatePublic 两种 spec 都被拒。
        //     原因有两层：
        //       一层是 getInstance 不带 provider 时只取第一个提供者，
        //         在 Android 上那正是 AndroidKeyStore，它只认 Keystore 那一族；
        //       另一层是上一版修法用 p.get("KeyFactory.Ed25519") 先筛提供者，
        //         那个服务键在 Android 的提供者里查不到，于是每个提供者都被筛掉，
        //         循环体一步没走到，最后落到「默认」那一条，等于没修。
        //   判据是「验签为什么不过，必须落在『用的那条 API 在那个平台上存不存在、
        //     它有没有另一条同族的路』这一个事实上」
        //   处置：把「找哪个提供者」换成「哪个工厂真能解析我们手上这把公钥」，
        //     也就是 ed25519KeyFactories 里做的接受性验收。
        val oldSpec = try { X509EncodedKeySpec(der) } catch (_: Throwable) { null }
        if (oldSpec == null) {
            oldRoute.append("那 X509 公钥造不出来（公钥字节读不出）")
        } else {
            val oldFactories = ed25519KeyFactories(oldSpec)
            for (r in oldFactories.first) {
                try {
                    val key = r.kf.generatePublic(oldSpec)
                    val v = r.sig
                    v.initVerify(key)
                    v.update(data)
                    val ok1 = v.verify(sig)
                    oldRoute.append(
                        "那提供者[" + r.provider + "/" + r.algorithm + "]验签"
                            + (if (ok1) "**过**了" else "**不过**（签名配不上那公钥）"),
                    )
                    if (ok1) { ok = true; break }
                } catch (t: Throwable) {
                    oldRoute.append("那提供者[" + r.provider + "/" + r.algorithm + "]**红**：" + t.javaClass.name + ": " + (t.message ?: ""))
                }
            }
            if (oldFactories.first.isEmpty()) {
                // 全部试完一个都没有：报缺，把试过哪些、各自为什么失败列出来。
                // 不静默落回 getInstance("Ed25519") 那一条 —— 那一条在 Android 上必然是 AndroidKeyStore。
                oldRoute.append("没有能用的工厂。试过：" + (if (oldFactories.second.isBlank()) "（一个提供者都没有）" else oldFactories.second))
            }
        }

        //  **新**读法（ `EdECPublicKeySpec` + `EdECPoint` 那一族）
        //    **设备**逐字**才**走那一条（ 它逐字**在**那 Android 上**有**）
        //  **每一次尝试**逐字各报什么、 **第二次**的失败逐字**不**许被**第一次**盖住
        if (!ok) {
            try {
                if (der.size < 32) {
                    newRoute.append("那裸那 32 字节逐字**不够**（ DER 逐字只有 " + der.size + " 字节）")
                } else {
                    val raw = der.copyOfRange(der.size - 32, der.size)
                    // 新读法用的也是同一批挑出来的工厂。
                    // 挑选时用 X509 公钥做接受性验收，验签时再换成 EdECPublicKeySpec ——
                    // 两者的公钥是同一把，只是编码形式不同。
                    val newFactories = ed25519KeyFactories(oldSpec ?: X509EncodedKeySpec(der))
                    for (r in newFactories.first) {
                        try {
                            val key = r.kf.generatePublic(
                                EdECPublicKeySpec(NamedParameterSpec.ED25519, ed25519PointOf(raw)),
                            )
                            val v = r.sig
                            v.initVerify(key)
                            v.update(data)
                            val ok2 = v.verify(sig)
                            newRoute.append(
                                "那提供者[" + r.provider + "/" + r.algorithm + "]验签"
                                    + (if (ok2) "**过**了" else "**不过**（签名配不上那公钥）"),
                            )
                            if (ok2) { ok = true; break }
                        } catch (t: Throwable) {
                            newRoute.append("那提供者[" + r.provider + "/" + r.algorithm + "]**红**：" + t.javaClass.name + ": " + (t.message ?: ""))
                        }
                    }
                    if (newFactories.first.isEmpty()) {
                        newRoute.append("没有能用的工厂。试过：" + (if (newFactories.second.isBlank()) "（一个提供者都没有）" else newFactories.second))
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
    private data class Ed25519Factory(
        val provider: String,
        val algorithm: String,
        val kf: KeyFactory,
        val sig: Signature,
    )

    /**
     * 在一个提供者里找能用的 Signature，两个算法拼写都试。
     * 返回第一个配对的（Signature 与它用的拼写），没有就返回 null。
     */
    private fun signatureFor(p: Provider): Pair<Signature, String>? {
        for (sa in ED25519_ALGORITHM_SPELLINGS) {
            try {
                return Pair(Signature.getInstance(sa, p), sa)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * 挑出能用的 Ed25519 验签工厂。
     *
     * 判据是「这个工厂能不能解析我们真正要验的那把公钥」——
     * 我们不需要知道哪个提供者登记了 Ed25519，只需要知道哪几个工厂
     * 能拿 APK 里那把公钥做验签。所以直接把公钥的 X509 字节传进来试。
     *
     * 为什么不看服务键名：旧写法用 p.get("KeyFactory.Ed25519") 先筛一遍。
     * 那一步在 Android 上把每一个提供者都筛掉了（该服务键在那些提供者里查不到），
     * 于是循环体一步都没走到，最后落到「默认」那一条，
     * 而它解析到的正是 AndroidKeyStore ——
     * 那一族的 KeyFactory 只用于 KeyGenParameterSpec 生成密钥，
     * 拿 X509EncodedKeySpec 去生成公钥必然抛 InvalidKeySpecException。
     * 设备读数里提供者名是「默认」，就是这个原因。
     *
     * AndroidKeyStore 在挑选阶段就排除掉，不等它抛异常：
     * 它的 KeyFactory 本来就不是给「解析 X509 公钥做验签」用的，
     * 留着它只会把一个必然失败的候选混进诊断里。
     *
     * 遍历不依赖任何服务键名，所以「有可用工厂就一定能找出来」：
     * 每个提供者都试两种算法拼写，每个组合都用真公钥做一次接受性验收，
     * 能过 generatePublic 才算可用。唯一被排除的是 AndroidKeyStore。
     */
    private fun ed25519KeyFactories(spec: X509EncodedKeySpec): Pair<List<Ed25519Factory>, String> {
        val out = ArrayList<Ed25519Factory>()
        val tried = StringBuilder()
        val providers = try {
            Security.getProviders()
        } catch (_: Throwable) {
            emptyArray<Provider>()
        }
        for (p in providers) {
            // AndroidKeyStore 在挑选阶段排除：它不是给解析 X509 公钥用的
            if (p.name == ANDROID_KEYSTORE_PROVIDER) {
                tried.append(p.name).append(": 排除（它只用于生成密钥，不解析 X509 公钥）; ")
                continue
            }
            for (alg in ED25519_ALGORITHM_SPELLINGS) {
                try {
                    val kf = KeyFactory.getInstance(alg, p)
                    // 签名那一族单独找一遍：有些实现把密钥那一族与签名那一族
                    // 登记在不同的名字下（密钥那边叫 EdDSA、签名那边只叫 Ed25519），
                    // 所以签名不跟着密钥的拼写走，两个拼写都试，谁先成用谁。
                    val sigPair = signatureFor(p)
                    if (sigPair == null) {
                        tried.append(p.name).append("/").append(alg)
                            .append(": 那一族没有可用的 Signature; ")
                        continue
                    }
                    // 接受性验收：用真正要验的那把公钥试一次，过不了就跳过
                    kf.generatePublic(spec)
                    out.add(Ed25519Factory(p.name, alg + "+" + sigPair.second, kf, sigPair.first))
                    tried.append(p.name).append("/").append(alg).append("+").append(sigPair.second).append(": 可用; ")
                } catch (t: Throwable) {
                    tried.append(p.name).append("/").append(alg).append(": ")
                        .append(t.javaClass.simpleName).append(": ")
                        .append(t.message ?: "").append("; ")
                }
            }
        }
        return Pair(out, tried.toString())
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
