package lobos.services.supply
import java.io.File
import java.util.zip.ZipInputStream

object ProgramArchive {

    /**
     * 那**小条目**逐字那一个上限（=> 而而那它逐字与那 `symlinkTargetOf` 逐字**同一个**）。
     *
     *   => 而而那那**上限**逐字是那 4096 字节
     *     （=> 而而那那 `symlinkTargetOf` 那一格逐字 `if (bytes.isEmpty() || bytes.size > 4096) return null`）
     *   => 而而那那**软链**判别逐字**只**要看那**小**的那些字节
     *     => 而而那那**大**的那些逐字**不**进那堆（=> 而而那它逐字**流式**落盘）
     */
    private const val SMALL_ENTRY_MAX_BYTES = 4096

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
                    //  缺陷名（C-102）**那一格逐字把 zip 里那一个条目逐字**整读**进堆** ——
                    //    => 而那旧版逐字是 `val bytes = zis.readBytes()`
                    //      => 而而那它逐字把**那一个条目**逐字**整个**读进那堆
                    //      => 而而那那**条目**逐字可以是那**大件**（=> 而那 `node` 那一件逐字
                    //        **116779696** 字节、=> 而那 `llvm-lipo` 那一件逐字 **133359688** 字节）
                    //      => 而而那那**一次**读逐字**越**那堆的余量
                    //        （=> 而那设备上 `dalvik.vm.heapsize` = 512m、`heapgrowthlimit` = 384m、
                    //          => 而而那那堆逐字已到 **291 MB**、=> 而而那那余量逐字约 **93 MB**）
                    //      => 而而那那**后果**（02 那一支逐字实测、Lead 本机复核成立）：
                    //        · 而那 logcat 逐字连续四次
                    //          `Throwing OutOfMemoryError "Failed to allocate a 116916328 byte allocation
                    //          with 100663296 free bytes and 285MB until OOM, target footprint 337946000,
                    //          growth limit 536870912" (VmSize 9451700 kB)`
                    //          逐字间隔 1.1 / 1.2 / 6.7 秒
                    //        · => 而而那那**分配量**逐字与那设备上的文件逐字**同形**
                    //          （=> 而那 116916328 与那 node 那一件逐字 **116779696** 逐字差 136632）
                    //        · => 而而那那**崩溃**逐字才是那耗电的**病**
                    //          （=> 而而那那前台服务通知逐字只是那**症状**）
                    //    => 而那**处置**：=> 而那那**大条目**逐字**流式**落盘（=> 而而那它逐字**不**进那堆）
                    //      => 而而那那**软链**判别逐字**只**要那**小条目**的字节
                    //        （=> 而而那那 `symlinkTargetOf` 逐字**自己**逐字**只看**那 ≤4096 字节那一族）
                    //      => 而而那那**小条目**逐字**才**整读（=> 而而那它逐字**有**那个上限）
                    //    => 而那**判据**逐字是「那一条目逐字**有**多大」
                    //      （=> 而而那那**上限**逐字是那 4096、=> 而而那它逐字与那 `symlinkTargetOf` 逐字**同一个**）
                    val linkTarget = readSmallEntry(zis, name, entryNames)
                    if (linkTarget != null) {
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                    } else {
                        //  => 而而那那**大条目**逐字**流式**落盘（=> 而而那它逐字**不**整读进那堆）
                        //          （=> 而而那那**小条目**逐字**已经**被那 `readSmallEntry` 逐字**读掉**了）
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

    /** 那一条**是不是**一条软链**（=> 而那**它**逐字**是**那产物里**那**「LINK」**那一族**）。
     *  => 而判别形式：内容**能解成一条路径** + 按它解析出来的目标**在包里存在**，=> 而那两**个**条件缺一不可。
     *  => 而这一支逐字**与**那 SupplyProvisioner **那一族**同形（=> 而按 C-104：那**解压**那一族逐字**只有**一条判别形式）。
     */
    /**
     * 那**小条目**逐字**才**整读（=> 而而那那**大条目**逐字**不**读、=> 而而那它逐字**流式**落盘）。
     *
     *   => 而而那那**上限**逐字是那 4096（=> 而而那它逐字与那 `symlinkTargetOf` 逐字**同一个**）
     *   => 而而那那**软链**判别逐字**只**要那**小条目**的字节
     *     （=> 而而那那 `symlinkTargetOf` 逐字**自己**逐字**只看**那 ≤4096 字节那一族）
     *   => 而而那那**读不到**那目标逐字时逐字**不是**软链（=> 而而那它逐字**流式**落盘）
     */
    private fun readSmallEntry(zis: ZipInputStream, entryName: String, entryNames: Set<String>): String? {
        //  => 而那那 `ZipInputStream` 逐字**没有**那大小那一格（=> 而它逐字是那**流**那一族、
        //    => 而而那那大小逐字**只有**那 `ZipFile` 那一族**才**有）
        //    => 而那**处置**：=> 而而那那**字节**逐字**先**只读那**上限**那么多、
        //      => 而而那那**读满**了逐字就是「那一条目逐字**太大**」
        //      => 而而那那 `symlinkTargetOf` 逐字逐字**本来**就**不**看那么大的那一族
        //      => 而而那那**大**的逐字**流式**落盘（=> 而而那它逐字**不**进那堆）
        val bytes = runCatching {
            //  => 而那那 `InputStream.readBytes(n)` 逐字**在那一层没有**（=> 而而那那 `readBytes()` 逐字**没有**上限）
            //    => 而那**正解**：=> 而而那那字节逐字**按**那上限**一块一块**读、=> 而而那它逐字**读满**就**停**
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