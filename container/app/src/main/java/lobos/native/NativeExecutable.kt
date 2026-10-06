package lobos.native

/**
 * 原生件（底座件）的声明。
 *
 * ── version / sha256 为什么加在这里 ──
 * 原先没有这两个字段，于是「底座件有版本、可 OTA 更新、可回滚」三件事都不成立：
 * 装机时只有名字，没有身份可比。现状核实：`PrefixProvisioner.expected()`
 * 只返回名字列表，`NativePreparer.prepare()` 只做校验不更新。
 *
 * `version` 用空串表示「随 APK、不单独更新」——
 * 不是每件底座件都要 OTA 通道（flock/posix 这种自有 C 件随内核走就够）。
 *
 * `sha256` 是 **APK 里那份原件**的哈希，用途只有一个：
 * 判断「当前底座用的是原件还是 OTA 更新过的那份」。
 * 它由 CI 在打包时算进 `.github/native-assets.txt` 的旁注，不在源码里硬写 ——
 * 硬写就必然与实际构建出的字节对不上。
 */
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

    /** 上游版本（如 "5.2.15"、"1.36.1"、"3.6.3"）；空串 = 随 APK、不单独更新。 */
    val version: String = "",

    /**
     * 落位名：底座里叫什么。与 [libName] 不同 —— jniLibs 只打包 `.so`，
     * 而用户在 PATH 里敲的应该是工具名（如 `pty-session` 而不是 `librivospty.so`）。
     * 空串 = 与 [libName] 同名。
     */
    val installName: String = "",
) {
    /** 底座里的真实文件名。 */
    val installedAs: String get() = installName.ifBlank { libName }
}