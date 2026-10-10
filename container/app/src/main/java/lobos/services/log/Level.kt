package lobos.services.log


enum class Level(val code: String) {
    INFO("info"),
    WARN("warn"),
    ERROR("error");

    companion object {
        fun of(ok: Boolean?): Level = when (ok) {
            false -> ERROR
            true -> INFO
            null -> INFO
        }
    }
}