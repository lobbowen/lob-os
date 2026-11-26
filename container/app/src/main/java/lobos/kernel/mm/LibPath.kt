package lobos.kernel.mm

import lobos.kernel.layout.SystemDirs

object LibPath {

    fun searchPath(): String = SystemDirs.lib().absolutePath
}
