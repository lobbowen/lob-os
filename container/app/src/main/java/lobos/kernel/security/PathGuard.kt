package lobos.kernel.security

import java.io.File
import lobos.kernel.layout.SystemDirs

object PathGuard {

    fun integrityRoots(): List<File> = listOf(
        SystemDirs.etc(),
        SystemDirs.usr(),
        SystemDirs.vardir(),
        SystemDirs.run(),
        SystemDirs.opt(),
        File(lobos.kernel.spi.KernelServices.paths().rootDir, "adb"),
        File(lobos.kernel.spi.KernelServices.paths().rootDir, "program-verify.js"),
        File(lobos.kernel.spi.KernelServices.paths().rootDir, "runtime.json"),
    )

    fun rejection(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (!path.startsWith("/")) return "只接受绝对路径（拒绝相对路径绕过）"
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return "路径不可解析"
        for (root in integrityRoots()) {
            val canon = runCatching { root.canonicalFile }.getOrNull() ?: continue
            if (target == canon || target.path.startsWith(canon.path + "/")) {
                return "系统状态受完整性保护（S3）：" + canon.path
            }
        }
        return null
    }
}
