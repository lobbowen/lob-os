package lobos.pieces

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import lobos.RuntimeDiagnostics
import lobos.os.ElfFacts
import lobos.os.PieceScan
import lobos.os.SystemDirs
import lobos.runtime.ProcessSupervisor
import org.json.JSONArray
import org.json.JSONObject

sealed class AssetStatus {

    /**
     * 铺出来了，但与**登记**不符 —— 文件被换过或被删了。
     * 照抄 dpkg -V：拿数据库里记的比，不是看磁盘就下结论。
     */
    data class Mismatched(
        val id: String,
        val path: String,
        val mismatched: List<String>,
    ) : AssetStatus()

    data class Ready(
        val exe: lobos.os.PieceScan.Found,
        val path: String,
    ) : AssetStatus()

    data class MissingFromLib(
        val exe: lobos.os.PieceScan.Found,
        val path: String,
        val inApk: Boolean,
        val libListing: String,
    ) : AssetStatus()

    data class MissingDependency(
        val exe: lobos.os.PieceScan.Found,
        val dep: String,
        val libListing: String,
    ) : AssetStatus()

    data class NotExecutable(
        val exe: lobos.os.PieceScan.Found,
        val path: String,
        val errnoHint: Int?,
        val raw: String,
    ) : AssetStatus()
    /** 编译期已保证正确，装上去却不能用 —— 与 Linux 一致：系统不验，运行时自己会报错 */
    data class Unusable(

        val exe: lobos.os.PieceScan.Found,
        val path: String,
        val exit: Int,
        val output: String,
    ) : AssetStatus()
}

data class PrepareReport(val entries: List<Pair<lobos.os.PieceScan.Found, AssetStatus>>) {

    val allRequiredReady: Boolean
        get() = entries.filter { it.first.required }.all { it.second is AssetStatus.Ready }

    val failedRequired: List<Pair<lobos.os.PieceScan.Found, AssetStatus>>
        get() = entries.filter { it.first.required && it.second !is AssetStatus.Ready }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for ((exe, st) in entries) {
            val o = JSONObject()
            o.put("id", exe.id)
            o.put("libName", exe.entry.substringAfterLast("/", ""))
            o.put("required", exe.required)
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

        // 校验两件事（照抄 dpkg -V 的形状）：
        //   ① 这个件铺全了没有      —— verifyInternal
        //   ② 铺出来的文件与登记符不符没有 —— PieceScan.verify
        // 少了 ②，被换过一个文件也发现不了。
        val entries = lobos.os.PieceScan.scan(ctx).map { f ->
            val st = verifyInternal(ctx, f, libDir, listing, apkLibNames)
            if (st is AssetStatus.Ready) {
                val v = lobos.os.PieceScan.verify(ctx, f.id)
                if (!v.ok) {
                    AssetStatus.Mismatched(f.id, f.entry, v.mismatched)
                } else st
            } else st
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
        return report
    }
    /**
     * 一件就位没有 —— 照 dpkg 的判据形状逐条问：
     *   ① nativeLibraryDir（= 件的落位处）里有没有它
     *      没有 → MissingFromLib，并说清是「APK 里有但没解压」还是「APK 里就没有」
     *   ② 它的 ELF 依赖（DT_NEEDED）在同目录能不能找到
     *      找不到 → MissingDependency（libcurl 依赖 libz.so.1 那类）
     *   ③ 在但读不了 → NotExecutable
     * 全部通过 → Ready
     *
     * 形状取自 PieceScan.verify（登记与磁盘比对），那是 dpkg -V 那一层；
     * 这里判的是「能不能用」，两者分工不同。
     */
    private fun verifyInternal(
        ctx: Context,
        exe: lobos.os.PieceScan.Found,
        libDir: File,
        listing: String,
        apkLibNames: Set<String>,
    ): AssetStatus {
        val name = exe.entry.substringAfterLast("/", "")
        val f = File(libDir, name)

        if (!f.exists()) {
            val inApk = apkLibNames.contains(name)
            return AssetStatus.MissingFromLib(exe, f.absolutePath, inApk, listing)
        }

        // ② 依赖闭包：同目录能不能解析（ELF 读不出来就跳过这一问，
        //    读不出不等于没问题 —— 那由 PieceScan.verify 那层负责）
        val needed = lobos.os.ElfFacts.read(f)?.needed.orEmpty()
        for (dep in needed) {
            if (!File(libDir, dep).exists()) {
                return AssetStatus.MissingDependency(exe, dep, listing)
            }
        }

        // ③ 在位但不可用
        if (!f.canRead() || f.length() == 0L) {
            return AssetStatus.NotExecutable(exe, f.absolutePath, null, "文件存在但不可读或长度为 0")
        }
        return AssetStatus.Ready(exe, f.absolutePath, "就位")
    }

    /**
     * 程序的库搜索路径 —— **只指我们自己的面**。
     *
     * ★ 不含 nativeLibraryDir：实测那个目录不可写（属主 system，我们是应用 uid），
     *   所以「把系统建在 APK 目录下」这条路走不通 —— 已堵死，别再走。
     *
     * nativeLibraryDir 只作**取源**：APK 里的 .so 从那里取出来，
     * 落到 usr/lib/<id>/<版本>/lib/ 之后就用我们自己的面。
     */
    fun libSearchPath(ctx: Context): String = lobos.os.SystemDirs.lib(ctx).absolutePath


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
