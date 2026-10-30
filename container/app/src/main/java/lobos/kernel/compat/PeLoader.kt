package lobos.kernel.compat

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * 用户态 PE L2 加载器（=> 而那 BLUEPRINT 那 **L2** 那一档：映射节、解析导入、
 * 按 LOB-OS 库搜索路径（`mm/LibPath`）加载依赖、构造进程映像）。
 *
 * 分层纪律（=> 而那 L1 那一族只答「是什么」、而这一族答「怎么装」）：
 *  - 形状识别在 `lobos.kernel.proc.ExecEntry`（那一族逐字只读那 MZ 与那 e_lfanew）。
 *  - 本文件**不重做**形状识别：它从 PE 字节起手，自己解头、解节、解导入表。
 *  - 本文件**不执行** x86/x86-64 指令：它只把映像构造出来。
 */
class PeLoader private constructor(
    val image: PeProcessImage,
    val dependencies: List<PeDependency>,
) {

    companion object {
        private const val DOS_MAGIC = 0x5A4D
        private const val PE_SIGNATURE = 0x00004550
        private const val FILE_ALIGNMENT = 0x200
        private const val DEFAULT_SECTION_ALIGNMENT = 0x1000

        private const val OPT_MAGIC_PE32 = 0x10B
        private const val OPT_MAGIC_PE32_PLUS = 0x20B

        /** 加载一个 PE，并把它的导入表里点名的 DLL 在那搜索路径上找齐。 */
        fun load(executable: File, librarySearchPath: List<File>): PeLoader {
            val executableBytes = Files.readAllBytes(executable.toPath())
            val executableImage = parseImage(executableBytes)
            val resolver = DependencyResolver(librarySearchPath)
            val dependencies = resolver.resolve(executableImage.imports)
            return PeLoader(executableImage, dependencies)
        }

        /** 用 mm/LibPath 那一格（=> 而那**它**逐字是那 LOB-OS 库搜索路径的**唯一**生成点）加载。 */
        fun load(ctx: Context, executable: File): PeLoader {
            val dirs = lobos.kernel.mm.LibPath.searchPath(ctx)
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map(::File)
            return load(executable, dirs)
        }

        /** 只解析并映射，不解析依赖。 */
        fun parse(bytes: ByteArray): PeProcessImage = parseImage(bytes)

        private fun parseImage(bytes: ByteArray): PeProcessImage {
            val r = PeReader(bytes)
            r.requireRange(0, 2)
            if (r.u16(0) != DOS_MAGIC) throw PeFormatException("not a DOS MZ image")
            r.requireRange(0x3C, 4)
            val peOffset = r.u32(0x3C).toInt()
            r.requireRange(peOffset, 24)
            if (r.u32(peOffset) != PE_SIGNATURE) throw PeFormatException("missing PE signature")

            val coff = peOffset + 4
            val machine = r.u16(coff)
            val sectionCount = r.u16(coff + 2)
            val optionalSize = r.u16(coff + 16)
            val optional = coff + 20
            r.requireRange(optional, optionalSize)
            val magic = r.u16(optional)
            val pe32Plus = when (magic) {
                OPT_MAGIC_PE32 -> false
                OPT_MAGIC_PE32_PLUS -> true
                else -> throw PeFormatException("unsupported optional-header magic 0x${magic.toString(16)}")
            }

            val imageBase = if (pe32Plus) r.u64(optional + 24) else r.u32(optional + 28)
            val sectionAlignment = r.u32(optional + 32)
            val fileAlignment = r.u32(optional + 36)
            val entryPointRva = r.u32(optional + 16)
            val sizeOfImage = r.u32(optional + 56)
            val sizeOfHeaders = r.u32(optional + 60)

            //  缺陷名（C-102）**那数据目录那一格逐字按 PE32 的偏移读、=> 而而那 PE32+ 那一族
            //    逐字把目录读到了错的地方** ——
            //    => 而它治**的**是「那导入表那一格逐字从哪一格取」。
            //    => 而那**成因**：=> 而那 PE32+ 的可选头**少一个** BaseOfData（4 字节）、
            //       => 而而那**后面每一格**逐字**往前挪**了 4 个字节、=> 而而那 NumberOfRvaAndSizes
            //       逐字从 92 挪到了 108、=> 而而那数据目录逐字从 96 挪到了 112。
            //    => 而那**后果**：=> 而那 PE32+ 那一族逐字把**别的**目录读成了导入表
            //       （=> 而那一族多半会把一段无关的字节读成 DLL 名）。
            //    => 而那**处置**：=> 而那两格逐字按 pe32Plus **分两支**取、=> 而而那**不合并**。
            val directoryCountOffset = if (pe32Plus) 108 else 92
            val directoryBase = if (pe32Plus) 112 else 96
            val directoryCount = r.u32(optional + directoryCountOffset)
            val exportDirectory = directoryAt(r, optional, directoryBase, directoryCount, 0)
            val importDirectory = directoryAt(r, optional, directoryBase, directoryCount, 1)
            val exportRva = exportDirectory.first
            val exportSize = exportDirectory.second
            val importRva = importDirectory.first
            val importSize = importDirectory.second

            val sectionTable = optional + optionalSize
            val sections = ArrayList<PeSection>(sectionCount)
            for (i in 0 until sectionCount) {
                val off = sectionTable + i * 40
                r.requireRange(off, 40)
                sections += PeSection(
                    name = r.cString(off, 8),
                    virtualSize = r.u32(off + 8),
                    virtualAddress = r.u32(off + 12),
                    rawSize = r.u32(off + 16),
                    rawOffset = r.u32(off + 20),
                    characteristics = r.u32(off + 36),
                )
            }

            val effectiveAlignment = if (sectionAlignment == 0) DEFAULT_SECTION_ALIGNMENT else sectionAlignment
            val effectiveFileAlignment = if (fileAlignment == 0) FILE_ALIGNMENT else fileAlignment
            val headerBytes = r.slice(0, sizeOfHeaders.toInt())
            val imageSize = if (sizeOfImage > 0) sizeOfImage.toInt() else {
                sections.maxOfOrNull { alignUp(it.virtualAddress + it.virtualSize, effectiveAlignment) }?.toInt() ?: 0
            }
            if (imageSize < headerBytes.size) {
                throw PeFormatException("PE image size is smaller than its headers")
            }

            //  => 映射节：头先落，其余每一节按它自己的 VirtualAddress 落，缺的那段留零。
            val mapped = ByteArray(imageSize)
            headerBytes.copyInto(mapped)

            for (section in sections) {
                if (section.virtualAddress >= mapped.size) {
                    throw PeFormatException("section ${section.name} starts outside the image")
                }
                val copySize = minOf(section.rawSize, section.virtualSize.takeIf { it != 0L } ?: section.rawSize)
                if (copySize == 0L) continue
                r.requireRange(section.rawOffset.toInt(), copySize.toInt())
                val source = r.slice(section.rawOffset.toInt(), copySize.toInt())
                val destination = section.virtualAddress.toInt()
                if (destination + source.size > mapped.size) {
                    throw PeFormatException("section ${section.name} exceeds the mapped image")
                }
                source.copyInto(mapped, destination)
            }

            //  => 而那 reader 逐字要带着**那两格**（=> 而那节表与那 PE32+ 那一档），
            //     => 而而那**不**带的话、=> 而而那 thunk 那一步逐字**恒**按 PE32 读。
            r.sections = sections
            r.isPe32Plus = pe32Plus

            val view = ImageView(mapped, pe32Plus)
            val imports = parseImports(view, importRva, importSize)
            val exports = parseExports(view, exportRva, exportSize)
            return PeProcessImage(
                machine = machine,
                pe32Plus = pe32Plus,
                imageBase = imageBase,
                entryPointRva = entryPointRva,
                sectionAlignment = effectiveAlignment,
                fileAlignment = effectiveFileAlignment,
                sizeOfImage = imageSize,
                sizeOfHeaders = sizeOfHeaders.toInt(),
                sections = sections,
                imports = imports,
                exports = exports,
                bytes = mapped,
            )
        }

        /** 读数据目录第 index 格（=> 而那**它**返回 RVA，而那大小取不到就返回 0）。 */
        private fun directoryAt(
            r: PeReader,
            optional: Int,
            directoryBase: Int,
            directoryCount: Long,
            index: Int,
        ): Pair<Long, Long> {
            if (index >= directoryCount) return 0L to 0L
            val at = optional + directoryBase + index * 8
            r.requireRange(at, 8)
            return r.u32(at) to r.u32(at + 4)
        }

        private fun parseImports(view: ImageView, importRva: Long, importSize: Long): List<PeImport> {
            if (importRva == 0L) return emptyList()
            var descriptor = view.rvaToOffset(importRva)
            val result = ArrayList<PeImport>()
            while (descriptor >= 0 && descriptor + 20 <= view.size) {
                val originalThunk = view.u32(descriptor)
                val nameRva = view.u32(descriptor + 12)
                val firstThunk = view.u32(descriptor + 16)
                if (originalThunk == 0L && nameRva == 0L && firstThunk == 0L) break
                if (nameRva == 0L) throw PeFormatException("import descriptor has no DLL name")
                val dllName = view.string(view.rvaToOffset(nameRva))
                val lookupRva = if (originalThunk != 0L) originalThunk else firstThunk
                result += PeImport(dllName, parseThunks(view, lookupRva))
                descriptor += 20
            }
            return result
        }

        //  缺陷名（C-102）**那 thunk 那一格逐字恒按 4 字节步长读、=> 而而那 PE32+ 那一族
        //    逐字把导入项逐字读成了别的东西** ——
        //    => 而它治**的**是「那导入表逐项逐字能不能解析出来」。
        //    => 而那**成因**：=> 而那 PE32+ 的导入名表逐字是 **8 字节**一项（低 4 字节是 RVA、
        //       高 4 字节是 0），=> 而而那**序号标志位**逐字在**第 63 位**、=> 而而那 **PE32** 那一族
        //       逐字是 4 字节一项、标志位在**第 31 位**。
        //    => 而那**处置**：=> 而那步长与那标志位逐字按 pe32Plus **分两支**取、=> 而而那**不合并**。
        private fun parseThunks(view: ImageView, lookupRva: Long): List<PeImportSymbol> {
            if (lookupRva == 0L) return emptyList()
            var offset = view.rvaToOffset(lookupRva)
            if (offset < 0) return emptyList()
            val step = if (view.pe32Plus) 8 else 4
            val ordinalFlag = if (view.pe32Plus) 0x8000000000000000L else 0x80000000L
            val result = ArrayList<PeImportSymbol>()
            while (offset >= 0 && offset + step <= view.size) {
                val value = if (view.pe32Plus) view.u64(offset) else view.u32(offset)
                if (value == 0L) break
                if (value and ordinalFlag != 0L) {
                    result += PeImportSymbol.Ordinal((value and 0xFFFF).toInt())
                } else {
                    val hintNameOffset = view.rvaToOffset(value)
                    if (hintNameOffset < 0) {
                        throw PeFormatException("import name RVA 0x${value.toString(16)} is not mapped")
                    }
                    result += PeImportSymbol.Name(
                        hint = view.u16(hintNameOffset),
                        name = view.string(hintNameOffset + 2),
                    )
                }
                offset += step
            }
            return result
        }

        /** 读导出表（=> 而那**它**答的是「那一件依赖逐字导出了哪些符号」）。 */
        private fun parseExports(view: ImageView, exportRva: Long, exportSize: Long): PeExports {
            if (exportRva == 0L) return PeExports(null, emptyMap(), emptyMap())
            val dir = view.rvaToOffset(exportRva)
            if (dir < 0 || dir + 40 > view.size) return PeExports(null, emptyMap(), emptyMap())
            val nameRva = view.u32(dir + 12)
            val ordinalBase = view.u32(dir + 16)
            val numberOfFunctions = view.u32(dir + 20)
            val numberOfNames = view.u32(dir + 24)
            val functionsRva = view.u32(dir + 28)
            val namesRva = view.u32(dir + 32)
            val ordinalsRva = view.u32(dir + 36)

            val byName = LinkedHashMap<String, Long>()
            val byOrdinal = LinkedHashMap<Int, Long>()
            for (i in 0 until numberOfFunctions) {
                val at = view.rvaToOffset(functionsRva + i * 4)
                if (at < 0) continue
                byOrdinal[(ordinalBase + i).toInt()] = view.u32(at)
            }
            for (i in 0 until numberOfNames) {
                val nameAt = view.rvaToOffset(namesRva + i * 4)
                if (nameAt < 0) continue
                val symbolName = view.string(view.rvaToOffset(view.u32(nameAt)))
                val ordinalAt = view.rvaToOffset(ordinalsRva + i * 2)
                if (ordinalAt < 0) continue
                val ordinalIndex = view.u16(ordinalAt)
                val functionAt = view.rvaToOffset(functionsRva + ordinalIndex * 4)
                if (functionAt < 0) continue
                byName[symbolName] = view.u32(functionAt)
            }
            val dllName = if (nameRva == 0L) null else view.string(view.rvaToOffset(nameRva))
            return PeExports(dllName, byName, byOrdinal)
        }

        private fun alignUp(value: Long, alignment: Long): Long =
            if (alignment <= 1) value else ((value + alignment - 1) / alignment) * alignment
    }
}

