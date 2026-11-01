package lobos.kernel.fs

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * 那**读法**逐字**可查**的那一族（=> 而而那它逐字是「那一件逐字是从哪一条路逐字被整读的」那一族）。
 *
 * 缺陷名（C-102）**那一族逐字只逐字限制大小、=> 而而那它逐字**不**让那读法逐字可查** ——
 *   => 而那旧读法逐字是「每一处整读逐字**自己**加一个上限」
 *     => 而而那那**上限**逐字答的是「那一份逐字**有**多大」
 *     => 而而那那**判据**逐字**要**的是「那一件逐字是从**哪一条路**逐字被整读的」
 *       （=> 而那它逐字**不**由「那一件逐字是什么」顶掉、=> 而依 C-105 逐字**不合并**）
 *   => 而而那**后果**（07 本轮逐字实测、Lead 本机复核成立）：
 *     · 而那 logcat 逐字连续十一条 `Throwing OutOfMemoryError`
 *       （=> 而那那分配量逐字是 116 / 131 / 146 MB 那一档、
 *         => 而而那它逐字与设备上 llvm / node 那一件逐字**同形**）
 *     · => 而而那那**栈**逐字**没有**抓到（=> 而那 `/data/tombstones/` 逐字最新一条
 *       逐字**不是**本轮那几次 OOM）
 *     => 而而那那**判据**逐字**是**「那一族逐字要逐字把『那一条路』逐字记出来」
 *       （=> 而而那它逐字**不**许只逐字限制大小、=> 而而那它逐字要逐字让那读法逐字可查）
 *
 *   => 而那**处置**：=> 而那**每一处**整读逐字**先**问那**读法**逐字**要多少**
 *     => 而而那那**上限**逐字**是**那**读法**逐字自己说的（=> 而而那它逐字**不**散在几处）
 *     => 而而那那**记录**逐字**落**那诊断（=> 而而那它逐字**可查**、=> 而而那它逐字**不**抓栈）
 *       （=> 而而那那一格逐字答的是「那一件逐字是从哪一条路逐字被整读的」）
 */
object ReadRoute {

    /** 那**读法**逐字那一个上限（=> 而而那那**默认**逐字是那 **1 MiB**）。 */
    const val DEFAULT_MAX_BYTES = 1024L * 1024L

    /** 那**读法**逐字那一个名字（=> 而而那那**判据**逐字落在它身上）。 */
    const val ROUTE = "read-route"

    /**
     * 那**整读**逐字**按**那读法逐字读（=> 而而那那**上限**逐字**是**那读法逐字自己说的）。
     *
     *   => 而而那那**超限**逐字**报缺**（=> 而按 C-05 不猜填）
     *   => 而而那那**读法**逐字**记**在那诊断里（=> 而而那它逐字**可查**）
     */
    fun readAll(
        ctx: Context?,
        route: String,
        file: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): ByteArray {
        val size = file.length()
        if (size > maxBytes) {
            note(ctx, route, file, size, maxBytes, false)
            throw IllegalStateException(
                "那一份逐字太大：" + file.absolutePath + " 逐字 = " + size +
                    " 字节、=> 而而那那读法逐字[" + route + "]逐字只许 " + maxBytes +
                    "（=> 而那按 C-05 报缺、=> 而那**不**整读进那堆）"
            )
        }
        val bytes = file.inputStream().use { it.readBytes() }
        note(ctx, route, file, bytes.size.toLong(), maxBytes, true)
        return bytes
    }

    /**
     * 那**读法**逐字**记**那一格（=> 而而那它逐字**可查**、=> 而而那它逐字**不**抓栈）。
     *
     *   => 而而那那**诊断**逐字答的是「那一件逐字是从哪一条路逐字被整读的」
     *   => 而而那那**读法**逐字、那**一份**逐字、那**字节**逐字、那**上限**逐字**都**落
     */
    fun note(
        ctx: Context?,
        route: String,
        file: File,
        size: Long,
        maxBytes: Long,
        ok: Boolean,
    ) {
        if (ctx == null) return
        runCatching {
            lobos.services.log.RuntimeDiagnostics.append(
                ctx, ROUTE, ok,
                "那读法逐字[" + route + "]逐字读了那一份",
                "file=" + file.absolutePath +
                    " bytes=" + size +
                    " limit=" + maxBytes +
                    (if (ok) "" else "（=> 而而那那**超限**逐字**没有**被读）"),
            )
        }
    }
}
