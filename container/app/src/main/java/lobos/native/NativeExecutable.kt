package lobos.native

data class NativeExecutable(
    val id: String,

    val libName: String,

    val humanName: String,

    val probeArgs: List<String>,

    val probeExpect: String?,

    val requiredDeps: List<String>,

    val required: Boolean,

    val note: String = "",

    val buildTier: String = "self-c",
)
