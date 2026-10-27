package lobos.services.supply
import java.io.File
import java.util.zip.ZipInputStream

object ProgramArchive {

    fun unzip(zip: File, dest: File) {
        val destRoot = dest.canonicalFile
        //  缺陷名（C-102）**这一支把软链落成了普通文件**
        //    => 而而它治**的**是「那产物里的软链逐字**是不是**还**是**软链**」。
        //    => 而那 zipfile **那一族**逐字**不带**那软链（=> 而它逐字把**那一条**落成**那一个**普通文件、
        //       而而那**普通文件**逐字**只有**那软链**正文**那**几个**字节**）。
        //    => 而那**后果**逐字**是**：=> 而那 usr/bin/sh 逐字**起不来**（=> 而那 rc=127 逐字是「command not found」
        //       那一档、=> 而而那**它**逐字**被**读成「那库不在那设备上」）。
        //    => 而那**处置**：=> 而那软链逐字**按**那产物里**那**「LINK」**那一族**逐条**重建**。
        //    => 而而那**判别形式**：=> 而那内容**像**一条路径、=> 而按它解析出来的目标**在**包里**存在**。
        //       （=> 而那三个 1 字节的 x 逐字**不会**被**误判**：=> 而它解析成 bin/x、=> 而包里**没有** bin/x）
        //    => 而而那**它**逐字**不是**「那 DT_RUNPATH 要改」那个形状、=> 而而那**它**逐字**不是**「那加载器坏了」那个形状。
        //    => 而按 C-104：=> 而这**解压**那一族逐字**只有**一条路（=> 而这一支**就是**它）。
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
                    val bytes = zis.readBytes()
                    val linkTarget = symlinkTargetOf(name, bytes, entryNames)
                    if (linkTarget != null) {
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                    } else {
                        out.outputStream().use { os -> os.write(bytes) }
                    }
                    count += 1
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            if (count == 0) throw IllegalStateException("程序包内没有任何文件条目")
        }
    }

    /** 那一条**是不是**一条软链**（=> 而那**它**逐字**是**那产物里**那**「LINK」**那一族**）。
     *  => 而判别形式：内容**能解成一条路径** + 按它解析出来的目标**在包里存在**，=> 而那两**个**条件缺一不可。
     *  => 而这一支逐字**与**那 SupplyProvisioner **那一族**同形（=> 而按 C-104：那**解压**那一族逐字**只有**一条判别形式）。
     */
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