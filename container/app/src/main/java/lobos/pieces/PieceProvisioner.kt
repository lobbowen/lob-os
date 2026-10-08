package lobos.pieces

import lobos.runtime.ProcessSupervisor
import android.content.Context
import android.util.Log
import lobos.RuntimeDiagnostics
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

sealed class AssetStatus {

    data class Ready(
        val exe: Piece,
        val path: String,
    ) : AssetStatus()

    data class MissingFromLib(
        val exe: Piece,
        val path: String,
        val inApk: Boolean,
        val libListing: String,
    ) : AssetStatus()

    data class MissingDependency(
        val exe: Piece,
        val dep: String,
        val libListing: String,
    ) : AssetStatus()

    data class NotExecutable(
        val exe: Piece,
        val path: String,
        val errnoHint: Int?,
        val raw: String,
    ) : AssetStatus()
    /** 编译期已保证正确，装上去却不能用 —— 与 Linux 一致：系统不验，运行时自己会报错 */
    data class Unusable(

        val exe: Piece,
        val path: String,
        val exit: Int,
        val output: String,
    ) : AssetStatus()
}

data class PrepareReport(val entries: List<Pair<Piece, AssetStatus>>) {

    val allRequiredReady: Boolean
        get() = entries.filter { it.first.required }.all { it.second is AssetStatus.Ready }

    val failedRequired: List<Pair<Piece, AssetStatus>>
        get() = entries.filter { it.first.required && it.second !is AssetStatus.Ready }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for ((exe, st) in entries) {
            val o = JSONObject()
            o.put("id", exe.id)
            o.put("libName", exe.libName)
            o.put("humanName", exe.humanName)
            o.put("required", exe.required)
            o.put("note", exe.note)
            o.put("requiredDeps", JSONArray(exe.requiredDeps))
            when (st) {
                is AssetStatus.Ready -> {
                    o.put("status", "ready")
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
                    o.put("hint", "依赖必须与本体同目录，且本体要自带含 \$ORIGIN 的 DT_RUNPATH：" +
                        "linker 不查 nativeLibraryDir，LD_LIBRARY_PATH 只在进程环境里才有效")
                }
                is AssetStatus.NotExecutable -> {
                    o.put("status", "not_executable")
                    o.put("path", st.path)
                    st.errnoHint?.let { o.put("errno", it) }
                    o.put("raw", st.raw)
                    o.put("hint", "依赖已确认完好，errno=13 可确定归因到 SELinux W^X 拒 exec")
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
                "$tag ${exe.libName} —— 就位（${exe.humanName}）" +
            is AssetStatus.MissingFromLib ->
                "$tag ${exe.libName} —— ✗ 不在 nativeLibraryDir。" +
                    if (st.inApk) "APK 内有该条目 → 安装期未解压（查 extractNativeLibs / useLegacyPackaging）"
                    else "APK 内也没有该条目 → 打包期就丢了（查构建脚本与 keepDebugSymbols）"
            is AssetStatus.MissingDependency ->
                "$tag ${exe.libName} —— ✗ 缺少依赖 ${st.dep}（它必须先于本体补齐，否则会被误判为 SELinux 拒 exec）"
            is AssetStatus.NotExecutable ->
                "$tag ${exe.libName} —— ✗ 无法 exec（依赖已确认完好，errno=${st.errnoHint ?: "?"}）"
            is AssetStatus.Unusable ->
                "$tag ${exe.libName} —— ✗ 起不来 exit=${st.exit}，输出: ${st.output.ifBlank { "(空)" }}"
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

        val entries = PieceRegistry.ALL.map { exe ->
            exe to verifyInternal(ctx, exe, libDir, listing, apkLibNames)
        }
        val report = PrepareReport(entries)

        RuntimeDiagnostics.append(
            ctx, "native-assets", report.allRequiredReady,
            if (report.allRequiredReady) "系统件全部就位（${entries.size} 项）"
            else "系统件校验失败：${report.failedRequired.joinToString(", ") { it.first.libName }}",
            "nativeLibraryDir=${libDir.absolutePath}\n" +
                "依赖解析方式=二进制自带 \$ORIGIN RUNPATH（照抄 ldconfig "checks the header"）\n" +
                "lib 目录内容（${listing.lines().size - 3} 项）:\n" +
                listing.lineSequence().drop(2).joinToString("\n") { "  $it" } + "\n" +
                report.toDiagnosticLines().joinToString("\n"),
            data = report.toJson(),
        )
        val capEntries = PieceRegistry.CAPABILITY.map { exe ->
            exe to verifyInternal(ctx, exe, libDir, listing, apkLibNames)
        }
        val capReport = PrepareReport(capEntries)
        val capReady = capEntries.count { it.second is AssetStatus.Ready }
        RuntimeDiagnostics.append(
            ctx, "capability-assets", capReady == capEntries.size,
            "能力件 $capReady/${capEntries.size} 就位",
            capReport.toDiagnosticLines().joinToString("\n"),
            data = capReport.toJson(),
        )
        return report
    }

    fun verify(ctx: Context, exe: Piece): AssetStatus {
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val apkNames = try {
            readApkLibEntries(ctx)
        } catch (e: Exception) {
            emptySet()
        }
        return verifyInternal(ctx, exe, libDir, listLibDir(libDir), apkNames)
    }

    fun libSearchPath(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    private fun verifyInternal(
        ctx: Context,
        exe: Piece,
        libDir: File,
        listing: String,
        apkLibNames: Set<String>,
    ): AssetStatus {
        val f = File(libDir, exe.libName)

        if (!f.exists()) {
            val inApk = apkLibNames.contains(exe.libName)
            return AssetStatus.MissingFromLib(exe, f.absolutePath, inApk, listing)
        }

        for (dep in exe.requiredDeps) {
            if (!File(libDir, dep).exists()) {
                return AssetStatus.MissingDependency(exe, dep, listing)
            }

            return if (f.canRead() || f.length() > 0) {
                AssetStatus.Ready(exe, f.absolutePath, "数据资产：${f.length()} 字节（不是拿来执行的probe）")
            } else {
                AssetStatus.NotExecutable(exe, f.absolutePath, null, "文件存在但不可读且长度为 0")
            }
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
