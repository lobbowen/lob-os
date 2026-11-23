package lobos.services.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lobos.services.reg.ProgramIndex
import lobos.services.reg.UnitEntry
import lobos.services.supply.CatalogClient
import lobos.services.supply.InstallProgress
import lobos.services.supply.InstallGuard
import lobos.services.supply.PackageInstaller
import lobos.services.supply.ProgramDir
import lobos.ui.components.ComponentItem
import lobos.ui.components.ComponentDetail
import lobos.ui.components.ComponentsEvent
import lobos.ui.components.ComponentsUiState
import lobos.ui.components.VersionItem
import org.json.JSONArray

class ComponentsViewModel : ViewModel() {
    private val _state = MutableStateFlow<ComponentsUiState>(ComponentsUiState.Loading)
    val state: StateFlow<ComponentsUiState> = _state
    private var filter = 0
    private var detailId: String? = null
    private var notice: String? = null
    private var installing = false

    fun load(ctx: Context) {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                runCatching { CatalogClient.refresh(ctx, false) }
                gather(ctx, filter, detailId, notice)
            }
        }
    }

    fun onEvent(ctx: Context, event: ComponentsEvent) {
        when (event) {
            is ComponentsEvent.Filter -> { filter = event.idx; load(ctx) }
            is ComponentsEvent.OpenDetail -> { detailId = event.id; load(ctx) }
            ComponentsEvent.Back -> { detailId = null; load(ctx) }
            is ComponentsEvent.SwitchVersion -> {
                val id = detailId ?: return
                viewModelScope.launch {
                    notice = "正在切换到 ${event.version}…"
                    load(ctx)
                    val r = withContext(Dispatchers.IO) {
                        runCatching { PackageInstaller.activate(ctx, id, event.version) }.getOrNull()
                    }
                    notice = if (r?.optBoolean("ok") == true) "已切换到 ${event.version}"
                             else "切换失败：${r?.optString("error", "未知原因") ?: "未知原因"}"
                    load(ctx)
                }
            }
            is ComponentsEvent.Install -> install(ctx, event.id, event.version)
            is ComponentsEvent.UninstallVersion -> viewModelScope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { PackageInstaller.uninstallVersion(ctx, event.id, event.version) }.getOrNull()
                }
                notice = if (r?.optBoolean("ok") == true) "已卸载版本 ${event.id} ${event.version}"
                         else "卸载失败：${r?.optString("error", "未知原因") ?: "未知原因"}"
                load(ctx)
            }
            is ComponentsEvent.Uninstall -> viewModelScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { PackageInstaller.uninstall(ctx, event.id) }.getOrDefault(false) }
                notice = if (r) "已卸载组件 ${event.id}" else "卸载失败：${event.id}"
                if (r) detailId = null
                load(ctx)
            }
        }
    }

    private fun install(ctx: Context, id: String, version: String?) {
        if (installing) { notice = "有安装任务正在进行，请稍候"; load(ctx); return }
        installing = true
        viewModelScope.launch {
            notice = "正在准备安装 $id…"
            load(ctx)
            val progressJob = launch {
                repeat(200) {
                    val p = InstallProgress.current()
                    if (p.id == id && p.stage != InstallProgress.Stage.IDLE && p.stage != InstallProgress.Stage.DONE && p.stage != InstallProgress.Stage.FAILED) {
                        notice = "正在安装 $id（${InstallProgress.label(p.stage)}）"
                        load(ctx)
                    }
                    delay(200)
                }
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { PackageInstaller.install(ctx, id, version, activate = false) }.getOrNull()
            }
            progressJob.cancel()
            installing = false
            val prog = InstallProgress.current()
            notice = when {
                result?.optBoolean("ok") == true -> "已安装 $id ${result.optString("version", "")}".trim()
                prog.stage == InstallProgress.Stage.FAILED -> "安装失败：${prog.detail.ifBlank { result?.optString("error", "未知原因") ?: "未知原因" }}"
                else -> "安装失败：${result?.optString("error", "未知原因") ?: "未知原因"}"
            }
            load(ctx)
        }
    }

    companion object {
        private val RUNTIME_IDS = setOf("python", "node", "go")
        private val LIB_IDS = setOf("openssl", "zlib", "libffi", "crypto", "libcxx", "sqlite-lib")
        private val KIND_RUNTIME = "runtime"
        private val KIND_TOOL = "tool"
        private val KIND_LIBRARY = "library"

        fun gather(ctx: Context, filter: Int, detailId: String? = null, note: String? = null): ComponentsUiState {
            val programs = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
            val catalog = runCatching { CatalogClient.entries(ctx) }.getOrDefault(JSONArray())

            fun kindOf(id: String): String = when {
                RUNTIME_IDS.any { id == it || id.startsWith(it + "-") } -> KIND_RUNTIME
                LIB_IDS.any { id == it || id.startsWith(it + "-") } -> KIND_LIBRARY
                else -> KIND_TOOL
            }
            fun kindLabel(kind: String): String = when (kind) {
                KIND_RUNTIME -> "运行时"
                KIND_LIBRARY -> "库"
                else -> "工具"
            }
            fun item(p: UnitEntry): ComponentItem {
                val id = p.id
                val activeVer = runCatching { ProgramDir(ctx, id).currentVersion() }.getOrNull()
                val shownVer = activeVer ?: p.version
                val kind = kindOf(id)
                return ComponentItem(
                    id = id, name = id, detail = "$shownVer · ${kindLabel(kind)}",
                    status = if (activeVer != null) "使用中" else "已装",
                    installed = true, inUse = activeVer != null, kind = kind,
                )
            }

            val all = programs.map { item(it) }.toMutableList()
            val installedIds = programs.map { it.id }.toSet()
            for (i in 0 until catalog.length()) {
                val e = catalog.optJSONObject(i) ?: continue
                val id = e.optString("name").ifBlank { continue }
                if (id in installedIds) continue
                val vers = e.optJSONArray("versions")
                val ver = if (vers != null && vers.length() > 0) vers.optJSONObject(0)?.optString("version", "") ?: ""
                          else e.optString("version", "")
                val size = e.optLong("size", 0L)
                val kind = kindOf(id)
                val detailText = buildString {
                    if (ver.isNotBlank()) append("v").append(ver)
                    append(" · ").append(kindLabel(kind))
                    if (size > 0) append(" · ").append(InstallGuard.human(size))
                }
                all.add(ComponentItem(id, id, detailText, "未装", false, kind = kind))
            }

            val runtime = all.filter { it.kind == KIND_RUNTIME }.toMutableList()
            val tools = all.filter { it.kind == KIND_TOOL }.toMutableList()
            val libraries = all.filter { it.kind == KIND_LIBRARY }.toMutableList()
            val detail = detailId?.let { buildDetail(ctx, it, programs, catalog) }
            return ComponentsUiState.Ready(runtime, tools, libraries, filter, detail, note)
        }

        private fun buildDetail(ctx: Context, id: String, programs: List<UnitEntry>, catalog: JSONArray): ComponentDetail {
            val dir = ProgramDir(ctx, id)
            val current = runCatching { dir.currentVersion() }.getOrNull()
            val installedVersions = runCatching { dir.installedVersions() }.getOrDefault(emptyList()).toSet()

            var catalogVersions = emptyList<String>()
            var deps = emptyList<String>()
            var caps = "命令与运行时环境"
            for (i in 0 until catalog.length()) {
                val e = catalog.optJSONObject(i) ?: continue
                if (e.optString("name") != id) continue
                val vers = e.optJSONArray("versions")
                if (vers != null) {
                    catalogVersions = (0 until vers.length()).mapNotNull { vers.optJSONObject(it)?.optString("version", "")?.ifBlank { null } }
                }
                val arr = e.optJSONArray("requires")
                if (arr != null) deps = (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
                val c = e.optString("capabilities", "")
                if (c.isNotBlank()) caps = c
                break
            }

            val allVersions = (installedVersions + catalogVersions).distinct().sortedWith(compareByDescending<String> { it })
            val versionItems = allVersions.map { v ->
                val installed = v in installedVersions
                val active = v == current
                val detail = when {
                    active -> "当前激活"
                    installed -> "已安装"
                    else -> "可安装"
                }
                VersionItem(v, detail, active, installed)
            }
            val entry = programs.firstOrNull { it.id == id }
            if (entry != null) {
                if (deps.isEmpty()) deps = entry.requires?.toList().orEmpty()
                if (!entry.capabilities.isNullOrEmpty()) caps = entry.capabilities.joinToString(" · ")
            }
            return ComponentDetail(id, id, versionItems, deps, caps, installedVersions.isNotEmpty())
        }
    }
}
