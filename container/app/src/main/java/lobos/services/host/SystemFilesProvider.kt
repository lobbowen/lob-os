package lobos.services.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File

class SystemFilesProvider : ContentProvider() {

    companion object {
        const val MIME_GUESS = "application/octet-stream"
        const val AUTHORITY = "lobos.os.files"

        private fun safe(root: File, rel: String): File? {
            if (rel.isBlank()) return null
            if (rel.startsWith("/")) return null
            if (rel.split('/').any { it == ".." || it == "." }) return null
            val f = File(root, rel).canonicalFile
            return if (f.path == root.path || f.path.startsWith(root.path + "/")) f else null
        }
    }

    private fun filesRoot(): File? = context?.filesDir

    override fun onCreate(): Boolean = context != null

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

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val root = filesRoot() ?: return null
        val (kind, rel) = split(uri) ?: return null
        if (kind != "file") return null
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
