package lobos.pieces

data class Piece(
    val id: String,

    val libName: String,

    val humanName: String,



    val requiredDeps: List<String>,

    val required: Boolean,

    val note: String = "",

    val buildTier: String = "self-c",

    val version: String = "",

    val installName: String = "",

    val versionArgs: List<String> = emptyList(),

    /**
     * 这个件在系统里担什么角色。
     *
     * 内核靠它回答「给我一个命令解释器」「哪个件提供这些命令」这类问题，
     * 而不是在代码里写死 `shellBin()`、`BUSYBOX_APPLETS` 那种常量表。
     *
     * 取值见 [lobos.os.SystemRoles]。
     */
    val role: String = "",

    /** 这个件对外提供的具名能力，供控制面板与依赖查询（空列表 = 不提供具名能力） */
    val provides: List<String> = emptyList(),
) {
    val installedAs: String get() = installName.ifBlank { libName }


    /** 落位后叫什么 —— 库用 libName，可执行件用 installName（`rg` 而非 `ripgrep`） */
    val landingName: String get() = if (role == SystemRoles.LIBRARY) libName else installedAs
}