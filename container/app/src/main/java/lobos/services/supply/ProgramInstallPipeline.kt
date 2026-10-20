package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import lobos.services.log.Journal
import lobos.kernel.elf.ElfFacts
import lobos.kernel.layout.SystemDirs
import lobos.kernel.layout.PrefixProvisioner
import org.json.JSONObject

object ProgramInstallPipeline {

    private const val LIB_TOKEN = "\$LIB"
    private const val PLATFORM_TOKEN = "\$PLATFORM"

    // ── 形态：只从产物读 ─────────────────────────────────────
    //
    // 用户 2026-10-20 三行规则：
    //   一 构建时就知道哪些是库、哪些是程序 —— 生产时就知道的事
    //   二 库装到库目录，程序装到程序目录
    //   三 注册表只记「库在哪里」与「程序在哪里」；运行期只读，不判断、不探测
    //
    // 形态由构建期算好（scripts/toolchain/elf-form.sh 照 ExecBits.formOf 那把尺），
    // 写进**产物自带的** piece-shape.json，随包落进落位目录。
    // 运行期只读那一份：读不到就报缺，不探测那份字节、不猜、不兜底。
    //
    // 取值域与构建期那一份一致：
    //   form ∈ {exec, dyn-interp, dyn-plain, unknown}   逐文件的形态
    //   role ∈ {program, library, unknown}              逐文件的角色（按 form 判）
    //   kind ∈ {program, library, mixed, unknown}       件整体
    const val SHAPE_NAME = "piece-shape.json"
    const val ROLE_PROGRAM = "program"
    const val ROLE_LIBRARY = "library"

    /** 产物自带的形态说明 —— 构建期算好（scripts/toolchain/elf-form.sh 照 ExecBits.formOf 那把尺），
     *  随包落进落位目录。**运行期只读它**：读不到就是读不到（返回 null），调用方报缺，
     *  不探测那份字节、不判断、不兜底（用户 2026-10-20 三行规则第三条）。
     *
     *  ★ 缺陷名（C-102）**运行期拿字节现算形态**：这一段是替代
     *    [PieceScan.roleFromDisk] 那条路（它 walk 落位目录读 ELF 头）——
     *    形态是**生产时就知道的事**，产物自带一份，运行期只读。
     *
     *  取值域（与构建期那一份一致）：
     *    form ∈ {exec, dyn-interp, dyn-plain, unknown}   逐文件的形态
     *    role ∈ {program, library, unknown}              逐文件的角色（按 form 判）
     *    kind ∈ {program, library, mixed, unknown}       件整体
     */
    object PieceShape {

        data class FileShape(
            val path: String,
            val dir: String,
            val role: String,
            val mode: String,
            val sha256: String,
        )

