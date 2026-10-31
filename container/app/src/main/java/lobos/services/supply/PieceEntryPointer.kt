package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.layout.PrefixProvisioner
import lobos.services.log.RuntimeDiagnostics
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
         * "no-library" 这一件逐字没有库形态那几项 / "single" 单层无软链
         * / "chain0" 甲+chain[0] 择一出来恰好一条 / "multi" 择一出来多于一条 / "" 算不出来。
         *   => 而那档名逐字是 01 裁的 S610-01 §2.4 那五档（=> 而那 **"program" 那一档逐字取消**）。
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

    /**
     * 形态：注册表 assetEntry 非空 = 程序形态；空 = 库形态。
     *
     * 缺陷名（C-102）**这一格逐字把 assetEntry 非空当成「形态是什么」** ——
     *   => 而 01 裁的 S610-01-MIXED-SHAPE-TIER-RULING.md §2.2 第 3 条逐字：
     *     「『注册表 `assetEntry` 非空』那一格逐字答的是「**有没有程序入口**」，
     *      **不是**「形态是什么**」」
     *     ——依据逐字：ProgramInstallPipeline.kt:948 到 :953 那一段逐字写
     *     「程序形态 `assetEntry` = 落位后那一条／库形态 `assetEntry` 空（这一件没有程序入口）」、
     *     :970 逐字 `assetEntry = if (isProgram) entryRel else ""`（**逐字是派生索引**）。
     *   => 而而这一格逐字把那**派生索引**当成了形态、=> 而那**注释**逐字也这么写
     *     （=> 而那正是缺陷名那一半）。
     *   => 而那**三格各答一件事**（依 C-105 不合并）：
     *     · 「这一件是什么形态」=> 归产物那一份的 `kind`（program / library / mixed / unknown）；
     *     · 「有没有程序入口」=> 归 files[] 里 `role=program` 那一族；
     *     · 「这一件有几条库链」=> 归本件那一档（tier）。
     */
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
     *
     * 缺陷名（C-102）**那 shape 那一格逐字是必填、=> 而而调用方逐字漏传就编译不过** ——
     *   => 而那旧版逐字是 `shape: ...Piece?`（=> 而**没有**默认值），
     *     => 而那 3ec28a6 那一笔那四处逐字改成了 `locateAll(ctx, e)`（=> 而那**两参**形态）
     *     => 而那**两参**形态逐字**没有**、=> 而那四处逐字编译不过
     *     （=> 而那真机读数：run 41193831000 / job 124512628916 逐字
     *       「PieceUpdater.kt:338:44 No value passed for parameter 'shape'」四行）。
     *   => 而那**处置**：=> 而那 shape 那一格逐字给**默认值**
     *     （=> 而那默认值逐字就是 `shapeOf(ctx, pe)`），
     *     => 而而那与上面那个两参 `locate` 逐字**同一口径**
     *     （=> 而那 :126 逐字 `locate(ctx, pe, shapeOf(ctx, pe))`）
     *     => 而而那**不引入第二处真相**（=> 而那形态说明那一格逐字**只有一处**来源：shapeOf）。
     *   => 而那**调用方**逐字**不必**自己读那形态说明（=> 而那四处上下文里逐字**没有**读好的 shape
     *     => 而那若逐字让它们各自读、=> 而那就是**四处**重复读同一份）。
     */
    fun locateAll(
        ctx: Context,
        pe: UnitEntry,
        shape: ProgramInstallPipeline.PieceShape.Piece? = shapeOf(ctx, pe),
    ): LocatedAll {
        val role = roleOf(pe)
        //  => 而那**程序形态**那一支逐字**不再**早 return（=> 而那是 C-997 那一族的落点）：
        //     => 而那旧版逐字是 `if (role == ROLE_PROGRAM) { … return prog }`
        //       => 而而那 openssl 那一件逐字**有**程序入口（bin/openssl）=> 而而它逐字被归到 program 那一支
        //       => 而而那它那**两条库指针**在那一档里逐字**读不到**
        //       （=> 而 07 本轮实测逐字：设备侧 diag.jsonl 逐字把 openssl 报成 program 档位）。
        //     => 而那 openssl 那一件逐字是 **kind=mixed**
        //       （=> 而那 06 归档那件包的 piece-shape.json 逐字：programs=["bin/openssl"]、
        //         libraries=["lib/libcrypto-lobos.so","lib/libcrypto.so","lib/libssl-lobos.so","lib/libssl.so"]）。
        //     => 而那**判据**逐字改成「那一件逐字有没有 `role=library` 的项」
        //       （=> 而按 01 裁的 S610-01 §2.4 逐字「**`program` 那一档逐字取消**、=> 而改成 **`no-library`**」）。
        if (shape == null) {
            return LocatedAll(
                role, emptyList(),
                "形态说明读不到（" + ProgramInstallPipeline.SHAPE_NAME + "）—— 形态是生产时就知道的事，" +
                    "运行期只读它；读不到就报缺，不探测字节、不猜",
            )
        }
        val libItems = shape.byRole(ProgramInstallPipeline.ROLE_LIBRARY)
        if (libItems.isEmpty()) {
            //  => 而那**新档** no-library（=> 而按 01 裁的 S610-01 §2.4 第 5 条逐字：
            //     「**那一件逐字没有 `role=library` 的项**（`items.isEmpty()`）
            //      => 而它逐字**不是缺**（**它是程序形态那一族**）」）。
            //  => 而而那**程序入口**逐字**照样**要给（=> 而那那一件逐字有程序入口）。
            val progItems = shape.byRole(ProgramInstallPipeline.ROLE_PROGRAM)
            val progPointers = if (progItems.size == 1) {
                //  => 而那**程序入口**逐字**不**由注册表 assetEntry 答
                //     （=> 而按 01 裁的 S610-01 §2.2 第 3 条逐字「『有没有程序入口』那一格逐字归
                //       **产物那一份的 `files[]` 里 `role=program` 那一族**」、
                //      => 而那 assetEntry 那一格逐字是**派生索引**、=> 而那它逐字不是那一格的真源）。
                val prog = progItems.first()
                val name = prog.path.substringAfterLast('/')
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
            //  => 而那一档逐字**有出口**（=> 而那一档逐字是 "no-library"、=> 而而它逐字算出来了、
            //   => 而而它逐字**同样**不能只赋值不报）。
            reportTier(ctx, pe, "no-library", progPointers.size, progPointers.map { it.name },
                hasLibraryItems = false)
            return LocatedAll(role, progPointers)
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
        //  => 而那**程序入口**逐字**也**要给（=> 而那 openssl 那一件逐字是 **kind=mixed**：
        //     => 而它逐字**既有**程序入口（bin/openssl）**又有**两条库链）。
        //     => 而而那程序入口逐字**不**由注册表 assetEntry 答（=> 而按 01 裁的 S610-01 §2.2 第 3 条逐字
        //       「『有没有程序入口』那一格逐字归**产物那一份的 `files[]` 里 `role=program` 那一族**」）。
        val progItems = shape.byRole(ProgramInstallPipeline.ROLE_PROGRAM)
        if (progItems.size == 1) {
            val prog = progItems.first()
            val pname = prog.path.substringAfterLast('/')
            val pdir = ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_PROGRAM)
            if (pname.isNotBlank() && pdir != null) {
                out.add(
                    Located(
                        role = ProgramInstallPipeline.ROLE_PROGRAM,
                        name = pname,
                        pointer = File(PrefixProvisioner.binDir(ctx), pname),
                        landedRel = pdir + "/" + pname,
                        detail = "程序形态：入口指针按产物那一份 files[] 里 role=program 那一项定位（usr/bin/" + pname + "）",
                        tier = layer.tier,
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
        //  缺陷名（C-102）**那一档逐字算出来了、赋到值了，而逐字没有任何一处读它、逐字也没有任何一处把它报出来 ⇒ 那一族等于没做**
        //    => 而那旧版逐字是 `tier = layer.tier`（=> 而那**赋值**逐字**没有消费方**：
        //      而 Lead 本机实跑 `grep -rn '\.tier' --include=*.kt container/` 逐字命中**只有 1 处**、=> 而那**就是**这一行赋值）。
        //    => 而那与 C-993 逐字**同一形状**（=> 而那判别式被写了、被赋值了、=> 而没有任何一处读它）。
        //    => 而那**后果**：=> 而那设备侧逐字读不到它（=> 而 07 本轮实读：single / chain0 / multi /
        //      pointerLayer / 单层无软链 在 diag.jsonl 与 events.jsonl 里逐字**全部 0**，
        //      => 而那阳性对照逐字 diag 的 "stage": 命中 **914**、events 的 "category":"diag: 命中 **470**）
        //      => 而而那读不到它是「**那一档没有出口**」、=> 而**不是**「那一笔没进设备」。
        //    => 而那**处置**：=> 而那**出口**逐字是那诊断那一族（=> 而那 RuntimeDiagnostics.append
        //      逐字写进 diag.jsonl、=> 而那正是 07 能读到的那一份）。
        //      => 而而那**为什么**是诊断那一族而不是 Journal.events：
        //        · 而那 Journal.events 那一族逐字是「**动作**」的记录（=> 而那它逐字按 category 分）；
        //          而而那一档逐字是「**这一件有几条链、那一档是什么形态**」那一个**事实**、
        //          => 而那它逐字是**形状**那一类、=> 而那 diag.jsonl 那一族逐字就是那形状那一族。
        //        · 而那 07 本轮实读逐字：events.jsonl 里 "category":"diag: 命中 470
        //          => 而那 events 那一族逐字**也**收 diag 那一族的事、=> 而而那两条**都能**读到。
        //      => 而而那**判据侧**那一格（=> 而那档名口径 program / single / chain0 / multi / 空）**归 01**裁。
        reportTier(ctx, pe, layer.tier, out.size, out.map { it.name }, hasLibraryItems = true)
        return LocatedAll(role, out)
    }

    /**
     * 那一档的**出口**（=> 而它逐字把那一档**报出来**）。
     *
     * 缺陷名（C-102）**那一档逐字算出来了、赋到值了，而逐字没有任何一处读它、逐字也没有任何一处把它报出来 ⇒ 那一族等于没做**
     *   => 而那旧版逐字是 `tier = layer.tier`（=> 而那**赋值**逐字**没有消费方**：
     *     而 Lead 本机实跑 `grep -rn '\.tier' --include=*.kt container/` 逐字命中**只有 1 处**、
     *     => 而那**就是**那一行赋值）。
     *   => 而那与 C-993 逐字**同一形状**（=> 而那判别式被写了、被赋值了、=> 而没有任何一处读它）。
     *   => 而那**后果**：=> 而那设备侧逐字读不到它
     *     （=> 而 07 本轮实读：single / chain0 / multi / pointerLayer / 单层无软链
     *       在 diag.jsonl 与 events.jsonl 里逐字**全部 0**，
     *       => 而那阳性对照逐字 diag 的 "stage": 命中 **914**、events 的 "category":"diag: 命中 **470**）
     *     => 而而那读不到它是「**那一档没有出口**」、=> 而**不是**「那一笔没进设备」。
     *   => 而那**处置**：=> 而那**出口**逐字是那诊断那一族
     *     （=> 而那 RuntimeDiagnostics.append 逐字写进 diag.jsonl、=> 而那正是 07 能读到的那一份）。
     *   => 而而那**为什么**是诊断那一族而不是 Journal.events：
     *     · 而那 Journal.events 那一族逐字是「**动作**」的记录（=> 而那它逐字按 category 分）；
     *       而而那一档逐字是「**这一件有几条链、那一档是什么形态**」那一个**事实**、
     *       => 而那它逐字是**形状**那一类、=> 而那 diag.jsonl 那一族逐字就是那形状那一族。
     *     · 而那 07 本轮实读逐字：events.jsonl 里 "category":"diag: 命中 470
     *       => 而那 events 那一族逐字**也**收 diag 那一族的事、=> 而而那两条**都能**读到。
     *   => 而而那**判据侧**那一格（=> 而那档名口径 program / single / chain0 / multi / 空）**归 01**裁。
     */
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
                " （=> 而那一档逐字答的是「**这一件的库形态那几项**：它逐字有几条库链、那一档是什么形态」" +
                "；=> 而那档名逐字是 single / chain0 / multi / 空 / no-library）",
        )
    }


    fun locate(ctx: Context, id: String): Located? =
        ProgramIndex.get(ctx, id)?.let { locate(ctx, it) }
}
