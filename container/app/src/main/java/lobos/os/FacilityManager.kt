package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import lobos.ota.ProgramInstaller
import lobos.ota.ProgramManager
import lobos.ota.ProgramOtaUpdater

object FacilityManager {

    data class Reality(val name: String, val installed: Boolean, val version: String, val evidence: String)

    private fun probe(ctx: Context, f: FacilityRegistry.Facility): Reality {
        val name = f.name
        val stateDir = f.stateDir
        val d = File(ctx.filesDir, stateDir)
        val versionFile = File(d, "VERSION")
        val version = if (versionFile.isFile) {
            runCatching { versionFile.readText().trim() }.getOrDefault("")
        } else {
            ""
        }
        val apk = if (f.libName.isNotBlank()) File(ctx.applicationInfo.nativeLibraryDir, f.libName) else null
        val ota = currentVersion(ctx, name)
        val caBundle = File(ctx.filesDir, "usr/ca-bundle.pem")
        val evidence = when {
            apk != null -> apk.absolutePath
            name == "ca" -> caBundle.absolutePath
            ota != null -> File(d, ota).absolutePath
            else -> d.absolutePath
        }
        val installed = when {
            apk != null -> apk.isFile
            name == "ca" -> caBundle.isFile
            ota != null -> File(d, ota).isDirectory
            else -> d.isDirectory
        }
        return Reality(name, installed, version, evidence)
    }

    @Synchronized
    fun reconcile(ctx: Context) {
        val all = FacilityRegistry.all(ctx)
        val arr = JSONArray()
        var installed = 0
        for (f in all) {
            val r = probe(ctx, f)
            if (r.installed) installed += 1
            arr.put(
                JSONObject().apply {
                    put("name", f.name)
                    put("kind", f.kind.name)
                    put("enabled", f.enabled)
                    put("installed", r.installed)
                    put("version", if (r.version.isBlank()) f.version else r.version)
                    put("deps", JSONArray(f.deps))
                    put("stateDir", f.stateDir)
                    put("tier", f.tier)
                    put("source", f.source)
                    put("evidence", r.evidence)
                },
            )
        }
        StateFiles.writeJson(
            File(FacilityRegistry.root(ctx), "state.json"),
            JSONObject().apply {
                put("updatedAt", System.currentTimeMillis())
                put("installedCount", installed)
                put("total", all.size)
                put("facilities", arr)
            },
        )
        val missing = all.filter { !probe(ctx, it).installed }.map { it.name }
        if (missing.isNotEmpty()) {
            Journal.note(ctx, "facility", false, "设施缺失（登记与实物不一致）", "缺=" + missing.joinToString(","))
        }
    }

    @Synchronized
    fun status(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (f in FacilityRegistry.all(ctx)) {
            val r = probe(ctx, f)
            arr.put(
                JSONObject().apply {
                    put("name", f.name)
                    put("kind", f.kind.name)
                    put("enabled", f.enabled)
                    put("installed", r.installed)
                    put("declaredVersion", f.version)
                    put("currentVersion", currentVersion(ctx, f.name) ?: "")
                    put("deps", JSONArray(f.deps))
                    put("stateDir", f.stateDir)
                    put("tier", f.tier)
                    put("source", f.source)
                    put("evidence", r.evidence)
                },
            )
        }
        return arr
    }

    fun nodeBin(ctx: Context): File? {
        val v = currentVersion(ctx, "node") ?: return null
        val dir = FacilityRegistry.dirFor(ctx, "node")
        val bin = File(File(dir, v), "bin/node")
        return bin.takeIf { it.isFile }
    }

    fun managerFor(ctx: Context, name: String): ProgramManager? {
        val dir = FacilityRegistry.dirFor(ctx, name)
        return ProgramManager(ctx, name, dir)
    }

    fun currentVersion(ctx: Context, name: String): String? =
        runCatching { managerFor(ctx, name)?.currentVersion() }.getOrNull()

    fun install(
        ctx: Context,
        name: String,
        zip: File,
        manifest: JSONObject?,
        manifestFile: File?,
    ): ProgramInstaller.InstallResult {
        val dir = FacilityRegistry.dirFor(ctx, name)
        val r = ProgramInstaller.install(
            context = ctx, zip = zip, manifest = manifest, source = ProgramInstaller.Source.OTA,
            manifestFile = manifestFile, programId = name, storeRoot = dir,
        )
        if (r.ok && !r.version.isNullOrBlank()) {
            FacilityRegistry.setVersion(ctx, name, r.version!!)
            reconcile(ctx)
        }
        return r
    }

    fun upgrade(ctx: Context, name: String, checkOnly: Boolean = false): ProgramOtaUpdater.Outcome? =
        runCatching { managerFor(ctx, name)?.let { ProgramOtaUpdater.checkAndUpdate(ctx, it, checkOnly) } }.getOrNull()

    fun assemble(ctx: Context) {
        val enabled = FacilityRegistry.all(ctx).filter { it.enabled }
        val view = JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("facilities", JSONArray(enabled.map { it.name }))
        }
        val usr = File(ctx.filesDir, "usr")
        usr.mkdirs()
        StateFiles.writeJson(File(usr, "facilities.json"), view)
        val cur = File(usr, "current")
        cur.mkdirs()
        for (f in enabled) {
            val dir = FacilityRegistry.dirFor(ctx, f.name)
            val version = currentVersion(ctx, f.name) ?: continue
            val target = File(dir, version)
            if (!target.isDirectory) continue
            val link = File(cur, f.name)
            runCatching {
                if (link.exists()) link.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            }
        }
        Journal.note(ctx, "facility", null, "装配视图已更新（usr/）", "启用=" + enabled.joinToString(",") { it.name })
    }

    @Synchronized
    fun setEnabled(ctx: Context, name: String, enabled: Boolean): Boolean {
        if (!FacilityRegistry.setEnabled(ctx, name, enabled)) return false
        Journal.note(ctx, "facility", null, if (enabled) "启用设施" else "停用设施", "name=" + name)
        reconcile(ctx)
        return true
    }

    @Synchronized
    fun uninstall(ctx: Context, name: String): Boolean {
        val reg = FacilityRegistry.all(ctx).firstOrNull { it.name == name } ?: return false
        val dir = File(ctx.filesDir, reg.stateDir)
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        val f = FacilityRegistry.fileFor(ctx)
        val o = StateFiles.readJson(f)
        if (o != null) {
            val arr = o.optJSONArray("facilities")
            val out = JSONArray()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    if (e.optString("name") != name) out.put(e)
                }
            }
            o.put("facilities", out)
            StateFiles.writeJson(f, o)
        }
        Journal.note(ctx, "facility", removed, "卸载设施", "name=" + name + " dir=" + reg.stateDir)
        reconcile(ctx)
        return removed
    }
}
