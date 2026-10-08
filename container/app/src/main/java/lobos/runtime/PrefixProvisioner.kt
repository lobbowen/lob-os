package lobos.runtime

import android.content.Context
import android.system.Os
import java.io.File
import lobos.os.ProgramManager

object PrefixProvisioner {

    /** 件自带的说明 —— 与件同目录，系统靠它知道这件是什么 */
    const val META_NAME = "component-meta.json"

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"


    fun root(ctx: Context): File = lobos.os.SystemDirs.usr(ctx)
    fun binDir(ctx: Context): File = lobos.os.SystemDirs.bin(ctx)
    fun libDir(ctx: Context): File = lobos.os.SystemDirs.lib(ctx)

    fun includeDir(ctx: Context): File = lobos.os.SystemDirs.include(ctx)

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))

    /**
     * 铺一件 —— 落位的形状就是身份，说明与件同目录。
     *
     *   usr/lib/<id>/<版本>/bin/<名字>    命令（usr/bin 建软链，PATH 里有）
     *   usr/lib/<id>/<版本>/lib/<名字>.so  库
 *
 * 命令与库都建全局软链（usr/bin/<名字> 与 usr/lib/<库文件名>）——
 * 「切换版本」就是改那一条软链，所有程序立刻生效。
     *
     * 形态由调用方从文件本身判断（可执行件还是共享库）—— 说明里不声明形态，
     * 照抄 ldconfig："checks the header and filenames"。
     */
    private fun landOne(
        ctx: Context,
        id: String,
        version: String,
        soName: String,
        src: File,
        isEntry: Boolean,
        meta: org.json.JSONObject?,
    ): String? {
        val verDir = lobos.os.SystemDirs.pieceDir(ctx, id, version)
        verDir.mkdirs()
        val dst = File(verDir, if (isEntry) "bin/$soName" else "lib/$soName")
        if (!src.isFile) return null
        if (!dst.isFile || dst.length() != src.length()) {
            dst.parentFile?.mkdirs()
            try {
                src.copyTo(dst, overwrite = true)
                if (isEntry) ExecBits.apply(dst)
            } catch (_: Exception) {
                dst.delete()
                return null
            }
        }
        // 说明随件同落 —— 内核靠它知道「这件是什么」
        if (meta != null) {
            runCatching {
                lobos.os.StateFiles.writeAtomic(
                    File(verDir, META_NAME), meta.toString(1) + "\n",
                )
            }
        }
        // 全局入口 —— 命令与库**都**建软链，同一件事：
        //   命令  usr/bin/<名字>              → usr/lib/<id>/<版本>/bin/<名字>
        //   库usr/lib/<库文件名>   → usr/lib/<id>/<版本>/lib/<库文件名>
        //
        // 「切换版本」就是改这一条软链，所有程序立刻生效 ——
        // **不给每个程序单独配**：程序只管用，用的是全局那份。
        // 这正是 ldconfig 对 libfoo.so → .so.1 → .so.1.12 做的事
        // （ldconfig(8)：「checks the header and filenames when determining
        //  which versions should have their links updated」）。
        val link = if (isEntry) {
            lobos.os.SystemDirs.bin(ctx).let { File(it, soName) }
        } else {
            lobos.os.SystemDirs.lib(ctx).let { File(it, soName) }
        }
        link.parentFile?.mkdirs()
        if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
        java.nio.file.Files.deleteIfExists(link.toPath())
        java.nio.file.Files.createSymbolicLink(
            link.toPath(),
            dst.toPath().toAbsolutePath().normalize(),
        )
        return dst.absolutePath
    }

    /**
     * 扫 APK 里的说明 —— jniLibs 下与件同名的 .meta.json 是唯一的数据源。
     *
     * 内核不预置任何一件的清单（那是这块设计的第一条）：它只认落位。
     * 目录里没有说明的 .so 不铺 —— 说不清自己是什么的东西不该进系统。
     */
    private fun scanMeta(nativeDir: File): List<org.json.JSONObject> {
        val out = mutableListOf<org.json.JSONObject>()
        val kids = nativeDir.listFiles() ?: return out
        for (f in kids) {
            if (!f.name.endsWith(META_NAME)) continue
            runCatching {
                val m = org.json.JSONObject(f.readText())
                // 说明自己的文件名就是那件的 .so 名（内核不预置名字）
                m.put("file", f.name)
                out += m
            }
        }
        return out.sortedBy { it.optString("id", "") }
    }

    /**
     * 说明的文件名去掉 .meta.json → 那件的 .so 名。
     *
     * 内核不预置任何一件的名字（照抄 ldconfig：只认文件名模式）；
     * 名字就在落位处那份说明的文件名里。
     */
    private fun fileNameOf(meta: org.json.JSONObject): String =
        meta.optString("file", "").removeSuffix(META_NAME)

    fun provision(ctx: Context): List<String> {
        val ready = mutableListOf<String>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        // 说明随件打进APK 的 jniLibs —— 它是唯一的数据源（deb-control(5) 的做法：
        // 每个包自带 control，内核不预置任何一件的清单）。
        // 扫 jniLibs 里带说明的条目，铺成 usr/lib/<id>/<版本>/ 的形状。
        for (m in scanMeta(nativeDir)) {
            val id = m.optString("id", "")
            val version = m.optString("version", "")
            if (id.isBlank() || version.isBlank()) continue
            // .so 的名字就是说明的文件名去掉 .meta.json（内核不预置任何一件的名字）
            val soName = fileNameOf(m)
            val src = File(nativeDir, soName)
            if (!src.isFile) continue
            // 形态看文件本身：ELF 里有没有 PT_INTERP / 是不是 ET_EXEC
            val isEntry = ExecBits.isRunnable(src)
            val landed = landOne(ctx, id, version, soName, src, isEntry, m) ?: continue
            ready += landed
        }
        linkHeadersInclude(ctx)?.let { ready += it }
        val caDst = caBundle(ctx)
        try {
            caDst.parentFile?.mkdirs()
            ctx.assets.open(CA_BUNDLE_ASSET).use { input ->
                caDst.outputStream().use { out -> input.copyTo(out) }
            }
            ready += CA_BUNDLE_NAME
        } catch (_: Exception) { caDst.delete() }
        registerProvisioned(ctx)
        return ready
    }

    /**
     * 铺完之后登记 —— 与应用走同一条路（装完 upsert），只是源不同。
     *
     * 此前这些件只被「拷到 $PREFIX」就结束了，不进注册表。后果是系统答不出
     * 「装了什么、什么版本、哪些文件是它铺的」—— 内核只能到处硬编码它们的名字。
     *
     * **登记的位置就是落位的位置**：`usr/bin/<name>` 或 `usr/lib/<name>`，
     * 不另设 stateDir —— 注册表说的与磁盘上的必须是同一处。
     */
    /**
     * 铺完之后登记 —— 数据来自**扫落位**（[lobos.os.PieceScan]），不来自任何表。
     *
     * 铺位的形态就是身份：有bin/ 是命令 · 只有 .so 是库 · 有 include/ 是头文件集；
     * 目录名是版本。扫一遍就得到注册表要的全部字段。
     */
    private fun registerProvisioned(ctx: Context) {
        for (f in lobos.os.PieceScan.scan(ctx)) {
            val prev = lobos.os.ProgramIndex.get(ctx, f.id)
            if (prev != null && prev.version == f.version && prev.stateDir == f.dir.absolutePath) {
                continue
            }
            lobos.os.ProgramIndex.upsert(
                ctx,
                (prev ?: lobos.os.ProgramIndex.empty(f.id, lobos.os.Level.PIECE)).copy(
                    version = f.version,
                    stateDir = f.dir.absolutePath,
                    assetEntry = f.entry,
                    role = f.role,
                    sha256 = f.sha256,
                ),
            )
        }
    }

    private fun isManagedByUpdate(ctx: Context, dst: File): Boolean = try {
        if (!java.nio.file.Files.isSymbolicLink(dst.toPath())) return@try false
        val target = dst.toPath().toRealPath()
        target.startsWith(libDir(ctx).toPath().toAbsolutePath())
    } catch (_: Throwable) {
        false
    }

    fun shellBin(ctx: Context): File? = lobos.os.PieceScan.shellBin(ctx)

    private fun linkHeadersInclude(ctx: Context): List<String> {
        val headersRoot = headersIncludeDir(ctx) ?: return emptyList()
        val link = includeDir(ctx)
        return try {
            link.parentFile?.mkdirs()
            val cur = runCatching { link.toPath().toRealPath() }.getOrNull()
            if (cur != null && cur == runCatching { headersRoot.toPath().toRealPath() }.getOrNull()) {
                return listOf("include")
            }
            if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                return emptyList()
            }
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            java.nio.file.Files.createSymbolicLink(link.toPath(), headersRoot.toPath())
            listOf("include")
        } catch (_: Exception) {
            runCatching { link.delete() }
            emptyList()
        }
    }

    fun headersIncludeDir(ctx: Context): File? {
        return try {
        // 头文件集那件 = 落位里有 include/ 的那一个（扫落位找，不查表）
        val dir = lobos.os.PieceScan.scan(ctx)
            .firstOrNull { File(it.dir, "include").isDirectory }?.dir ?: return null
        } catch (_: Throwable) {
            null
        }
    }
}
