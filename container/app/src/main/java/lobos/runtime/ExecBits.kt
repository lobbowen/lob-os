package lobos.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ELF 头的事实 —— 照抄 `ldconfig(8)`：「checks the **header** and filenames … when
 * determining which versions should have their links updated」。
 *
 * 它读文件头一次，不执行任何代码；据此知道这个二进制是命令还是库、
 * 它要哪些库（`DT_NEEDED`）。系统对每一件的认识都来自这里。
 */
object ExecBits {

    /** 加执行位（ELF 或 shebang 脚本都算可执行） */
    fun apply(file: File) {
        if (isRunnable(file)) file.setExecutable(true, false)
    }

    /**
     * 这个文件能不能**当命令直接跑** —— 读 ELF 头的 `e_type`：
     *   ET_EXEC(2) / ET_DYN(3) 且带 `PT_INTERP` → 有解释器，是程序
     *   ET_DYN(3) 且无 `PT_INTERP`           → 共享库
     *   `#!` 开头的脚本                        → 程序
     */
    fun isRunnable(file: File): Boolean {
        val head = ByteArray(64)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (_: Throwable) {
            return false
        }
        if (read < 4) return false
        if (head[0] != '\u007f'.code.toByte() ||
            String(head, 1, 3, Charsets.ISO_8859_1) != "ELF"
        ) {
            return read >= 2 && String(head, 0, read, Charsets.ISO_8859_1).startsWith("#!")
        }
        if (read < 20) return false
        val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val eType = buf.getShort(16).toInt() and 0xffff
        if (eType == 2) return true                 // ET_EXEC
        if (eType != 3) return false                // ET_DYN：看有没有解释器
        return hasInterpreter(file)
    }

    /** 程序段里有没有 PT_INTERP（3）—— 有就是要有解释器才能跑，那就是程序 */
    private fun hasInterpreter(file: File): Boolean = runCatching {
        val b = file.readBytes()
        if (b.size < 64) return@runCatching false
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val phOff = buf.getLong(0x20).toInt()
        val phEnt = buf.getShort(0x36).toInt() and 0xffff
        val phNum = buf.getShort(0x38).toInt() and 0xffff
        for (i in 0 until phNum) {
            val at = phOff + i * phEnt
            if (at < 0 || at + 4 > b.size) break
            if (b[at].toInt() and 0xff == 3) return@runCatching true
        }
        false
    }.getOrDefault(false)
}
