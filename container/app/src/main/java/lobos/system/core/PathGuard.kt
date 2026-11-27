package lobos.system.core

import java.io.File
import lobos.system.fs.SystemDirs

/**
 * Capability-bridge path policy, NOT a system-wide integrity boundary.
 *
 * This only guards the explicit fs.* bridge methods. A guest is a same-uid
 * process and can reach the same files via its own libc open(), which bypasses
 * this check (and bypasses LD_PRELOAD hooks entirely for static ELFs). Therefore
 * we must not claim "system state is protected" in the rejection text -- that
 * would be a false guarantee. The accurate statement is that the bridge itself
 * declines to touch system directories.
 */
object PathGuard {

    fun integrityRoots(): List<File> = listOf(
        SystemDirs.etc(),
        SystemDirs.usr(),
        SystemDirs.vardir(),
        SystemDirs.run(),
        SystemDirs.opt(),
        File(lobos.system.core.KernelServices.paths().rootDir, "adb"),
        File(lobos.system.core.KernelServices.paths().rootDir, "program-verify.js"),
        File(lobos.system.core.KernelServices.paths().rootDir, "runtime.json"),
    )

    fun rejection(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (!path.startsWith("/")) return "只接受绝对路径（拒绝相对路径绕过）"
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return "路径不可解析"
        for (root in integrityRoots()) {
            val canon = runCatching { root.canonicalFile }.getOrNull() ?: continue
            if (target == canon || target.path.startsWith(canon.path + "/")) {
                return "能力桥不允许访问系统目录：" + canon.path
            }
        }
        return null
    }
}
