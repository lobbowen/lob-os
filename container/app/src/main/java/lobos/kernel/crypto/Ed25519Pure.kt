package lobos.kernel.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * 纯实现的 Ed25519 验签（RFC 8032 第 5.1.7 节）。
 *
 * 为什么要有这一份：有些 Android 设备上的提供者一个都不支持 Ed25519
 * （API 36 的那台机器上，AndroidNSSP、AndroidOpenSSL、CertPathProvider、
 * AndroidKeyStoreBCWorkaround、BC、HarmonyJSSE 全都报 NoSuchAlgorithmException），
 * 平台那一路在那种设备上根本不存在。验签是一道闸门，结果必须与设备无关，
 * 所以这一份是唯一的验签路径 —— 不走平台提供者。
 *
 * 这里只做验签，不做签名。
 *
 * 实现照 RFC 8032：
 *   公钥 32 字节，小端编码，y 是低 255 位，最高位是 x 的奇偶标志；
 *   签名 64 字节，前 32 字节是 R（点，小端编码），后 32 字节是 S（小端标量）。
 */
object Ed25519Pure {

    /** 曲线素数 p = 2^255 - 19 */
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** 曲线常数 d = -121665 / 121666 (mod p) */
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    /** 群阶 L = 2^252 + 27742317777372353535851937790883648493 */
    private val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** 曲线上一个点（仿射坐标） */
    private data class Point(val x: BigInteger, val y: BigInteger)

    /** 无穷远点 */
    private val IDENTITY: Point = Point(BigInteger.ZERO, BigInteger.ONE)

