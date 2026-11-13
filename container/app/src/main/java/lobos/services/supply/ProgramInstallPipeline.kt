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

    const val SHAPE_NAME = "piece-shape.json"
    const val ROLE_PROGRAM = "program"
    const val ROLE_LIBRARY = "library"

    const val ROLE_SHELL = "shell"

    object PieceShape {

        data class FileShape(
            val path: String,
            val dir: String,
            val role: String,
            val mode: String,
            val sha256: String,

            val chain: List<String> = emptyList(),

            val soname: String = "",
        )

        data class Piece(
            val kind: String,
            val entry: String,
            val files: List<FileShape>,

            val derivedLinks: List<String> = emptyList(),

            val extraDirs: List<String> = emptyList(),

            val systemRole: String = "",
        ) {
            fun roleOf(rel: String): String? = files.firstOrNull { it.path == rel }?.role

            fun byRole(role: String): List<FileShape> = files.filter { it.role == role }
        }

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

                            (fo.optJSONArray("chain") ?: JSONArray()).let { ca ->
                                (0 until ca.length()).mapNotNull { j ->
                                    ca.optString(j, "").trim().takeIf { it.isNotEmpty() }
                                }
                            },

                            fo.optString("soname", "").trim(),
                        )
                    )
                }
            }
            return Piece(
                kind = o.optString("kind", "").trim(),
                entry = o.optString("entry", "").trim(),

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

        fun dirForRole(role: String): String? = when (role) {
            ROLE_PROGRAM -> "bin"
            ROLE_LIBRARY -> "lib"

            ROLE_SHELL -> "bin"
            else -> null
        }

        fun landedRel(piece: Piece, rel: String): String? {
            val role = piece.roleOf(rel) ?: return null
            val dir = dirForRole(role) ?: return null
            return dir + "/" + rel.substringAfterLast('/')
        }

        fun pointerLayerOf(piece: Piece, role: String, verDir: File?): List<FileShape> =
            pointerLayerWithTier(piece, role, verDir).pointers

        fun pointerLayerWithTier(piece: Piece, role: String, verDir: File?): PointerLayer {
            val items = piece.byRole(role)

            if (items.isEmpty()) {

                return PointerLayer(emptyList(), "no-library")
            }
            if (role != ROLE_LIBRARY) {

                return PointerLayer(listOf(items.first()), "no-library")
            }

            if (items.size == 1) {
                val only = items.first()
                val base = only.path.substringAfterLast('/')
                val chainLen = only.chain.size
                val sonameSelf = only.soname.isNotBlank() && only.soname == base
                if (chainLen == 1 && sonameSelf) {

                    return PointerLayer(listOf(only), "single")
                }
            }
            val soft = softLinkPathsOf(piece, verDir)
                ?: return PointerLayer(emptyList(), "")
            val pointerLayer = items.filter { soft.contains(it.path) }
            if (pointerLayer.isEmpty()) {

                return PointerLayer(emptyList(), "")
            }

            val roots = items.mapNotNull { it.chain.firstOrNull()?.takeIf { s -> s.isNotBlank() } }.toSet()
            if (roots.isEmpty()) return PointerLayer(emptyList(), "")
            val picked = pointerLayer.filter { roots.contains(it.path.substringAfterLast('/')) }
            val tier = if (picked.size > 1) "multi" else "chain0"
            return PointerLayer(picked, tier)
        }

        data class PointerLayer(
            val pointers: List<FileShape>,
            val tier: String,
        )

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

    data class Landed(
        val entryRel: String,
        val role: String,
        val kind: String,
        val moved: List<String>,
        val libName: String = "",

        val probe: String = "",
    )

    data class LandFailure(val reason: String, val detail: String)

    fun landByShape(dest: File, shape: PieceShape.Piece, declaredEntry: String, diagnostic: Boolean = false): Pair<Landed?, LandFailure?> {

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

        val moved = mutableListOf<String>()

        val isDiagnostic = diagnostic
        var probe = ""
        if (isDiagnostic) {
            probe += "[进入搬动循环] 项数=" + map.size + "；"
        }
        for ((src, dst) in map) {
            val s = File(dest, src)
            val d = File(dest, dst)

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

            if (isDiagnostic && !dst.startsWith(".")) {
                probe += "落位后 " + dst + " 是" +
                    (if (java.nio.file.Files.isSymbolicLink(d.toPath())) "软链" else "实体") +
                    " 摘要=" + shortShaOf(d) + ";"
            }
        }

        for ((src, dst) in map) {
            val d = File(dest, dst)
            if (!java.nio.file.Files.isSymbolicLink(d.toPath())) continue
            val raw = runCatching {
                java.nio.file.Files.readSymbolicLink(d.toPath()).toString()
            }.getOrNull() ?: continue
            if (raw.startsWith("/")) continue
            val resolved = normalizeRel(src.substringBeforeLast('/', "") + "/" + raw)
                ?: continue
            val newTarget = map[resolved] ?: continue
            val newLink = relativeRel(dst.substringBeforeLast('/', ""), newTarget) ?: continue
            if (newLink == raw) continue
            runCatching { d.delete() }
            runCatching { android.system.Os.symlink(newLink, d.absolutePath) }
        }

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

        for (fs in shape.files) {
            if (fs.mode.isBlank()) continue
            val dst = map[fs.path] ?: continue
            val f = File(dest, dst)
            if (!f.isFile) continue
            val m = runCatching { fs.mode.toInt(8) }.getOrNull() ?: continue
            runCatching { android.system.Os.chmod(f.absolutePath, m) }
        }

        val entrySrc = declaredEntry.trim()
        if (entrySrc.isEmpty()) {

            val libRole = shape.systemRole.takeIf { it.isNotBlank() && it != "unknown" }
                ?: shape.files.firstOrNull()?.role
            if (libRole == null) {
                return null to LandFailure(
                    "entry-missing",
                    "产物没声明入口，且形态说明里没有落位项 —— 报缺，不猜一个",
                )
            }

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

        val entryRole = shape.systemRole.takeIf { it.isNotBlank() && it != "unknown" }
            ?: return null to LandFailure(
                "entry-system-role-unknown",
                "产物那一份形态说明里没有那系统角色 —— 报缺，不猜" +
                    "（它逐字该从那 component-meta.json 派生、按 C-05 不猜填）",
            )
        return Landed(entryDst, entryRole, shape.kind, moved, "", probe) to null
    }

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

                      stateDir = lobos.services.reg.ProgramManager.relStateDir(context, spec.programId),
                      sha256 = baseEntry.sha256,

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

        if (landed.probe.isNotBlank()) {
            RuntimeDiagnostics.append(
                context, "ota-shape-probe", true, "落位搬动读数 " + spec.programId,
                landed.probe,
            )
        }

        val role = landed.role

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

        val isProgram = role == ROLE_PROGRAM

        val isLibrary = role == ROLE_LIBRARY
        val upserted = runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                context,
                lobos.services.reg.UnitEntry(
                  id = spec.programId,
                      version = version,
                      enabled = true,

                  stateDir = SystemDirs.relPieceDir(spec.programId),
                  sha256 = base.sha256,

                  libName = if (entryRel.isNotBlank()) entryRel.substringAfterLast("/") else landed.libName,

                      assetEntry = if (isLibrary) "" else entryRel,
                      role = role,
                      requires = meta.requires(),
                      required = meta.requiredOr(false),
                      invalid = base.invalid,

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

        for (d in dirs) {
            if (!d.isDirectory) continue

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

    private fun link(binDir: File, name: String, target: File): Boolean {
        val l = File(binDir, name)
        fun build(t: File): Boolean = runCatching {
            l.parentFile?.mkdirs()
            if (l.exists() || java.nio.file.Files.isSymbolicLink(l.toPath())) l.delete()
            android.system.Os.symlink(t.absolutePath, l.absolutePath)
            true
        }.getOrDefault(false)
        if (!build(target)) return false

        return target.isFile
    }

    private fun writeManifestFile(context: Context, programId: String, text: String): File? {
        val f = File(context.cacheDir, "install-manifest-" + programId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        return runCatching { f.writeText(text) }.let { if (it.isSuccess) f else null }
    }
    private val SYSTEM_LIB_DIRS = listOf("/system/lib64", "/system/lib", "/apex/com.android.runtime/lib64/bionic")
}