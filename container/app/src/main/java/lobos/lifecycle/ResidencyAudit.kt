package lobos.lifecycle

import android.content.Context
import lobos.log.KillAudit
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object ResidencyAudit {

    private const val FILE = "residency.txt"
    private const val BOOT_BASIS_TOLERANCE_MS = 60_000L
    private const val INTERRUPTION_PREFIX = "常驻被打断"

    private data class Debt(val lastAliveMs: Long, val gapMs: Long, val deviceReboot: Boolean)

    @Volatile
    private var debt: Debt? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    private fun bootBasisMs(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    @Synchronized
    fun auditPreviousExit(ctx: Context) {
        if (debt != null) return
        val lines = try {
            file(ctx).readText().trim().split("\n")
        } catch (_: Throwable) {
            return
        }
        if (lines.size < 2) return
        val lastAliveMs = lines[0].toLongOrNull() ?: return
        val priorBasisMs = lines[1].toLongOrNull() ?: return
        debt = Debt(
            lastAliveMs = lastAliveMs,
            gapMs = System.currentTimeMillis() - lastAliveMs,
            deviceReboot = abs(priorBasisMs - bootBasisMs()) > BOOT_BASIS_TOLERANCE_MS,
        )
        // 存活时间本身是状态（继续写 residency.txt），但「检测到一次中断」是事件 ——
        // 之前只在内存里，进程一死就没了，而这是常驻失败的第一手证据。
        runCatching {
            val d = debt
            lobos.log.Journal.note(
                ctx, "residency", null, "检测到常驻中断",
                "上次存活时间=$lastAliveMs 中断时长=${d?.gapMs}ms 是否设备重启=${d?.deviceReboot}",
            )
        }
    }

    @Synchronized
    fun heartbeat(ctx: Context) {
        val text = "${System.currentTimeMillis()}\n${bootBasisMs()}"
        runCatching { lobos.os.StateFiles.writeAtomic(file(ctx), text) }
    }

    @Synchronized
    fun interruption(ctx: Context): String? {
        val d = debt ?: return null
        return interruptionText(
            lastAliveAt = timeFmt.format(Date(d.lastAliveMs)),
            gapText = humanGap(d.gapMs),
            deviceReboot = d.deviceReboot,
            attribution = KillAudit.attribution(d.lastAliveMs),
        )
    }

    fun interruptionText(lastAliveAt: String, gapText: String, deviceReboot: Boolean, attribution: String): String =
        if (deviceReboot) {
            "上次常驻结束于设备重启（$lastAliveAt），不是 App 被回收"
        } else {
            "$INTERRUPTION_PREFIX：上次存活到 $lastAliveAt，中断 $gapText；$attribution；本设计不提供死后恢复"
        }

    private fun humanGap(gapMs: Long): String = when {
        gapMs < 0 -> "时长不明（期间改过系统时间）"
        gapMs < 60_000L -> "${gapMs / 1000} 秒"
        else -> "${gapMs / 60_000L} 分 ${(gapMs % 60_000L) / 1000} 秒"
    }
}
