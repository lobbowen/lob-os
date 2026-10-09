package lobos.os

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ElfFacts {

    data class Dynamic(
        val needed: List<String>,
        val runPath: String?,
        val interp: String?,
    )

    private const val DT_NEEDED = 1
    private const val DT_STRTAB = 5
    private const val DT_STRSZ = 10
    private const val DT_RPATH = 15
    private const val DT_RUNPATH = 29
    private const val PT_INTERP = 3

    // 用 try/catch 而不是 runCatching { … }：lambda 里的 `return null` 是**非局部返回**，
    // 编译器无法据此推断 lambda 的返回类型（报 "Return type mismatch: expected
    // \"ElfFacts.Dynamic?\", actual \"Any?\""）。这里有 7 处 `return null`。
    // 语义与 runCatching 相同（吞掉异常、返回 null），但类型是明确的。
    fun read(file: File): Dynamic? = try {
        val b = file.readBytes()
        if (b.size < 64) return null
        // b[] 是 Byte、字面量是 Int —— Kotlin 不允许直接比（报
        // "Operator != cannot be applied to Byte and Int"），两边都转成 Int 比。
        fun magic(i: Int): Int = b[i].toInt() and 0xff
        if (magic(0) != 0x7f || magic(1) != 'E'.code || magic(2) != 'L'.code || magic(3) != 'F'.code) return null
        if (magic(4) != 2) return null
        if (magic(5) != 1) return null

        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        val phOff = buf.getLong(0x20).toInt()
        val phEnt = buf.getShort(0x36).toInt() and 0xffff
        val phNum = buf.getShort(0x38).toInt() and 0xffff

        var dynFileOff = -1L
        var dynFileSize = 0L
        var interp: String? = null

        for (i in 0 until phNum) {
            val o = phOff + i * phEnt
            if (o + 56 > b.size) break
            buf.position(o)
            val type = buf.int
            val fileOff = buf.getLong(8)
            val fileSize = buf.getLong(32)
            when (type) {
                2 -> { dynFileOff = fileOff; dynFileSize = fileSize }
                PT_INTERP -> {
                    if (fileOff in 0 until b.size) {
                        var e = fileOff.toInt()
                        while (e < b.size && b[e] != 0.toByte()) e += 1
                        interp = String(b, fileOff.toInt(), e - fileOff.toInt(), Charsets.UTF_8)
                    }
                }
            }
        }
        if (dynFileOff <= 0) return null

        var strtabVaddr = -1L
        var strsz = 0L
        val neededV = mutableListOf<Long>()
        var runPathV = -1L
        var rpathV = -1L

        var p = dynFileOff
        val dynEnd = dynFileOff + dynFileSize
        while (p + 16 <= dynEnd && p + 16 <= b.size) {
            buf.position(p.toInt())
            val tag = buf.getLong()
            // 原本写成 `val val` —— val 是 Kotlin 关键字，不能当变量名
            val dval = buf.getLong()
            if (tag == 0L) break
            when (tag) {
                DT_NEEDED.toLong() -> neededV += dval
                DT_STRTAB.toLong() -> strtabVaddr = dval
                DT_STRSZ.toLong() -> strsz = dval
                DT_RUNPATH.toLong() -> runPathV = dval
                DT_RPATH.toLong() -> rpathV = dval
            }
            p += 16
        }
        if (strtabVaddr < 0) return null

        val strtabOff = vaddrToOffset(b, buf, phOff, phEnt, phNum, strtabVaddr) ?: return null

        val cstr = label@ { off: Long ->
            if (strtabOff + off >= b.size) return@label ""
            var e = (strtabOff + off).toInt()
            val s = e
            while (e < b.size && b[e] != 0.toByte()) e += 1
            String(b, s, e - s, Charsets.UTF_8)
        }

        val needed = neededV.mapNotNull { cstr(it).ifBlank { null } }
        val rp = if (runPathV >= 0) cstr(runPathV) else if (rpathV >= 0) cstr(rpathV) else null
        val origin = rp?.contains("\$ORIGIN") == true

        Dynamic(needed, rp, interp)
    } catch (_: Throwable) {
        null
    }

    private fun vaddrToOffset(
        b: ByteArray, buf: ByteBuffer, phOff: Int, phEnt: Int, phNum: Int, vaddr: Long,
    ): Long? {
        for (i in 0 until phNum) {
            val o = phOff + i * phEnt
            if (o + 56 > b.size) break
            buf.position(o)
            val type = buf.int
            if (type != 1) continue
            val off = buf.getLong(8)
            val v = buf.getLong(16)
            val fileSz = buf.getLong(32)
            val memSz = buf.getLong(40)
            if (vaddr in v until v + memSz) return off + (vaddr - v)
            if (fileSz == 0L && memSz > 0) continue
        }
        return null
    }
}