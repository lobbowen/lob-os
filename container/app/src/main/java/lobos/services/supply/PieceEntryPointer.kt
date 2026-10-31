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
 *
 * 缺陷名（C-102）**库形态的入口指针按 files[] 的**首项**取** ——
 *   而那首项逐字是「LC_ALL=C sort 的**副产品**」（=> 而那顺序逐字是路径字典序）、
 *   => 而它在 crypto / openssl / zlib 上逐字给的是**实体名**那一层（=> 而而那不是指针层）。
 *   而 01 裁的 S604-01-LIBRARY-ENTRY-LAYER-RULING.md §1.5 第 1 条逐字给的缺陷名是
 *   「取 files[] 的**首项**作为入口指针名，而首项是 LC_ALL=C sort 的**副产品**（无语义）」。
 *   而 §1.3 逐字裁的判别式是**两步**：
 *     甲：那一项在**落位目录里是软链**（=> 而不是普通文件）=> 而它才是「指针层」；
 *     乙：在甲给出的那些项里，取 basename 等于**该件 files[] 里 role=library 各项 chain[0]** 的那一个。
 *   => 而本件逐字**照那两步**实现、=> 而那 chain 那一格逐字是**产物自带**的
 *     （=> 而那 build 期 scripts/toolchain/elf-form.sh 的 elf_chain_names 写的）。
 *
 * 缺陷名（C-102）**库形态的入口指针逐字只取一条、=> 而而那一件逐字可能有好几条** ——
 *   而 01 裁的 S604-01 §1.4 第 3 条逐字：openssl 那一件逐字有**两条**链
 *   （libcrypto.so 与 libssl.so）、=> 而「**这一格不是「取第一个」**」。
 *   => 而本件逐字返回**全部**那几条、=> 而那落位侧逐字**逐条**建链。
 *
 * C-05 报缺口径（01 裁的 S604-01 §3.1 四档，每档都要说得出话）：
 *   1. 形态说明读不到 ⇒ 报缺（不探测字节、不猜）。
 *   2. 形态说明读得到但 role=library 的项一条都没有 ⇒ 报缺。
 *   3. 库项存在但**开发名那一层取不出**（chain[0] 取不到、或择一不唯一）⇒ 报缺
 *      （不按首项、不按字典序、不猜）—— zlib 是唯一一格依赖 chain[0] 才唯一的。
 *   4. **单层无软链**（curl：唯一库项是普通文件、chain 长度 1、soname 与 basename 同名）
 *      ⇒ **不报缺**，取那唯一库项的 basename 记为开发名，=> 而**显式记为「单层无软链」这一档**。
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
        /**
         * 这一条指针落在**哪一档**（=> 而那各档逐字是 01 裁的 S604-01 §3.1 那四档 + 程序形态那一档）：
         * "program" 程序形态 / "chain0" 甲+chain[0] 择一 / "single" 单层无软链 / "multi" 多指针档。
         */
        val tier: String = "",
    )

    /** 定位的结果。=> 而那库形态逐字**可以**有好几条（=> 而那 openssl 那一件逐字是两条）。 */
    data class LocatedAll(
        val role: String,
        val pointers: List<Located>,
        /** 报缺那一档的话（=> 而那 pointers 逐字为空时逐字是那一句）。 */
        val missing: String = "",
    ) {
        fun firstOrNull(): Located? = pointers.firstOrNull()
    }

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
     * 定位这一件的入口指针。返回 null = 定位不到（只有库形态会这样）。
     *  => 而那库形态逐字**可能**有多条、=> 而这一格逐字**取全部**（=> 而那第一条逐字是给老调用方用的）。
     */
    fun locate(ctx: Context, pe: UnitEntry): Located? = locate(ctx, pe, shapeOf(ctx, pe))

    /** 指定形态说明来定位（测试与诊断用）。 */
    fun locate(
        ctx: Context,
        pe: UnitEntry,
        shape: ProgramInstallPipeline.PieceShape.Piece?,
    ): Located? = locateAll(ctx, pe, shape).firstOrNull()

    /**
     * 定位这一件的**全部**入口指针。
     *
     * 缺陷名（C-102）**那一格逐字只取一条、=> 而而那一件逐字可能有好几条** ——
     *   => 而那 openssl 那一件逐字有两条链（libcrypto.so 与 libssl.so），
     *     => 而 01 裁的 S604-01 §1.4 第 3 条逐字「**这一格不是「取第一个」**」。
     *   => 而本件逐字返回**全部**那几条。
     */
    fun locateAll(
        ctx: Context,
        pe: UnitEntry,
        shape: ProgramInstallPipeline.PieceShape.Piece?,
    ): LocatedAll {
        val role = roleOf(pe)
        if (role == ProgramInstallPipeline.ROLE_PROGRAM) {
            val name = pe.assetEntry.substringAfterLast('/')
            if (name.isBlank()) {
                return LocatedAll(role, emptyList(), "注册表 assetEntry 逐字是空的 —— 报缺，不猜")
            }
            val dir = ProgramInstallPipeline.PieceShape.dirForRole(role)
                ?: return LocatedAll(role, emptyList(), "程序形态那一格逐字没有落位目录 —— 报缺，不猜")
            return LocatedAll(
                role,
                listOf(
                    Located(
                        role = role,
                        name = name,
                        pointer = File(PrefixProvisioner.binDir(ctx), name),
                        landedRel = dir + "/" + name,
                        detail = "程序形态：入口指针按注册表 assetEntry 的最后一段定位（usr/bin/" + name + "）",
                        tier = "program",
                    )
                ),
            )
        }
        //  => 而那以下逐字是**库形态**那一族（=> 而按 01 裁的 S604-01 §3.1 那四档处置）。
        if (shape == null) {
            return LocatedAll(
                role, emptyList(),
                "形态说明读不到（" + ProgramInstallPipeline.SHAPE_NAME + "）—— 形态是生产时就知道的事，" +
                    "运行期只读它；读不到就报缺，不探测字节、不猜",
            )
        }
        val libItems = shape.byRole(ProgramInstallPipeline.ROLE_LIBRARY)
        if (libItems.isEmpty()) {
            return LocatedAll(role, emptyList(), "这一件没有库形态的项 —— 报缺，不猜一个")
        }
        //  => 而那下面逐字**调**那唯一那套口径（=> 而按 C-104 判别源要唯一）：
        //    而那 ProgramInstallPipeline.PieceShape.pointerLayerOf 那一格逐字是**那唯一**一处、
        //    => 而而 PreinstalledPieces 那一族逐字**也调**它。
        //  => 而那档名逐字从那**唯一**那一格取（=> 而那 pointerLayerWithTier 那一格）、
        //     => 而而那「单层无软链」那一档逐字**显式**是 "single"
        //     （=> 而按 01 裁的 S604-01 §3.1 第 4 条逐字「并在这一格显式记『单层无软链』」）。
        val layer = ProgramInstallPipeline.PieceShape.pointerLayerWithTier(shape, role, verDirOf(ctx, pe))
        val picked = layer.pointers
        if (picked.isEmpty()) {
            //  => 而那**择一不出来**时（=> 而那三档：形态说明不带 chain[0] / 择一不唯一 / 指针层取不出）
            //     => 而那按 01 裁的 S604-01 §3.1 逐字**报缺**（=> 而不按首项、不按字典序、不猜）。
            return LocatedAll(
                role, emptyList(),
                "库项存在但**开发名那一层**取不出（形态说明里没有 chain[0]、或择一不唯一）" +
                    "—— 报缺，不按首项、不按字典序、不猜",
            )
        }
        //  => 而那 openssl 那一件逐字给**两条**（=> 而 libcrypto.so 与 libssl.so 各一条链）。
        //     => 而这一格逐字**不是**「取第一个」（=> 而 01 裁的 S604-01 §1.4 第 3 条）。
        val out = mutableListOf<Located>()
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
                        " 里 files[] 的库形态那一条（软链层、=> 而那 basename 等于 chain[0]）定位（usr/lib/" + base + "）",
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
        return LocatedAll(role, out)
    }


    fun locate(ctx: Context, id: String): Located? =
        ProgramIndex.get(ctx, id)?.let { locate(ctx, it) }
}
