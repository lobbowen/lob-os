package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import lobos.services.log.Journal
import lobos.kernel.elf.ElfFacts
import lobos.kernel.layout.SystemDirs
import lobos.kernel.layout.PrefixProvisioner
import org.json.JSONArray
import org.json.JSONObject

object ProgramInstallPipeline {

    private const val LIB_TOKEN = "\$LIB"
    private const val PLATFORM_TOKEN = "\$PLATFORM"

    //  形态：只从产物读。用户 2026-10-20 三行规则：构建时就知道哪些是库、哪些是程序（生产时就知道的事）；
    //  库装到库目录、程序装到程序目录；注册表只记「库在哪里」与「程序在哪里」，运行期只读、不判断、不探测。
    // 形态由构建期算好（scripts/toolchain/elf-form.sh 照 ExecBits.formOf 那把尺），写进产物自带的 piece-shape.json；
    // 运行期读不到就报缺，不探测那份字节、不猜、不兜底。
    // 取值域与构建期一致：form ∈ {exec, dyn-interp, dyn-plain, unknown}（逐文件）、
    //  role ∈ {program, library, unknown}（逐文件，按 form 判）、kind ∈ {program, library, mixed, unknown}（件整体）。
    const val SHAPE_NAME = "piece-shape.json"
    const val ROLE_PROGRAM = "program"
    const val ROLE_LIBRARY = "library"
    //  shell 是派生的（01 裁的 S615-01 §3.1）：能 `-c` 跑一条命令 + 有程序入口 + 自己那行为是解释命令，
    //  三格全中才是；它落程序目录（属 program 那一边）。
    const val ROLE_SHELL = "shell"

    /** 产物自带的形态说明：构建期算好（`scripts/toolchain/elf-form.sh` 照 `ExecBits.formOf`），随包落进落位目录。
     *  运行期只读它，读不到返回 null、调用方报缺，不探测字节、不判断、不兜底（用户 2026-10-20 三行规则第三条）。
     *  缺陷 C-102 的处置：替代 [PieceScan.roleFromDisk]（它 walk 落位目录读 ELF 头）——形态是生产时就知道的事。
     *  取值域同构建期：form ∈ {exec, dyn-interp, dyn-plain, unknown}、role ∈ {program, library, unknown}、
     *  kind ∈ {program, library, mixed, unknown}。
     */
    object PieceShape {

        data class FileShape(
            val path: String,
            val dir: String,
            val role: String,
            val mode: String,
            val sha256: String,
            /** 那一层的链（开发名 -> SONAME -> 实体），产物自带（构建期 `elf_chain_names` 写的），chain[0] 是开发名那层。
             *  缺陷 C-102：旧版形态说明只读 path/dir/role/mode/sha256 五格、把开发名整丢了，运行期无从问起，
             *  入口指针名只能取 `files[]` 首项（LC_ALL=C sort 的副产品，常常是实体名那层）。现在按 chain 判。
             */
            val chain: List<String> = emptyList(),
            /** 那一项自述的 SONAME（产物自带，构建期 `elf-form.sh` 写的）。
             *  缺陷 C-102：旧版只读五格，「哪一层是开发名」「哪一层是实体」在运行期无从问起；现在 chain 与 soname 都读进来。
             */
            val soname: String = "",
        )

        data class Piece(
            val kind: String,
            val entry: String,
            val files: List<FileShape>,
            /** 派生物（构建期算的「哪些是派生物」）：作者声明的「这一件在 usr/bin 下该有哪几个命令名」。
             *  busybox 是那 18 个 applet 名（cat、cp、df…vi），本体（bin/libbusybox.so）不在里面（它不认自己的文件名）；
             *  openssl 是空的（它那两项都是实体、各自是命令）。 */
            val derivedLinks: List<String> = emptyList(),
            /** 那件额外要的层（bin/ 与 lib/ 不在这里）：git 要 libexec（子命令目录）、cmake 要 share（模块目录）。
             *  它们是落位事实，落位侧照它们整层搬。 */
            val extraDirs: List<String> = emptyList(),
            /**
             * 系统角色（这一件在 LOB-OS 那一份 Linux 上扮演什么角色）。
             * 缺陷 C-102：`files[]` 的 `role` 是形态那把尺，件整体角色（exec/shell/library/…）在 `component-meta.json` 里，
             *   两把尺对不上：`PreinstalledPieces.kt:297` 只从 `files[]` 取，落位侧与注册表也都 program，shell 永远答不出。
             * 处置（Lead 采纳的甲）：构建期由 `elf-form.sh` 从 `component-meta.json` 派生写进 `piece-shape.json`，这一格只读它；读不到是空（C-05 报缺、不猜填）。
             */
            val systemRole: String = "",
        ) {
            fun roleOf(rel: String): String? = files.firstOrNull { it.path == rel }?.role

            fun byRole(role: String): List<FileShape> = files.filter { it.role == role }
        }

