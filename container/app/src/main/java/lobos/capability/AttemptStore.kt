package lobos.capability

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AttemptStore {

    private const val TIMELINE_KEEP = 6

    @Volatile
    var lastPair: PairAttempt? = null
        private set

    @Volatile
    var pairAttempts: List<PairAttempt> = emptyList()
        private set

    @Volatile
    private var pairCount = 0

    fun recordPair(atMs: Long, ok: Boolean, reason: String = "") {
        val attempt = PairAttempt(atMs, ok, reason)
        lastPair = attempt
        pairCount += 1
        pairAttempts = (pairAttempts + attempt).takeLast(TIMELINE_KEEP)
    }

    fun humanPairTimeline(): List<String> {
        if (pairAttempts.isEmpty()) return emptyList()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val firstIndex = pairCount - pairAttempts.size + 1
        return pairAttempts.mapIndexed { i, a ->
            "第 ${firstIndex + i} 次 ${fmt.format(Date(a.atMs))} " +
                if (a.ok) "成功" else "失败：" + a.reason.ifBlank { "未归因" }.take(60)
        }.reversed()
    }
}
