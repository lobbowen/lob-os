package lobos.kernel.layout

import android.content.Context
import java.io.File

object Landed {

    fun entryAt(root: File, entryRel: String): File? {
        if (root.path.isBlank() || entryRel.isBlank()) return null
        val f = File(root, entryRel)
        return f.takeIf { it.isFile }
    }

    fun entryOf(ctx: Context, stateDir: String, entryRel: String): File? =
        entryAt(File(stateDir), entryRel)
}
