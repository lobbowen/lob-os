package lobos.log

/**
 * 日志级别。
 *
 * 判据来源：`Journal.note()` 早就带 `ok: Boolean?` 三态，但只把它拼进文本
 * `"（ok）"` / `"（failed）"` 就丢掉了 —— 结构化级别不存在，遥测无法按级别筛。
 * 这里把它补回来。
 *
 * 命名说明：与 `os.Level`（程序层级 INFRA/CAPABILITY/CHANNEL/APPLICATION）
 * 无关。两者都在 `lobos` 命名空间下，跨包引用时务必写全限定名，
 * 或在 import 处写清楚是哪一个。
 *
 * 落盘字段名是 `logLevel`，不是 `level`，避免与 `os.Level` 在数据里混淆。
 */
enum class Level(val code: String) {
    INFO("info"),
    WARN("warn"),
    ERROR("error");

    companion object {
        /** `note()` 的三态映射。`null`（未标注成败）按 INFO，不当错误。 */
        fun of(ok: Boolean?): Level = when (ok) {
            false -> ERROR
            true -> INFO
            null -> INFO
        }
    }
}