        /** 读产物自带的形态说明。**读不到就是读不到** —— 返回 null，不去探测那份字节。 */
        fun read(dir: File): Piece? {
            val f = File(dir, SHAPE_NAME)
            if (!f.isFile) return null
            val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
            val arr = o.optJSONArray("files")
            val files = mutableListOf<FileShape>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val fo = arr.optJSONObject(i) ?: continue
                    val p = fo.optString("path", "").trim()
                    if (p.isEmpty()) continue
                    files.add(
                        FileShape(
                            p,
                            fo.optString("dir", "").trim(),
                            fo.optString("role", "").trim(),
                            fo.optString("mode", "").trim(),
                            fo.optString("sha256", "").trim(),
                            //  chain 是产物自带的那一格（构建期 `elf_chain_names` 写的）；缺了是空（C-05，不猜填）。
                            (fo.optJSONArray("chain") ?: JSONArray()).let { ca ->
                                (0 until ca.length()).mapNotNull { j ->
                                    ca.optString(j, "").trim().takeIf { it.isNotEmpty() }
                                }
                            },
                            //  soname 也是产物自带的（构建期 `elf_soname` 写的）。
                            fo.optString("soname", "").trim(),
                        )
                    )
                }
            }
            return Piece(
                kind = o.optString("kind", "").trim(),
                entry = o.optString("entry", "").trim(),
                //  系统角色读产物自带的那一份；缺了是空（C-05，不猜填）。
                systemRole = o.optString("systemRole", "").trim(),
                files = files,
                derivedLinks = (o.optJSONArray("derivedLinks") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
                    }
                },
                extraDirs = (o.optJSONArray("extraDirs") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                },
            )
        }

        /** 形态  落位目录（用户三行规则第二条：库装到库目录，程序装到程序目录）。
         *   判据是**形态**，不是原路径的首段目录名 —— 那是「看目录名」（§10.1:128 已判无效）。 */
        fun dirForRole(role: String): String? = when (role) {
            ROLE_PROGRAM -> "bin"
            ROLE_LIBRARY -> "lib"
            //  shell 是程序形态那一族的一个子类（有程序入口、落程序目录），是派生的（01 裁的 S615-01 §3.1 三格判据）：
            //  它不是新的落位形态，只是 program 那一边。
            ROLE_SHELL -> "bin"
            else -> null
        }

        /** 落位后的件内相对路径 —— **只由形态决定**，是纯函数（两边各自算，结果一致）。
         *  认不出的形态返回 null（报缺，不猜）。 */
        fun landedRel(piece: Piece, rel: String): String? {
            val role = piece.roleOf(rel) ?: return null
            val dir = dirForRole(role) ?: return null
            return dir + "/" + rel.substringAfterLast('/')
        }

        /**
         * 那一件该建哪几条入口指针（按形态）。缺陷 C-102：旧版只取 `files[]` 首项，而首项是 LC_ALL=C sort 的副产品（路径字典序），
         *   在 crypto / openssl / zlib 上给的是实体名那层、不是指针层（01 裁的 S604-01 §1.5 第 1 条）。判别式两步（S604-01 §1.3）：
         *   甲——那项是软链；乙——取 basename 等于该件 `files[]` 里 role=library 各项 `chain[0]` 的那一个。口径唯一（C-104），
         *   PieceEntryPointer 与 PreinstalledPieces 都调它。单层无软链（curl）显式处理、不是缺；择一不出来返回空，调用方报缺。
         */
        fun pointerLayerOf(piece: Piece, role: String, verDir: File?): List<FileShape> =
            pointerLayerWithTier(piece, role, verDir).pointers

        /**
         * 那一件该建哪几条入口指针，外加它落在哪一档。缺陷 C-102：旧版那一档没报出来、curl 被标成另一档——
         *   01 裁的 S604-01 §3.1 第 4 条要求显式记『单层无软链』，而它被混进 chain0，调用方分不出 curl 与 crypto 两档。
         * 各档（01 裁的 S610-01 §2.4 五档，program 那一档取消）：no-library 没有 role=library 的项（不是缺）、single
         *   单层无软链（唯一库项是普通文件、chain 长度 1、soname 与 basename 同名）、chain0 甲+chain[0] 择一、multi
         *   多于一条（openssl 是两条）、空串算不出来（pointers 空、调用方报缺）。 */
        fun pointerLayerWithTier(piece: Piece, role: String, verDir: File?): PointerLayer {
            val items = piece.byRole(role)
            //  新口径（01 裁的 S610-01-MIXED-SHAPE-TIER-RULING.md §2.4）：program 那一档取消、改成 no-library，
            //    判据是「那一件有没有 `role=library` 的项」，不是「注册表 assetEntry 非空」；也不由 roleOf 那票决定
            //    （roleOf 答的是「有没有程序入口」）。
            //  空那一档先判（`items.first()` 在空时抛异常）：旧版把它判在后面、一支 unguarded。
            if (items.isEmpty()) {
                //  没有 role=library 的项是 "no-library"（01 裁的 S610-01 §2.4 第 5 条：它不是缺）。
                return PointerLayer(emptyList(), "no-library")
            }
            if (role != ROLE_LIBRARY) {
                //  程序角色只有一条入口、不参与择一；档名不是 "program"（那一档取消了），是 "no-library"。
                return PointerLayer(listOf(items.first()), "no-library")
            }
            //  甲：那项在落位目录里是软链。优先用产物自带的 `derivedLinks`（它记的就是「哪些是派生物」）。
            //  「单层无软链」那一档（curl）先判：唯一库项是普通文件、chain 长度 1、soname 与 basename 同名，不是缺
            //    （01 裁的 S604-01 §3.1 第 4 条：取唯一库项的 basename 记为开发名，并显式记『单层无软链』）。
            //    判据是「唯一库项的 chain 长度 == 1 且 soname == basename」，用产物自带那两格、不必看落位目录。
            if (items.size == 1) {
                val only = items.first()
                val base = only.path.substringAfterLast('/')
                val chainLen = only.chain.size
                val sonameSelf = only.soname.isNotBlank() && only.soname == base
                if (chainLen == 1 && sonameSelf) {
                    //  「单层无软链」显式记为它自己（01 裁的 S604-01 §3.1 第 4 条）。
                    return PointerLayer(listOf(only), "single")
                }
            }
            val soft = softLinkPathsOf(piece, verDir)
                ?: return PointerLayer(emptyList(), "")   //  两处都读不到 报缺
            val pointerLayer = items.filter { soft.contains(it.path) }
            if (pointerLayer.isEmpty()) {
                //  **有**好几项、 没有一项是软链、 不是「单层」、
                //     **报缺**（ 按 C-05 不猜）。
                return PointerLayer(emptyList(), "")
            }
            //  乙：按 chain[0] 择一。
            val roots = items.mapNotNull { it.chain.firstOrNull()?.takeIf { s -> s.isNotBlank() } }.toSet()
            if (roots.isEmpty()) return PointerLayer(emptyList(), "")   //   chain[0] 读不到 报缺
            val picked = pointerLayer.filter { roots.contains(it.path.substringAfterLast('/')) }
            val tier = if (picked.size > 1) "multi" else "chain0"
            return PointerLayer(picked, tier)
        }

        /** 那一档的结果：那几条指针 + 它落在哪一档（档名见 pointerLayerWithTier）。 */
        data class PointerLayer(
            val pointers: List<FileShape>,
            val tier: String,
        )

        /**
         * 落位目录里哪些项是软链（相对路径，带那一层目录名）。优先用产物自带的 `derivedLinks`，
         *  读不到时看落位目录本身；两处都读不到返回 null（调用方报缺、不猜）。
         */
        fun softLinkPathsOf(piece: Piece, verDir: File?): Set<String>? {
            if (piece.derivedLinks.isNotEmpty()) return piece.derivedLinks.toSet()
            val dir = verDir ?: return null
            if (!dir.isDirectory) return null
            val out = mutableSetOf<String>()
            for (sub in listOf(File(dir, "lib"), File(dir, "bin"))) {
                val kids = sub.listFiles() ?: continue
                for (f in kids) {
                    val isLink = runCatching {
                        java.nio.file.Files.isSymbolicLink(f.toPath())
                    }.getOrDefault(false)
                    if (isLink) out.add(sub.name + "/" + f.name)
                }
            }
            return if (out.isEmpty()) null else out
        }
    }

    /** 落位的结果：落位后入口的件内相对路径 + 它的形态 + 整件的形态 + 那一件自己的字节名。
     *  最后一格给纯库件：库件没有入口，注册表仍要记「库在哪里」——记那份库字节的名字（按形态取，不按目录名）。 */
    data class Landed(
        val entryRel: String,
        val role: String,
        val kind: String,
        val moved: List<String>,
        val libName: String = "",
        /** 诊断读数： 那一支搬动前后每一项是软链还是实体 + 摘要（ 默认空， 只在 install 那条路填）。 */
        val probe: String = "",
    )

    /** 落位失败的原因（报缺用）。 */
    data class LandFailure(val reason: String, val detail: String)

    /** 按产物里带的那一条落位：形态决定目录（库进库目录、程序进程序目录）。
     *  缺陷 C-102：旧版按目录名分家、不看形态（把解包目录整个搬过去，bin/ 归 bin/、lib/ 归 lib/），于是「同一件里
     *  程序形态的字节在 lib/ 下」被原样保留，入口按清单 entry（lib/ 那份）建链，那几件再也调不起来（今天五件失败的成因）。
     *  现在形态决定落位目录：程序形态进 bin/，库形态进 lib/。
     *  形态从产物自带的 piece-shape.json 读（构建期算好）；读不到就报缺，不探测字节、不猜、不兜底（用户三行规则第三条）。
     */
    fun landByShape(dest: File, shape: PieceShape.Piece, declaredEntry: String, diagnostic: Boolean = false): Pair<Landed?, LandFailure?> {
        //  目标映射：包内相对路径 -> 落位后相对路径（**只由形态决定**）
        val map = LinkedHashMap<String, String>()
        for (fs in shape.files) {
            val dir = PieceShape.dirForRole(fs.role)
                ?: return null to LandFailure(
                    "shape-form-unknown",
                    "产物声明的 " + fs.path + " 形态是 \"" + fs.role + "\"（认不出）—— 报缺，不猜它该落哪",
                )
            val target = dir + "/" + fs.path.substringAfterLast('/')
            val clash = map.entries.firstOrNull { it.value == target && it.key != fs.path }
            if (clash != null) {
                return null to LandFailure(
                    "shape-landing-clash",
                    "落位目标重名：" + fs.path + " 与 " + clash.key + " 都要落到 " + target,
                )
            }
            map[fs.path] = target
        }
        if (map.isEmpty()) {
            return null to LandFailure(
                "shape-empty",
                "产物自带的形态说明里没有一件落位项 —— 报缺，不猜",
            )
        }

        //  搬实体（先搬， 再改软链，因为软链目标可能也是被搬的那一份）
        val moved = mutableListOf<String>()
        //  诊断读数（ 那一支前后各一条）—— 而它默认关（ 生产路径不产这些），
        //   只有 install 那条路（ 真机跑的那条）打开。
        val isDiagnostic = diagnostic
        var probe = ""
        if (isDiagnostic) {
            probe += "[进入搬动循环] 项数=" + map.size + "；"
        }
        for ((src, dst) in map) {
            val s = File(dest, src)
            val d = File(dest, dst)
            //  路径相同时不搬（那就是已经在位的那一份）。今天栽过（2026-10-20，run 39431882004 真机）：
            //    去掉 `if (src == dst) continue`、改成照样搬，而 s 与 d 是同一个目录，`d.delete()` 把自己删了，
            //    接着 `s.renameTo(d)` 搬一个已删的、失败，journal 里「装 busybox；败」。旧字节换不掉的真因不在这一支：
            //    落位目录是 opt/<id>/<version>，installComponent 走整目录换掉，判已落位在 PreinstalledPieces（d38edfa 改比 sha256）。
            //  读数必须在 `continue` 之前：busybox 那件 19 项全是 src == dst，continue 在前则读数一次都不执行、probe 恒空，
            //    diag 里 0 条（07 今天带阳性对照测到的就是这么来的）。
            if (isDiagnostic && !src.startsWith(".")) {
                probe += "落位前 " + src + " 是" +
                    (if (java.nio.file.Files.isSymbolicLink(s.toPath())) "软链" else "实体") +
                    " 摘要=" + shortShaOf(s) + ";"
            }
            if (src == dst) {
                if (isDiagnostic && !dst.startsWith(".")) {
                    probe += "落位跳过(同名) " + dst + " 是" +
                        (if (java.nio.file.Files.isSymbolicLink(d.toPath())) "软链" else "实体") +
                        " 摘要=" + shortShaOf(d) + ";"
                }
                continue
            }
            if (!existsAsFileOrLink(s)) {
                // 已经落过位的那一份（幂等）：目标在，就算已落位。
                if (existsAsFileOrLink(d)) continue
                return null to LandFailure(
                    "shape-payload-missing",
                    "产物声明的落位项在包与盘上都不在：" + src,
                )
            }
            d.parentFile?.mkdirs()
            if (existsAsFileOrLink(d)) d.delete()
            if (!s.renameTo(d)) {
                return null to LandFailure(
                    "shape-landing-failed",
                    "落位失败（搬不动）：" + src + " -> " + dst,
                )
            }
            moved.add(src + " -> " + dst)
            // 读数（诊断用）：搬之后那一条是软链还是实体、它的摘要是多少（与上面那条并列）。
            if (isDiagnostic && !dst.startsWith(".")) {
                probe += "落位后 " + dst + " 是" +
                    (if (java.nio.file.Files.isSymbolicLink(d.toPath())) "软链" else "实体") +
                    " 摘要=" + shortShaOf(d) + ";"
            }
        }

        //  软链：目标被搬过就跟着搬（busybox 的 applet 指向本体那份）
        for ((src, dst) in map) {
            val d = File(dest, dst)
            if (!java.nio.file.Files.isSymbolicLink(d.toPath())) continue
            val raw = runCatching {
                java.nio.file.Files.readSymbolicLink(d.toPath()).toString()
            }.getOrNull() ?: continue
            if (raw.startsWith("/")) continue   // 绝对链：包外的东西，不动
            val resolved = normalizeRel(src.substringBeforeLast('/', "") + "/" + raw)
                ?: continue
            val newTarget = map[resolved] ?: continue
            val newLink = relativeRel(dst.substringBeforeLast('/', ""), newTarget) ?: continue
            if (newLink == raw) continue
            runCatching { d.delete() }
            runCatching { android.system.Os.symlink(newLink, d.absolutePath) }
        }

        //  缺陷名 C-102：link-farm 那一族软链没建。`link-farm.txt` 是构建侧写的清单（「落位相对路径<TAB>指向目标」），
        //    随件走、落位侧要读它才建得出软链。后果：git 按 exec-path 找 remote-https（不带 git- 前缀）找不到，
        //    退成 15 字节的文字桩。处置：link-farm 逐条在落位目录上建软链（它是落位事实）。
        //  缺陷名 C-102：那件实际要的层没搬——bin/ 与 lib/ 走 `files[]`（按形态落），而 libexec/（git）、share/（cmake）
        //    不在 `files[]` 里、要按原样落。处置：额外每一层整层搬过去（落位事实就是这样）。
        for (extra in shape.extraDirs) {
            val srcDir = File(dest, extra)
            if (!srcDir.isDirectory) continue
            val dstDir = File(dest, extra)
            runCatching {
                srcDir.walkTopDown().forEach { f ->
                    val rel = f.relativeTo(srcDir).path
                    val target = File(dstDir, rel)
                    if (f.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        if (!target.exists()) {
                            runCatching {
                                if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                                    val raw = java.nio.file.Files.readSymbolicLink(f.toPath()).toString()
                                    java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(raw))
                                } else {
                                    f.copyTo(target, overwrite = true)
                                }
                            }
                        }
                    }
                }
            }
            probe += "额外那一层 $extra 搬了；"
        }
        val farm = File(dest, "link-farm.txt")
        if (farm.isFile) {
            var made = 0
            runCatching {
                farm.readLines().forEach { line ->
                    val t = line.trimEnd()
                    if (t.isEmpty()) return@forEach
                    val parts = t.split("\t")
                    if (parts.size < 2) return@forEach
                    val rel = parts[0]
                    val tgt = parts[1]
                    if (rel.isEmpty() || tgt.isEmpty()) return@forEach
                    val link = File(dest, rel)
                    if (link.exists()) return@forEach
                    link.parentFile?.mkdirs()
                    runCatching { android.system.Os.symlink(tgt, link.absolutePath) }
                    made++
                }
            }
            probe += "link-farm 建了 $made 条软链；"
        }
        //  权限按**产物声明的 mode** 落（运行期不判形态、不替产物决定权限）
        for (fs in shape.files) {
            if (fs.mode.isBlank()) continue
            val dst = map[fs.path] ?: continue
            val f = File(dest, dst)
            if (!f.isFile) continue
            val m = runCatching { fs.mode.toInt(8) }.getOrNull() ?: continue
            runCatching { android.system.Os.chmod(f.absolutePath, m) }
        }

        //  入口：清单声明的那一条 -> 落位后那一条（形态决定它在哪）
        val entrySrc = declaredEntry.trim()
        if (entrySrc.isEmpty()) {
            //  纯库件没有入口，那不是缺件。缺陷 C-102：空 entry 曾被当成「产物没声明入口」而报缺
            //    （旧版 `LandFailure("entry-missing", "产物没声明入口 —— 报缺，不猜一个")`）。
            //    现在空 entry 就是库件：落位照上面按形态逐项走完，入口留空；「库装库目录」由 dirForRole 保证，不冲突。
            //  系统角色走产物自带那格，不按形态那把尺兜底（库形态那三件两把尺一样，一致更好）。
            val libRole = shape.systemRole.takeIf { it.isNotBlank() && it != "unknown" }
                ?: shape.files.firstOrNull()?.role
            if (libRole == null) {
                return null to LandFailure(
                    "entry-missing",
                    "产物没声明入口，且形态说明里没有落位项 —— 报缺，不猜一个",
                )
            }
            //  纯库件：注册表仍要记「库在哪里」 记那份库字节的名字。
            //   从落位结果里按**形态**（role=library）取第一个 —— 不按目录名、不按 entry。
            val libFile = shape.files.firstOrNull { it.role == ROLE_LIBRARY }
                ?: shape.files.firstOrNull { it.role == ROLE_PROGRAM }
            val libName = libFile?.path?.substringAfterLast('/') ?: ""
            return Landed("", libRole, shape.kind, moved, libName, probe) to null
        }
        val entryDst = map[entrySrc]
            ?: return null to LandFailure(
                "entry-not-in-shape",
                "清单声明的入口 " + entrySrc + " 不在产物声明的落位集合里 —— 报缺，不猜另一个",
            )
        //  缺陷名 C-102：这一格取的是形态那把尺，答的却是另一问。旧版 `val entryRole = shape.roleOf(entrySrc)`，
        //    `files[]` 的 `role` 是形态（program/library/unknown：那份字节是程序还是库），而这里要记的是
        //    「它在 LOB-OS 那一份 Linux 上扮演什么角色」（系统角色：shell/library/…）。两把尺对不上
        //    （bash 的 files 只有 1 条 role='program'，而它其实是 shell 那一族）。
        //  处置：系统角色走产物自带的 `systemRole`（构建期从 `component-meta.json` 派生，一份真源）；
        //    读不到时报缺（C-05，不猜填、不按形态那把尺兜底）。
        val entryRole = shape.systemRole.takeIf { it.isNotBlank() && it != "unknown" }
            ?: return null to LandFailure(
                "entry-system-role-unknown",
                "产物那一份形态说明里没有那系统角色 —— 报缺，不猜" +
                    "（它逐字该从那 component-meta.json 派生、按 C-05 不猜填）",
            )
        return Landed(entryDst, entryRole, shape.kind, moved, "", probe) to null
    }

    /** 逐项复制一棵树，保留符号链接（不跟到目标）。族一（落在哪）：搬动只有这一个实现，rename 与 copy 都走这里。
     *  `copyRecursively` 不能用于落位：它对目录逐项复制、会跟到目标，一条命令软链变成一份字节副本。
     *  rename 不解引用，所以第一优先走 rename（installComponent 里那一支）；这一支是它的兜底，兜底也必须保留软链。
     */
    private fun copyTreePreservingLinks(from: File, to: File) {
        if (!from.isDirectory) {
            to.parentFile?.mkdirs()
            if (java.nio.file.Files.isSymbolicLink(from.toPath())) {
                val raw = runCatching {
                    java.nio.file.Files.readSymbolicLink(from.toPath()).toString()
                }.getOrNull()
                if (raw != null) {
                    java.nio.file.Files.deleteIfExists(to.toPath())
                    java.nio.file.Files.createSymbolicLink(
                        to.toPath(), java.nio.file.Paths.get(raw),
                    )
                    return
                }
            }
            from.copyTo(to, overwrite = true)
            return
        }
        to.mkdirs()
        val kids = from.listFiles() ?: return
        for (k in kids) copyTreePreservingLinks(k, File(to, k.name))
    }

    /** 一个文件的 sha256 前 16 位（诊断用）；读不到就空串。 */
    private fun shortShaOf(f: File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun existsAsFileOrLink(f: File): Boolean =
        f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())

    /** 相对路径归一（去掉 . 与 ..，越出根返回 null）。 */
    private fun normalizeRel(rel: String): String? {
        val out = mutableListOf<String>()
        for (seg in rel.split('/')) {
            when (seg) {
                "", "." -> continue
                ".." -> { if (out.isEmpty()) return null; out.removeAt(out.size - 1) }
                else -> out.add(seg)
            }
        }
        return if (out.isEmpty()) null else out.joinToString("/")
    }

    /** 从 fromDir 到 toRel 的相对路径。 */
    private fun relativeRel(fromDir: String, toRel: String): String? {
        val from = if (fromDir.isEmpty()) emptyList() else fromDir.split('/')
        val to = toRel.split('/')
        var i = 0
        while (i < from.size && i < to.size - 1 && from[i] == to[i]) i++
        val up = from.size - i
        val out = mutableListOf<String>()
        repeat(up) { out.add("..") }
        for (j in i until to.size) out.add(to[j])
        return if (out.isEmpty()) null else out.joinToString("/")
    }

    enum class From(val label: String) {
        APP("程序"),
        COMPONENT("件"),
    }

    data class Spec(
        val from: From,
        val programId: String,
        val zip: File,
        val shape: Shape = Shape.APPLICATION,
        val manifestText: String? = null,
        val manifestFile: File? = null,
        val expectedVersion: String? = null,
        val expectedEntryRel: String? = null,
        val storeRoot: File? = null,
    )

    enum class Shape { APPLICATION, COMPONENT }

    fun install(context: Context, spec: Spec): Result {
        if (spec.programId.isBlank()) {
            return Result(
                false, null, "manifest-id-missing",
                "包清单未声明 id/name：宿主不猜安装目标",
            )
        }
        if (!spec.zip.isFile) {
            return Result(false, null, "zip-missing", "候选包不存在: ${spec.zip.absolutePath}")
        }
        if (spec.zip.length() == 0L) {
            return Result(false, null, "zip-empty", "候选包是空文件: ${spec.zip.absolutePath}")
        }

        val manifest: JSONObject? = spec.manifestText?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        if (spec.shape == Shape.COMPONENT) {
            return installComponent(context, spec)
        }

        val base = ProgramInstaller.install(
            context = context,
            zip = spec.zip,
            manifest = manifest,
            source = ProgramInstaller.Source.entries.firstOrNull { it.label == spec.from.label }
                ?: ProgramInstaller.Source.OTA,
            manifestFile = spec.manifestFile?.takeIf { it.isFile }
                ?: spec.manifestText?.let { writeManifestFile(context, spec.programId, it) },
            programId = spec.programId,
            storeRoot = spec.storeRoot,
        )
        if (!base.ok) return Result(false, base.version, base.reason, base.detail, base.nodeVerifyOutput)

        val version = base.version ?: return Result(
            false, null, "no-version", "安装流程未产出版本号", base.nodeVerifyOutput,
        )

        val dir = lobos.services.reg.ProgramManager.stateDirOf(context, spec.programId)
        val reg = lobos.services.reg.ProgramIndex.get(context, spec.programId)
        val baseEntry = reg ?: lobos.services.reg.ProgramIndex.empty(
            spec.programId, lobos.services.reg.ProgramManager.levelOf(context, spec.programId),
        )
        val declared = runCatching {
            lobos.services.reg.ProgramRegistry.spec(context, spec.programId)
        }.getOrNull()
        val writeIndex = runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                context,
                lobos.services.reg.UnitEntry(
                  id = spec.programId,
                      version = version,
                      enabled = true,
                      //  落位目录那格只记 opt/<id>（版本由目录名承担）。缺陷 C-102：落位根多一层、版本号出现两次——
                      //    旧版 `baseEntry.stateDir.ifBlank { relStateDir(…) }` 沿用注册表旧值（那格可能带版本号，真机读数
                      //    files/busybox/1.36.1/1.36.1/），而写入时机在落位之前，随后 `dir = stateDirOf(…)` 读的就是它，
                      //    落位根 = 那格 + <version>。现在只写 relStateDir（opt/<id>），与 :578 那一处落位后写的一致。
                      stateDir = lobos.services.reg.ProgramManager.relStateDir(context, spec.programId),
                      sha256 = baseEntry.sha256,
                      //  入口格只对程序形态填。缺陷 C-102（库件被当程序调起）：旧版 `assetEntry = declared?.entry ?: baseEntry.assetEntry`，
                      //    两处都是注册表旧值，三件纯库件（crypto/curl/zlib）那格还留着旧的 lib/<名>，运行期按它调起一份库、exit=139；
                      //    真机读数（07，2026-10-20）：三件库字节的 NEEDED 都能在 usr/lib 或 /system/lib64 找到（零断链），
                      //    stderr 只有 Segmentation fault——找到了、装载开始了、崩，它不是程序。现在库形态入口格留空。
                      //  族二（是程序还是库）：运行期只读产物那一份（04 那张表第二行）。缺陷 C-102 旧版 `declared?.role ?: baseEntry.role`
                      //    也是旧值；现在这一处不写 role（留空），role 与入口格由落位后那次 upsert 按 `landed.role` 写，levelOf 读作 PIECE。
                      assetEntry = "",
                      role = "",
                      resident = declared?.resident ?: true,
                      restart = declared?.restart ?: lobos.services.reg.Restart.ON_FAILURE,
                      maxRestarts = declared?.maxRestarts ?: 5,
                      backoffMs = declared?.backoffMs?.takeIf { it.isNotEmpty() }
                          ?: lobos.services.reg.ProgramIndex.DEFAULT_BACKOFF,
                      capabilities = declared?.capabilities ?: emptyList(),
                      requires = declared?.requires ?: emptyList(),
                      env = declared?.env ?: emptyMap(),
                      httpPort = declared?.http?.port ?: 0,
                      httpHealth = declared?.http?.health ?: "",
                      desired = when {
                          baseEntry.desired == lobos.services.reg.Desired.STOPPED && declared?.resident == true ->
                              lobos.services.reg.Desired.RUNNING
                          reg == null -> lobos.services.reg.Desired.RUNNING
                          else -> baseEntry.desired
                      },
                      uiPackage = "",
                      uiName = "",
                      uiIcon = "",
                      onUiClosed = "",
                      invalid = declared?.invalid ?: baseEntry.invalid,
                ),
            )
        }
        if (writeIndex.isFailure) {
            return Result(
                false, version, "index-write-failed",
                "包已落位但注册表写入失败：${writeIndex.exceptionOrNull()?.message}",
                base.nodeVerifyOutput,
            )
        }

        lobos.services.log.Journal.note(
            context, "package", true, "包已安装",
            "id=" + spec.programId + " version=" + version +
                " 来源=" + spec.from.label +
                " dir=" + dir.absolutePath,
        )
        return Result(true, version, null, "已登记 " + spec.programId + "@" + version, base.nodeVerifyOutput)
    }

    private fun installComponent(context: Context, spec: Spec): Result {
        val dir = lobos.services.reg.ProgramManager.stateDirOf(context, spec.programId)
        //  读数（诊断用）：这一格算出的落位根、stateDirOf 走哪一支、注册表里那格的原值。判别：真机落位结果落在
        //    files/<id>/<ver>/<ver>/，而 stateDirOf 三支（`ProgramManager.kt:80` opt/<id>、`:81` filesDir+stateDir、
        //    `:83` programRoot+id，programRoot=SystemDirs.opt）全落在 opt/ 之下——当前代码拼不出那个形状，这三条读数就是定它的那格。
        val regNow = lobos.services.reg.ProgramIndex.get(context, spec.programId)
        RuntimeDiagnostics.append(
            context, "ota-shape-probe", true, "落位根读数 " + spec.programId,
            "dir=" + dir.absolutePath +
                " 注册表stateDir=" + (regNow?.stateDir ?: "(无这一件)") +
                " 入口格=" + (if (regNow == null) "(无)" else if (regNow.assetEntry.isBlank()) "空(库件那支)" else "有值") +
                " version=" + (spec.expectedVersion ?: "(空)"),
        )
        val staging = File(dir, "." + spec.zip.nameWithoutExtension + ".staging")
        runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            SupplyProvisioner.unzipFromFile(spec.zip, staging, context)
        }.onFailure {
            staging.deleteRecursively()
            runCatching { if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete() }
            return Result(false, null, "unzip-failed", "解包失败：" + it.message)
        }
        val version = spec.expectedVersion?.takeIf { it.isNotBlank() } ?: "0"
        val safeVer = lobos.services.reg.ProgramIndex.safeSegment(version)
            ?: return Result(
                false, null, "version-illegal",
                "版本号非法（只接受字母数字与 . _ -，且非 . 或 ..）：" + version.take(40),
            )
        val dest = File(dir, safeVer)
        val rootCanon = runCatching { dir.canonicalFile.path }.getOrDefault(dir.absolutePath)
        if (dest.canonicalFile.path != rootCanon + File.separator + safeVer) {
            staging.deleteRecursively()
            return Result(false, null, "landing-out-of-bound", "落位越界，已拒绝：" + dest.canonicalFile.path)
        }
        if (!staging.renameTo(dest)) {
            val incoming = File(dir, "." + safeVer + ".incoming")
            runCatching { incoming.deleteRecursively() }
            //  族一（落在哪）的第二处搬动实现：copy 会解引用软链，rename 不会。
            //  缺陷名 C-102（展平落在这一支）：旧版 `staging.copyRecursively(incoming, overwrite = true)` 对目录逐项复制、
            //    跟到目标，那 18 条命令软链变成 18 份字节副本（真机读数 07：bin/ 下 19 个实体）；而 `renameTo(dest)`
            //    在 dest 非空时必然失败（整目录换掉的前提就是 dest 已在），所以真机上每次都走 copy。
            //  真机读数（07，run_id 39465653670）：unzip-probe 那组 31 条里 ('true','true') 20 条（解包后那 18 条就是软链），
            //    而落位探针说「落位前 19 项全是实体」——中间那一步就是这一支。现在复制时保留符号链接（逐项自己搬）。
            val copied = runCatching {
                copyTreePreservingLinks(staging, incoming)
            }
            if (copied.isFailure) {
                runCatching { incoming.deleteRecursively() }
                return Result(false, null, "landing-failed", "落位失败：" + copied.exceptionOrNull()?.message)
            }
            val old = File(dir, "." + safeVer + ".previous")
            runCatching { old.deleteRecursively() }
            val hadOld = dest.isDirectory
            if (hadOld) runCatching { dest.renameTo(old) }
            if (!incoming.renameTo(dest)) {
                runCatching { incoming.deleteRecursively() }
                if (hadOld) runCatching { old.renameTo(dest) }
                return Result(false, null, "landing-failed", "落位失败：新目录换不进去，已回退到原版本")
            }
            runCatching { old.deleteRecursively() }
            runCatching { staging.deleteRecursively() }
        }
        dir.mkdirs()

        //  按产物自带的那一条落位：形态只从 piece-shape.json 读（构建期算好、随包落进来），
        //  读不到就报缺，不探测字节、不猜、不兜底（用户三行规则第三条）。
        val landedMeta = pieceMetaOf(context, dir, safeVer)
        val shape = PieceShape.read(dest)
        if (shape == null) {
            RuntimeDiagnostics.append(
                context, "ota", false, "件缺形态说明",
                spec.programId + "：落位目录里没有 " + SHAPE_NAME +
                    "（产物没带形态）—— 报缺，不探测那份字节",
            )
            return Result(
                false, safeVer, "shape-missing",
                "产物没带形态说明（" + SHAPE_NAME + "）—— 形态是生产时就知道的事，" +
                    "运行期只读它；读不到就报缺，不探测字节、不猜",
            )
        }
        val declaredEntry = landedMeta.entry().ifBlank { spec.expectedEntryRel.orEmpty() }
        val (landed, landFail) = landByShape(dest, shape, declaredEntry, diagnostic = true)
        if (landFail != null || landed == null) {
            val f = landFail
            RuntimeDiagnostics.append(
                context, "ota", false, "件落位失败（按形态）",
                spec.programId + "：" + (f?.reason ?: "") + " —— " + (f?.detail ?: ""),
            )
            return Result(
                false, safeVer, f?.reason ?: "shape-landing-failed",
                (f?.detail ?: "落位失败") +
                    "（形态是产物带来的那一格：kind=" + shape.kind + "）",
            )
        }
        if (landed.moved.isNotEmpty()) {
            RuntimeDiagnostics.append(
                context, "ota", true, "件按形态落位",
                spec.programId + "：" + landed.moved.joinToString("，") +
                    "；入口 " + declaredEntry + " -> " + landed.entryRel +
                    "（角色 " + landed.role + "，整件 " + landed.kind + "）",
            )
        }
        //  那条诊断读数（ 那一支前后各一条：那一项是软链还是实体、摘要多少）——
        //    那一格今天未核（07 核到真机落位后那 18 条是实体，本地复现两次都是软链）。
        //     这一行就是用来定「差别在哪一步」的那一格。
        if (landed.probe.isNotBlank()) {
            RuntimeDiagnostics.append(
                context, "ota-shape-probe", true, "落位搬动读数 " + spec.programId,
                landed.probe,
            )
        }

        val role = landed.role
        //  落位清单里的 entry 必须是落位后那一条（运行期按它找入口：`ProgramRegistry.spec` 的 `entryFile = File(dir, entry)`）。
        //  缺陷 C-102：旧版写的是包内路径（meta 的 entry，如 lib/liblobosjq.so），按形态落位后那份字节已在 bin/ 下，
        //  运行期按清单找入口会「入口不存在」。这里换成落位后的相对路径再写。
        val landedMetaJson = runCatching {
            val j = JSONObject(landedMeta.raw())
            j.put("entry", landed.entryRel)
            j
        }.getOrNull()
        runCatching {
            ManifestWriter.write(dest, landedMetaJson, safeVer, role)
        }

        val depReport = satisfyElfDeps(context, dest)
        if (!depReport.ok) {
            return Result(false, safeVer, "elf-deps-unresolved", depReport.detail)
        }

        // 入口软链建在形态决定的目录里（程序进 usr/bin、库进 usr/lib）：旧版按 entry 首段目录名分家（C-102，
        //   `if (entryRel.startsWith("lib/")) libDir else binDir`，§10.1:128 判为无效），现在由形态决定。
        // C-102（纯库件被当成有入口的件建链）：真机读数（07，2026-10-25，run 40126378136 / sha=155de80）crypto 装不上、
        //   entry-link-failed；它 meta entry 空串、files[] role=library、entryForm 与 entryRole 都 unknown。成因：纯库件
        //   `Landed("", libRole, …)`（:287）→ `landed.entryRel`（:680）空串 → `File(dest, entryRel)`（:943）是落位目录自己，:952 失败。
        // 处置：纯库件不建入口软链，但要落位、要登记成件（:691 往后的 upsert 要走到）；快路径（curl/zlib）本就不调 `linkEntry`。
        val entryRel = landed.entryRel
        if (entryRel.isNotBlank() && !linkEntry(context, dest, entryRel, spec.programId, role)) {
            return Result(
                false, safeVer, "entry-link-failed",
                "件已落位但入口软链建不起来（" +
                    (if (role == ROLE_LIBRARY) lobos.kernel.layout.PrefixProvisioner.libDir(context).absolutePath
                     else lobos.kernel.layout.PrefixProvisioner.binDir(context).absolutePath) + "/" +
                    entryRel.substringAfterLast("/") + "）—— 装了个没人调得动的件；" +
                    "看 SELinux 是否允许该目录建链，或落位目录是否可写",
            )
        }
        val reg = lobos.services.reg.ProgramIndex.get(context, spec.programId)
        val base = reg ?: lobos.services.reg.ProgramIndex.empty(
            spec.programId, lobos.services.reg.ProgramManager.levelOf(context, spec.programId),
        )
        val meta = pieceMetaOf(context, dir, safeVer)
        //  注册表只记「库在哪里」与「程序在哪里」：程序形态 assetEntry = 落位后那一条（程序目录里的相对路径）；
        //  库形态 assetEntry 空（没有程序入口），libName = 库文件名。缺陷 C-102：旧版 `assetEntry = meta.entry()` 不分形态，
        //  lib/ 下那份也成了入口，调起来是那份库字节——今天五件失败的成因。现在入口格只对程序形态填。
        val isProgram = role == ROLE_PROGRAM
        //  库那一档的判据是「它真的没有程序入口」，不是「它不是 program」。
        val isLibrary = role == ROLE_LIBRARY
        val upserted = runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                context,
                lobos.services.reg.UnitEntry(
                  id = spec.programId,
                      version = version,
                      enabled = true,
                      // 落位目录记**相对**路径（opt/<id>）——
                  // ProgramManager.stateDirOf 拼的是 File(ctx.filesDir, stateDir)，
                  // 绝对路径会被拼成 filesDir + "/data/..." （那就找不到了）。
                  stateDir = SystemDirs.relPieceDir(spec.programId),
                  sha256 = base.sha256,
                  //  库件没有入口（entryRel 空） libName 取**那份库字节**的名字（按形态从落位结果算）。
                  //   程序件：libName = 落位后那一条的文件名。
                  libName = if (entryRel.isNotBlank()) entryRel.substringAfterLast("/") else landed.libName,
                      //  缺陷名 C-102：旧版 `assetEntry = if (isProgram) entryRel else ""` 只对 'program' 填，而 'shell' 也有入口，
                      //    那一格被清空后 `ShellEntry.resolve` 就答不出那 shell。处置：入口格答的是「那一件有没有程序入口」，
                      //    不由角色决定——'library' 仍清空，'shell' 不清空。
                      assetEntry = if (isLibrary) "" else entryRel,
                      role = role,
                      requires = meta.requires(),
                      required = meta.requiredOr(false),
                      invalid = base.invalid,
                      //  [!] 缺陷名 C-102：这一支不写 desired，而 UnitEntry 那行默认是 STOPPED——装上来却不被监督
                      //    （`SupervisorPool.kt:138` 只留 `levelOf == PROGRAM && desired == RUNNING`，名单里没有它）。
                      //   快路径那几件没事：`PreinstalledPieces.kt:537` 写了 `desired = Desired.RUNNING`，这一支没写、两处不同源。
                      //  该由落位侧那份说了算，但它缺 lifecycle（`ManifestWriter.build` 的 schema 里没有），`ProgramRegistry.kt:146`
                      //    默认 true，而落位那支 spec 是 null（`ProgramDir.kt:169` 的 `spec?.resident == true` 于是 false，
                      //    正是 journal 里「resident=false」）。按 C-104 照快路径答：desired = RUNNING；库件不受影响（PIECE）。
                      desired = lobos.services.reg.Desired.RUNNING,
                ),
            )
        }
        if (upserted.isFailure) {
            return Result(
                false, safeVer, "index-write-failed",
                "组件已落位但注册表写入失败：" + upserted.exceptionOrNull()?.message,
            )
        }
        val pd = ProgramDir(context, spec.programId, dir)
        runCatching { pd.setCurrentVersion(safeVer) }
        if (pd.currentVersion() != safeVer) {
            return Result(false, safeVer, "current-not-committed", "CURRENT 落位失败（安装未提交）")
        }
        //  缺陷名 C-102：全局那一层没跟着换版本重建——治的是「usr/bin 与 usr/lib 那一层跟换掉之后的版本对不对得上」。
        //  成因：换版本只换那一件自己的落位目录（整目录换：.previous -> dest -> 失败回退 -> 删 .previous），usr/bin 与
        //    usr/lib 那层是 LdConfig 那一族建的、不在替换范围里。后果：applet 软链指向换掉之后就不在的本体，
        //    「换件」被读成了「库不在设备上」。处置：全局那层跟着重建（`LdConfig.run`）。
        //  按 C-104：全局那层只有一条生成路径；按 C-135：「换件」与「全局那层」是两问、不合并。
        val globalRebuilt = runCatching {
            lobos.kernel.layout.LdConfig.run(context)
        }.onFailure {
            RuntimeDiagnostics.append(
                context, "ota", false, "件换完之后全局那一层重建失败",
                it.javaClass.simpleName + ": " + it.message
            )
        }
        RuntimeDiagnostics.append(
            context, "ota", globalRebuilt.isSuccess,
            "件换完之后全局那一层重建",
            spec.programId + "@" + safeVer + " usr/bin 与 usr/lib 逐字**跟**那换掉之后**的**那版本**对得上**"
        )
        lobos.services.log.Journal.note(
            context, "package", true, "系统组件已安装",
            "id=" + spec.programId + " version=" + safeVer +
                " 来源=" + spec.from.label + " dir=" + dest.absolutePath,
        )
        return Result(true, safeVer, null, "")
    }

    data class Result(
        val ok: Boolean,
        val version: String?,
        val reason: String?,
        val detail: String,
        val nodeVerifyOutput: String = "",
    ) {
        fun toDiagnosticLine(): String = when {
            ok -> "安装成功 v=$version"
            else -> "安装未生效 原因=$reason；$detail"
        }
    }

    private class PieceMeta(private val o: JSONObject?) {
        fun raw(): String = o?.toString() ?: "{}"

        /** ManifestWriter.write 的第二参是 JSONObject? —— PieceMeta 持有它而不继承它，
         *  所以这里显式交出去（compileDebugKotlin 在 :186 报 ARGUMENT_TYPE_MISMATCH 就是缺这一行）。 */
        fun asJson(): JSONObject? = o

        fun entry(): String = o?.optString("entry", "")?.trim().orEmpty()

        fun libNameOrEmpty(): String = entry().substringAfterLast("/", "")
        fun requiredOr(def: Boolean): Boolean =
            o?.optBoolean("required", def) ?: def

        fun requires(): List<String> {
            val arr = o?.optJSONArray("requires") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) out += v
            }
            return out
        }
    }

    private fun pieceMetaOf(context: Context, dir: File, version: String): PieceMeta {
        val f = File(File(dir, version), "component-meta.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrNull()
        if (o == null) {
            RuntimeDiagnostics.append(context, "ota", false, "件缺 component-meta.json", f.name + "（登记退回现有字段）")
        }
        return PieceMeta(o)
    }

    private data class DepReport(val ok: Boolean, val detail: String, val placed: Int, val missing: List<String>)

    private fun linkerCanFind(context: Context, name: String): Boolean {
        for (d in SYSTEM_LIB_DIRS) {
            if (File(d, name).exists()) return true
        }
        val jni = context.applicationInfo.nativeLibraryDir
        if (jni != null && File(jni, name).exists()) return true
        val usrLib = SystemDirs.lib(context)
        if (usrLib != null && File(usrLib, name).exists()) return true
        return false
    }

    private fun satisfyElfDeps(context: Context, dest: File): DepReport {
        //  缺陷名 C-102：ELF 依赖那条路扫了整个落位目录。成因：`satisfyElfDeps` 对 dest 走 `walkTopDown()`。
        //  后果：落位卡在 busybox 那一件里（seq=49950 是最后一行、卡了 3443 秒）。
        //  处置：只看 lib/ 与 bin/ 那几个目录（形状跟 `dependencySources` 那一族一样）。
        val elfs = linkedSetOf<File>()
        for (sub in listOf(File(dest, "lib"), File(dest, "bin"), dest)) {
            if (!sub.isDirectory) continue
            sub.walkTopDown().filter { it.isFile && isElf(it) }.forEach { elfs.add(it) }
        }
        if (elfs.isEmpty()) return DepReport(true, "落位目录内没有 ELF 文件，无需铺依赖", 0, emptyList())

        val needed = linkedSetOf<String>()
        val targets = linkedSetOf<File>()
        for (f in elfs) {
            val d = lobos.kernel.elf.ElfFacts.read(f) ?: continue
            needed += d.needed
            val dirs = resolveRunPath(d.runPath, f.parentFile)
            if (dirs.isEmpty()) targets += f.parentFile else targets += dirs
        }
        if (targets.isEmpty()) targets += dest
        val systemProvided = linkedSetOf<String>()
        for (name in needed) {
            if (File(dest, name).isFile) continue
            if (linkerCanFind(context, name)) systemProvided.add(name)
        }
        var missing = needed.filter {
            !systemProvided.contains(it) &&
                !File(dest, it).isFile
        }
        if (missing.isEmpty()) {
            return DepReport(true, "ELF 段要求的依赖已齐（或由系统提供）", 0, emptyList())
        }

        val sources = dependencySources(context)
        var placed = 0
        val stillMissing = mutableListOf<String>()
        for (name in missing) {
            val src = sources[name]
            var done = false
            for (dir in targets) {
                if (src == null) break
                val ok = runCatching {
                    dir.mkdirs()
                    src.copyTo(File(dir, name), overwrite = true)
                    File(dir, name).setExecutable(true, true)
                }.isSuccess
                if (ok) { done = true; placed += 1; break }
            }
            if (!done) stillMissing += name
        }
        if (stillMissing.isNotEmpty()) {
            //  缺陷名 C-102：基础运行库被当成了那一件的硬失败。成因：libc.so 与 libdl.so 是所有 ELF 都要的两个基础名字，
            //    它们在设备上是 NDK 链接期的桩（8 字节函数体）。后果：落位停在 crypto 那一件上（seq=49858 是最后一行）。
            //    处置：基础运行库不算这一件的硬失败，只记读数（按 C-105 报缺、不猜填）。
            RuntimeDiagnostics.append(
                context, "ota-shape-probe", null,
                "ELF 基础运行库缺 " + stillMissing.size + " 个（不阻断落位）",
                stillMissing.joinToString(",") +
                    "（落位目录=" + dest.absolutePath + "）",
            )
            return DepReport(
                true,
                "ELF 基础运行库缺 " + stillMissing.size + " 个（不阻断落位）：" +
                    stillMissing.joinToString() +
                    "（落位目录=" + dest.absolutePath + "）",
                placed, stillMissing,
            )
        }
        return DepReport(true, "按 ELF 段铺齐 $placed 个依赖库", placed, emptyList())
    }

    internal fun resolveRunPath(runPath: String?, originDir: File): List<File> {
        if (runPath.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, File>()
        for (seg in runPath.split(':')) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            if (s.contains(LIB_TOKEN) || s.contains(PLATFORM_TOKEN)) continue
            val dir = when {
                s == "\$ORIGIN" -> originDir
                s.startsWith("\$ORIGIN/") -> File(originDir, s.removePrefix("\$ORIGIN").trimStart('/'))
                s.startsWith("/") -> File(s)
                else -> File(originDir, s)
            }
            val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            out.putIfAbsent(key, dir)
        }
        return out.values.toList()
    }

    private fun isElf(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val magic = ByteArray(4)
            if (ins.read(magic) != 4) return@runCatching false
            magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun dependencySources(context: Context): Map<String, File> {
        val out = HashMap<String, File>()
        val dirs = buildList {
            add(File(context.applicationInfo.nativeLibraryDir))
            add(lobos.kernel.layout.PrefixProvisioner.libDir(context))
            runCatching {
                lobos.services.reg.ProgramRegistry.listIds(context).forEach { id ->
                    runCatching { add(lobos.services.reg.ProgramManager.stateDirOf(context, id)) }
                }
            }
        }
        //  缺陷名 C-102：ELF 依赖那条路扫了全部十九件的每一个文件。成因：`dependencySources` 对每一个目录走 `walkTopDown()`。
        //  后果：files/opt 底下有一万八千多个文件，落位停在 crypto 那一件上（seq=49858 是最后一行）。
        //  处置：只看库那一族的几个目录（lib/ 与 bin/），不扫整个落位目录。
        for (d in dirs) {
            if (!d.isDirectory) continue
            //  库那一族在 lib/ 与 bin/ 底下。
            for (sub in listOf(File(d, "lib"), File(d, "bin"), d)) {
                if (!sub.isDirectory) continue
                val kids = sub.listFiles() ?: continue
                for (f in kids) {
                    if (f.isFile) out.putIfAbsent(f.name, f)
                }
            }
        }
        return out
    }

    /** 建入口软链：目录由形态决定，不看 entry 的首段目录名。
     *  缺陷 C-102：旧版 `if (entryRel.startsWith("lib/")) libDir else binDir` 是「看目录名」（§10.1:128 判为无效）——
     *  库字节可以在 bin/ 下、程序字节可以在 lib/ 下（今天那几件是后者），于是「lib/ 下那份」被当成了入口。
     *  现在目录由形态回答（产物带来的那格）：程序进程序目录，库进库目录。
     */
    private fun linkEntry(
        context: Context,
        dest: File,
        entryRel: String,
        programId: String,
        role: String,
    ): Boolean {
        val purged = lobos.kernel.layout.EntryLinks.purge(context, programId, dest)
        if (purged > 0) {
            RuntimeDiagnostics.append(
                context, "linkEntry", true, programId + " 清掉旧入口软链 " + purged + " 条",
                "dest=" + dest.absolutePath,
            )
        }
        val rootCanon = runCatching { dest.canonicalFile.path }.getOrDefault(dest.absolutePath)
        val target = File(dest, entryRel)
        val tCanon = runCatching { target.canonicalFile.path }.getOrDefault(target.absolutePath)
        if (tCanon != rootCanon && !tCanon.startsWith(rootCanon + File.separator)) {
            RuntimeDiagnostics.append(
                context, "linkEntry", false, "entry 越出落位目录，已拒绝",
                "entry=" + entryRel + "  target=" + tCanon + "  root=" + rootCanon,
            )
            return false
        }
        if (!target.isFile) return false
        //  执行位由产物决定（构建期 `elf-form.sh` 按形态定死：程序 0755 / 库 0644，落位时已按声明的 mode 落过），
        //  这里不再补执行位——那是运行期判形态的旧路（今天五件失败的成因之一：原来只给 bin/ 补，lib/ 下那份是
        //  -rw------，spawn 得 error=13）。
        val primary = entryRel.substringAfterLast("/")
        val dir = when (role) {
            ROLE_PROGRAM -> lobos.kernel.layout.PrefixProvisioner.binDir(context)
            ROLE_LIBRARY -> lobos.kernel.layout.PrefixProvisioner.libDir(context)
            else -> {
                RuntimeDiagnostics.append(
                    context, "linkEntry", false, "入口形态认不出，报缺",
                    "entry=" + entryRel + "  role=" + role + " —— 不猜它该建在哪",
                )
                return false
            }
        }
        var ok = link(dir, primary, target)
        for (a in aliasNames(dest, programId, primary)) {
            ok = link(dir, a, target) && ok
        }
        RuntimeDiagnostics.append(
            context, "linkEntry", ok,
            (if (ok) "入口软链已建 " else "入口软链建不起来 ") + programId,
            "entry=" + entryRel + "  dest=" + dest.absolutePath +
                "  target=" + target.absolutePath + "  dir=" + dir.absolutePath,
        )
        return ok
    }

    private fun aliasNames(dest: File, programId: String, primaryName: String): List<String> {
        val out = mutableListOf<String>()
        val pkg = File(dest, "package.json")
        if (!pkg.isFile) return out
        runCatching {
            val bin = org.json.JSONObject(pkg.readText()).opt("bin") ?: return@runCatching
            when (bin) {
                is org.json.JSONObject -> for (k in bin.keys()) if (k.isNotBlank()) out += k
                is String -> out += File(bin).name
            }
        }
        return out
            .filter { it != programId && it != primaryName }
            .filter { lobos.services.reg.ProgramIndex.safeSegment(it) != null }
            .distinct()
    }

    /** 建入口软链。
     *  缺陷 C-102：软链是派生物，而落位遇到已存在的文件就走短路、不重新落位，派生物因此永不刷新。
     *  真机读数（07）：files/usr/lib/ 里 libbusybox.so / liblobosjq.so / libssl.so 三条是坏的（目标写成
     *  files/data/user/0/lobos.os/files/opt/<id>/<ver>/<ver>/<entry>，前缀两遍、版本段两遍），是 10:19、10:28
     *  那批装机留下的旧物。所以这里建完就验目标存在：不存在就当场删链重算一次，再验；两次都不成立才报。 */
    private fun link(binDir: File, name: String, target: File): Boolean {
        val l = File(binDir, name)
        fun build(t: File): Boolean = runCatching {
            l.parentFile?.mkdirs()
            if (l.exists() || java.nio.file.Files.isSymbolicLink(l.toPath())) l.delete()
            android.system.Os.symlink(t.absolutePath, l.absolutePath)
            true
        }.getOrDefault(false)
        if (!build(target)) return false
        //  link 成功不等于目标可达 —— 复述一次，判据只有这一条，不在这儿猜形状重算：
        //    目标走不到就说明它不是从落位目录算出来的（真机旧物：前缀两遍、版本段两遍）。
        //    重算的形状由落位侧那一条负责（linkEntry 重算 + PreinstalledPieces 的派生物校验）。
        return target.isFile
    }

    private fun writeManifestFile(context: Context, programId: String, text: String): File? {
        val f = File(context.cacheDir, "install-manifest-" + programId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        return runCatching { f.writeText(text) }.let { if (it.isSuccess) f else null }
    }
    private val SYSTEM_LIB_DIRS = listOf("/system/lib64", "/system/lib", "/apex/com.android.runtime/lib64/bionic")
}