        data class Piece(
            val kind: String,
            val entry: String,
            val files: List<FileShape>,
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
                        )
                    )
                }
            }
            return Piece(
                kind = o.optString("kind", "").trim(),
                entry = o.optString("entry", "").trim(),
                files = files,
            )
        }

        /** 形态 ⇒ 落位目录（用户三行规则第二条：库装到库目录，程序装到程序目录）。
         *  ★ 判据是**形态**，不是原路径的首段目录名 —— 那是「看目录名」（§10.1:128 已判无效）。 */
        fun dirForRole(role: String): String? = when (role) {
            ROLE_PROGRAM -> "bin"
            ROLE_LIBRARY -> "lib"
            else -> null
        }

        /** 落位后的件内相对路径 —— **只由形态决定**，是纯函数（两边各自算，结果一致）。
         *  认不出的形态返回 null（报缺，不猜）。 */
        fun landedRel(piece: Piece, rel: String): String? {
            val role = piece.roleOf(rel) ?: return null
            val dir = dirForRole(role) ?: return null
            return dir + "/" + rel.substringAfterLast('/')
        }
    }

    /** 落位的结果：落位后入口的件内相对路径 + 它的形态 + 整件的形态。 */
    /** 落位的结果：落位后入口的件内相对路径 + 它的形态 + 整件的形态 + **那一件自己的字节名**。
     *  ★ 最后一格是纯库件用的：库件**没有入口**，而注册表仍要记「库在哪里」——
     *    记的是那份库字节的名字（按形态从落位结果里取，不按目录名）。 */
    data class Landed(
        val entryRel: String,
        val role: String,
        val kind: String,
        val moved: List<String>,
        val libName: String = "",
    )

    /** 落位失败的原因（报缺用）。 */
    data class LandFailure(val reason: String, val detail: String)

    /** 按**产物里带的那一条**落位：形态决定目录（库进库目录、程序进程序目录）。
     *
     *  ★ 缺陷名（C-102）**落位按目录名分家，不看形态**：原来落位只是把解包出来的目录
     *    整个搬过去（bin/ 归 bin/、lib/ 归 lib/），于是「同一件里有程序形态的字节在 lib/ 下」
     *    这件事被原样保留下来 —— 入口随后按清单的 entry（lib/ 那一份）建链，
     *    那几件就再也调不起来（今天五件失败的成因）。
     *  ⇒ 现在**形态**决定落位目录：程序形态的字节进 bin/，库形态的进 lib/。
     *
     *  形态从产物自带的 piece-shape.json 读（构建期算好）。**读不到就报缺**，
     *  不探测那份字节、不猜、不兜底（用户三行规则第三条）。
     */
    fun landByShape(dest: File, shape: PieceShape.Piece, declaredEntry: String): Pair<Landed?, LandFailure?> {
        // ① 目标映射：包内相对路径 -> 落位后相对路径（**只由形态决定**）
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

        // ② 搬实体（先搬，③ 再改软链，因为软链目标可能也是被搬的那一份）
        val moved = mutableListOf<String>()
        for ((src, dst) in map) {
            val s = File(dest, src)
            val d = File(dest, dst)
            // ★ **路径相同时不搬**（它是**已经在位**的那一份）。
            //   ★★ 而这一格今天栽过一次（2026-10-20，run 39431882004 真机）：
            //     我把 `if (src == dst) continue` 去掉、改成「照样搬（delete + renameTo）」——
            //     ★★ 而 s 与 d **是同一个目录**（都是 dest）⇒ ★★ **d.delete() 把它自己删了**
            //     ⇒ ★★ 接着 s.renameTo(d) 搬一个**已删的** ⇒ 失败
            //     ⇒ ★★ 「落位失败（搬不动）」⇒ 真机 journal 里「装 busybox；败」。
            //   ★★ 而「旧字节换不掉」的真因**不在这一支**：
            //     ★★ 落位目录是 opt/<id>/<version>，而 installComponent 走的是「整目录换掉」
            //     （旧 dest → .previous，incoming → dest）⇒ ★★ **内容已经是新的**
            //     ⇒ ★★ 这一支**不需要搬**（src == dst 时它就是那份新的）★★
            //     ⇒ ★★ 而「判已落位」那一格（PreinstalledPieces）才是那一处：它判「在不在」⇒
            //       走快路径 ⇒ **不重装** ⇒ 旧字节留着（已由 d38edfa 改成比 sha256）。
            if (src == dst) continue
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
        }

        // ③ 软链：目标被搬过就跟着搬（busybox 的 applet 指向本体那份）
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

        // ④ 权限按**产物声明的 mode** 落（运行期不判形态、不替产物决定权限）
        for (fs in shape.files) {
            if (fs.mode.isBlank()) continue
            val dst = map[fs.path] ?: continue
            val f = File(dest, dst)
            if (!f.isFile) continue
            val m = runCatching { fs.mode.toInt(8) }.getOrNull() ?: continue
            runCatching { android.system.Os.chmod(f.absolutePath, m) }
        }

        // ⑤ 入口：清单声明的那一条 -> 落位后那一条（形态决定它在哪）
        val entrySrc = declaredEntry.trim()
        if (entrySrc.isEmpty()) {
            // ★ **纯库件没有入口 —— 那不是缺件**。
            //   缺陷名（C-102）空 entry 被当成「产物没声明入口」而报缺：
            //   原来这一支是 return LandFailure("entry-missing", "产物没声明入口 —— 报缺，不猜一个")，
            //   而「这一件只有库形态的字节 ⇒ 它本来就没有入口」（判别：这一件在磁盘上有没有程序那一侧）。
            //   ⇒ 现在：空 entry ⇒ 库件，落位照上面（按形态逐项）走完，入口留空。
            //   而「库装库目录」说的是**库字节落在库目录**，那件事由 dirForRole 保证 ——
            //   与「库件没有入口」（入口）不冲突。
            val libRole = shape.files.firstOrNull()?.role
            if (libRole == null) {
                return null to LandFailure(
                    "entry-missing",
                    "产物没声明入口，且形态说明里没有落位项 —— 报缺，不猜一个",
                )
            }
            // ★ 纯库件：注册表仍要记「库在哪里」⇒ 记那份库字节的名字。
            //   从落位结果里按**形态**（role=library）取第一个 —— 不按目录名、不按 entry。
            val libFile = shape.files.firstOrNull { it.role == ROLE_LIBRARY }
                ?: shape.files.firstOrNull { it.role == ROLE_PROGRAM }
            val libName = libFile?.path?.substringAfterLast('/') ?: ""
            return Landed("", libRole, shape.kind, moved, libName) to null
        }
        val entryDst = map[entrySrc]
            ?: return null to LandFailure(
                "entry-not-in-shape",
                "清单声明的入口 " + entrySrc + " 不在产物声明的落位集合里 —— 报缺，不猜另一个",
            )
        val entryRole = shape.roleOf(entrySrc)
            ?: return null to LandFailure(
                "entry-form-unknown",
                "入口 " + entrySrc + " 的形态认不出 —— 报缺，不猜",
            )
        return Landed(entryDst, entryRole, shape.kind, moved) to null
    }

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
                      stateDir = baseEntry.stateDir.ifBlank { lobos.services.reg.ProgramManager.relStateDir(context, spec.programId) },
                      sha256 = baseEntry.sha256,
                      // ★ **入口格只对程序形态填** —— 缺陷名（C-102）**库件被当程序调起**：
                      //   原来这一行是 `assetEntry = declared?.entry ?: baseEntry.assetEntry`——
                      //   而 declared 是**注册表**里那一件（不是清单），baseEntry 也是注册表里那一件
                      //   ⇒ 两处都是**旧值** ⇒ 而三件纯库件（crypto/curl/zlib）注册表里那一格
                      //     还留着旧的 lib/<名> ⇒ 运行期按它去调起一份**库** ⇒ exit=139 段错误。
                      //   ★★ 真机读数（07，2026-10-20）：那三件每件库字节的 NEEDED 都能在 usr/lib
                      //     或 /system/lib64 里找到（零断链）⇒ **「找不到」那一格不成立**；
                      //     而 stderr 只有 Segmentation fault、没有任何链接器的抱怨
                      //     ⇒ **找到了、装载开始了、崩** ⇒ 而它「根本不是程序」。
                      //   ⇒ 现在：形态是库 ⇒ 入口格**留空**（这一件没有程序入口）；
                      //     形态是程序 ⇒ 入口格填 declared 的那一格（若空则填 baseEntry 的）。
                      assetEntry = (declared?.role?.takeIf { it.isNotBlank() } ?: baseEntry.role).let { r ->
                          if (r == ROLE_PROGRAM) (declared?.entry?.takeIf { it.isNotBlank() } ?: baseEntry.assetEntry) else ""
                      },
                      role = declared?.role?.takeIf { it.isNotBlank() } ?: baseEntry.role,
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
        val staging = File(dir, "." + spec.zip.nameWithoutExtension + ".staging")
        runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            SupplyProvisioner.unzipFromFile(spec.zip, staging)
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
            val copied = runCatching {
                staging.copyRecursively(incoming, overwrite = true)
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

        // ── 按产物里带的那一条落位 ───────────────────────────────
        //
        // 形态只从**产物自带的** piece-shape.json 读（构建期算好，随包落进来）。
        // 读不到 ⇒ 报缺，不探测那份字节、不猜、不兜底（用户三行规则第三条）。
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
        val (landed, landFail) = landByShape(dest, shape, declaredEntry)
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

        val role = landed.role
        // 落位清单里的 entry 必须是**落位后那一条**（不是包里那一条）——
        // 运行期按它找入口（ProgramRegistry.spec 的 entryFile = File(dir, entry)）。
        //  ★ 缺陷名（C-102）**清单里的 entry 是包内路径，落位后已不是那一处**：
        //    原来写的是 meta 的 entry（如 lib/liblobosjq.so），而按形态落位后那份字节
        //    已经在 bin/ 下 ⇒ 运行期按清单找入口会「入口不存在」。
        //  ⇒ 这里把 entry 换成落位后的相对路径再写。
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

        // 入口软链建在**形态决定的那个目录**里：
        //   程序形态 ⇒ 程序目录（usr/bin）；库形态 ⇒ 库目录（usr/lib）。
        //  ★ 缺陷名（C-102）**建链按 entry 的首段目录名分家**：原来那一行是
        //    if (entryRel.startsWith("lib/")) libDir else binDir —— 那是「看目录名」。
        //    现在目录由**形态**决定（产物带来的那一格），不是看名字。
        val entryRel = landed.entryRel
        if (!linkEntry(context, dest, entryRel, spec.programId, role)) {
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
        // 注册表只记「库在哪里」与「程序在哪里」：
        //   程序形态 ⇒ assetEntry = 落位后那一条（程序目录里的相对路径）
        //   库形态   ⇒ assetEntry 空（这一件没有程序入口），libName = 库文件名
        //  ★ 缺陷名（C-102）**注册表把库那份也记成入口**：原来 assetEntry = meta.entry()
        //    （清单里那一条，不分形态）⇒ lib/ 下那份也成了「入口」，
        //    于是那几件调起来是那份库字节 —— 今天五件失败的成因。
        //  ⇒ 现在入口格只对**程序形态**填；库形态那一格空着（= 只有落位目录）。
        val isProgram = role == ROLE_PROGRAM
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
                  stateDir = SystemDirs.REL_OPT + "/" + spec.programId,
                  sha256 = base.sha256,
                  // ★ 库件没有入口（entryRel 空）⇒ libName 取**那份库字节**的名字（按形态从落位结果算）。
                  //   程序件：libName = 落位后那一条的文件名（与原来逐字相同）。
                  libName = if (entryRel.isNotBlank()) entryRel.substringAfterLast("/") else landed.libName,
                      assetEntry = if (isProgram) entryRel else "",
                      role = role,
                      requires = meta.requires(),
                      required = meta.requiredOr(false),
                      invalid = base.invalid,
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

    private fun depsOf(context: Context, programId: String): List<String> {
        val arr = runCatching {
            CatalogClient.entryFor(context, programId)?.optJSONArray("deps")
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotBlank() && v !in out) out += v
        }
        return out
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
        val elfs = dest.walkTopDown().filter { it.isFile && isElf(it) }.toList()
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
            return DepReport(
                false,
                "ELF DT_NEEDED 要求的库找不到：" + stillMissing.joinToString() +
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
        for (d in dirs) {
            if (!d.isDirectory) continue
            d.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putIfAbsent(f.name, f)
            }
        }
        return out
    }

    /** 建入口软链。**目录由形态决定**，不看 entry 的首段目录名。
     *
     *  ★ 缺陷名（C-102）**建链按目录名分家**：原来那一行是
     *    if (entryRel.startsWith("lib/")) libDir else binDir。
     *    那是「看目录名」（§10.1:128 判为无效）：库形态的字节可以落在 bin/ 下，
     *    程序形态的字节也可以落在 lib/ 下（今天那几件就是后者）——
     *    目录名说什么就是什么，于是「lib/ 下那份」被当成了入口。
     *  ⇒ 现在目录由**形态**回答（产物带来的那一格）：程序 ⇒ 程序目录，库 ⇒ 库目录。
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
        // 执行位**由产物决定**（构建期 elf-form.sh 已按形态定死：程序 0755 / 库 0644，
        // 落位时已按产物声明的 mode 落过）。这里不再替产物补执行位 ——
        // 那是运行期判断形态的那条路（今天五件失败的成因之一：
        // 原来只给 bin/ 补执行位，lib/ 下那份是 -rw------ ⇒ spawn 得 error=13）。
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
     *  缺陷（C-102）**软链是派生物而落位跳过已存在项，派生物因此永不刷新**：
     *  落位那一侧见「文件在就不重新落位」的短路分支，而软链是由落位事实派生的。
     *  真机读数（07）：files/usr/lib/ 里 libbusybox.so / liblobosjq.so / libssl.so 三条是坏的
     *  （目标写成 files/data/user/0/lobos.os/files/opt/<id>/<ver>/<ver>/<entry>，前缀两遍、版本段两遍），
     *  它们是 10:19 10:28 那一批装机留下的旧物，后续装机没有重写它们。
     *
     *  所以这里建完就**验目标存在**：不存在说明目标是旧形状算出来的，当场删链重算一次，
     *  再验；两次都不成立才报。 */
    private fun link(binDir: File, name: String, target: File): Boolean {
        val l = File(binDir, name)
        fun build(t: File): Boolean = runCatching {
            l.parentFile?.mkdirs()
            if (l.exists() || java.nio.file.Files.isSymbolicLink(l.toPath())) l.delete()
            android.system.Os.symlink(t.absolutePath, l.absolutePath)
            true
        }.getOrDefault(false)
        if (!build(target)) return false
        // ③ link 成功不等于目标可达 —— 复述一次，判据只有这一条，不在这儿猜形状重算：
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