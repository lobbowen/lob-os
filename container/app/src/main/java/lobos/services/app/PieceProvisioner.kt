package lobos.services.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import lobos.services.log.RuntimeDiagnostics
import lobos.kernel.elf.ElfFacts
import lobos.services.supply.PieceScan
import lobos.kernel.layout.SystemDirs
import lobos.kernel.proc.ProcessSupervisor
import org.json.JSONArray
import org.json.JSONObject

sealed class AssetStatus {

    data class Mismatched(
        val id: String,
        val path: String,
        val mismatched: List<String>,
    ) : AssetStatus()

    data class MissingFromPiece(
        val exe: PieceScan.Found,
        val path: String,
        val pieceListing: String,
    ) : AssetStatus()
    data class Ready(
        val exe: PieceScan.Found,
        val path: String,
    ) : AssetStatus()

    data class MissingFromLib(
        val exe: PieceScan.Found,
        val path: String,
        val inApk: Boolean,
        val libListing: String,
    ) : AssetStatus()

    data class MissingDependency(
        val exe: PieceScan.Found,
        val dep: String,
        val libListing: String,
    ) : AssetStatus()

    data class NotExecutable(
        val exe: PieceScan.Found,
        val path: String,
        val errnoHint: Int?,
        val raw: String,
    ) : AssetStatus()
    data class Unusable(

        val exe: PieceScan.Found,
        val path: String,
        val exit: Int,
        val output: String,
    ) : AssetStatus()
}

data class PrepareReport(val entries: List<Pair<PieceScan.Found, AssetStatus>>) {

    val allRequiredReady: Boolean
        get() = entries.filter { it.first.required }.all { it.second is AssetStatus.Ready }

    val failedRequired: List<Pair<PieceScan.Found, AssetStatus>>
        get() = entries.filter { it.first.required && it.second !is AssetStatus.Ready }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for ((exe, st) in entries) {
            val o = JSONObject()
            o.put("id", exe.id)
            o.put("libName", exe.libName)
            o.put("required", exe.required)
            when (st) {
                is AssetStatus.Ready -> {
                    o.put("status", "ready")
                    o.put("path", st.path)
                }
                is AssetStatus.MissingFromPiece -> {
                    o.put("status", "missing_from_piece")
                    o.put("path", st.path)
                }
                is AssetStatus.MissingFromLib -> {
                    o.put("status", "missing_from_lib")
                    o.put("path", st.path)
                    o.put("inApk", st.inApk)
                    o.put("libListing", st.libListing)
                    o.put("hint", if (st.inApk) "安装期未解压（extractNativeLibs 未生效）"
                    else "打包期就丢了（构建脚本未拷入 / 被 strip 掉）")
                }
                is AssetStatus.MissingDependency -> {
                    o.put("status", "missing_dependency")
                    o.put("missingDep", st.dep)
                    o.put("libListing", st.libListing)
                    o.put("hint", "依赖必须与本体同目录，且本体要自带含 \$ORIGIN 的 DT_RUNPATH" +
                        " —— 那样跟着文件走，不依赖任何环境变量")
                }
                is AssetStatus.NotExecutable -> {
                    o.put("status", "not_executable")
                    o.put("path", st.path)
                    st.errnoHint?.let { o.put("errno", it) }
                    o.put("raw", st.raw)
                    o.put("hint", "依赖已确认完好，errno=13 可确定归因到 SELinux W^X 拒 exec")
                }
                is AssetStatus.Mismatched -> {
                    o.put("status", "mismatched")
                    o.put("path", st.path)
                    o.put("mismatched", JSONArray(st.mismatched))
                    o.put("hint", "登记的文件与磁盘上的不符（dpkg -V：comparing the files installed with the files metadata stored in the database）—— 重新铺一次即可修复")
                }
            is AssetStatus.Unusable -> {
                    o.put("path", st.path)
                    o.put("exit", st.exit)
                    o.put("output", st.output)
                }
            }
            arr.put(o)
        }
        return JSONObject().apply {
            put("allRequiredReady", allRequiredReady)
            put("assets", arr)
        }
    }

    fun toDiagnosticLines(): List<String> = entries.map { (exe, st) ->
        val tag = if (exe.required) "[必需]" else "[可选]"
        when (st) {
            is AssetStatus.Ready ->
                "$tag ${exe.entry.substringAfterLast("/", "")} —— 就位"
            is AssetStatus.MissingFromPiece ->
                "件没落位：${st.path} —— 落位目录里没有入口文件（查装件是否成功）"
            is AssetStatus.MissingFromLib ->
                "$tag ${exe.entry.substringAfterLast("/", "")} —— ✗ 不在 nativeLibraryDir。" +
                    if (st.inApk) "APK 内有该条目 → 安装期未解压（查 extractNativeLibs / useLegacyPackaging）"
                    else "APK 内也没有该条目 → 打包期就丢了（查构建脚本与 keepDebugSymbols）"
            is AssetStatus.MissingDependency ->
                "$tag ${exe.entry.substringAfterLast("/", "")} —— ✗ 缺少依赖 ${st.dep}（它必须先于本体补齐，否则会被误判为 SELinux 拒 exec）"
            is AssetStatus.Mismatched ->
                "$tag ${exe.entry.substringAfterLast("/", "")} —— ✗ 与登记不符：${st.mismatched.joinToString(", ")}"
            is AssetStatus.NotExecutable ->
                "$tag ${exe.entry.substringAfterLast("/", "")} —— ✗ 无法 exec（依赖已确认完好，errno=${st.errnoHint ?: "?"}）"
            is AssetStatus.Unusable ->
                "$tag ${exe.entry.substringAfterLast("/", "")} —— ✗ 起不来 exit=${st.exit}，输出: ${st.output.ifBlank { "(空)" }}"
        }
    }
}

