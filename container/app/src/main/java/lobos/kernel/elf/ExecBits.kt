package lobos.kernel.elf

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ExecBits {

    fun apply(file: File) {
        if (isRunnable(file)) file.setExecutable(true, false)
    }

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