    /**
     * 域元素里的平方根 -1，也就是 2^((p-1)/4)。
     * p - 1 是正的，但这里先把它转成无符号的指数再算 ——
     * BigInteger.pow 不接受负指数，而按有符号算 (p-1)/4 会算成负数。
     */
    private val SQRT_M1: BigInteger = BigInteger.TWO
        .modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    /**
     * 验签。
     *
     * @param pub32 公钥 32 字节（小端 y，最高位是 x 的奇偶标志）
     * @param msg 被签名的消息
     * @param sig 签名 64 字节（前 32 字节 R，后 32 字节 S，小端）
     * @return 验签通过为 true
     */
    fun verify(pub32: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pub32.size != 32 || sig.size != 64) return false
        try {
            val a = decodePoint(pub32) ?: return false

            // R 与 S：R 是点，S 是标量
            val rBytes = sig.copyOfRange(0, 32)
            val sBytes = sig.copyOfRange(32, 64)
            val r = decodePoint(rBytes) ?: return false
            val s = littleEndianToBigInteger(sBytes)
            // RFC 8032 第 5.1.7 节：S 必须小于 L，否则拒绝
            if (s >= L) return false

            // h = SHA-512(R || A || M) 的前 64 字节，按小端读成整数再 mod L
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(rBytes)
            digest.update(pub32)
            digest.update(msg)
            val h = littleEndianToBigInteger(digest.digest().copyOfRange(0, 64)).mod(L)

            // 验签等式：[S]B = R + [h]A
            val left = scalarMultiply(BASE_POINT, s)
            val right = add(r, scalarMultiply(a, h))
            // 比较编码后的 32 字节，不直接比点坐标
            return encodePoint(left) contentEquals encodePoint(right)
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * 解一个 32 字节的压缩点。
     *
     * y 小端，最高位是 x 的奇偶标志；解出 x 用 x^2 = (y^2 - 1) / (d*y^2 + 1)。
     * 解不出（分母为零、或者平方根不存在）就是非法点，返回 null。
     */
    private fun decodePoint(enc: ByteArray): Point? {
        // 最高位是 x 的奇偶标志，必须先摘掉再把剩下 31 字节当 y 读 ——
        // 不先摘的话 y 会多出 2^255 那一项，后面 mod p 也救不回来（那个值不是 2^255 减掉 y）。
        val yLe = enc.copyOfRange(0, 32)
        val xOdd = (yLe[31].toInt() and 0x80) != 0
        yLe[31] = (yLe[31].toInt() and 0x7f).toByte()
        val y = littleEndianToBigInteger(yLe).mod(P)

        // y^2 - 1 与 d*y^2 + 1
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)

        // x^2 = u / v
        val vInv = v.modInverse(P)
        if (vInv.signum() == 0) return null
        var x2 = u.multiply(vInv).mod(P)

        // 平方根：x = x2^((p+3)/8)，再按需要乘 sqrt(-1)
        val exp = P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))
        var x = x2.modPow(exp, P)
        if (!x.multiply(x).mod(P).equals(x2)) {
            x = x.multiply(SQRT_M1).mod(P)
        }
        if (!x.multiply(x).mod(P).equals(x2)) return null

        // 挑与奇偶标志一致的那一个
        if (x.testBit(0) != xOdd) {
            x = P.subtract(x).mod(P)
        }
        if (x.signum() == 0 && xOdd) return null
        return Point(x, y)
    }

    /** 标量乘（仿射坐标，双倍加倍） */
    private fun scalarMultiply(p: Point, k: BigInteger): Point {
        var result = IDENTITY
        var addend = p
        var scalar = k
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) {
                result = add(result, addend)
            }
            addend = double(addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    /**
     * 点加（仿射坐标）。
     *
     * Ed25519 的曲线是 -x^2 + y^2 = 1 + d*x^2*y^2，
     * 所以加法的仿射形式是：
     *   x3 = (x1*y2 + y1*x2) / (1 + d*x1*x2*y1*y2)
     *   y3 = (y1*y2 + x1*x2) / (1 - d*x1*x2*y1*y2)
     * （曲线的 a = -1，所以 y 的分子是 y1*y2 - a*x1*x2 = y1*y2 + x1*x2）
     *
     * 无穷远点取 (0, 1)，按上面两式算出来正好是另一个点，
     * 所以这里不用单独判它。
     */
    private fun add(p: Point, q: Point): Point {
        val a = p.x.multiply(q.x).mod(P)
        val b = p.y.multiply(q.y).mod(P)
        val c = D.multiply(a).multiply(b).mod(P)
        val e = p.x.multiply(q.y).add(p.y.multiply(q.x)).mod(P)
        val g = BigInteger.ONE.add(c).mod(P)
        val f = BigInteger.ONE.subtract(c).mod(P)
        val gInv = g.modInverse(P)
        if (gInv.signum() == 0) return IDENTITY
        val fInv = f.modInverse(P)
        if (fInv.signum() == 0) return IDENTITY
        val x3 = e.multiply(gInv).mod(P)
        val y3 = b.add(a).multiply(fInv).mod(P)
        return Point(x3, y3)
    }

    /** 点倍：直接用点加算，避免再写一套公式出错 */
    private fun double(p: Point): Point = add(p, p)

    /** 编码成 32 字节（小端 y，最高位放 x 的奇偶） */
    private fun encodePoint(p: Point): ByteArray {
        val out = ByteArray(32)
        val y = p.y.mod(P)
        val be = y.toByteArray()
        // 小端：把大端字节数组倒过来，去掉可能的前导 0
        var start = 0
        while (start < be.size && be[start].toInt() == 0) start++
        for (i in 0 until 32) {
            val idx = be.size - 1 - i
            out[i] = if (idx >= start) be[idx] else 0
        }
        if (p.x.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    /** 小端字节数组转成大整数 */
    private fun littleEndianToBigInteger(le: ByteArray): BigInteger {
        val be = ByteArray(le.size)
        for (i in le.indices) {
            be[le.size - 1 - i] = le[i]
        }
        return BigInteger(1, be)
    }

    /** 基点 B：x = 15112221349535400772501151409588531511454012693041857206046113283949847762202，y = 46316835694926478169428394003475163141307993866256225615783033603165251855960 */
    private val BASE_POINT: Point = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
    )
}
