package lobos.kernel.elf

import java.io.File

object ExecBits {

    fun apply(file: File) {
        if (hasExecutableHeader(file)) file.setExecutable(true, false)
    }

    fun hasExecutableHeader(file: File): Boolean =
        file.isFile &&
            lobos.kernel.proc.ExecEntry.detect(file) != lobos.kernel.proc.ExecEntry.Shape.Unknown

    fun isRunnable(file: File): Boolean {
        if (!file.isFile) return false
        if (lobos.kernel.proc.ExecEntry.detect(file) == lobos.kernel.proc.ExecEntry.Shape.Unknown) return false
        return file.canExecute()
    }

}
