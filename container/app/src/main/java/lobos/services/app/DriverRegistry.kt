package lobos.services.app

import android.content.Context
import java.io.File
import lobos.services.log.Journal
import lobos.kernel.fs.StateFiles
import lobos.kernel.layout.SystemDirs
import org.json.JSONArray
import org.json.JSONObject

object DriverRegistry {

    const val MARKER = "LOBOS_DEGRADE"

    data class Driver(
        val id: String,
        val assetId: String?,
        val provides: String,
        val substitution: String,
        val degradeMode: String,
    )

    val DRIVERS: List<Driver> = listOf(
        Driver(
            id = "d1.link-interpose",
            assetId = "posix",
            provides = "link() / linkat()",
            substitution = "link → copy：硬链接写成独立副本，共享 inode 语义丢失",
            degradeMode = "ALWAYS",
        ),
        Driver(
            id = "d1.tmp-paths",
            assetId = "posix",
            provides = "/tmp 路径（约 20 个路径类系统调用）",
            substitution = "/tmp/** → \$TMPDIR/**",
            degradeMode = "ALWAYS",
        ),
        Driver(
            id = "d1.open-fallback",
            assetId = "posix",
            provides = "open() / openat()",
            substitution = "EACCES → \$HOME 目录 fd：越权定位被降级为可读目录 fd",
            degradeMode = "ON_ERROR",
        ),
        Driver(
            id = "d1.exec-path",
            assetId = "posix",
            provides = "execve() shebang 解释器",
            substitution = "/usr/bin/x、/bin/x 与 env 解释器 → 在 PATH 中重新解析",
            degradeMode = "ON_ERROR",
        ),
        Driver(
            id = "d2.flock",
            assetId = "flock",
            provides = "flock()",
            substitution = "无 flock 编译件时按文件锁语义模拟（跨进程互斥由 JS 侧兜底）",
            degradeMode = "ON_ERROR",
        ),
        Driver(
            id = "d2.pty-probe",
            assetId = "ptyprobe",
            provides = "PTY 探测工具",
            substitution = "不做替换：自带自证工具（打印 name:OK / name:FAIL）",
            degradeMode = "NONE",
        ),
    )

    data class Entry(
        val driver: String,
        val mode: String,
        val count: Int,
        val firstAt: Long,
        val lastAt: Long,
        val sample: String,
    )

    private fun ledgerFile(ctx: Context): File {
        val d = SystemDirs.libvar(ctx)
        d.mkdirs()
        return File(d, "compat-degradations.json")
    }

    fun degradeLog(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.log(ctx), "compat-degrade.log")

    fun degradations(ctx: Context): List<Entry> {
        val root = StateFiles.readJson(ledgerFile(ctx)) ?: return emptyList()
        val arr = root.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                driver = o.optString("driver", ""),
                mode = o.optString("mode", ""),
                count = o.optInt("count", 0),
                firstAt = o.optLong("firstAt", 0L),
                lastAt = o.optLong("lastAt", 0L),
                sample = o.optString("sample", ""),
            )
        }
    }

    private fun persist(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("driver", e.driver)
                put("mode", e.mode)
                put("count", e.count)
                put("firstAt", e.firstAt)
                put("lastAt", e.lastAt)
                put("sample", e.sample)
            })
        }
        StateFiles.writeJson(ledgerFile(ctx), JSONObject().apply { put("entries", arr) })
    }

    @Synchronized
    fun ingest(ctx: Context): Int {
        val log = degradeLog(ctx)
        val text = runCatching { if (log.isFile) log.readText() else "" }.getOrDefault("")
        if (text.isBlank()) return 0
        val hits = account(ctx, text)
        runCatching { StateFiles.writeAtomic(log, "") }
        return hits
    }

    @Synchronized
    private fun account(ctx: Context, text: String): Int {
        val cur = degradations(ctx).toMutableList()
        val now = System.currentTimeMillis()
        var hits = 0
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (!line.contains(MARKER)) continue
            val body = line.substringAfter(MARKER).trim()
            if (body.isEmpty()) continue
            val parts = body.split(" ")
            val driver = parts.getOrNull(0) ?: continue
            val mode = parts.getOrNull(1) ?: ""
            val detail = parts.drop(2).joinToString(" ").trim()
            hits += 1
            val idx = cur.indexOfFirst { it.driver == driver && it.mode == mode }
            if (idx >= 0) {
                val e = cur[idx]
                cur[idx] = e.copy(count = e.count + 1, lastAt = now, sample = detail.ifBlank { e.sample })
            } else {
                cur.add(Entry(driver, mode, 1, now, now, detail))
                val declared = DRIVERS.firstOrNull { it.id == driver }
                lobos.services.log.Journal.note(
                    ctx, "compat", false,
                    "兼容层替换首次发生（shim 自行声明，非探测）",
                    "driver=" + driver + " mode=" + mode +
                        " 声明=" + (declared?.substitution ?: "未登记（要补注册表）") + " 样本=" + detail,
                )
            }
        }
        if (hits > 0) persist(ctx, cur)
        return hits
    }

    fun report(ctx: Context): JSONObject {
        val drivers = JSONArray()
        var missing = 0
        for (d in DRIVERS) {
            // 装没装 —— 查注册表有没有这一条（dpkg -s 的判据）。
            // 不问「文件在不在」：那是 verify() 的事；混问会把「登记在册但文件被删」
            // 误报成「没装」，而 dpkg -s 在那种情况下照样说 installed。
            val present = d.assetId == null ||
                // isPiece 是注册表里「这是不是件」的唯一判据（PieceEntry 已删）
                lobos.services.reg.ProgramIndex.isPiece(ctx, d.assetId)
            if (!present) missing += 1
            drivers.put(JSONObject().apply {
                put("id", d.id)
                put("assetId", d.assetId ?: JSONObject.NULL)
                put("asset", entryOf(ctx, d.assetId))
                put("present", present)
                put("provides", d.provides)
                put("substitution", d.substitution)
                put("degradeMode", d.degradeMode)
            })
        }
        val degs = degradations(ctx)
        val degArr = JSONArray()
        for (e in degs) {
            degArr.put(JSONObject().apply {
                put("driver", e.driver)
                put("mode", e.mode)
                put("count", e.count)
                put("lastAt", e.lastAt)
                put("sample", e.sample)
            })
        }
        return JSONObject().apply {
            put("drivers", drivers)
            put("driverCount", DRIVERS.size)
            put("missingDrivers", missing)
            put("degradations", degArr)
            put("degradationKinds", degs.size)
            put("marker", MARKER)
            put("channel", degradeLog(ctx).absolutePath)
        }
    }

    fun summary(ctx: Context): String {
        val degs = degradations(ctx)
        return DRIVERS.size.toString() + " 个驱动；已观测降级 " + degs.size + " 类" +
            (if (degs.isEmpty()) "" else "：" + degs.joinToString("、") { it.driver + "×" + it.count })
    }
    /**
     * 这个件落位后的入口文件名 —— 问注册表，不预置任何名字。
     *
     * 驱动表里存的是 id（`flock` / `ptyprobe`），不是文件名；
     * 文件名由落位形状决定（有 bin/ 是命令 · 只有 .so 是库）。
     */
    private fun entryOf(ctx: Context, id: String?): String {
        if (id.isNullOrBlank()) return ""
        val e = lobos.services.reg.ProgramIndex.get(ctx, id) ?: return ""
        val n = e.assetEntry.substringAfterLast("/")
        return if (e.assetEntry.startsWith("bin/")) n else ""
    }
}
