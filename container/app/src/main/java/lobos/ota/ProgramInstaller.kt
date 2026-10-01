package lobos.ota

import android.content.Context
import java.io.File
import org.json.JSONObject

object ProgramInstaller {

    enum class Source(val label: String) {
        OTA("远端 OTA"),
        NONE("无"),
    }

    data class InstallResult(
        val ok: Boolean,
        val version: String?,
        val source: Source,
        val reason: String?,
        val detail: String,
        val nodeVerifyOutput: String = "",
    ) {
        fun toDiagnosticLine(): String = when {
            ok -> "内核安装成功 v=$version（来源=${source.label}）"
            else -> "内核安装未生效（来源=${source.label}）原因=$reason；$detail"
        }
    }

    fun install(
        context: Context,
        zip: File,
        manifest: JSONObject?,
        source: Source,
        nodeBin: File? = lobos.os.NodeRuntime.path(context),
        manifestFile: File? = null,
        programId: String,
        storeRoot: java.io.File? = null,
    ): InstallResult {
        if (programId.isBlank()) {
            return InstallResult(false, null, source, "manifest-id-missing", "包清单未声明 id/name：内核不猜安装目标")
        }
        if (nodeBin == null) {
            return InstallResult(false, null, source, "runtime-missing", lobos.os.NodeRuntime.missing(context))
        }
        if (!zip.isFile) {
            return InstallResult(false, null, source, "zip-missing", "候选包不存在: ${zip.absolutePath}")
        }
        if (zip.length() <= 0) {
            return InstallResult(false, null, source, "zip-empty", "候选包是空文件: ${zip.absolutePath}")
        }
        val needsSignature = manifest?.optString("signature", "")?.isNotBlank() == true
        if (needsSignature && (manifestFile == null || !manifestFile.isFile)) {
            return InstallResult(
                false, null, source, "signature-unverifiable",
                "清单声明了 signature 但缺少可校验的清单文件：拒绝安装（fail-closed）",
            )
        }

        val verify = ProgramVerifier.verify(context, zip, manifest, nodeBin, manifestFile)
        if (!verify.ok) {
            return InstallResult(
                ok = false, version = verify.version, source = source,
                reason = verify.reason, detail = verify.detail, nodeVerifyOutput = verify.raw,
            )
        }
        val version = verify.version
            ?: return InstallResult(false, null, source, "no-version", "校验通过但包内无 version", verify.raw)

        val km = ProgramManager(context, programId, storeRoot)
        if (km.isBelowFloor(version)) {
            return InstallResult(
                ok = false, version = version, source = source, reason = "version-below-floor",
                detail = "候选 " + version + " 低于版本下限 " + km.floorVersion() + " —— 拒绝安装（防回退）。",
                nodeVerifyOutput = verify.raw,
            )
        }
        val previousVersion = km.currentVersion()

        val dest = km.programDir(version)
        if (dest.isDirectory && km.entryPath(version).exists()) {
            km.markPending(version, previousVersion)
            km.setCurrentVersion(version)
            return InstallResult(
                ok = true, version = version, source = source, reason = "already-installed",
                detail = "该版本已落盘，直接切指针（待健康检查通过后提交）", nodeVerifyOutput = verify.raw,
            )
        }

        val tmp = File(km.programDir(version).parentFile, ProgramManager.stagingDirName(version))
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            km.unzipInto(zip, tmp)
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            val reason = if (e is IllegalStateException) "unsafe-or-empty-zip" else "unzip-failed"
            return InstallResult(
                false, version, source, reason,
                "${e::class.java.simpleName}: ${e.message ?: ""}", verify.raw
            )
        }

        val stageRoot = File(tmp, "program").let { if (it.isDirectory) it else tmp }

        val installedManifest = km.readProgramManifest(version, stageRoot)
        if (installedManifest == null) {
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "postcheck-manifest-unreadable",
                "解包后读不到 program-manifest.json —— ZipInputStream 与校验器对包结构理解不一致", verify.raw)
        }
        if (installedManifest.version != version) {
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "postcheck-version-mismatch",
                "校验阶段 version=$version，解包后读到 ${installedManifest.version}", verify.raw)
        }
        if (verify.entryOk == false) {
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "postcheck-entry-missing",
                "包内缺少入口 ${installedManifest.entry}", verify.raw)
        }

        val aside = if (dest.exists()) {
            File(dest.parentFile, dest.name + ".replaced-" + System.currentTimeMillis())
        } else null
        if (aside != null && !dest.renameTo(aside)) {
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "swap-aside-failed",
                "旧版本目录无法让位：不动指针，保持现状", verify.raw)
        }
        val staged = File(stageRoot, version)
        if (!staged.renameTo(dest)) {
            if (aside != null) aside.renameTo(dest)
            tmp.deleteRecursively()
            return InstallResult(false, version, source, "rename-failed",
                "无法把 ${staged.absolutePath} 重命名为 ${dest.absolutePath}（旧版本已复位）", verify.raw)
        }
        if (aside != null) aside.deleteRecursively()

        km.markPending(version, previousVersion)
        km.setCurrentVersion(version)
        tmp.deleteRecursively()
        lobos.ProvisioningProbe.refreshProgramOtaVersions(context)
        return InstallResult(
            ok = true, version = version, source = source, reason = null,
            detail = "已落盘并切换指针（待健康检查通过后提交）: ${dest.absolutePath}", nodeVerifyOutput = verify.raw,
        )
    }

}
