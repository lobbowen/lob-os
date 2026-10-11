package lobos.services.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 诊断的外部镜像 —— **为真机验收而存在**。
 *
 * ## 为什么需要它
 *
 * 诊断原本只写 `files/var/log/`，那是应用私有目录。后果是：
 *   · adb shell 读不到（Android 11+ 关闭 shell 访问应用私有目录）
 *   · release 版 `run-as` 不可用（不可 debuggable）
 *   · `logcat --uid=<uid>` 在新系统上无输出
 *
 * 也就是说：**装到真机上之后，程序发生了什么我们完全看不到**。
 * 验收只能靠猜 —— 「13 个基础件铺没铺开」「哪个环节报了什么」全都验不了。
 *
 * ## 为什么镜像到 `/sdcard`
 *
 * `/sdcard/Android/data/<包名>/` 是应用**自己可写、shell 可读**的那一块
 * （Android 11+ 每个应用有自己那份外部目录，别的应用进不去，但 shell 能遍历）。
 * 这是唯一既能写又能被外部读到的位置，不需要任何额外权限。
 *
 * ## 边界
 *
 * 这**不是**产品功能，是验收通道。它只镜像「已经发生的诊断」，不新增判断、
 * 不改变行为；关掉它程序照常运行。
 *
 * 是否镜像由 `Mirror.enabled` 决定 —— 正式版可以关掉，只在验收时开。
 */
object Mirror {

    private const val TAG = "LobosMirror"
    private const val DIR = "lob-os-diag"

    /**
     * 是否镜像。
     *
     * 默认真 —— 真机验收期间我们看不到别的东西。
     * 需要关掉时（正式对外发布）把这个默认值改成 false。
     */
    const val ENABLED = true

    /** 应用自己的外部目录 —— 不申请任何权限也能写。 */
    fun dir(ctx: Context): File? = runCatching {
        ctx.getExternalFilesDir(null)?.resolve(DIR)?.apply { mkdirs() }
    }.getOrNull()

    /**
     * 把一段诊断追加到外部镜像。
     *
     * 任何失败都静默 —— 镜像失败绝不能影响程序本身。
     */
    fun append(ctx: Context, name: String, line: String) {
        if (!ENABLED) return
        runCatching {
            val d = dir(ctx) ?: return
            val f = File(d, name)
            val body = (line.trimEnd() + "\n").toByteArray()
            // ★ 必须用 FileOutputStream(f, true)。File.outputStream() 默认是
            //   **truncate** —— 每写一行就把前面写的全清掉。实测后果：
            //   state.txt 里只剩最后一行（binEntries=），前面几个字段全丢，
            //   看起来像「快照没写全」，其实是每次都覆盖。
            FileOutputStream(f, true).use { it.write(body) }
            // 限长，避免无限增长
            if (f.length() > 512 * 1024L) {
                val keep = f.readBytes().let { it.copyOfRange(it.size - 256 * 1024, it.size) }
                f.writeBytes(keep)
            }
        }.onFailure { Log.w(TAG, "镜像失败: ${it.message}") }
    }

    /**
     * 状态快照 —— 验收时最常问的「现在什么情况」。
     *
     * 写成一行行 `key=value`，方便 shell 侧 grep。
     */
    fun snapshot(ctx: Context, pairs: List<Pair<String, String>>) {
        if (!ENABLED) return
        val stamp = System.currentTimeMillis()
        append(ctx, "state.txt", "# 快照 $stamp")
        pairs.forEach { (k, v) -> append(ctx, "state.txt", "$k=$v") }
    }
}
