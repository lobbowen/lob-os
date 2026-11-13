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

    /**
     * 那**头几段**逐字的上限（ 它逐字防一个畸形文件把堆吃光）。
     *   它逐字是 16 MiB（ 程序头表、那动态段与那字符串表逐字**都**远小于它）
     */
    private const val MAX_SEGMENT_BYTES = 16L * 1024 * 1024

    /**
     * 那一件逐字**只要**那 ELF 头那几段（ 它逐字**不需要**整个文件）。
     *
     * 缺陷名（C-102）**那一格逐字把整个文件读进 JVM 堆、 1.4 GiB 那个文件逐字进堆、 堆逐字装不下**
     *   旧版逐字是 `val b = file.readBytes()`
     *     它逐字把**整个**文件逐字读进堆（ 最大的 libclang-cpp.so 逐字 1,543,940,120 字节）
     *     它逐字**只要**那 ELF 头、那程序头表、那动态段与那字符串表那几段
     *   **处置**： 它逐字**只**读那几段（ 它逐字用 RandomAccessFile 逐字按偏移读）
     *     它逐字**不**再把整个文件逐字读进堆。
     */
    fun read(file: File): Dynamic? = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            if (total < 64) return null
            //  **头**逐字读（ 它逐字只有 64 字节、 它逐字**不**是那整个文件）
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

            //  **程序头表**逐字扫一遍： 找那 PT_DYNAMIC 与那 PT_INTERP
            var dynFileOff = -1L
            var dynFileSize = 0L
            var interpOff = -1L
            var interpLen = 0L
            for (i in 0 until phNum) {
                //  `ph` 那一份逐字**从 phOff 开始**、 它逐字的下标逐字是**相对**那 phOff 的
                //    （ 旧版逐字是 `val o = phOff + i * phEnt` 逐字拿去 `pb.position(o)`
                //      `ph` 那一份的下标 0 逐字就是那 phOff 那一行
                //      个偏移逐字**大**了 phOff 那一段、 它逐字**读错了行**）
                val o = i * phEnt
                if (o + 56 > ph.size) break
                pb.position(o)
                val type = pb.int
                //  `getLong` 逐字是**相对当前位置**的、 它逐字**要**绝对偏移
                //    （ `pb.position(o)` 逐字把位置逐字设成 o、 `getLong(8)`
                //      逐字读的是 o+8 之后那 8 字节、 **不是** p_offset）
                val fileOff = pb.getLong(o + 8)
                val fileSize = pb.getLong(o + 32)
                when (type) {
                    2 -> { dynFileOff = fileOff; dynFileSize = fileSize }
                    PT_INTERP -> { interpOff = fileOff; interpLen = fileSize }
                }
            }
            if (dynFileOff <= 0 || dynFileSize <= 0) return null
            if (dynFileSize > MAX_SEGMENT_BYTES) return null

            //  **动态段**逐字读（ 它逐字通常只有几 KB）
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

            //  strtabOff 逐字是**虚拟地址**、 它逐字要换算成**文件偏移**
            val strtabOff = vaddrToOffset(ph, pb, phOff, phEnt, phNum, strtabVaddr) ?: return null
            if (strtabOff + strsz > total) return null
            val strtab = ByteArray(strsz.toInt())
            raf.seek(strtabOff)
            raf.readFully(strtab)

            //  **字符串**逐字从**那字符串表**读（ 它逐字**不**再从整个文件读）
            val cstr = label@ { off: Long ->
                if (off < 0 || off >= strtab.size) return@label ""
                var e = off.toInt()
                val s = e
                while (e < strtab.size && strtab[e] != 0.toByte()) e += 1
                String(strtab, s, e - s, Charsets.UTF_8)
            }

            //  **PT_INTERP** 逐字单独读（ 它逐字只有那一小段）
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

    /**
     * 那虚拟地址逐字换算成文件偏移（ 它逐字只看那**程序头表**那一份、
     *   它逐字**不**再要那整个文件）。
     */
    private fun vaddrToOffset(
        ph: ByteArray, buf: ByteBuffer, phOff: Long, phEnt: Int, phNum: Int, vaddr: Long,
    ): Long? {
        for (i in 0 until phNum) {
            //  `ph` 那一份逐字**从 phOff 开始**（ 下标逐字是**相对**的）
            val o = i * phEnt
            if (o + 56 > ph.size) break
            buf.position(o)
            val type = buf.int
            if (type != 1) continue
            //  `getLong` 逐字是**相对当前位置**的（ 它逐字**要**绝对偏移）
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