package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.layout.PrefixProvisioner
import lobos.services.log.RuntimeDiagnostics
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ProgramManager
import lobos.services.reg.UnitEntry

object PieceEntryPointer {

    data class Located(

        val role: String,

        val name: String,

        val pointer: File,

        val landedRel: String,

        val detail: String,

        val tier: String = "",
    )

    data class LocatedAll(
        val role: String,
        val pointers: List<Located>,

        val missing: String = "",
    ) {
        fun firstOrNull(): Located? = pointers.firstOrNull()
    }

    fun roleOf(pe: UnitEntry): String =
        if (pe.assetEntry.isNotBlank()) ProgramInstallPipeline.ROLE_PROGRAM else ProgramInstallPipeline.ROLE_LIBRARY

    fun verDirOf(ctx: Context, pe: UnitEntry): File? {
        val version = pe.version.ifBlank { ProgramManager.currentVersion(ctx, pe.id).orEmpty() }
        if (version.isBlank()) return null
        return File(ProgramManager.stateDirOf(ctx, pe.id), version)
    }

    private fun pieceRoot(ctx: Context, id: String): File =
        lobos.kernel.layout.SystemDirs.pieceDir(ctx, id)

    fun shapeOf(ctx: Context, pe: UnitEntry): ProgramInstallPipeline.PieceShape.Piece? {
        verDirOf(ctx, pe)?.let { dir ->
            ProgramInstallPipeline.PieceShape.read(dir)?.let { return it }
        }
        val root = pieceRoot(ctx, pe.id)
        val kids = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        for (k in kids) {
            ProgramInstallPipeline.PieceShape.read(k)?.let { return it }
        }
        return null
    }

    fun locate(ctx: Context, pe: UnitEntry): Located? = locate(ctx, pe, shapeOf(ctx, pe))

    fun locate(
        ctx: Context,
        pe: UnitEntry,
        shape: ProgramInstallPipeline.PieceShape.Piece?,
    ): Located? = locateAll(ctx, pe, shape).firstOrNull()

    fun locateAll(
        ctx: Context,
        pe: UnitEntry,
        shape: ProgramInstallPipeline.PieceShape.Piece? = shapeOf(ctx, pe),
    ): LocatedAll {
        val role = roleOf(pe)

        if (shape == null) {
            return LocatedAll(
                role, emptyList(),
                "形态说明读不到（" + ProgramInstallPipeline.SHAPE_NAME + "）—— 形态是生产时就知道的事，" +
                    "运行期只读它；读不到就报缺，不探测字节、不猜",
            )
        }
        val libItems = shape.byRole(ProgramInstallPipeline.ROLE_LIBRARY)
        if (libItems.isEmpty()) {

            val progItems = shape.byRole(ProgramInstallPipeline.ROLE_PROGRAM)

            val progPointers = if (progItems.isNotEmpty()) {

                val progEntry = shape.entry.trim()
                val name = progEntry.substringAfterLast('/')
                val dir = ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_PROGRAM)
                if (name.isBlank() || dir == null) {
                    emptyList()
                } else {
                    listOf(
                        Located(
                            role = ProgramInstallPipeline.ROLE_PROGRAM,
                            name = name,
                            pointer = File(PrefixProvisioner.binDir(ctx), name),
                            landedRel = dir + "/" + name,
                            detail = "程序形态：入口指针按产物那一份 files[] 里 role=program 那一项定位（usr/bin/" + name + "）",
                            tier = "no-library",
                        )
                    )
                }
            } else {
                emptyList()
            }

            reportTier(ctx, pe, "no-library", progPointers.size, progPointers.map { it.name },
                hasLibraryItems = false)
            return LocatedAll(role, progPointers)
        }

        val layer = ProgramInstallPipeline.PieceShape.pointerLayerWithTier(
            shape, ProgramInstallPipeline.ROLE_LIBRARY, verDirOf(ctx, pe))
        val picked = layer.pointers
        if (picked.isEmpty()) {

            return LocatedAll(
                role, emptyList(),
                "库项存在但**开发名那一层**取不出（形态说明里没有 chain[0]、或择一不唯一）" +
                    "—— 报缺，不按首项、不按字典序、不猜",
            )
        }

        val out = mutableListOf<Located>()

        val progItems = shape.byRole(ProgramInstallPipeline.ROLE_PROGRAM)
        if (progItems.isNotEmpty()) {
            val pname = shape.entry.trim().substringAfterLast('/')
            val pdir = ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_PROGRAM)
            if (pname.isNotBlank() && pdir != null) {
                out.add(
                    Located(
                        role = ProgramInstallPipeline.ROLE_PROGRAM,
                        name = pname,
                        pointer = File(PrefixProvisioner.binDir(ctx), pname),
                        landedRel = pdir + "/" + pname,
                        detail = "程序形态：入口指针按产物那一份 files[] 里 role=program 那一项定位（usr/bin/" + pname + "）",

                        tier = "program-entry",
                    )
                )
            }
        }
        for (item in picked) {
            val base = item.path.substringAfterLast('/')
            if (base.isBlank()) continue
            val landed = ProgramInstallPipeline.PieceShape.landedRel(shape, item.path)
                ?: continue
            out.add(
                Located(
                    role = role,
                    name = base,
                    pointer = File(PrefixProvisioner.libDir(ctx), base),
                    landedRel = landed,
                    detail = "库形态：入口指针按落位目录 " + ProgramInstallPipeline.SHAPE_NAME +
                        " 里 files[] 的库形态那一条（软链层、 basename 等于 chain[0]）定位（usr/lib/" + base + "）",
                    tier = layer.tier,
                )
            )
        }
        if (out.isEmpty()) {
            return LocatedAll(
                role, emptyList(),
                "库项存在但**开发名那一层**取不出（择一之后算不出落位相对路径）—— 报缺，不按首项、不按字典序、不猜",
            )
        }

        val libNames = picked.map { it.path.substringAfterLast('/') }
        reportTier(ctx, pe, layer.tier, libNames.size, libNames, hasLibraryItems = true)
        return LocatedAll(role, out)
    }

    private fun reportTier(
        ctx: Context,
        pe: UnitEntry,
        tier: String,
        count: Int,
        names: List<String>,
        hasLibraryItems: Boolean,
    ) {
        RuntimeDiagnostics.append(
            ctx, "lib-entry-tier", true,
            "入口指针档位 " + tier,
            "id=" + pe.id + " 档位=" + tier +
                " 指针条数=" + count +
                " 指针=" + names.joinToString(",") +
                " 有库形态项=" + hasLibraryItems +
                " （一档逐字答的是「**这一件的库形态那几项**：它逐字有几条库链、那一档是什么形态」" +
                "；档名逐字是 single / chain0 / multi / 空 / no-library）",
        )
    }

    fun locate(ctx: Context, id: String): Located? =
        ProgramIndex.get(ctx, id)?.let { locate(ctx, it) }
}
