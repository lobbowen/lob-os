package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.fs.StateFiles
import lobos.kernel.layout.SystemDirs
import org.json.JSONArray
import org.json.JSONObject

object PieceOriginStore {
    const val FILE = "piece-origins.json"
    const val SOURCE_APK = "apk"
    const val SOURCE_OTA = "ota"

    data class Slot(
        val role: String,
        val entryPath: String,
        val targetPath: String,
    ) {
        val hasEntry: Boolean get() = entryPath.isNotBlank()
        val hasTarget: Boolean get() = targetPath.isNotBlank()
        val complete: Boolean get() = hasEntry && hasTarget
    }

    data class Origin(
        val id: String,
        val active: String,
        val apkVersion: String,
        val apkSlots: List<Slot>,
        val otaVersion: String,
        val otaSlots: List<Slot>,
        val atMs: Long,
    ) {
        val fromApk: Boolean get() = active == SOURCE_APK
        val fromOta: Boolean get() = active == SOURCE_OTA
        val hasApkOriginal: Boolean get() = apkSlots.any { it.complete }
        val hasOta: Boolean get() = otaSlots.any { it.complete }

        val apkEntryPath: String get() = apkSlots.firstOrNull { it.complete }?.entryPath.orEmpty()
        val apkTargetPath: String get() = apkSlots.firstOrNull { it.complete }?.targetPath.orEmpty()
        val otaEntryPath: String get() = otaSlots.firstOrNull { it.complete }?.entryPath.orEmpty()
        val otaTargetPath: String get() = otaSlots.firstOrNull { it.complete }?.targetPath.orEmpty()
    }

    private fun file(ctx: Context): File = File(SystemDirs.etc(ctx), FILE)

    private fun read(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())

    private fun slotsOf(o: JSONObject, key: String): List<Slot> {
        val arr = o.optJSONArray(key)
        if (arr != null) {
            val out = mutableListOf<Slot>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val role = s.optString("role", "").trim()
                val entry = s.optString("entryPath", "").trim()
                val target = s.optString("targetPath", "").trim()
                if (role.isEmpty() && entry.isEmpty() && target.isEmpty()) continue
                out += Slot(role, entry, target)
            }
            return out
        }

        val legacyEntry = o.optString("apkEntryPath", "").trim()
        val legacyTarget = o.optString("apkTargetPath", "").trim()
        if (legacyEntry.isEmpty() && legacyTarget.isEmpty()) return emptyList()
        return listOf(Slot("", legacyEntry, legacyTarget))
    }

    private fun legacyOtaSlots(o: JSONObject): List<Slot> {
        val entry = o.optString("otaEntryPath", "").trim()
        val target = o.optString("otaTargetPath", "").trim()
        if (entry.isEmpty() && target.isEmpty()) return emptyList()
        return listOf(Slot("", entry, target))
    }

    fun all(ctx: Context): List<Origin> {
        val root = read(ctx)
        val out = mutableListOf<Origin>()
        for (key in root.keys()) {
            val o = root.optJSONObject(key) ?: continue
            val otaSlots = if (o.has("otaSlots")) slotsOf(o, "otaSlots") else legacyOtaSlots(o)
            out += Origin(
                id = o.optString("id", key),
                active = o.optString("active", ""),
                apkVersion = o.optString("apkVersion", ""),
                apkSlots = slotsOf(o, "apkSlots"),
                otaVersion = o.optString("otaVersion", ""),
                otaSlots = otaSlots,
                atMs = o.optLong("atMs", 0L),
            )
        }
        return out
    }

    fun get(ctx: Context, id: String): Origin? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun recordApkSlot(
        ctx: Context,
        id: String,
        version: String,
        role: String,
        entryPath: String,
        targetPath: String,
    ) {
        val cur = get(ctx, id)

        val slots = cur?.apkSlots.orEmpty().filterNot { it.entryPath == entryPath } +
            Slot(role, entryPath, targetPath)
        write(ctx, Origin(
            id = id,
            active = if (otaInCharge(cur)) SOURCE_OTA else SOURCE_APK,
            apkVersion = version,
            apkSlots = slots,
            otaVersion = cur?.otaVersion.orEmpty(),
            otaSlots = cur?.otaSlots.orEmpty(),
            atMs = System.currentTimeMillis(),
        ))
    }

    @Synchronized
    fun recordOtaSlot(
        ctx: Context,
        id: String,
        version: String,
        role: String,
        entryPath: String,
        targetPath: String,
    ) {
        val cur = get(ctx, id)

        val slots = cur?.otaSlots.orEmpty().filterNot { it.entryPath == entryPath } +
            Slot(role, entryPath, targetPath)
        write(ctx, Origin(
            id = id,
            active = SOURCE_OTA,
            apkVersion = cur?.apkVersion.orEmpty(),
            apkSlots = cur?.apkSlots.orEmpty(),
            otaVersion = version,
            otaSlots = slots,
            atMs = System.currentTimeMillis(),
        ))
    }

    @Synchronized
    fun markApkActive(ctx: Context, id: String) {
        val cur = get(ctx, id) ?: return
        write(ctx, cur.copy(active = SOURCE_APK, atMs = System.currentTimeMillis()))
    }

    fun otaInCharge(origin: Origin?): Boolean = otaSlotInCharge(origin) != null

    fun otaSlotInCharge(origin: Origin?): Slot? =
        origin?.otaSlots?.firstOrNull { slotInCharge(it) }

    fun otaSlotsInCharge(origin: Origin?): List<Slot> =
        origin?.otaSlots.orEmpty().filter { slotInCharge(it) }

    fun slotInCharge(slot: Slot): Boolean {
        if (!slot.complete) return false
        val link = File(slot.entryPath)
        val target = File(slot.targetPath)
        if (!java.nio.file.Files.isSymbolicLink(link.toPath())) return false
        return runCatching {
            link.toPath().toRealPath().toString() == target.toPath().toRealPath().toString()
        }.getOrDefault(false)
    }

    fun apkSlotFor(origin: Origin?, otaSlot: Slot): Slot? {
        if (origin == null) return null
        return origin.apkSlots.firstOrNull { it.complete && it.entryPath == otaSlot.entryPath }
            ?: origin.apkSlots.firstOrNull { it.complete && it.role == otaSlot.role }
    }

    private fun write(ctx: Context, origin: Origin) {
        val root = read(ctx)
        root.put(origin.id, JSONObject().apply {
            put("id", origin.id)
            put("active", origin.active)
            put("apkVersion", origin.apkVersion)
            put("apkSlots", JSONArray().apply {
                origin.apkSlots.forEach { s ->
                    put(JSONObject().apply {
                        put("role", s.role)
                        put("entryPath", s.entryPath)
                        put("targetPath", s.targetPath)
                    })
                }
            })
            put("otaVersion", origin.otaVersion)
            put("otaSlots", JSONArray().apply {
                origin.otaSlots.forEach { s ->
                    put(JSONObject().apply {
                        put("role", s.role)
                        put("entryPath", s.entryPath)
                        put("targetPath", s.targetPath)
                    })
                }
            })
            put("atMs", origin.atMs)
        })
        StateFiles.writeAtomic(file(ctx), root.toString(2))
    }
}