data class PeSection(
    val name: String,
    val virtualSize: Long,
    val virtualAddress: Long,
    val rawSize: Long,
    val rawOffset: Long,
    val characteristics: Long,
)

data class PeProcessImage(
    val machine: Int,
    val pe32Plus: Boolean,
    val imageBase: Long,
    val entryPointRva: Long,
    val sectionAlignment: Long,
    val fileAlignment: Long,
    val sizeOfImage: Int,
    val sizeOfHeaders: Int,
    val sections: List<PeSection>,
    val imports: List<PeImport>,
    val exports: PeExports,
    val bytes: ByteArray,
) {
    /** 入口地址（=> 而那**它**逐字是那映像基址加那入口 RVA）。 */
    val entryPointAddress: Long get() = imageBase + entryPointRva
}

data class PeImport(val dllName: String, val symbols: List<PeImportSymbol>)

sealed class PeImportSymbol {
    data class Name(val hint: Int, val name: String) : PeImportSymbol()
    data class Ordinal(val ordinal: Int) : PeImportSymbol()
}

data class PeExports(
    val dllName: String?,
    val byName: Map<String, Long>,
    val byOrdinal: Map<Int, Long>,
)

/**
 * 一件依赖（=> 而那**它**逐字是那导入表里点名的那个 DLL 在那搜索路径上落到的那一份）。
 *
 * `resolved` 逐字记那**能**解出来的那几项（=> 而那地址是那映像里的 RVA）；
 * `unsupported` 逐字记那**解不出来**的那几项（=> 而那按 BLUEPRINT 那 M3 那一行
 * 逐字要「报不支持」而**不**是静默跳过）。
 */
