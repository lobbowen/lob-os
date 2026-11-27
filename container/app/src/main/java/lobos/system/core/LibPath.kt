package lobos.system.core

import lobos.system.fs.SystemDirs

object LibPath {

    fun searchPath(): String = SystemDirs.lib().absolutePath
}
