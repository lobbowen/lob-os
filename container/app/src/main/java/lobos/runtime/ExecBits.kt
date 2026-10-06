package lobos.runtime

import java.io.File
import java.nio.file.Files

// 不是 internal：安装器（lobos.ota.ProgramInstallPipeline）落位组件后要靠它给
// entry 加执行位。原先只有 os/PackageInstaller 用，而该文件删掉了那段实现后
// 它就没有调用方了；这里恢复为跨包可用。
object ExecBits {

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