data class PeDependency(
    val requestedName: String,
    val file: File,
    val image: PeProcessImage,
    val resolved: Map<String, Long> = emptyMap(),
    val unsupported: List<String> = emptyList(),
)

class PeFormatException(message: String) : IllegalArgumentException(message)

/**
 * 按那搜索路径找依赖。
 *
 *  => 而那**它**逐字只做一件事：=> 而那 DLL 名逐字在**那一串目录**里落到哪一份。
 *  => 而那**它**逐字不替那程序自己声明的路径（=> 而那按 C-92，=> 而那**具体**的名字删掉了
 *     而这一行逐字还剩「按那库搜索路径找」这个**形状**）。
 */
private class DependencyResolver(searchPath: List<File>) {
    private val roots = searchPath.filter { it.isDirectory }

    /** 那一格：名字逐字（小写）-> 那一份文件。 */
    private val index: Map<String, File> by lazy {
        val out = LinkedHashMap<String, File>()
        for (root in roots) {
            val direct = root.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            for (f in direct) out.putIfAbsent(f.name.lowercase(), f)
        }
        out
    }

    fun resolve(imports: List<PeImport>): List<PeDependency> {
        val result = LinkedHashMap<String, PeDependency>()
        for (import in imports) {
            val key = import.dllName.lowercase()
            if (result.containsKey(key)) continue
            val file = find(import.dllName)
                ?: throw PeFormatException("dependent library not found (unsupported): ${import.dllName}")
            val depImage = PeLoader.parse(Files.readAllBytes(file.toPath()))
            val (resolved, unsupported) = matchSymbols(import.symbols, depImage.exports)
            result[key] = PeDependency(import.dllName, file, depImage, resolved, unsupported)
        }
        return result.values.toList()
    }

