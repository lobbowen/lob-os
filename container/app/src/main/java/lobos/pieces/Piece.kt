package lobos.pieces

import lobos.os.SystemRoles

/**
 * 一件 —— 系统文件（随 APK 走）。
 *
 * 这里只留**推导不出来**的东西：
 *  id / version      落位目录名就是（PieceScan 扫出来）
 *  libName           .so 文件名（liblobosrg.so —— 不是 lib<id>.so 的规律）
 *  entry / role      落位形状决定（有 bin/ 是命令 · 只有 .so 是库）
 *  requiredDeps      ElfFacts 读 DT_NEEDED
 *  sha256            不记，文件就在那
 *
 * 删掉的字段与原因：
 *  humanName   纯文字，Linux 无对应物（.deb 里也没有「这是什么东西」的中文名）
 *  note        纯文字。「缺件会怎样」是依赖与后果的话，该由构建期校验说
 *  buildTier   构建期的事（upstream/self-c），零引用
 */
data class Piece(
    /** .so 文件名 —— 它是这一件在落位时的真名 */
    val libName: String,

    /** 是不是系统必需的：缺了系统起不来 */
    val required: Boolean,

    /** 版本 —— 构建期定；空表示用文件内容指纹兜底（见 runtime/Fingerprint） */
    val version: String = "",

    /** 落位后的入口文件名；空表示与 libName 同名 */
    val installName: String = "",

    /** 跑什么参数能问出这件的版本（它是可执行件时才用得上） */
    val versionArgs: List<String> = emptyList(),

    /**
     * 这个件在系统里担什么角色 —— multi-command 推不出来（一个二进制多个命令）。
     * 其余形态（有 bin/ 是命令 · 只有 .so 是库）由落位形状决定。
     * 取值见 [lobos.os.SystemRoles]。
     */
    val role: String = "",

    /** 这个件对外提供的具名能力，供控制面板与依赖查询（空列表 = 不提供） */
    val provides: List<String> = emptyList(),
) {
    val id: String get() = installName.ifBlank { libName.removePrefix("lib") }
    val installedAs: String get() = installName.ifBlank { libName }

    /** 落位后叫什么 —— 库用 libName，可执行件用 installName（`rg` 而非 `ripgrep`） */
    val landingName: String get() = if (role == SystemRoles.LIBRARY) libName else installedAs
}
