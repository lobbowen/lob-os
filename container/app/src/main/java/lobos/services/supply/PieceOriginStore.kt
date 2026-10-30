package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.fs.StateFiles
import lobos.kernel.layout.SystemDirs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where does the active entry of a system piece come from: the APK original or an OTA version.
 *
 * C-103 (why are there two sides): the preinstall path and the OTA update path both write the same
 * entry pointer (usr/bin/<name> or usr/lib/<name>). The preinstall side points it at the copy
 * unpacked from the APK assets (the APK original); the OTA side points it at an OTA version
 * directory. Neither side recorded which one was active, and PrefixProvisioner.provision does not
 * rebuild piece bodies, so "the entry is back to the APK original" had no judgeable fact.
 * This store is that missing fact: two sides per piece (the APK original and the OTA version)
 * plus which one is active.
 *
 * Slots:
 *   apk side  — the entry pointer and the body that the preinstall side landed (the APK original)
 *   ota side  — the entry pointer and the body that the OTA side put in place
 *   active    — which of the two the entry pointer currently points at
 *
 * Defect name (C-102) **one pointer slot per piece lost the library-role pointer**:
 * the store kept a single "apkEntryPath / apkTargetPath" pair, so a piece that has no program
 * entry (a library piece: assetEntry is empty in the registry) had no place to write its entry
 * pointer at all — the library side only got the body, and the pointer itself (usr/lib/<name>)
 * was never recorded. Then "the entry is back to the APK original" had no pointer to compare for
 * that whole family, and the rollback path had to recompute the pointer name from an empty
 * assetEntry, which yields an empty name (an entry under lib/ with no name at all).
 * Now every pointer is recorded per role: the program role and the library role each own their
 * own slot (role + entry pointer + body), and the lookup is by pointer path or by role.
 */
object PieceOriginStore {
    const val FILE = "piece-origins.json"
    const val SOURCE_APK = "apk"
    const val SOURCE_OTA = "ota"

    /** One entry pointer of one role: where the pointer lives and which body it points at. */
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

        //  下面这四格是**派生索引**：从按角色分的指针格子里取第一个完整的那一条。
        //  缺陷名（C-102）**只留了一对平铺格子**（apkEntryPath / apkTargetPath）：
        //   而库形态那一件的那一条指针**根本没地方记** => 而这一对格子
        //   对纯库件恒为空 => 而「有没有来源指针」「回退落在哪」这两问对纯库件答不出。
        //   现在：真实来源是 apkSlots / otaSlots（按角色分），下面这四格只是它们的派生视图。
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
        // 旧格式（8fdec62 那一版按 requestId 之外的一对平铺格子写的）—— 读得到就不丢。
        //   而那一版只有**一对**指针格子，=> 而库形态那一件的那一条指针**根本没记**（见类注释那一段）。
        //   => 而这一支把它按「角色未知」收进来：指针格子空的那一件**不算**完整指针
        //   （=> 而回退会报缺，=> 而不会拿一个没有指针的记录去顶替）。
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

    /**
     * The preinstall side records the APK original: the entry pointer of that role and the body
     * it points at.
     *
     * Defect name (C-102) **the library-role body overwrote the entry slot**:
     * a piece with both roles has two landed bodies, and the library one was written into the
     * same slot the rollback uses as its landing point. Now the two roles write different slots.
     *
     * Defect name (C-102) **this call reset the active slot even while an OTA version is in
     * charge**: the preinstall path runs on every boot, and it used to write active=apk
     * unconditionally. The OTA version was still the one the entry pointer pointed at, so the
     * record said "apk" while the pointer said otherwise — and "the entry is back to the APK
     * original" was again unjudgeable, this time by a write that only recorded one side.
     * Now: the active slot follows the pointer, not the writer.
     */
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
        val slots = cur?.apkSlots.orEmpty().filterNot { it.role == role } + Slot(role, entryPath, targetPath)
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

    /** The OTA side records the OTA version and makes it the active pointer. */
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
        val slots = cur?.otaSlots.orEmpty().filterNot { it.role == role } + Slot(role, entryPath, targetPath)
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

    /** After a rollback the APK original is the active pointer again. */
    @Synchronized
    fun markApkActive(ctx: Context, id: String) {
        val cur = get(ctx, id) ?: return
        write(ctx, cur.copy(active = SOURCE_APK, atMs = System.currentTimeMillis()))
    }

    /**
     * Is the OTA version still in charge? The judgement is made on the pointer, not on the record:
     * the entry link must be a symlink and its real path must be the OTA body.
     *
     * Defect name (C-102) **「是不是软链」当成了「有没有被接管」**:
     * the preinstall path builds a symlink too (usr/bin/<name> points at the landed copy), so
     * "the entry is a symlink" is true for every piece and answers nothing. The judgeable fact is
     * "the pointer now points at the body that was recorded on the OTA side".
     */
    fun otaInCharge(origin: Origin?): Boolean = otaSlotInCharge(origin) != null

    /** The OTA slot that is currently in charge (the pointer points at its body), or null. */
    fun otaSlotInCharge(origin: Origin?): Slot? =
        origin?.otaSlots?.firstOrNull { slotInCharge(it) }

    /** All the entry pointers that the OTA side currently holds (one per role). */
    fun otaSlotsInCharge(origin: Origin?): List<Slot> =
        origin?.otaSlots.orEmpty().filter { slotInCharge(it) }

    /** Is that one pointer pointing at the body that was recorded for it? */
    fun slotInCharge(slot: Slot): Boolean {
        if (!slot.complete) return false
        val link = File(slot.entryPath)
        val target = File(slot.targetPath)
        if (!java.nio.file.Files.isSymbolicLink(link.toPath())) return false
        return runCatching {
            link.toPath().toRealPath().toString() == target.toPath().toRealPath().toString()
        }.getOrDefault(false)
    }

    /**
     * The APK original slot that belongs to that OTA pointer: same pointer path first, then the
     * same role. The pointer path is the primary key because the OTA side may take over a
     * pointer that was recorded under another name.
     */
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
