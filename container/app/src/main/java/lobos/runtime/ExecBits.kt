package lobos.runtime

import java.io.File
import java.nio.file.Files

internal object ExecBits {

    fun apply(file: File) {
        val head = ByteArray(4)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (_: Throwable) {
            return
        }
        if (read < 2) return
        val magic = String(head, 0, read, Charsets.ISO_8859_1)
        val elf = magic.length == 4 && magic[0] == '\u007f' && magic.substring(1) == "ELF"
        if (elf || magic.startsWith("#!")) file.setExecutable(true, false)
    }

}
