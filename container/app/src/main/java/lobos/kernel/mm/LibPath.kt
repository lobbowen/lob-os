package lobos.kernel.mm

import android.content.Context
import lobos.kernel.layout.SystemDirs

/**
 * 程序的库搜索路径。
 *
 * 这是内核该有的：**它是「动态链接器去哪里找 .so」这个形状问题**，
 * 与「系统里有哪些件」无关 —— 后者是服务层的账。
 *
 * ★ 只指我们自己的面，**不含 nativeLibraryDir**：
 *   实测那个目录不可写（属主 system，我们是应用 uid），所以「把系统建在
 *   APK 目录下」这条路走不通 —— 已堵死，别再走。
 *
 *   nativeLibraryDir 只作**取源**：APK 里的 .so 从那里取出来，落到
 *   `usr/lib/<id>/<版本>/lib/` 之后就用我们自己的面。
 *
 * 为什么放在 mm 而不是 layout：ld.so(8) 的搜索顺序里
 * `DT_RPATH → LD_LIBRARY_PATH → DT_RUNPATH`，其中 LD_LIBRARY_PATH
 * 这一段就是这里给出的。对应内核的 mm（内存管理）。
 */
object LibPath {

    /**
     * 程序的 `LD_LIBRARY_PATH` 取值。
     *
     * 单段 —— 就是 `$PREFIX/usr/lib`。要加段时改这里，别在调用方各自拼。
     */
    fun searchPath(ctx: Context): String = SystemDirs.lib(ctx).absolutePath
}
