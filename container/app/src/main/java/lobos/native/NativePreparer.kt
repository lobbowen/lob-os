package lobos.native

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
        val exe: NativeExecutable,
        val path: String,
        val probeOutput: String,
    ) : AssetStatus()

    data class MissingFromLib(
        val exe: NativeExecutable,
        val path: String,
        val inApk: Boolean,
        val libListing: String,
    ) : AssetStatus()

    data class MissingDependency(
        val exe: NativeExecutable,
        val dep: String,
        val libListing: String,
    ) : AssetStatus()

    data class NotExecutable(
        val exe: NativeExecutable,
        val path: String,
        val errnoHint: Int?,
        val raw: String,
    ) : AssetStatus()

    data class ProbeFailed(
        val exe: NativeExecutable,
        val path: String,
        val exit: Int,
        val output: String,
    ) : AssetStatus()
}

data class PrepareReport(val entries: List<Pair<NativeExecutable, AssetStatus>>) {

    val allRequiredReady: Boolean
        get() = entries.filter { it.first.required }.all { it.second is AssetStatus.Ready }

    val failedRequired: List<Pair<NativeExecutable, AssetStatus>>
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
                    o.put("probeOutput", st.probeOutput)
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
                is AssetStatus.ProbeFailed -> {
                    o.put("status", "probe_failed")
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
                    if (exe.probeArgs.isNotEmpty()) "，探针输出: ${st.probeOutput.ifBlank { "(空)" }}" else ""
            is AssetStatus.MissingFromLib ->
                "$tag ${exe.libName} —— ✗ 不在 nativeLibraryDir。" +
                    if (st.inApk) "APK 内有该条目 → 安装期未解压（查 extractNativeLibs / useLegacyPackaging）"
                    else "APK 内也没有该条目 → 打包期就丢了（查构建脚本与 keepDebugSymbols）"
            is AssetStatus.MissingDependency ->
                "$tag ${exe.libName} —— ✗ 缺少依赖 ${st.dep}（它必须先于 exec-probe 补齐，否则会被误判为 SELinux 拒 exec）"
            is AssetStatus.NotExecutable ->
                "$tag ${exe.libName} —— ✗ 无法 exec（依赖已确认完好，errno=${st.errnoHint ?: "?"}）"
            is AssetStatus.ProbeFailed ->
                "$tag ${exe.libName} —— ✗ 探针失败 exit=${st.exit}，输出: ${st.output.ifBlank { "(空)" }}"
        }
    }
}

object NativePreparer {

    private const val TAG = "NativePreparer"

    fun prepare(ctx: Context): PrepareReport {
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val listing = listLibDir(libDir)

        val apkLibNames: Set<String> = try {
            readApkLibEntries(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "读取 APK lib 条目失败", e)
            emptySet()
        }

        val entries = NativeAssetRegistry.ALL.map { exe ->
            exe to verifyInternal(ctx, exe, libDir, listing, apkLibNames)
        }
        val report = PrepareReport(entries)

        RuntimeDiagnostics.append(
            ctx, "native-assets", report.allRequiredReady,
            if (report.allRequiredReady) "原生资产全部就位（${entries.size} 项）"
            else "原生资产校验失败：${report.failedRequired.joinToString(", ") { it.first.libName }}",
            "nativeLibraryDir=${libDir.absolutePath}\n" +
                "依赖解析方式=二进制自带 \$ORIGIN RUNPATH；探针裸环境跑，不设 LD_LIBRARY_PATH\n" +
                "lib 目录内容（${listing.lines().size - 3} 项）:\n" +
                listing.lineSequence().drop(2).joinToString("\n") { "  $it" } + "\n" +
                report.toDiagnosticLines().joinToString("\n"),
            data = report.toJson(),
        )
        val capEntries = NativeAssetRegistry.CAPABILITY.map { exe ->
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

    fun verify(ctx: Context, exe: NativeExecutable): AssetStatus {
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
        exe: NativeExecutable,
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
        }

        if (exe.probeArgs.isEmpty() && exe.probeExpect == null) {
            return if (f.canRead() || f.length() > 0) {
                AssetStatus.Ready(exe, f.absolutePath, "数据资产：${f.length()} 字节（不做 exec-probe）")
            } else {
                AssetStatus.NotExecutable(exe, f.absolutePath, null, "文件存在但不可读且长度为 0")
            }
        }

        return probe(exe, f)
    }

    private fun probe(exe: NativeExecutable, f: File): AssetStatus {
        try {
            val cmd = mutableListOf(f.absolutePath).apply { addAll(exe.probeArgs) }
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply { environment().clear() }
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val exit = p.waitFor()

            if (exit != 0) return AssetStatus.ProbeFailed(exe, f.absolutePath, exit, out)
            val expect = exe.probeExpect
            if (expect != null && !out.contains(expect)) {
                return AssetStatus.ProbeFailed(exe, f.absolutePath, exit, out)
            }
            return AssetStatus.Ready(exe, f.absolutePath, out)
        } catch (e: IOException) {
            return AssetStatus.NotExecutable(exe, f.absolutePath, parseErrno(e.message), err(e))
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
