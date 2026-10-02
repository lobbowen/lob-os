package lobos.ui

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProbeJournal {

    private const val FILE = "probe-journal.txt"
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var browsePairingStartedAt: Long = 0L
    @Volatile var pairingRecordFirstSeenAt: Long = 0L
    @Volatile var browseConnectStartedAt: Long = 0L
    @Volatile var connectRecordFirstSeenAt: Long = 0L
    @Volatile var codeReceivedAt: Long = 0L
    @Volatile var deepLinkEmittedAt: Long = 0L

    fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    @Synchronized
    fun append(ctx: Context, tag: String, message: String) {
        val line = "${tsFmt.format(Date())} [$tag] $message\n"
        runCatching { lobos.os.StateFiles.appendBounded(file(ctx), line) }
    }

    @Synchronized
    fun clear(ctx: Context) {
        runCatching { file(ctx).writeText("") }
        browsePairingStartedAt = 0L; pairingRecordFirstSeenAt = 0L
        browseConnectStartedAt = 0L; connectRecordFirstSeenAt = 0L
        codeReceivedAt = 0L; deepLinkEmittedAt = 0L
    }

    fun verdicts(): String {
        fun ms(x: Long) = if (x > 0) x.toString() else "—"
        val v1 = "① 下拉通知栏时配对对话框存活 —— 需人工对照：输码时刻 ${ms(codeReceivedAt)} 前后对话框是否仍在"
        val v2 = "② mDNS 可见性：pairing 记录=${if (pairingRecordFirstSeenAt > 0) "见过" else "未见过"}; connect 记录=${if (connectRecordFirstSeenAt > 0) "见过" else "未见过"}"
        val v3 = "③ 无线调试深链：intent 已发于 ${ms(deepLinkEmittedAt)} —— 需人工确认落在哪一页"
        val v4 = if (browsePairingStartedAt in 1L until pairingRecordFirstSeenAt)
            "④ browse→首记录延迟 = ${pairingRecordFirstSeenAt - browsePairingStartedAt} ms"
        else "④ browse→首记录延迟 —— 未采到（先开 browse 再开对话框）"
        return "$v1\n$v2\n$v3\n$v4"
    }

    fun readAll(ctx: Context): String = runCatching { file(ctx).readText() }.getOrDefault("")
}
