package lobos.services.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 私有目录的只读出口。
 *
 * ## 为什么需要它
 *
 * 我们是一个**跑在 Android 上的虚拟系统**。用户和别的应用要取系统里的数据
 * （`usr/lib` 的铺位、诊断日志、快应用），如果只有一个封闭的私有目录，
 * 那就是「数据在但取不到」—— 用户得先 root 再 hack，那不叫产品。
 *
 * Android 给的正规机制就是 ContentProvider：`content://lobos.os.files/…`
 * 任何应用都能读。我们用它，不用绕开 Android 另发明一套。
 *
 * ## 为什么是「只读」
 *
 * `files/` 下不只是用户数据，还有系统自己的状态：件落位、软链、
 * resident 台账、诊断。**写权限放出去等于让任意应用改系统状态** ——
 * 那不是开放，是失控。所以 `openFile` 只给读模式，写一律拒。
 *
 * ## 为什么不开 debuggable
 *
 * `android:debuggable=true` 会让设备上**任何带 shell 的东西**都能
 * `run-as lobos.os` 读整个私有目录 —— 包括用户装的恶意软件。
 * 那是对全设备敞开，不是对「用户想取自己的数据」敞开。
 * Provider 精准得多：开哪个路径、是否可写，由我们定。
 *
 * ## 用法
 *
 * ```
 * content query --uri content://lobos.os.files/tree/usr/lib          列目录
 * content query --uri content://lobos.os.files/tree/var/log           列日志目录
 * content read  --uri content://lobos.os.files/file/var/log/diagnostics.txt
 * adb shell run-as …  不需要 —— 任何应用都可以
 * ```
 *
 * `tree/<相对路径>` → 列目录；`file/<相对路径>` → 读文件。
 * 相对路径是相对于 `files/`，即用户看到的 `/data/user/0/lobos.os/files/`。
 */
class SystemFilesProvider : ContentProvider() {

    companion object {
        const val MIME_GUESS = "application/octet-stream"
        const val AUTHORITY = "lobos.os.files"

        /** 防目录穿越：`..` 与绝对路径一律拒。 */
        private fun safe(root: File, rel: String): File? {
            if (rel.isBlank()) return null
            if (rel.startsWith("/")) return null
            if (rel.split('/').any { it == ".." || it == "." }) return null
            val f = File(root, rel).canonicalFile
            // canonical 之后必须仍在 root 之内
            return if (f.path == root.path || f.path.startsWith(root.path + "/")) f else null
        }
    }

    private fun filesRoot(): File? = context?.filesDir

    override fun onCreate(): Boolean = context != null

    // ── list：列目录 ────────────────────────────────────────────────
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val root = filesRoot() ?: return null
        val (kind, rel) = split(uri) ?: return null

        if (kind == "file") {
            // 单个文件：查它存不存在、大小多少
            val f = safe(root, rel) ?: return null
            val cols = arrayOf("_name", "length", "_type")
            return MatrixCursor(cols).apply {
                addRow(arrayOf<Any?>(f.name, f.length(), MIME_GUESS))
            }
        }

        val dir = safe(root, rel) ?: return null
        val cols = projection ?: arrayOf("_name", "length", "_type", "modified")
        val cur = MatrixCursor(cols)
        if (dir.isDirectory) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                cur.addRow(
                    arrayOf(
                        f.name,
                        f.length(),
                        if (f.isDirectory) "vnd.android.document/directory" else MIME_GUESS,
                        f.lastModified(),
                    )
                )
            }
        }
        return cur
    }

    private fun split(uri: Uri): Pair<String, String>? {
        val path = uri.path ?: return null
        val seg = path.trim('/').split('/', limit = 2)
        if (seg.size != 2 || (seg[0] != "tree" && seg[0] != "file")) return null
        return seg[0] to Uri.decode(seg[1])
    }

    // ── read：读文件（只读）────────────────────────────────────────
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val root = filesRoot() ?: return null
        val (kind, rel) = split(uri) ?: return null
        if (kind != "file") return null
        // 只给读。写一律拒 —— 理由见类注释。
        if (!mode.startsWith("r")) {
            throw SecurityException("SystemFilesProvider 只读，写入被拒绝（mode=$mode）")
        }
        val f = safe(root, rel) ?: return null
        if (!f.isFile) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }


    override fun getType(uri: Uri): String? {
        val (kind, _) = split(uri) ?: return null
        if (kind == "tree") return "vnd.android.document/directory"
        val root = filesRoot() ?: return null
        val (_, rel) = split(uri) ?: return null
        val f = safe(root, rel) ?: return null
        return if (f.isDirectory) "vnd.android.document/directory" else MIME_GUESS
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw SecurityException("SystemFilesProvider 只读")

    override fun update(
        uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?,
    ): Int = throw SecurityException("SystemFilesProvider 只读")

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int =
        throw SecurityException("SystemFilesProvider 只读")

}

