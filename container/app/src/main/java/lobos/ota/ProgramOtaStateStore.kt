package lobos.ota

import java.io.File

class ProgramOtaStateStore(
    private val root: File,
    private val onCurrentChanged: ((String) -> Unit)? = null,
) {

    data class Pending(val version: String, val from: String?)

    private val currentPointer: File get() = File(root, "CURRENT")
    private val floorFile: File get() = File(root, "FLOOR")
    private val pendingFile: File get() = File(root, "PENDING")

    fun currentVersion(): String? =
        if (currentPointer.exists()) currentPointer.readText().trim().ifBlank { null } else null

    fun setCurrentVersion(version: String) {
        root.mkdirs()
        lobos.os.StateFiles.writeAtomic(currentPointer, version)
        onCurrentChanged?.invoke(version)
    }

    fun floorVersion(): String? = try {
        floorFile.readText().trim().ifBlank { null }
    } catch (_: Throwable) { null }

    fun setFloor(version: String) {
        val cur = floorVersion()
        if (cur != null && ProgramOtaVersions.compare(version, cur) <= 0) return
        root.mkdirs()
        lobos.os.StateFiles.writeAtomic(floorFile, version)
    }

    fun isBelowFloor(version: String): Boolean = ProgramOtaVersions.isBelowFloor(version, floorVersion())

    fun markPending(version: String, from: String?) {
        root.mkdirs()
        lobos.os.StateFiles.writeAtomic(pendingFile, version + "\n" + (from ?: ""))
    }

    fun pending(): Pending? = try {
        val lines = pendingFile.readText().split("\n")
        val v = lines.getOrNull(0)?.trim().orEmpty()
        if (v.isBlank()) null else Pending(v, lines.getOrNull(1)?.trim()?.ifBlank { null })
    } catch (_: Throwable) { null }

    fun clearPending() { try { pendingFile.delete() } catch (_: Throwable) { } }

    fun rollbackTo(from: String): Boolean {
        if (!File(root, from).isDirectory) return false
        setCurrentVersion(from)
        return true
    }
}
