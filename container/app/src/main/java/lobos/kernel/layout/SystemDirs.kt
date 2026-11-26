package lobos.kernel.layout

import java.io.File
import lobos.kernel.spi.KernelServices

object SystemDirs {

    const val REL_OPT = "opt"

    /** Root of the LobOS system disk inside the APK, from the host SPI. */
    private fun root(): File = KernelServices.paths().rootDir

    fun etc(): File = File(root(), "etc")
    fun usr(): File = File(root(), "usr")
    fun bin(): File = File(usr(), "bin")
    fun lib(): File = File(usr(), "lib")
    fun include(): File = File(usr(), "include")
    fun vardir(): File = File(root(), "var")
    fun libvar(): File = File(vardir(), "lib")
    fun log(): File = File(vardir(), "log")
    fun run(): File = File(root(), "run")
    fun opt(): File = File(root(), REL_OPT)
    fun pieceDir(id: String): File = File(opt(), id)
    fun pieceDir(id: String, version: String): File = File(pieceDir(id), version)
    fun relPieceDir(id: String): String = REL_OPT + "/" + id

    fun clearRun(): Int {
        val root = run()
        if (!root.isDirectory) return 0
        var n = 0
        for (k in root.list() ?: return 0) {
            val f = File(root, k)
            if (runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }.getOrDefault(false)) n++
        }
        return n
    }

    fun clearRunVolatile(): Int {
        val root = run()
        if (!root.isDirectory) return 0
        var n = 0
        for (k in listOf("tmp", "staging", "install.lock")) {
            if (runCatching { File(root, k).deleteRecursively() }.getOrDefault(false)) n++
        }
        return n
    }

    fun ensureAll() {
        for (d in listOf(etc(), usr(), bin(), lib(), include(), vardir(), libvar(), log(), run(), opt())) {
            runCatching { d.mkdirs() }
        }
    }
}
