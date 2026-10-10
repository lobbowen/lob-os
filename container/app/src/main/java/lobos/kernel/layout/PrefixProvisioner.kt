package lobos.kernel.layout

import android.content.Context
import android.system.Os
import java.io.File
import lobos.kernel.elf.ExecBits

object PrefixProvisioner {

    /** 件自带的说明 —— 与件同目录，系统靠它知道这件是什么 */
    const val META_NAME = "component-meta.json"

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"

    /** 件说明在 assets 里的目录 —— 与 scripts/recipes/piece-env.sh 的 META_ASSETS 同一个 */
    private const val META_ASSET_DIR = "supply/meta"


    fun root(ctx: Context): File = lobos.kernel.layout.SystemDirs.usr(ctx)
    fun binDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.bin(ctx)
    fun libDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.lib(ctx)

    fun includeDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.include(ctx)

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
        val verDir = lobos.kernel.layout.SystemDirs.pieceDir(ctx, id, version)
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
            lobos.kernel.layout.SystemDirs.bin(ctx).let { File(it, soName) }
        } else {
            lobos.kernel.layout.SystemDirs.lib(ctx).let { File(it, soName) }
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
     * 扫 APK 里的说明 —— assets/supply/meta/ 下与件同名的 .meta.json。
     *
     * 内核不预置任何一件的清单（那是这块设计的第一条）：它只认落位。
     * 目录里没有说明的 .so 不铺 —— 说不清自己是什么的东西不该进系统。
     *
     * ★ 为什么走 assets 而不是 nativeLibraryDir：
     *   jniLibs 目录里的 .meta.json **进不了 APK** —— AGP 的 jniLibs 打包
     *   只取 *.so（JniLibsPackaging 只有 excludes / pickFirsts /
     *   keepDebugSymbols，没有「非 .so 也打进去」的开关）。
     *   此前这里扫 nativeLibraryDir，永远扫不到任何东西，于是 provision()
     *   静默返回空列表 —— APK 里的每一件都铺不出来，而构建期全绿。
     *   assets 放得下任意文件（同 CA_BUNDLE_ASSET 与 supply/channel.json
     *   的既有做法），所以说明随 assets 走。
     */
    private fun scanMeta(ctx: Context): List<org.json.JSONObject> {
        val out = mutableListOf<org.json.JSONObject>()
        val names = try {
            ctx.assets.list(META_ASSET_DIR)?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        for (name in names) {
            if (!name.endsWith(META_NAME)) continue
            runCatching {
                val m = org.json.JSONObject(
                    ctx.assets.open("$META_ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                )
                // 说明自己的文件名就是那件的 .so 名（内核不预置名字）
                m.put("file", name)
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
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        // 件本体在 nativeLibraryDir（AGP 只把 *.so 打进 APK），
        // 说明在 assets/supply/meta/ —— 两者配套，见 scanMeta 的注释。
        // 铺成 usr/lib/<id>/<版本>/ 的形状。
        val metas = scanMeta(ctx)
        for (m in metas) {
            val id = m.optString("id", "")
            val version = m.optString("version", "")
            if (id.isBlank() || version.isBlank()) continue
            // .so 的名字就是说明的文件名去掉 .meta.json（内核不预置任何一件的名字）
            val soName = fileNameOf(m)
            // 共享库是一整条链（libz.so → libz.so.1 → libz.so.1.3.2），三层是
            // **同一份字节的三个名字**（land_piece 用 cp -f 解开了软链，见
            // piece-env.sh）。但 AGP 的 jniLibs 打包只认 *.so 结尾，
            // 版本化命名那两层进不了 APK —— 于是按 DT_NEEDED 的名字找的
            // linker 在真机上会找不到 libz.so.1。
            //
            // 所以：这一层的字节不在包里时，拿**同 id 那份在包里的**顶上。
            // 同一个件的几层本来就是同一份字节，不是两份不同的库。
            val src = File(nativeDir, soName).takeIf { it.isFile }
                ?: nativeDir.let { d ->
                    // 同 id 的其它层：说明文件名去掉 .meta.json 后以 .so 开头
                    metas
                        .asSequence()
                        .filter { it.optString("id", "") == id }
                        .map { fileNameOf(it) }
                        .firstOrNull { n -> n.endsWith(".so") && File(d, n).isFile }
                        ?.let { File(d, it) }
                }
                ?: continue
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
        // 不是软链就不是我们建的 —— 先判形，再看它指向哪
        if (!java.nio.file.Files.isSymbolicLink(dst.toPath())) false
        else dst.toPath().toRealPath().startsWith(libDir(ctx).toPath().toAbsolutePath())
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

    // 头文件集那件 = 落位里有 include/ 的那一个（扫落位找，不查表）
    fun headersIncludeDir(ctx: Context): File? =
        try {
            lobos.os.PieceScan.scan(ctx)
                .firstOrNull { File(it.dir, "include").isDirectory }?.dir
        } catch (_: Throwable) {
            null
        }
}