object PieceProvisioner {

    private const val TAG = "PieceProvisioner"

    fun prepare(ctx: Context): PrepareReport {
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val listing = listLibDir(libDir)

        val apkLibNames: Set<String> = try {
            readApkLibEntries(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "读取 APK lib 条目失败", e)
            emptySet()
        }

        val unverified = ArrayList<String>()
        val entries = PieceScan.scan(ctx).map { f ->
            val st = verifyInternal(ctx, f, libDir, listing, apkLibNames)
            val final = if (st is AssetStatus.Ready) {
                val v = PieceScan.verify(ctx, f.id)
                when {
                    v.ok -> st
                    v.unverifiable -> {
                        unverified.add(f.id)
                        st
                    }
                    v.mismatched.isEmpty() -> {
                        unverified.add(f.id)
                        st
                    }
                    else -> AssetStatus.Mismatched(f.id, f.entry, v.mismatched)
                }
            } else st
            f to final
        }
        if (unverified.isNotEmpty()) {
            RuntimeDiagnostics.append(
                ctx, "native-assets", true,
                "内容指纹未核（登记里没有 files 表）" + unverified.size + " 件",
                unverified.joinToString(",") + " —— 这不算不符；改判据或补登记再核",
            )
        }
        val report = PrepareReport(entries)

        RuntimeDiagnostics.append(
            ctx, "native-assets", report.allRequiredReady,
            if (report.allRequiredReady) "系统件全部就位（${entries.size} 项）"
            else "系统件校验失败：${report.failedRequired.joinToString(", ") { it.first.libName }}",
            "nativeLibraryDir=${libDir.absolutePath}\n" +
                "依赖解析方式=二进制自带 \$ORIGIN RUNPATH（照抄 ldconfig checks the header）\n" +
                "lib 目录内容（${listing.lines().size - 3} 项）:\n" +
                listing.lineSequence().drop(2).joinToString("\n") { "  $it" } + "\n" +
                report.toDiagnosticLines().joinToString("\n"),
            data = report.toJson(),
        )
        return report
    }
    private val SYSTEM_LIB_DIRS = listOf("/system/lib64", "/system/lib", "/apex/com.android.runtime/lib64/bionic")
    private fun verifyInternal(
        ctx: Context,
        exe: PieceScan.Found,
        libDir: File,
        listing: String,
        apkLibNames: Set<String>,
    ): AssetStatus {
        val entryRel = exe.entry.substringAfterLast("/", "")
        if (entryRel.isNotBlank()) {
            val f = File(exe.dir, exe.entry)
            if (!f.isFile) {
                return AssetStatus.MissingFromPiece(exe, f.absolutePath, resolveListing(exe.dir))
            }
            if (!f.canExecute()) {
                return AssetStatus.NotExecutable(exe, f.absolutePath, null, "件落位了，但入口没有执行位")
            }
            val missing = missingDeps(ctx, f, exe.dir)
            if (missing.isNotEmpty()) {
                return AssetStatus.MissingDependency(exe, missing.first(), resolveListing(exe.dir))
            }
            return AssetStatus.Ready(exe, f.absolutePath)
        }
        val libs = runCatching {
            File(exe.dir, "lib").listFiles()?.filter { it.isFile } ?: emptyList()
        }.getOrDefault(emptyList())
        if (libs.isEmpty()) {
            return AssetStatus.MissingFromPiece(exe, File(exe.dir, "lib").absolutePath, resolveListing(exe.dir))
        }
        val bad = libs.mapNotNull { missingDeps(ctx, it, exe.dir).firstOrNull() }
        if (bad.isNotEmpty()) {
            return AssetStatus.MissingDependency(exe, bad.first(), resolveListing(exe.dir))
        }
        return AssetStatus.Ready(exe, File(exe.dir, "lib").absolutePath)
    }
    private fun missingDeps(ctx: Context, f: File, dir: File): List<String> {
        val needed = lobos.kernel.elf.ElfFacts.read(f)?.needed.orEmpty()
        if (needed.isEmpty()) return emptyList()
        val here = File(dir, "lib")
        val out = ArrayList<String>()
        for (dep in needed) {
            val found = File(here, dep).isFile || File(dir, dep).isFile || linkerCanFind(ctx, dep)
            if (!found) out.add(dep)
        }
        return out
    }
    private fun linkerCanFind(ctx: Context, name: String): Boolean {
        for (d in SYSTEM_LIB_DIRS) {
            if (File(d, name).exists()) return true
        }
        val jni = ctx.applicationInfo.nativeLibraryDir
        if (jni != null && File(jni, name).exists()) return true
        runCatching {
            val usrLib = lobos.kernel.layout.SystemDirs.lib(ctx)
            if (usrLib != null && File(usrLib, name).exists()) return true
        }
        runCatching {
            val joined = lobos.services.reg.RuntimeEnvironment.pieceLibDirs(ctx)
            for (p in joined.split(':')) {
                if (p.isNotBlank() && File(p, name).exists()) return true
            }
        }
        return false
    }
    private fun resolveListing(dir: File): String {
        val top = runCatching { dir.listFiles()?.sortedBy { it.name } ?: emptyList() }.getOrDefault(emptyList())
        return buildString {
            appendLine("件落位目录=${dir.absolutePath}")
            appendLine("顶层条目数=${top.size}")
            top.take(24).forEach { appendLine("${it.name}  ${if (it.isDirectory) "目录" else it.length().toString() + " 字节"}") }
        }
    }

    private fun parseErrno(msg: String?): Int? {
        if (msg == null) return null
        return Regex("""errno=(\d+)""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun listLibDir(libDir: File): String {
        val files = try {
            libDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            return "nativeLibraryDir=${libDir.absolutePath}\n（列举失败: ${e.message}）\n"
        }
        return buildString {
            appendLine("nativeLibraryDir=${libDir.absolutePath}")
            appendLine("文件数=${files.size}")
            files.forEach { appendLine("${it.name}  ${it.length()} 字节") }
        }
    }

    private fun readApkLibEntries(ctx: Context): Set<String> {
        val apkPath = ctx.applicationInfo.sourceDir
        return ZipFile(apkPath).use { zf ->
            zf.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.substringAfterLast('/') }
                .toSet()
        }
    }

    private fun err(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message}"
}
