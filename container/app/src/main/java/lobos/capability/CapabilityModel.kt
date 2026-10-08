package lobos.capability

enum class CapStatus {
    GRANTED,
    ACTION,
    BLOCKED,
    FAILED,
}

enum class AcquireKind {
    AUTO,
    USER_TAP,
    RUNTIME_DIALOG,
    USER_CODE,
    SILENT_VIA_ADB,
}

data class Acquisition(
    val kind: AcquireKind,
    val label: String,
    val target: String? = null,
)

data class CapVerdict(val status: CapStatus, val detail: String = "")

data class Capability(
    val id: String,
    val title: String,
    val optional: Boolean = false,
    val requires: Set<String> = emptySet(),
    val judge: (Evidence) -> CapVerdict,
    val acquirer: (Evidence) -> List<Acquisition> = { emptyList() },
    val bridgeToken: String? = null,
)
