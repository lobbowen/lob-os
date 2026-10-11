package lobos.kernel.mm

import android.content.Context
import lobos.kernel.layout.SystemDirs

object LibPath {

    fun searchPath(ctx: Context): String = SystemDirs.lib(ctx).absolutePath
}