    private fun matchSymbols(
        symbols: List<PeImportSymbol>,
        exports: PeExports,
    ): Pair<Map<String, Long>, List<String>> {
        val resolved = LinkedHashMap<String, Long>()
        val unsupported = ArrayList<String>()
        for (symbol in symbols) {
            when (symbol) {
                is PeImportSymbol.Name -> {
                    val rva = exports.byName[symbol.name]
                    if (rva == null) unsupported += symbol.name
                    else resolved[symbol.name] = rva
                }
                is PeImportSymbol.Ordinal -> {
                    val rva = exports.byOrdinal[symbol.ordinal]
                    if (rva == null) unsupported += "#${symbol.ordinal}"
                    else resolved["#${symbol.ordinal}"] = rva
                }
            }
        }
        return resolved to unsupported
    }

    private fun find(name: String): File? {
        val candidates = if (name.endsWith(".dll", ignoreCase = true)) listOf(name) else listOf("$name.dll", name)
        for (candidate in candidates) {
            index[candidate.lowercase()]?.let { return it }
        }
        return null
    }
}

/** 从文件字节里读数的那一副（=> 而那**它**逐字是那**头**那一层，不读映射后的映像）。 */
private class PeReader(private val bytes: ByteArray) {
    var sections: List<PeSection> = emptyList()
        private set
    var isPe32Plus: Boolean = false
        private set

