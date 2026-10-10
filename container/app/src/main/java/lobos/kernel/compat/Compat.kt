package lobos.kernel.compat

import android.content.Context
import java.io.File

/**
 * 兼容性垫片的定位。
 *
 * ## 这些是什么
 *
 * `container/native/` 下我们自己写的 C：`LD_PRELOAD` 垫片、PTY 宿主、文件锁。
 * 它们**随源码走、随 APK 走、只有一个版本、不会 OTA 替换**。
 *
 * ## 为什么不是件
 *
 * |            | 件（如 jq）                    | 垫片（本目录）              |
 * |------------|-------------------------------|----------------------------|
 * | 谁用       | 用户/程序**主动调用**           | 系统**自动注入**每个进程     |
 * | 进 PATH    | 是                            | 否                         |
 * | 版本       | 上游真实版本                  | 我们自己的代码，没有「上游」 |
 * | 能 OTA 换  | 应该能                        | **不该**（换 = 换系统行为）  |
 * | 可版本共存 | 该                            | 不需要                     |
 *
 * 它们是**内核提供机制的一部分** —— 让 Android 上跑出 Linux 语义，
 * 跟 ELF 解析、PTY 帧协议是同一类东西。放在 `usr/lib/<id>/<版本>/` 那套
 * 形状里是错的：那套形状是为「多版本共存 + CURRENT 指针 + OTA 回滚」设计的，
 * 垫片一套都不需要，而且正是「把它当件处理」导致每次发 APK 都要重编。
 *
 * ## 落在哪
 *
 * 编译进 APK 的 `lib/arm64-v8a/`，由 [lib] 定位。
 * **不进 `$PREFIX/usr/lib/<id>/<版本>/`、不进 PATH、不进件清单。**
 */
object Compat {

    /** 垫片的库文件名。编译脚本与本文件必须一致。 */
    const val LIB_POSIX = "liblobosposix.so"
    const val LIB_PTYPROBE = "liblobosptyprobe.so"
    const val LIB_PTYSESSION = "librivospty.so"
    const val LIB_FLOCK = "liblobosflock.so"

    /**
     * `LD_PRELOAD` 的值：按注入顺序排好的链。
     *
     * 顺序有讲究：posix 垫在最前（路径与链接语义的兜底），ptyprobe 在后
     * （PTY 探测）。谁在前谁先看到符号替换。
     */
    val PRELOAD_ORDER: List<String> = listOf(LIB_POSIX, LIB_PTYPROBE)

    /** APK 原生库目录（只读，随 APK 而来）。 */
    fun libDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    /**
     * 按名字取一个垫片库。
     *
     * @return 库文件；APK 里没有则 null —— 调用方要能降级，不能假设它存在。
     */
    fun lib(ctx: Context, name: String): File? =
        libDir(ctx).resolve(name).takeIf { it.isFile }

    /**
     * 拼出 `LD_PRELOAD` 的值：只取**实际存在**的那些。
     *
     * 缺一个就少注一个，不能写一个不存在的路径 ——
     * `LD_PRELOAD` 里指向缺失文件的项会被加载器忽略，
     * 但会把后续项的解析时机搅乱（不同 bionic 版本行为不一致）。
     *
     * @return 形如 `a.so:b.so`；一个都没有则返回空串（不设这个变量）。
     */
    fun preload(ctx: Context): String =
        PRELOAD_ORDER.mapNotNull { name -> lib(ctx, name)?.name }.joinToString(":")

    /** PTY 宿主库 —— 它不是 preload，是被当作可执行程序驱动的。 */
    fun ptyHost(ctx: Context): File? = lib(ctx, LIB_PTYSESSION)

    /** 文件锁库。 */
    fun flockLib(ctx: Context): File? = lib(ctx, LIB_FLOCK)

    /** 全部垫片是否就位 —— 供真机验收查。 */
    fun allPresent(ctx: Context): Map<String, Boolean> =
        listOf(LIB_POSIX, LIB_PTYPROBE, LIB_PTYSESSION, LIB_FLOCK)
            .associateWith { lib(ctx, it) != null }
}
