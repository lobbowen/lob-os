package lobos.ota

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object ProgramArchive {

    fun unzip(zip: File, dest: File) {
        val destRoot = dest.canonicalFile
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name
                val out = File(dest, name).canonicalFile
                if (!out.path.startsWith(destRoot.path + File.separator) && out.path != destRoot.path) {
                    throw IllegalStateException("内核包条目路径越界（疑似目录穿越）: " + name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                    count += 1
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            if (count == 0) throw IllegalStateException("内核包内没有任何文件条目")
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
