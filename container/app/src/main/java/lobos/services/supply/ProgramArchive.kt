package lobos.services.supply
import java.io.File
import java.util.zip.ZipInputStream

object ProgramArchive {

    private const val SMALL_ENTRY_MAX_BYTES = 4096

    fun unzip(zip: File, dest: File) {
        val destRoot = dest.canonicalFile

        val entryNames = HashSet<String>()
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name
                val out = File(dest, name).canonicalFile
                if (!out.path.startsWith(destRoot.path + File.separator) && out.path != destRoot.path) {
                    throw IllegalStateException("程序包条目路径越界（疑似目录穿越）: " + name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()

                    val linkTarget = readSmallEntry(zis, name, entryNames)
                    if (linkTarget != null) {
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                    } else {

                        zis.copyTo(out.outputStream())
                    }
                    count += 1
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            if (count == 0) throw IllegalStateException("程序包内没有任何文件条目")
        }
    }

    private fun readSmallEntry(zis: ZipInputStream, entryName: String, entryNames: Set<String>): String? {

        val bytes = runCatching {

            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            var total = 0
            while (total <= SMALL_ENTRY_MAX_BYTES) {
                val n = zis.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
                total += n
            }
            buf.toByteArray()
        }.getOrNull() ?: return null
        if (bytes.size > SMALL_ENTRY_MAX_BYTES) return null
        return symlinkTargetOf(entryName, bytes, entryNames)
    }

    private fun symlinkTargetOf(entryName: String, bytes: ByteArray, entryNames: Set<String>): String? {
        if (bytes.isEmpty() || bytes.size > 4096) return null
        if (bytes[0] == 0x7f.toByte() && bytes.size > 3 &&
            bytes[1] == 0x45.toByte() && bytes[2] == 0x4c.toByte() && bytes[3] == 0x46.toByte()
        ) return null
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (text.isEmpty() || text.any { it == 0.toChar() } || text.contains(10.toChar())) return null
        val dir = entryName.substringBeforeLast("/", "")
        val resolved = java.nio.file.Paths.get(dir, text).normalize().toString().removePrefix("./")
        if (!entryNames.contains(resolved)) return null
        return text
    }

}