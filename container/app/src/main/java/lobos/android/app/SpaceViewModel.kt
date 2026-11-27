package lobos.android.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lobos.android.services.supply.Cleanup
import lobos.android.services.supply.InstallGuard
import lobos.ui.space.FsEntry
import lobos.ui.space.SpaceEvent
import lobos.ui.space.SpaceUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpaceViewModel : ViewModel() {
    private val _state = MutableStateFlow<SpaceUiState>(SpaceUiState.Error("加载中"))
    val state: StateFlow<SpaceUiState> = _state
    private var currentDir: String = ""

    fun load(ctx: Context) {
        if (currentDir.isBlank()) currentDir = ctx.filesDir.absolutePath
        viewModelScope.launch { _state.value = withContext(Dispatchers.IO) { gather(ctx, currentDir) } }
    }

    fun onEvent(ctx: Context, event: SpaceEvent) {
        when (event) {
            is SpaceEvent.OpenDir -> {
                currentDir = File(currentDir, event.name).absolutePath
                load(ctx)
            }
            is SpaceEvent.NavigateUp -> {
                val f = File(currentDir)
                if (f.parentFile != null && f.absolutePath != ctx.filesDir.absolutePath) {
                    currentDir = f.parentFile!!.absolutePath
                }
                load(ctx)
            }
            is SpaceEvent.Refresh -> load(ctx)
            is SpaceEvent.Clean -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { Cleanup.run(ctx) }
                    load(ctx)
                }
            }
        }
    }

    companion object {
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun gather(ctx: Context, path: String): SpaceUiState {
            val root = ctx.filesDir
            val free = InstallGuard.freeBytes(root)
            val used = runCatching { Cleanup.dirSize(root) }.getOrDefault(0L)
            val total = used + free
            val dir = File(path)
            val entries = runCatching {
                (dir.listFiles() ?: emptyArray())
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { f ->
                        FsEntry(
                            name = f.name,
                            isDir = f.isDirectory,
                            size = if (f.isDirectory) "${(f.listFiles()?.size ?: 0)} 项" else InstallGuard.human(f.length()),
                            modified = fmt.format(Date(f.lastModified())),
                            kind = if (f.isDirectory) "文件夹" else extOf(f.name),
                        )
                    }
            }.getOrDefault(emptyList())
            val crumbs = path.removePrefix(root.absolutePath).trim('/').split('/').filter { it.isNotEmpty() }
            return SpaceUiState.Ready(
                used = InstallGuard.human(used),
                free = InstallGuard.human(free),
                total = InstallGuard.human(total),
                usedRatio = if (total > 0) used.toFloat() / total else 0f,
                path = path,
                breadcrumb = crumbs,
                entries = entries,
                entryCount = entries.size,
            )
        }

        private fun extOf(name: String): String {
            val i = name.lastIndexOf('.')
            return if (i > 0 && i < name.length - 1) name.substring(i + 1).uppercase() else "文件"
        }
    }
}
