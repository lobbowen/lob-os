package lobos.kernel.elf

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ElfFacts {

    data class Dynamic(
        val needed: List<String>,
        val runPath: String?,
        val interp: String?,
        val soname: String?,
    )

    private const val DT_NEEDED = 1
    private const val DT_STRTAB = 5
    private const val DT_STRSZ = 10
    private const val DT_RPATH = 15
    private const val DT_RUNPATH = 29
    private const val DT_SONAME = 14
    private const val PT_INTERP = 3

    private const val MAX_SEGMENT_BYTES = 16L * 1024 * 1024

    fun read(file: File): Dynamic? = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            if (total < 64) return null

            val head = ByteArray(64)
            raf.readFully(head)
            fun magic(i: Int): Int = head[i].toInt() and 0xff
            if (magic(0) != 0x7f || magic(1) != 'E'.code || magic(2) != 'L'.code || magic(3) != 'F'.code) return null
            if (magic(4) != 2) return null
            if (magic(5) != 1) return null
            val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val phOff = hb.getLong(0x20)
            val phEnt = hb.getShort(0x36).toInt() and 0xffff
            val phNum = hb.getShort(0x38).toInt() and 0xffff
            if (phEnt <= 0 || phNum <= 0) return null
            val phBytes = phOff + phEnt.toLong() * phNum
            if (phBytes > MAX_SEGMENT_BYTES) return null
            val ph = ByteArray(phBytes.toInt())
            raf.seek(phOff)
            raf.readFully(ph)
            val pb = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)

            var dynFileOff = -1L
            var dynFileSize = 0L
            var interpOff = -1L
            var interpLen = 0L
            for (i in 0 until phNum) {

                val o = i * phEnt
                if (o + 56 > ph.size) break
                pb.position(o)
                val type = pb.int

                val fileOff = pb.getLong(o + 8)
                val fileSize = pb.getLong(o + 32)
                when (type) {
                    2 -> { dynFileOff = fileOff; dynFileSize = fileSize }
                    PT_INTERP -> { interpOff = fileOff; interpLen = fileSize }
                }
            }
            if (dynFileOff <= 0 || dynFileSize <= 0) return null
            if (dynFileSize > MAX_SEGMENT_BYTES) return null

            val dyn = ByteArray(dynFileSize.toInt())
            raf.seek(dynFileOff)
            raf.readFully(dyn)
            val db = ByteBuffer.wrap(dyn).order(ByteOrder.LITTLE_ENDIAN)

            var strtabVaddr = -1L
            var strsz = 0L
            val neededV = mutableListOf<Long>()
            var runPathV = -1L
            var sonameV = -1L
            var rpathV = -1L

            var p = 0
            while (p + 16 <= dyn.size) {
                db.position(p)
                val tag = db.getLong()
                val dval = db.getLong()
                if (tag == 0L) break
                when (tag) {
                    DT_NEEDED.toLong() -> neededV += dval
                    DT_STRTAB.toLong() -> strtabVaddr = dval
                    DT_STRSZ.toLong() -> strsz = dval
                    DT_RUNPATH.toLong() -> runPathV = dval
                    DT_SONAME.toLong() -> sonameV = dval
                    DT_RPATH.toLong() -> rpathV = dval
                }
                p += 16
            }
            if (strtabVaddr < 0) return null
            if (strsz <= 0 || strsz > MAX_SEGMENT_BYTES) return null

            val strtabOff = vaddrToOffset(ph, pb, phOff, phEnt, phNum, strtabVaddr) ?: return null
            if (strtabOff + strsz > total) return null
            val strtab = ByteArray(strsz.toInt())
            raf.seek(strtabOff)
            raf.readFully(strtab)

            val cstr = label@ { off: Long ->
                if (off < 0 || off >= strtab.size) return@label ""
                var e = off.toInt()
                val s = e
                while (e < strtab.size && strtab[e] != 0.toByte()) e += 1
                String(strtab, s, e - s, Charsets.UTF_8)
            }

            val interp: String? = if (interpOff in 0 until total && interpLen in 1..4096) {
                val seg = ByteArray(interpLen.toInt())
                raf.seek(interpOff)
                raf.readFully(seg)
                val z = seg.indexOf(0.toByte())
                String(seg, 0, if (z >= 0) z else seg.size, Charsets.UTF_8).ifBlank { null }
            } else null

            val needed = neededV.mapNotNull { cstr(it).ifBlank { null } }
            val rp = if (runPathV >= 0) cstr(runPathV) else if (rpathV >= 0) cstr(rpathV) else null

            Dynamic(needed, rp, interp, if (sonameV >= 0) cstr(sonameV).ifBlank { null } else null)
        }
    } catch (_: Throwable) {
        null
    }

    private fun vaddrToOffset(
        ph: ByteArray, buf: ByteBuffer, phOff: Long, phEnt: Int, phNum: Int, vaddr: Long,
    ): Long? {
        for (i in 0 until phNum) {

            val o = i * phEnt
            if (o + 56 > ph.size) break
            buf.position(o)
            val type = buf.int
            if (type != 1) continue

            val off = buf.getLong(o + 8)
            val v = buf.getLong(o + 16)
            val fileSz = buf.getLong(o + 32)
            val memSz = buf.getLong(o + 40)
            if (vaddr in v until v + memSz) return off + (vaddr - v)
            if (fileSz == 0L && memSz > 0) continue
        }
        return null
    }
}