    fun u16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
    fun u32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
    fun u64(offset: Int): Long = buffer.getLong(offset)

    fun requireRange(offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) {
            throw PeFormatException("PE read outside file at $offset length $length")
        }
    }

    fun slice(offset: Int, length: Int): ByteArray {
        requireRange(offset, length)
        return bytes.copyOfRange(offset, offset + length)
    }

    fun cString(offset: Int, maxLength: Int): String {
        requireRange(offset, 0)
        val end = minOf(bytes.size, offset + maxLength)
        val actualEnd = (offset until end).firstOrNull { bytes[it].toInt() == 0 } ?: end
        return String(bytes, offset, actualEnd - offset, Charsets.ISO_8859_1)
    }

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

/**
 * 映射后的映像那一副（=> 而那**它**逐字按 RVA 读）。
 *
 *  => 而那 RVA 到映像偏移逐字就是**同一个数**：=> 而那映像**已经**是按各节自己的
 *     VirtualAddress 摆好的一份、=> 而而那**不**要再换算回文件偏移。
 *  => 而那**旧**的换算逐字是「拿文件偏移当映像偏移用」——=> 而而那**后果**是那导入名逐字
 *     读到了别的地方、=> 而而那**处置**是那一处换算逐字**删掉**。
 */
private class ImageView(val bytes: ByteArray, val pe32Plus: Boolean) {
    val size: Int get() = bytes.size

    fun u16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
    fun u32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
    fun u64(offset: Int): Long = buffer.getLong(offset)

    /** RVA -> 映像偏移（=> 而那**它**逐字是恒等；=> 而那越界就返回 -1）。 */
    fun rvaToOffset(rva: Long): Int = if (rva in 0 until bytes.size.toLong()) rva.toInt() else -1

    fun string(offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset
        while (end < bytes.size && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
    }

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
