package lobos.kernel.layout

import java.io.File
import lobos.kernel.elf.ElfFacts

/**
 * ldconfig(8) 的三段链形状：libfoo.so -> libfoo.so.<major> -> libfoo.so.<full>。
 *
 *   - libfoo.so       linker 用的名字（-lfoo 的那个）
 *   - libfoo.so.1     中间层 = ELF 头里的 SONAME（DT_SONAME），运行时被记进 NEEDED 的名字
 *   - libfoo.so.1.12  实体文件
 *
 * 本仓的形状说明：
 *   - 实体文件由构建期给（scripts/recipes/bundle-env.sh 用 cp -Pf 把整条链拷进包）；
 *   - 但进 zip 时符号链接被展平成同名副本（zip 格式不存 symlink），
 *     所以「哪一层是实体」必须靠 ELF 头的 SONAME 加文件名版本层数来判，不能靠 ls -l。
 *   - 落位期（这里）负责在 usr/lib 补出三段链。
 *
 * bionic 侧 ldconfig 的原文今天【未核】（取不到 bionic 源码），
 * 这里的形状照 glibc man 页（ldconfig(8) "the library soname" 一节）写。
 */
object SonameChain {

    /** 一次建链的完整计划 */
    data class Plan(
        val soname: String,       // 中间层名，例 libz.so.1
        val entity: File,        // 实体文件，例 .../lib/libz.so.1.3.2
        val fullName: String?,   // 实体文件名（实体名 != SONAME 时），例 libz.so.1.3.2
        val linkerName: String?, // linker 用的名字（!= SONAME 时），例 libz.so
    )

    /** 落位结果 */
    data class Landed(
        val ok: Boolean,
        val created: Int,     // 本次新建的软链条数
        val detail: String,   // 失败时的说明
        val log: String,      // 给人看的形状
    )

    /** soname -> 去掉版本后缀的基名。libz.so.1 -> libz.so；libcrypto.so -> libcrypto.so */
    fun baseNameOf(soname: String): String {
        val i = soname.indexOf(".so")
        return if (i < 0) soname else soname.substring(0, i + 3)
    }

    /** .so 后面的段数。libz.so.1.3.2 -> 3；libz.so -> 0 */
    private fun versionDepth(name: String): Int {
        val i = name.indexOf(".so")
        if (i < 0) return 0
        val rest = name.substring(i + 3)
        if (rest.isEmpty()) return 0
        return rest.split('.').count { it.isNotEmpty() }
    }

    private fun sonameOf(f: File): String? =
        runCatching { ElfFacts.read(f)?.soname }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 给一个 piece 里的库文件定出三段链。
     * 实体是「同一个 SONAME 的兄弟里版本层数最多的那个」，
     * 因为 zip 展平后 libz.so / libz.so.1 / libz.so.1.3.2 三个副本同名同内容都在。
     * 非共享库（ELF 里没有 DT_SONAME）返回 null。
     */
    fun plan(target: File): Plan? {
        val soname = sonameOf(target) ?: return null
        val base = baseNameOf(soname)
        val dir = target.parentFile ?: return null
        val siblings = dir.listFiles()?.filter { it.isFile && it.name.startsWith(base) } ?: return null

        var entity = target
        var best = versionDepth(target.name)
        for (s in siblings) {
            if (sonameOf(s) != soname) continue
            val d = versionDepth(s.name)
            if (d > best || (d == best && s.name.length > entity.name.length)) {
                entity = s
                best = d
            }
        }

        return Plan(
            soname = soname,
            entity = entity,
            fullName = if (entity.name != soname) entity.name else null,
            linkerName = if (base != soname) base else null,
        )
    }

    /**
     * 在 usr/lib 里把三段链建出来：
     *   usr/lib/<full>   -> 实体文件（绝对路径）
     *   usr/lib/<soname> -> usr/lib/<full>
     *   usr/lib/<base>   -> usr/lib/<soname>
     * 名字退化时（实体名 == SONAME，或 SONAME 没版本）相应少一层。
     * 返回新建条数（已存在且指向一致的不重复计）。
     */
    fun apply(plan: Plan, usrLib: File): Int {
        var created = 0
        val full = plan.fullName?.let { File(usrLib, it) }
        if (full != null) created += put(full, plan.entity)
        val mid = File(usrLib, plan.soname)
        val midTarget = plan.fullName?.let { File(usrLib, it) } ?: plan.entity
        created += put(mid, midTarget)
        plan.linkerName?.let { created += put(File(usrLib, it), mid) }
        return created
    }

    /**
     * 落位一个 piece 里的库文件：能读出 SONAME 的走三段链，
     * 读不出的（静态库、无 DT_SONAME）退化成一条同名软链。
     */
    fun land(target: File, usrLib: File): Landed {
        val p = plan(target)
        if (p == null) {
            val dst = File(usrLib, target.name)
            val r = put(dst, target)
            return Landed(
                ok = r >= 0,
                created = if (r > 0) 1 else 0,
                detail = if (r < 0) target.absolutePath + " -> " + dst.absolutePath else "",
                log = "lib  " + target.name + " -> " + dst.name + "   " + target.absolutePath + "   (无 SONAME，单层)",
            )
        }
        val created = apply(p, usrLib)
        val shape = buildString {
            append(p.linkerName ?: p.soname)
            if (p.fullName != null) {
                append(" -> ").append(p.soname)
                append(" -> ").append(p.fullName)
            }
        }
        return Landed(
            ok = true,
            created = created,
            detail = "",
            log = "lib  " + target.name + " -> " + shape + "   " + p.entity.absolutePath,
        )
    }

    /**
     * 建一条软链。
     * 返回 1 = 本次新建，0 = 已存在且指向一致，-1 = 失败。
     */
    fun put(link: File, target: File): Int {
        val isLink = java.nio.file.Files.isSymbolicLink(link.toPath())
        if (isLink) {
            val cur = File(link.parentFile, java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
            if (runCatching { cur.canonicalFile }.getOrNull() == runCatching { target.canonicalFile }.getOrNull()) {
                return 0
            }
        }
        // 断链的旧软链 exists() 为 false（exists() 跟随符号链接），
        // 走不到 delete，于是 createSymbolicLink 在同一路径上必然 EEXIST 失败。
        // 所以是软链就按软链删，不问 exists()。
        if (isLink) {
            // ★ 编译错误（run 39416349435 第21步 / 39415331935 第21步）：`!` 套在 Any 上 ——
            //   Files.delete 返回**平台类型**（Boolean!）⇒ runCatching 的 Result<T> 推不出 T
            //   ⇒ getOrDefault/getOrElse 的结果类型退化成 Any ⇒ `!` 找不到 not 操作符。
            //   ⇒ 处置：**显式标注** Result<Boolean>（不做类型推断）。
            val deleted: Boolean = runCatching { java.nio.file.Files.delete(link.toPath()) }
                .getOrElse { false }
            if (!deleted) return -1
        } else if (link.exists() && !link.delete()) {
            return -1
        }
        return try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            1
        } catch (_: Throwable) {
            -1
        }
    }
}
