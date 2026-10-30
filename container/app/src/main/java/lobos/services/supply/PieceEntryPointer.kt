package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.layout.PrefixProvisioner
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ProgramManager
import lobos.services.reg.UnitEntry

/**
 * 入口指针在哪 —— 按形态分两支（01 裁定：回退的主体集合是**所有在册的件**，不分形态）。
 *
 * C-103（这一族为什么有两边）：
 *   入口指针的**位置**这一族有两个来源，而以前实现只按其中一个算：
 *   · 程序形态：注册表 assetEntry（形如 bin/xxx）—— 这一条对程序形态成立。
 *   · 库形态：注册表 assetEntry 为空（纯库件没有程序那一侧），
 *     而入口指针仍然存在（usr/lib/<name>），它的名字**只能**从**落位目录里产物自带的**
 *     piece-shape.json 的 files[] 里 role=="library" 的那一条取。
 *   而注册表那一格 libName 只是**派生索引**（它是「那份库字节的名字」那一格），
 *   不是入口指针的权威来源。
 *
 * 缺陷名（C-102）**库形态的入口指针按注册表那一格拼名字**：拼出来的是空名
 *   （usr/lib/ 这一条「没有名字的入口」），而「入口是不是软链」「软链当前指着谁」这一族
 *   全部无从判断 => 而「被接管没有」「回退成没成」这两问就答不出。
 *   现在：库形态的名字只从落位目录那份 piece-shape.json 取；取不到就报缺（不猜）。
 *
 * 缺陷名（C-102）**库形态的入口指针拼在 usr/lib/ 下却按 bin/ 那一条算**：
 *   而 OTA 那条路径（PieceUpdater.place）把新版本落到**新的版本目录**，
 *   而那一份目录里**没有** piece-shape.json（形态说明是产物带来的，OTA 包不带）。
 *   而库形态的入口名字**只能**从形态说明取 => 而那一支就取不到 =>
 *   而「OTA 那一件是库形态」就**没有任何一条路径能算出入口指针**。
 *   现在：形态说明按「登记那一版的落位目录 -> 该件落位根下别的版本目录」逐个找，
 *   找到就用；找不到就报缺（不拿注册表那一格派生索引顶替）。
 */
object PieceEntryPointer {

    /** 定位到的一个入口指针。 */
    data class Located(
        /** program 或 library。 */
        val role: String,
        /** 入口指针的名字（usr/bin/<name> 或 usr/lib/<name> 里的那一个）。 */
        val name: String,
        /** 入口指针的路径（那条软链）。 */
        val pointer: File,
        /** 落位后那一份在落位目录里的相对路径（bin/<name> 或 lib/<name>）。 */
        val landedRel: String,
        /** 定位依据（人话，写进诊断）。 */
        val detail: String,
    )

    /** 形态：注册表 assetEntry 非空 = 程序形态；空 = 库形态。 */
    fun roleOf(pe: UnitEntry): String =
        if (pe.assetEntry.isNotBlank()) ProgramInstallPipeline.ROLE_PROGRAM else ProgramInstallPipeline.ROLE_LIBRARY

    /** 登记那一版的落位目录。取不到就报缺（不猜）。 */
    fun verDirOf(ctx: Context, pe: UnitEntry): File? {
        val version = pe.version.ifBlank { ProgramManager.currentVersion(ctx, pe.id).orEmpty() }
        if (version.isBlank()) return null
        return File(ProgramManager.stateDirOf(ctx, pe.id), version)
    }

    /** 这一件的落位根（opt/<id>）。 */
    private fun pieceRoot(ctx: Context, id: String): File =
        lobos.kernel.layout.SystemDirs.pieceDir(ctx, id)

    /**
     * 找这一件的形态说明（落位目录里那份 piece-shape.json）。
     * 顺序：登记那一版的落位目录 -> 该件落位根下别的版本目录（版本名倒序）。
     * 找不到返回 null（报缺，不猜）。
     */
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

    /**
     * 定位这一件的入口指针。返回 null = 定位不到（只有库形态会这样：
     * 落位目录里读不到 piece-shape.json，或其中没有 role=library 的那一条）。
     */
    fun locate(ctx: Context, pe: UnitEntry): Located? = locate(ctx, pe, shapeOf(ctx, pe))

    /** 指定形态说明来定位（测试与诊断用）。 */
    fun locate(
        ctx: Context,
        pe: UnitEntry,
        shape: ProgramInstallPipeline.PieceShape.Piece?,
    ): Located? {
        val role = roleOf(pe)
        if (role == ProgramInstallPipeline.ROLE_PROGRAM) {
            val name = pe.assetEntry.substringAfterLast('/')
            if (name.isBlank()) return null
            val dir = ProgramInstallPipeline.PieceShape.dirForRole(role) ?: return null
            return Located(
                role = role,
                name = name,
                pointer = File(PrefixProvisioner.binDir(ctx), name),
                landedRel = dir + "/" + name,
                detail = "程序形态：入口指针按注册表 assetEntry 的最后一段定位（usr/bin/" + name + "）",
            )
        }
        if (shape == null) return null
        val libItem = shape.byRole(ProgramInstallPipeline.ROLE_LIBRARY).firstOrNull() ?: return null
        val name = libItem.path.substringAfterLast('/')
        if (name.isBlank()) return null
        val landedRel = ProgramInstallPipeline.PieceShape.landedRel(shape, libItem.path) ?: return null
        return Located(
            role = role,
            name = name,
            pointer = File(PrefixProvisioner.libDir(ctx), name),
            landedRel = landedRel,
            detail = "库形态：入口指针按落位目录 " + ProgramInstallPipeline.SHAPE_NAME +
                " 里 files[] 的库形态那一条定位（usr/lib/" + name + "）",
        )
    }

    fun locate(ctx: Context, id: String): Located? =
        ProgramIndex.get(ctx, id)?.let { locate(ctx, it) }
}
