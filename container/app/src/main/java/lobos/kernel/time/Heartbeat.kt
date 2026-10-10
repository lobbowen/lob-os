package lobos.kernel.time

/**
 * 心跳新鲜度判定。
 *
 * 「宿主还活着吗」这件事在内核只有一处算法：读 ResidencyStatus 的
 * updatedAt，看它距今是否不足 TTL。原先它被写了两遍 ——
 * OsHostService 与 ProgramStatus 各算一次，改一处忘另一处就会出现
 * 「一处说在线一处说离线」。
 *
 * 放内核是因为它是纯机制：读时间戳、比阈值，不认识任何 unit。
 */
object Heartbeat {

    /** 心跳有效期：60 秒。与节拍同量级（15 秒一拍，容忍连续丢 3 拍）。 */
    const val TTL_MS = 60_000L

    /**
     * 这份快照是不是「刚刚才更新过」。
     *
     * @param updatedAt 快照里的 updatedAt（毫秒墙钟）；0 或缺表示从未记录
     * @param nowMs     当前墙钟
     */
    fun fresh(updatedAt: Long, nowMs: Long): Boolean =
        updatedAt > 0L && nowMs - updatedAt in 0 until TTL_MS
}
