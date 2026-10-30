package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.fs.StateFiles
import lobos.kernel.layout.SystemDirs
import org.json.JSONObject

/**
 * Where does the active entry of a system piece come from: the APK original or an OTA version.
 *
 * C-103 (why are there two sides): the preinstall path and the OTA update path both write the same
 * entry pointer (usr/bin/<name> or usr/lib/<name>). The preinstall side points it at the copy
 * unpacked from the APK assets (the APK original); the OTA side points it at an OTA version
 * directory. Neither side recorded which one was active, and PrefixProvisioner.provision does not
 * rebuild piece bodies, so "the entry is back to the APK original" had no fact to be judged by.
 * This store is that missing fact: two slots per piece (the APK original and the OTA version)
 * plus which one is active.
 *
 * Slots:
 *   apk slot  — the entry pointer and the body that the preinstall side landed (the APK original)
 *   ota slot  — the entry pointer and the body that the OTA side put in place
 *   active    — which of the two the entry pointer currently points at
 */
object PieceOriginStore {
    const val FILE = "piece-origins.json"
    const val SOURCE_APK = "apk"
    const val SOURCE_OTA = "ota"

    data class Origin(
        val id: String,
        val active: String,
        val apkVersion: String,
        val apkEntryPath: String,
        val apkTargetPath: String,
        val otaVersion: String,
        val otaEntryPath: String,
        val otaTargetPath: String,
        val atMs: Long,
    ) {
        val fromApk: Boolean get() = active == SOURCE_APK
        val fromOta: Boolean get() = active == SOURCE_OTA
        val hasApkOriginal: Boolean get() = apkEntryPath.isNotBlank() && apkTargetPath.isNotBlank()
        val hasOta: Boolean get() = otaEntryPath.isNotBlank() && otaTargetPath.isNotBlank()
    }

    private fun file(ctx: Context): File = File(SystemDirs.etc(ctx), FILE)

    private fun read(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())

    fun all(ctx: Context): List<Origin> {
        val root = read(ctx)
        val out = mutableListOf<Origin>()
        for (key in root.keys()) {
            val o = root.optJSONObject(key) ?: continue
            out += Origin(
                id = o.optString("id", key),
                active = o.optString("active", ""),
                apkVersion = o.optString("apkVersion", ""),
                apkEntryPath = o.optString("apkEntryPath", ""),
                apkTargetPath = o.optString("apkTargetPath", ""),
                otaVersion = o.optString("otaVersion", ""),
                otaEntryPath = o.optString("otaEntryPath", ""),
                otaTargetPath = o.optString("otaTargetPath", ""),
                atMs = o.optLong("atMs", 0L),
            )
        }
        return out
    }

    fun get(ctx: Context, id: String): Origin? = all(ctx).firstOrNull { it.id == id }

    /**
     * The preinstall side records the APK original (entry pointer + landed body).
     *
     * Defect name (C-102) **the library-role body overwrote the entry slot**:
     * a piece with both roles has two landed bodies, and the library one was written into the
     * same "apkTargetPath" slot the rollback uses as its landing point. Now the two roles write
     * different slots: the program role owns the entry pointer, the library role owns a body.
     *
     * Defect name (C-102) **this call resets the active slot even while an OTA version is in
     * charge**: the preinstall path runs on every boot, and it used to write active=apk
     * unconditionally. The OTA version was still the one the entry pointer pointed at, so the
     * record said "apk" while the pointer said otherwise — and "the entry is back to the APK
     * original" was again unjudgeable, this time by a write that only recorded one side.
     * Now: the active slot follows the pointer, not the writer.
     */
    @Synchronized
    fun recordApkOriginal(
        ctx: Context,
        id: String,
        version: String,
        entryPath: String,
        targetPath: String,
    ) {
        val cur = get(ctx, id)
        write(ctx, Origin(
            id = id,
            active = if (otaInCharge(cur)) SOURCE_OTA else SOURCE_APK,
            apkVersion = version,
            apkEntryPath = entryPath,
            apkTargetPath = targetPath,
            otaVersion = cur?.otaVersion.orEmpty(),
            otaEntryPath = cur?.otaEntryPath.orEmpty(),
            otaTargetPath = cur?.otaTargetPath.orEmpty(),
            atMs = System.currentTimeMillis(),
        ))
    }

    /**
     * A library-role body of the APK original (that piece has no program entry, so there is no
     * entry pointer to record — only the body).
     */
    @Synchronized
    fun recordApkBody(ctx: Context, id: String, version: String, bodyPath: String) {
        val cur = get(ctx, id)
        if (cur != null && cur.apkEntryPath.isNotBlank()) return
        val base = cur ?: Origin(
            id = id, active = SOURCE_APK,
            apkVersion = "", apkEntryPath = "", apkTargetPath = "",
            otaVersion = "", otaEntryPath = "", otaTargetPath = "",
            atMs = 0L,
        )
        write(ctx, base.copy(
            apkVersion = if (base.apkVersion.isBlank()) version else base.apkVersion,
            apkTargetPath = bodyPath,
            atMs = System.currentTimeMillis(),
        ))
    }

    /** The OTA side records the OTA version and makes it the active pointer. */
    @Synchronized
    fun recordOtaVersion(
        ctx: Context,
        id: String,
        version: String,
        entryPath: String,
        targetPath: String,
    ) {
        val cur = get(ctx, id)
        write(ctx, Origin(
            id = id,
            active = SOURCE_OTA,
            apkVersion = cur?.apkVersion.orEmpty(),
            apkEntryPath = cur?.apkEntryPath.orEmpty(),
            apkTargetPath = cur?.apkTargetPath.orEmpty(),
            otaVersion = version,
            otaEntryPath = entryPath,
            otaTargetPath = targetPath,
            atMs = System.currentTimeMillis(),
        ))
    }

    /** After a rollback the APK original is the active pointer again. */
    @Synchronized
    fun markApkActive(ctx: Context, id: String) {
        val cur = get(ctx, id) ?: return
        write(ctx, cur.copy(active = SOURCE_APK, atMs = System.currentTimeMillis()))
    }

    /**
     * Is the OTA version still in charge? The judgement is made on the pointer, not on the record:
     * the entry link must be a symlink and its real path must be the OTA body.
     */
    fun otaInCharge(origin: Origin?): Boolean {
        val o = origin ?: return false
        if (!o.hasOta) return false
        val link = File(o.otaEntryPath)
        val target = File(o.otaTargetPath)
        if (!java.nio.file.Files.isSymbolicLink(link.toPath())) return false
        return runCatching {
            link.toPath().toRealPath().toString() == target.toPath().toRealPath().toString()
        }.getOrDefault(false)
    }

    private fun write(ctx: Context, origin: Origin) {
        val root = read(ctx)
        root.put(origin.id, JSONObject().apply {
            put("id", origin.id)
            put("active", origin.active)
            put("apkVersion", origin.apkVersion)
            put("apkEntryPath", origin.apkEntryPath)
            put("apkTargetPath", origin.apkTargetPath)
            put("otaVersion", origin.otaVersion)
            put("otaEntryPath", origin.otaEntryPath)
            put("otaTargetPath", origin.otaTargetPath)
            put("atMs", origin.atMs)
        })
        StateFiles.writeAtomic(file(ctx), root.toString(2))
    }
}
