package lobos.kernel.crypto

import java.math.BigInteger
import java.security.MessageDigest

object Ed25519Pure {

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    private val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    private data class Point(val x: BigInteger, val y: BigInteger)

    private val IDENTITY: Point = Point(BigInteger.ZERO, BigInteger.ONE)

    private val SQRT_M1: BigInteger = BigInteger.TWO
        .modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    fun verify(pub32: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pub32.size != 32 || sig.size != 64) return false
        try {
            val a = decodePoint(pub32) ?: return false

            val rBytes = sig.copyOfRange(0, 32)
            val sBytes = sig.copyOfRange(32, 64)
            val r = decodePoint(rBytes) ?: return false
            val s = littleEndianToBigInteger(sBytes)

            if (s >= L) return false

            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(rBytes)
            digest.update(pub32)
            digest.update(msg)
            val h = littleEndianToBigInteger(digest.digest().copyOfRange(0, 64)).mod(L)

            val left = scalarMultiply(BASE_POINT, s)
            val right = add(r, scalarMultiply(a, h))

            return encodePoint(left) contentEquals encodePoint(right)
        } catch (_: Throwable) {
            return false
        }
    }

    private fun decodePoint(enc: ByteArray): Point? {

        val yLe = enc.copyOfRange(0, 32)
        val xOdd = (yLe[31].toInt() and 0x80) != 0
        yLe[31] = (yLe[31].toInt() and 0x7f).toByte()
        val y = littleEndianToBigInteger(yLe).mod(P)

        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)

        val vInv = v.modInverse(P)
        if (vInv.signum() == 0) return null
        var x2 = u.multiply(vInv).mod(P)

        val exp = P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))
        var x = x2.modPow(exp, P)
        if (!x.multiply(x).mod(P).equals(x2)) {
            x = x.multiply(SQRT_M1).mod(P)
        }
        if (!x.multiply(x).mod(P).equals(x2)) return null

        if (x.testBit(0) != xOdd) {
            x = P.subtract(x).mod(P)
        }
        if (x.signum() == 0 && xOdd) return null
        return Point(x, y)
    }

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

    private fun double(p: Point): Point = add(p, p)

    private fun encodePoint(p: Point): ByteArray {
        val out = ByteArray(32)
        val y = p.y.mod(P)
        val be = y.toByteArray()

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

    private fun littleEndianToBigInteger(le: ByteArray): BigInteger {
        val be = ByteArray(le.size)
        for (i in le.indices) {
            be[le.size - 1 - i] = le[i]
        }
        return BigInteger(1, be)
    }

    private val BASE_POINT: Point = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
    )
}
