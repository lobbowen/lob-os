package lobos.os

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ELF 动态段读取。
 *
 * 判据来源：Linux 的包管理不要求包声明依赖 —— `ldd` 直接读 ELF 的 `DT_NEEDED`，
 * `ld.so` 直接读 `DT_RUNPATH`/`DT_RPATH` 决定去哪些目录找。包本身带着全部信息。
 *
 * 我们之前把这件事做成了「node 专用后门」（`PrefixProvisioner.placeNodeDeps`），
 * 那是补丁不是规范：换个带 `$ORIGIN` 的二进制还要再写一遍。
 *
 * 所以这里把同一件事做成通用能力：**装任何二进制包时读它自己的段，
 * 不认识"node"这个词。**
 *
 * 只解析 64 位小端 Android/arm64 这一种 —— 商店里的件都是这个形态。
 * 解析失败返回空，由调用方按"读不到段"处理（不猜、不硬编码）。
 */
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

    fun read(file: File): Dynamic? = runCatching {
        val b = file.readBytes()
        if (b.size < 64) return null
        if (b[0] != 0x7f || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        if (b[4] != 2) return null          // 只支持 64 位
        if (b[5] != 1) return null          // 只支持小端

        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        // ── program headers：找 PT_DYNAMIC 与 PT_INTERP ──
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
                2 -> { dynFileOff = fileOff; dynFileSize = fileSize }   // PT_DYNAMIC
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

        // ── dynamic entries ──
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
            val val = buf.getLong()
            if (tag == 0L) break
            when (tag) {
                DT_NEEDED.toLong() -> neededV += val
                DT_STRTAB.toLong() -> strtabVaddr = val
                DT_STRSZ.toLong() -> strsz = val
                DT_RUNPATH.toLong() -> runPathV = val
                DT_RPATH.toLong() -> rpathV = val
            }
            p += 16
        }
        if (strtabVaddr < 0) return null

        // vaddr → 文件偏移：按 PT_LOAD 段换算（PIE 下不等于 vaddr）
        val strtabOff = vaddrToOffset(b, buf, phOff, phEnt, phNum, strtabVaddr) ?: return null

        val cstr = { off: Long ->
            if (strtabOff + off >= b.size) return@runCatching ""
            var e = (strtabOff + off).toInt()
            val s = e
            while (e < b.size && b[e] != 0.toByte()) e += 1
            String(b, s, e - s, Charsets.UTF_8)
        }

        val needed = neededV.mapNotNull { cstr(it).ifBlank { null } }
        val rp = if (runPathV >= 0) cstr(runPathV) else if (rpathV >= 0) cstr(rpathV) else null
        val origin = rp?.contains("\$ORIGIN") == true

        Dynamic(needed, rp, interp)
    }.getOrNull()

    private fun vaddrToOffset(
        b: ByteArray, buf: ByteBuffer, phOff: Int, phEnt: Int, phNum: Int, vaddr: Long,
    ): Long? {
        for (i in 0 until phNum) {
            val o = phOff + i * phEnt
            if (o + 56 > b.size) break
            buf.position(o)
            val type = buf.int
            if (type != 1) continue            // PT_LOAD
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