package lobos.kernel.layout

import java.io.File
import lobos.kernel.elf.ElfFacts

object SonameChain {

    data class Plan(
        val soname: String,
        val entity: File,
        val fullName: String?,
        val linkerName: String?,
    )

    data class Landed(
        val ok: Boolean,
        val created: Int,
        val detail: String,
        val log: String,
    )

    fun baseNameOf(soname: String): String {
        val i = soname.indexOf(".so")
        return if (i < 0) soname else soname.substring(0, i + 3)
    }

    private fun versionDepth(name: String): Int {
        val i = name.indexOf(".so")
        if (i < 0) return 0
        val rest = name.substring(i + 3)
        if (rest.isEmpty()) return 0
        return rest.split('.').count { it.isNotEmpty() }
    }

    private fun sonameOf(f: File): String? =
        runCatching { ElfFacts.read(f)?.soname }.getOrNull()?.takeIf { it.isNotBlank() }

    private val sonameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun sonameOfCached(f: File): String? =
        sonameCache.getOrPut(f.absolutePath) { sonameOf(f) ?: "" }
            .takeIf { it.isNotBlank() }

    private val contentDigestCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun contentDigest(f: File): String =
        contentDigestCache.getOrPut(f.absolutePath) {
            lobos.services.supply.SupplySha.sha256(f)
        }

    fun plan(target: File): Plan? {
        val soname = sonameOfCached(target) ?: return null
        val base = baseNameOf(soname)
        val dir = target.parentFile ?: return null
        val siblings = dir.listFiles()?.filter { it.isFile && it.name.startsWith(base) } ?: return null

        var entity = target
        var best = versionDepth(target.name)
        for (s in siblings) {

            if (sonameOfCached(s) != soname) continue
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

    fun put(link: File, target: File): Int {
        val isLink = java.nio.file.Files.isSymbolicLink(link.toPath())
        if (isLink) {
            val cur = File(link.parentFile, java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
            if (runCatching { cur.canonicalFile }.getOrNull() == runCatching { target.canonicalFile }.getOrNull()) {
                return 0
            }
        }

        if (isLink) {

            val deleted: Boolean = runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
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
