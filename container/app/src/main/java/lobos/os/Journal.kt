package lobos.os

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

object Journal {

    private const val DIR = "os/journal"
    private const val FILE = "events.jsonl"
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_LINES = 500

    enum class Reason(val code: String) {
        OEM_BG_LIMIT("bgLimit"),
        OEM_KILL("o-kill"),
        LOW_MEMORY("lowMemory"),
        USER_STOPPED("userStopped"),
        CRASH("crash"),
        ANR("anr"),
        SIGNALED("signaled"),
        SELF_EXIT("exitSelf"),
        DEPENDENCY_DIED("dependencyDied"),
        PACKAGE_CHANGED("packageChanged"),
        PERMISSION_CHANGED("permissionChanged"),
        FREEZER("freezer"),
        UNKNOWN("unknown"),
        UNREADABLE("unreadable");

        companion object {
            fun fromExitInfo(reason: Int, description: String?): Reason {
                val d = description?.lowercase(Locale.US) ?: ""
                return when {
                    d.contains("bglimit") -> OEM_BG_LIMIT
                    d.contains("o-kill") || d.contains("okill") -> OEM_KILL
                    d.contains("freezer") -> FREEZER
                    d.contains("lowmem") || d.contains("low memory") -> LOW_MEMORY
                    d.contains("force stop") || d.contains("force-stop") ||
                        d.contains("user requested") || d.contains("user stopped") ||
                        d.contains("remove task") -> USER_STOPPED
                    d.contains("crash") -> CRASH
                    d.contains("anr") -> ANR
                    else -> when (reason) {
                        1 -> SELF_EXIT
                        2 -> SIGNALED
                        3 -> LOW_MEMORY
                        4, 5 -> CRASH
                        6 -> ANR
                        8 -> PERMISSION_CHANGED
                        10, 11 -> USER_STOPPED
                        12 -> DEPENDENCY_DIED
                        14 -> FREEZER
                        15, 16 -> PACKAGE_CHANGED
                        else -> UNKNOWN
                    }
                }
            }
        }
    }

    data class Event(
        val seq: Long,
        val atMs: Long,
        val category: String,
        val reason: Reason?,
        val detail: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("seq", seq)
            put("at", atMs)
            put("category", category)
            if (reason != null) put("reason", reason.code)
            put("detail", detail)
        }
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    @Volatile private var seq = 0L

    private fun file(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        d.mkdirs()
        return File(d, FILE)
    }

    private fun lastSeq(ctx: Context): Long = runCatching {
        val f = file(ctx)
        if (!f.exists()) 0L else (f.readLines().lastOrNull { it.isNotBlank() }
            ?.let { JSONObject(it).optLong("seq", 0L) } ?: 0L)
    }.getOrDefault(0L)

    @Synchronized
    fun note(ctx: Context, category: String, ok: Boolean?, detail: String, extra: String? = null): Event {
        val text = buildString {
            append(detail)
            if (!extra.isNullOrBlank()) append("；").append(extra)
            if (ok != null) append("（").append(if (ok) "ok" else "failed").append("）")
        }
        return append(ctx, category, null as Reason?, text)
    }

    fun append(ctx: Context, category: String, reason: Reason?, detail: String): Event {
        if (seq == 0L) seq = lastSeq(ctx)
        val ev = Event(++seq, System.currentTimeMillis(), category, reason, detail)
        runCatching {
            val f = file(ctx)
            if (f.length() > MAX_BYTES) rotate(f)
            FileOutputStream(f, true).use { out ->
                out.write((ev.toJson().toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
        return ev
    }

    private fun rotate(f: File) {
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.size <= KEEP_LINES) return
        val keep = lines.takeLast(KEEP_LINES)
        val archived = lines.dropLast(KEEP_LINES)
        lobos.os.StateFiles.writeAtomic(
            File(f.parentFile, FILE + ".1"), archived.joinToString("\n") + "\n"
        )
        lobos.os.StateFiles.writeAtomic(f, keep.joinToString("\n") + "\n")
    }

    @Synchronized
    fun events(ctx: Context, limit: Int = 50): List<Event> {
        val out = mutableListOf<Event>()
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            f.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val rawReason = o.optString("reason")
                out.add(
                    Event(
                        seq = o.optLong("seq"),
                        atMs = o.optLong("at"),
                        category = o.optString("category"),
                        reason = Reason.values().firstOrNull { it.code == rawReason },
                        detail = o.optString("detail"),
                    )
                )
            }
        }
        return out.takeLast(limit)
    }

    @Synchronized
    fun latestSeq(ctx: Context): Long = runCatching {
        val f = file(ctx)
        if (!f.exists()) 0L else (f.readLines().lastOrNull { it.isNotBlank() }
            ?.let { JSONObject(it).optLong("seq", 0L) } ?: 0L)
    }.getOrDefault(0L)

    fun tail(ctx: Context, limit: Int = 8): String = events(ctx, limit)
        .joinToString("\n") { fmt.format(Date(it.atMs)) + " [" + it.category + "] " + it.detail }
}
