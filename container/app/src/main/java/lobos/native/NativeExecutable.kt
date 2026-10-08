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

    val version: String = "",

    val installName: String = "",

    val versionArgs: List<String> = emptyList(),
) {
    val installedAs: String get() = installName.ifBlank { libName }

    val reportsVersion: Boolean get() = versionArgs.isNotEmpty()
}