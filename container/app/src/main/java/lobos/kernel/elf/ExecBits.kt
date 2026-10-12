package lobos.kernel.elf

import java.io.File

object ExecBits {

    fun apply(file: File) {
        if (isRunnable(file)) file.setExecutable(true, false)
    }

    fun isRunnable(file: File): Boolean =
        lobos.kernel.proc.ExecEntry.detect(file) != lobos.kernel.proc.ExecEntry.Shape.Unknown
}
