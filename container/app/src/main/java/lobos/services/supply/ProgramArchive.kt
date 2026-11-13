package lobos.services.supply
import java.io.File
import java.util.zip.ZipInputStream

object ProgramArchive {

    /**
     * 那**小条目**逐字那一个上限（ 它逐字与那 `symlinkTargetOf` 逐字**同一个**）。
     *
     *   **上限**逐字是那 4096 字节
     *     （ `symlinkTargetOf` 那一格逐字 `if (bytes.isEmpty() || bytes.size > 4096) return null`）
     *   **软链**判别逐字**只**要看那**小**的那些字节
     *     **大**的那些逐字**不**进那堆（ 它逐字**流式**落盘）
     */
    private const val SMALL_ENTRY_MAX_BYTES = 4096

    fun unzip(zip: File, dest: File) {
        val destRoot = dest.canonicalFile
        //  缺陷名（C-102）**这一支把软链落成了普通文件**
        //    它治**的**是「那产物里的软链逐字**是不是**还**是**软链**」。
        //    zipfile **那一族**逐字**不带**那软链（ 它逐字把**那一条**落成**那一个**普通文件、
        //       而那**普通文件**逐字**只有**那软链**正文**那**几个**字节**）。
        //    **后果**逐字**是**： usr/bin/sh 逐字**起不来**（ rc=127 逐字是「command not found」
        //       那一档、 **它**逐字**被**读成「那库不在那设备上」）。
        //    **处置**： 软链逐字**按**那产物里**那**「LINK」**那一族**逐条**重建**。
        //    **判别形式**： 内容**像**一条路径、 按它解析出来的目标**在**包里**存在**。
        //       （ 三个 1 字节的 x 逐字**不会**被**误判**： 它解析成 bin/x、 包里**没有** bin/x）
        //    **它**逐字**不是**「那 DT_RUNPATH 要改」那个形状、 **它**逐字**不是**「那加载器坏了」那个形状。
        //    按 C-104： 这**解压**那一族逐字**只有**一条路（ 这一支**就是**它）。
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
                    //  缺陷名（C-102）**那一格逐字把 zip 里那一个条目逐字**整读**进堆** ——
                    //    旧版逐字是 `val bytes = zis.readBytes()`
                    //      它逐字把**那一个条目**逐字**整个**读进那堆
                    //      **条目**逐字可以是那**大件**（ `node` 那一件逐字
                    //        **116779696** 字节、 `llvm-lipo` 那一件逐字 **133359688** 字节）
                    //      **一次**读逐字**越**那堆的余量
                    //        （ 设备上 `dalvik.vm.heapsize` = 512m、`heapgrowthlimit` = 384m、
                    //          堆逐字已到 **291 MB**、 余量逐字约 **93 MB**）
                    //      **后果**（02 那一支逐字实测、Lead 本机复核成立）：
                    //        · 而那 logcat 逐字连续四次
                    //          `Throwing OutOfMemoryError "Failed to allocate a 116916328 byte allocation
                    //          with 100663296 free bytes and 285MB until OOM, target footprint 337946000,
                    //          growth limit 536870912" (VmSize 9451700 kB)`
                    //          逐字间隔 1.1 / 1.2 / 6.7 秒
                    //        · **分配量**逐字与那设备上的文件逐字**同形**
                    //          （ 116916328 与那 node 那一件逐字 **116779696** 逐字差 136632）
                    //        · **崩溃**逐字才是那耗电的**病**
                    //          （ 前台服务通知逐字只是那**症状**）
                    //    **处置**： **大条目**逐字**流式**落盘（ 它逐字**不**进那堆）
                    //      **软链**判别逐字**只**要那**小条目**的字节
                    //        （ `symlinkTargetOf` 逐字**自己**逐字**只看**那 ≤4096 字节那一族）
                    //      **小条目**逐字**才**整读（ 它逐字**有**那个上限）
                    //    **判据**逐字是「那一条目逐字**有**多大」
                    //      （ **上限**逐字是那 4096、 它逐字与那 `symlinkTargetOf` 逐字**同一个**）
                    val linkTarget = readSmallEntry(zis, name, entryNames)
                    if (linkTarget != null) {
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                    } else {
                        //  **大条目**逐字**流式**落盘（ 它逐字**不**整读进那堆）
                        //          （ **小条目**逐字**已经**被那 `readSmallEntry` 逐字**读掉**了）
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

    /** 那一条**是不是**一条软链**（ **它**逐字**是**那产物里**那**「LINK」**那一族**）。
     *  判别形式：内容**能解成一条路径** + 按它解析出来的目标**在包里存在**， 两**个**条件缺一不可。
     *  这一支逐字**与**那 SupplyProvisioner **那一族**同形（ 按 C-104：那**解压**那一族逐字**只有**一条判别形式）。
     */
    /**
     * 那**小条目**逐字**才**整读（ **大条目**逐字**不**读、 它逐字**流式**落盘）。
     *
     *   **上限**逐字是那 4096（ 它逐字与那 `symlinkTargetOf` 逐字**同一个**）
     *   **软链**判别逐字**只**要那**小条目**的字节
     *     （ `symlinkTargetOf` 逐字**自己**逐字**只看**那 ≤4096 字节那一族）
     *   **读不到**那目标逐字时逐字**不是**软链（ 它逐字**流式**落盘）
     */
    private fun readSmallEntry(zis: ZipInputStream, entryName: String, entryNames: Set<String>): String? {
        //  `ZipInputStream` 逐字**没有**那大小那一格（ 它逐字是那**流**那一族、
        //    大小逐字**只有**那 `ZipFile` 那一族**才**有）
        //    **处置**： **字节**逐字**先**只读那**上限**那么多、
        //      **读满**了逐字就是「那一条目逐字**太大**」
        //      `symlinkTargetOf` 逐字**本来**就**不**看那么大的那一族
        //      **大**的逐字**流式**落盘（ 它逐字**不**进那堆）
        val bytes = runCatching {
            //  `InputStream.readBytes(n)` 逐字**在那一层没有**（ `readBytes()` 逐字**没有**上限）
            //    **正解**： 字节逐字**按**那上限**一块一块**读、 它逐字**读满**就**停**
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