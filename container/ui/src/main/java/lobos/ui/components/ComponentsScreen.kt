package lobos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import lobos.ui.theme.LobosColors
import lobos.ui.theme.LobosTheme

private data class ComponentSection(val title: String, val items: List<ComponentItem>)

@Composable
fun ComponentsScreen(
    state: ComponentsUiState,
    onEvent: (ComponentsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (state) {
            ComponentsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ComponentsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            is ComponentsUiState.Ready -> {
                val detail = state.detail
                if (detail != null) {
                    ComponentDetailScreen(
                        name = detail.name,
                        versions = detail.versions,
                        deps = detail.deps,
                        capabilities = detail.capabilities,
                        installed = detail.installed,
                        notice = state.notice,
                        onEvent = onEvent,
                        onBack = { onEvent(ComponentsEvent.Back) },
                        onUninstallComponent = { onEvent(ComponentsEvent.Uninstall(detail.id)) },
                    )
                } else {
                    ComponentsReady(state, onEvent)
                }
            }
        }
    }
}

@Composable
private fun ComponentsReady(state: ComponentsUiState.Ready, onEvent: (ComponentsEvent) -> Unit) {
    val filter = state.filter.coerceIn(0, 2)
    val sections = listOf(
        ComponentSection("运行时", state.runtime),
        ComponentSection("工具", state.tools),
        ComponentSection("基础库（随 APK）", state.libraries),
    ).mapNotNull { section ->
        val visible = section.items.filter { item ->
            when (filter) {
                0 -> item.installed
                1 -> !item.installed
                else -> true
            }
        }
        if (visible.isEmpty()) null else section.copy(items = visible)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
    ) {
        item(key = "header") {
            Text("组件", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "运行库 · 运行时 · 工具 · 功能组件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
        state.notice?.takeIf { it.isNotBlank() }?.let { notice ->
            item(key = "notice") {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notice.startsWith("安装失败")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
        item(key = "filter") {
            SegmentedFilter(selected = filter, onEvent = onEvent)
            Spacer(Modifier.height(24.dp))
        }
        sections.forEach { section ->
            item(key = section.title) {
                SectionLabel(section.title)
                Spacer(Modifier.height(9.dp))
                ComponentGroup(section.items, onEvent)
                Spacer(Modifier.height(22.dp))
            }
        }
        if (sections.isEmpty()) {
            item(key = "empty") {
                Text(
                    "当前筛选下没有组件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SegmentedFilter(selected: Int, onEvent: (ComponentsEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val labels = listOf("已安装", "可安装", "全部")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).clip(shape).background(cs.surfaceVariant).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val tabShape = RoundedCornerShape(11.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(46.dp)
                    .then(if (active) Modifier.shadow(3.dp, tabShape) else Modifier)
                    .clip(tabShape)
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onEvent(ComponentsEvent.Filter(index)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) cs.onSurface else cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ComponentGroup(items: List<ComponentItem>, onEvent: (ComponentsEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(cs.outlineVariant))
            }
            ComponentRow(
                item = item,
                onClick = {
                    if (item.installed) onEvent(ComponentsEvent.OpenDetail(item.id))
                    else onEvent(ComponentsEvent.Install(item.id))
                },
            )
        }
    }
}

@Composable
private fun ComponentRow(item: ComponentItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconColor = componentAccent(item)
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(iconColor.second),
            contentAlignment = Alignment.Center,
        ) {
            Icon(componentIcon(item), contentDescription = null, tint = iconColor.first, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.size(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(10.dp))
        StatusPill(item)
        Spacer(Modifier.size(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun StatusPill(item: ComponentItem) {
    val cs = MaterialTheme.colorScheme
    val success = LobosColors.success
    val text = when {
        item.inUse -> "使用中"
        item.installed -> item.status.ifBlank { "已安装" }
        else -> "安装"
    }
    val fg = when {
        item.inUse -> success
        item.installed -> cs.onSurfaceVariant
        else -> cs.primary
    }
    val bg = when {
        item.inUse -> success.copy(alpha = 0.12f)
        item.installed -> cs.surfaceVariant
        else -> cs.primaryContainer
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun componentIcon(item: ComponentItem) = when (item.kind) {
    "tool" -> Icons.Filled.Build
    "library" -> Icons.AutoMirrored.Filled.LibraryBooks
    else -> Icons.Filled.Terminal
}

@Composable
private fun componentAccent(item: ComponentItem): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val success = LobosColors.success
    return when (item.kind) {
        "tool" -> Pair(success, success.copy(alpha = 0.13f))
        "library" -> Pair(cs.secondary, cs.secondaryContainer)
        else -> Pair(cs.primary, cs.primaryContainer)
    }
}

@Composable
fun ComponentDetailScreen(
    name: String,
    versions: List<VersionItem>,
    deps: List<String>,
    capabilities: String,
    installed: Boolean = true,
    notice: String? = null,
    onEvent: (ComponentsEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onUninstallComponent: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier.fillMaxSize().background(cs.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
    ) {
        item(key = "header") {
            if (onBack != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = cs.primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(name, style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text("版本管理 · 依赖与能力", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            TextButton(onClick = { onEvent(ComponentsEvent.Uninstall(name)) }) {
                Text("卸载组件", style = MaterialTheme.typography.labelLarge, color = cs.error)
            }
            notice?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith("安装失败")) cs.error else cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(22.dp))
        }
        item(key = "versions") {
            SectionLabel("版本")
            Spacer(Modifier.height(9.dp))
            if (!installed) {
                versions.firstOrNull()?.version?.let { latestVersion ->
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.primaryContainer)
                            .clickable { onEvent(ComponentsEvent.Install(name, latestVersion)) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("安装 $latestVersion", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                versions.forEachIndexed { index, version ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(cs.outlineVariant))
                    }
                    VersionRow(
                        version = version,
                        onSwitch = { onEvent(ComponentsEvent.SwitchVersion(version.version)) },
                        onInstall = { onEvent(ComponentsEvent.Install(name, version.version)) },
                        onUninstall = { onEvent(ComponentsEvent.UninstallVersion(name, version.version)) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        if (deps.isNotEmpty()) {
            item(key = "deps") {
                SectionLabel("依赖")
                Spacer(Modifier.height(9.dp))
                InfoCard {
                    deps.forEachIndexed { index, dep ->
                        if (index > 0) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(cs.outlineVariant))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = LobosColors.success, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(dep, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = cs.onSurface)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
        item(key = "capabilities") {
            SectionLabel("能力")
            Spacer(Modifier.height(9.dp))
            InfoCard {
                Text(
                    capabilities,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        content = content,
    )
}

@Composable
private fun VersionRow(
    version: VersionItem,
    onSwitch: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val background = if (version.active) cs.primaryContainer else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .then(if (version.active) Modifier.border(1.5.dp, cs.primary, shape) else Modifier)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    version.version,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (version.active) cs.onPrimaryContainer else cs.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    version.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = cs.onSurfaceVariant,
                )
            }
            when {
                version.active -> Text("使用中", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                version.installed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer)
                                .clickable(onClick = onSwitch).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("切换", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        }
                        Box(
                            modifier = Modifier.height(44.dp).clip(shape).border(1.dp, cs.outline, shape)
                                .clickable(onClick = onUninstall).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("卸载", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer)
                            .clickable(onClick = onInstall).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("安装", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    }
                }
            }
        }
    }
}

private val previewRuntime = listOf(
    ComponentItem("python", "Python", "3.14.8 · 3 个版本 · 100 MB", "使用中", installed = true, inUse = true),
    ComponentItem("nodejs", "Node.js", "v24.21.0 · 未安装", "未安装", installed = false),
    ComponentItem("go", "Go", "未安装", "未安装", installed = false),
)

private val previewTools = listOf(
    ComponentItem("cmake", "CMake", "4.4.4 · 已安装", "已安装", installed = true, kind = "tool"),
    ComponentItem("git", "Git", "2.55.0 · 已安装", "已安装", installed = true, kind = "tool"),
    ComponentItem("sqlite", "SQLite", "3.53.4 · 已安装", "已安装", installed = true, kind = "tool"),
)

private val previewLibraries = listOf(
    ComponentItem("libssl", "libssl · libcrypto · libc++", "系统共享库 · ldconfig 管理", "随 APK", installed = true, kind = "library"),
)

@Preview(showBackground = true)
@Composable
private fun ComponentsReadyPreview() {
    LobosTheme(dynamicColor = false) {
        ComponentsScreen(
            ComponentsUiState.Ready(previewRuntime, previewTools, previewLibraries),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComponentsInstallablePreview() {
    LobosTheme(dynamicColor = false) {
        ComponentsScreen(
            ComponentsUiState.Ready(previewRuntime, previewTools, previewLibraries, filter = 1),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComponentsLoadingPreview() {
    LobosTheme(dynamicColor = false) {
        ComponentsScreen(ComponentsUiState.Loading, onEvent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ComponentDetailPreview() {
    LobosTheme(dynamicColor = false) {
        ComponentDetailScreen(
            name = "Python",
            versions = listOf(
                VersionItem("3.14.8", "当前稳定版 · 100 MB", active = true),
                VersionItem("3.13.2", "已安装 · 96 MB", active = false),
                VersionItem("3.12.1", "可安装 · 92 MB", active = false),
            ),
            deps = listOf("libssl >= 3.0", "libffi >= 3.4", "zlib >= 1.2"),
            capabilities = "脚本执行、包管理、虚拟环境与本地调试服务。",
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComponentDetailMinimalPreview() {
    LobosTheme(dynamicColor = false) {
        ComponentDetailScreen(
            name = "Go",
            versions = listOf(VersionItem("1.26.2", "可安装 · 154 MB", active = false)),
            deps = emptyList(),
            capabilities = "静态编译与系统工具链。",
            onEvent = {},
        )
    }
}
