package lobos.system.exec

import java.io.File

object ExecBits {

    fun apply(file: File) {
        if (hasExecutableHeader(file)) file.setExecutable(true, false)
    }

    /** Header-level check only, no dependency on proc/ExecEntry (breaks elf<->proc cycle). */
    fun hasExecutableHeader(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            file.inputStream().use { inp ->
                val buf = ByteArray(4)
                val n = inp.read(buf)
                if (n >= 4 && buf[0] == 0x7f.toByte() && buf[1] == 'E'.toByte() &&
                    buf[2] == 'L'.toByte() && buf[3] == 'F'.toByte()) return true
                // shebang check
                val all = ByteArray(256)
                val m = inp.read(all)
                if (m >= 2 && all[0] == '#'.toByte() && all[1] == '!'.toByte()) return true
                false
            }
        }.getOrDefault(false)
    }

    fun isRunnable(file: File): Boolean {
        if (!file.isFile) return false
        if (!hasExecutableHeader(file)) return false
        return file.canExecute()
    }
}
