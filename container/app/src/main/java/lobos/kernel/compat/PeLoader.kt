package lobos.kernel.compat

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class PeLoader private constructor(
    val image: PeProcessImage,
    val dependencies: List<PeDependency>,
) {

    companion object {
        private const val DOS_MAGIC = 0x5A4D
        private const val PE_SIGNATURE = 0x00004550L
        private const val FILE_ALIGNMENT = 0x200L
        private const val DEFAULT_SECTION_ALIGNMENT = 0x1000L

        private const val OPT_MAGIC_PE32 = 0x10B
        private const val OPT_MAGIC_PE32_PLUS = 0x20B

        fun load(executable: File, librarySearchPath: List<File>): PeLoader {
            val executableBytes = Files.readAllBytes(executable.toPath())
            val executableImage = parseImage(executableBytes)
            val resolver = DependencyResolver(librarySearchPath)
            val dependencies = resolver.resolve(executableImage.imports)
            return PeLoader(executableImage, dependencies)
        }

        fun load(ctx: Context, executable: File): PeLoader {
            val dirs = lobos.kernel.mm.LibPath.searchPath(ctx)
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map(::File)
            return load(executable, dirs)
        }

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

            val effectiveAlignment = if (sectionAlignment == 0L) DEFAULT_SECTION_ALIGNMENT else sectionAlignment
            val effectiveFileAlignment = if (fileAlignment == 0L) FILE_ALIGNMENT else fileAlignment
            val headerBytes = r.slice(0, sizeOfHeaders.toInt())
            val imageSize = if (sizeOfImage > 0L) sizeOfImage.toInt() else {
                sections.maxOfOrNull { alignUp(it.virtualAddress + it.virtualSize, effectiveAlignment) }?.toInt() ?: 0
            }
            if (imageSize < headerBytes.size) {
                throw PeFormatException("PE image size is smaller than its headers")
            }

            val mapped = ByteArray(imageSize)
            headerBytes.copyInto(mapped)

            for (section in sections) {
                if (section.virtualAddress >= mapped.size.toLong()) {
                    throw PeFormatException("section ${section.name} starts outside the image")
                }
                val copySize = minOf(section.rawSize, if (section.virtualSize != 0L) section.virtualSize else section.rawSize)
                if (copySize == 0L) continue
                r.requireRange(section.rawOffset.toInt(), copySize.toInt())
                val source = r.slice(section.rawOffset.toInt(), copySize.toInt())
                val destination = section.virtualAddress.toInt()
                if (destination.toLong() + copySize > mapped.size.toLong()) {
                    throw PeFormatException("section ${section.name} exceeds the mapped image")
                }
                source.copyInto(mapped, destination)
            }

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

        private fun directoryAt(
            r: PeReader,
            optional: Int,
            directoryBase: Int,
            directoryCount: Long,
            index: Int,
        ): Pair<Long, Long> {
            if (index.toLong() >= directoryCount) return 0L to 0L
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

        private fun parseThunks(view: ImageView, lookupRva: Long): List<PeImportSymbol> {
            if (lookupRva == 0L) return emptyList()
            var offset = view.rvaToOffset(lookupRva)
            if (offset < 0) return emptyList()
            val step = if (view.pe32Plus) 8 else 4

            val ordinalFlag = if (view.pe32Plus) Long.MIN_VALUE else 0x80000000L
            val result = ArrayList<PeImportSymbol>()
            while (offset >= 0 && offset + step <= view.size) {
                val value = if (view.pe32Plus) view.u64(offset) else view.u32(offset)
                if (value == 0L) break
                if (value and ordinalFlag != 0L) {
                    result += PeImportSymbol.Ordinal((value and 0xFFFFL).toInt())
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
            for (i in 0L until numberOfFunctions) {
                val at = view.rvaToOffset(functionsRva + i * 4)
                if (at < 0) continue
                byOrdinal[(ordinalBase + i).toInt()] = view.u32(at)
            }
            for (i in 0L until numberOfNames) {
                val nameAt = view.rvaToOffset(namesRva + i * 4)
                if (nameAt < 0) continue
                val symbolName = view.string(view.rvaToOffset(view.u32(nameAt)))
                val ordinalAt = view.rvaToOffset(ordinalsRva + i * 2)
                if (ordinalAt < 0) continue
                val ordinalIndex = view.u16(ordinalAt).toLong()
                val functionAt = view.rvaToOffset(functionsRva + ordinalIndex * 4)
                if (functionAt < 0) continue
                byName[symbolName] = view.u32(functionAt)
            }
            val dllName = if (nameRva == 0L) null else view.string(view.rvaToOffset(nameRva))
            return PeExports(dllName, byName, byOrdinal)
        }

        private fun alignUp(value: Long, alignment: Long): Long =
            if (alignment <= 1L) value else ((value + alignment - 1L) / alignment) * alignment
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

data class PeDependency(
    val requestedName: String,
    val file: File,
    val image: PeProcessImage,
    val resolved: Map<String, Long> = emptyMap(),
    val unsupported: List<String> = emptyList(),
)

class PeFormatException(message: String) : IllegalArgumentException(message)

private class DependencyResolver(searchPath: List<File>) {
    private val roots = searchPath.filter { it.isDirectory }

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
        val visiting = LinkedHashSet<String>()
        for (import in imports) resolveOne(import, result, visiting)
        return result.values.toList()
    }

    private fun resolveOne(
        import: PeImport,
        result: LinkedHashMap<String, PeDependency>,
        visiting: LinkedHashSet<String>,
    ) {
        val key = import.dllName.lowercase()
        if (result.containsKey(key)) return
        if (!visiting.add(key)) {
            throw PeFormatException("dependent library import cycle is unsupported: ${import.dllName}")
        }
        val file = find(import.dllName)
            ?: throw PeFormatException("dependent library not found (unsupported): ${import.dllName}")
        val depImage = PeLoader.parse(Files.readAllBytes(file.toPath()))
        val (resolved, unsupported) = matchSymbols(import.symbols, depImage.exports)
        if (unsupported.isNotEmpty()) {
            throw PeFormatException(
                "unsupported imports from ${import.dllName}: " + unsupported.joinToString(",")
            )
        }
        result[key] = PeDependency(import.dllName, file, depImage, resolved, unsupported)
        for (nested in depImage.imports) resolveOne(nested, result, visiting)
        visiting.remove(key)
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

private class PeReader(private val bytes: ByteArray) {
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

    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

private class ImageView(val bytes: ByteArray, val pe32Plus: Boolean) {
    val size: Int get() = bytes.size

    fun u16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
    fun u32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
    fun u64(offset: Int): Long = buffer.getLong(offset)

    fun rvaToOffset(rva: Long): Int = if (rva in 0 until bytes.size.toLong()) rva.toInt() else -1

    fun string(offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset
        while (end < bytes.size && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
    }

    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
