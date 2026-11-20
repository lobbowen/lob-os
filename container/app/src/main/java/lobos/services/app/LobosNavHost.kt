package lobos.services.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import lobos.ui.apps.AppsEvent
import lobos.ui.apps.AppsScreen
import lobos.ui.apps.AppsUiState
import lobos.ui.components.ComponentsEvent
import lobos.ui.components.ComponentsScreen
import lobos.ui.components.ComponentDetailScreen
import lobos.ui.components.ComponentsUiState
import lobos.ui.home.HomeEvent
import lobos.ui.home.HomeScreen
import lobos.ui.more.MoreEvent
import lobos.ui.more.MoreScreen
import lobos.ui.more.MoreUiState
import lobos.ui.space.SpaceScreen
import lobos.ui.theme.LobosTheme

sealed interface Destination {
    data object Home : Destination
    data object Terminal : Destination
    data object Components : Destination
    data class ComponentDetail(val id: String) : Destination
    data object Space : Destination
    data object Apps : Destination
    data object More : Destination
    data object Check : Destination
    data object Recovery : Destination
}

@Composable
fun LobosNavHost(modifier: Modifier = Modifier) {
    LobosTheme {
        val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }
        val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
        NavDisplay(
            backStack = backStack,
            modifier = modifier.fillMaxSize(),
            onBack = pop,
            entryProvider = { key -> NavEntry(key) { Route(key, backStack, pop) } },
        )
    }
}

@Composable
private fun Route(key: Destination, backStack: MutableList<Destination>, pop: () -> Unit) {
    when (key) {
        Destination.Home -> {
            val ctx = LocalContext.current
            val vm: HomeViewModel = viewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.load(ctx) }
            HomeScreen(state = state, onEvent = { e ->
                if (e is HomeEvent.OpenEntry) {
                    val d = when (e.key) {
                        "terminal" -> Destination.Terminal
                        "components" -> Destination.Components
                        "space" -> Destination.Space
                        "apps" -> Destination.Apps
                        "more" -> Destination.More
                        else -> null
                    }
                    d?.let { backStack.add(it) }
                } else vm.onEvent(ctx, e)
            })
        }
        Destination.Terminal -> TerminalRoute()
        Destination.Components -> {
            val ctx = LocalContext.current
            val vm: ComponentsViewModel = viewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.onEvent(ctx, ComponentsEvent.Back); vm.load(ctx) }
            ComponentsScreen(state = state, onEvent = { e ->
                if (e is ComponentsEvent.OpenDetail) backStack.add(Destination.ComponentDetail(e.id))
                else vm.onEvent(ctx, e)
            })
        }
        is Destination.ComponentDetail -> {
            val ctx = LocalContext.current
            val vm: ComponentsViewModel = viewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(key.id) { vm.onEvent(ctx, ComponentsEvent.OpenDetail(key.id)) }
            val detail = (state as? ComponentsUiState.Ready)?.detail
            ComponentDetailScreen(
                name = detail?.name ?: key.id,
                versions = detail?.versions ?: emptyList(),
                deps = detail?.deps ?: emptyList(),
                capabilities = detail?.capabilities ?: "",
                onEvent = { e -> vm.onEvent(ctx, e) },
                onBack = pop,
            )
        }
        Destination.Space -> {
            val ctx = LocalContext.current
            val vm: SpaceViewModel = viewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.load(ctx) }
            SpaceScreen(state = state, onEvent = { e -> vm.onEvent(ctx, e) })
        }
        Destination.Apps -> {
            val ctx = LocalContext.current
            AppsScreen(state = AppsUiState.Ready(emptyList()), onEvent = { e ->
                if (e is AppsEvent.AddApp) android.widget.Toast.makeText(ctx, "应用系统尚未就绪", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        Destination.More -> {
            val ctx = LocalContext.current
            MoreScreen(state = MoreUiState.Ready, onEvent = { e ->
                if (e is MoreEvent.Open) {
                    when (e.itemKey) {
                        "check" -> backStack.add(Destination.Check)
                        "diagnosis" -> backStack.add(Destination.Recovery)
                        else -> powerAction(ctx, e.itemKey)
                    }
                }
            })
        }
        Destination.Check, Destination.Recovery -> {
            val title = if (key is Destination.Check) "系统体检" else "故障诊断"
            lobos.ui.nav.SimpleScreen(title = title, onBack = pop)
        }
    }
}

private fun powerAction(ctx: android.content.Context, key: String) {
    when (key) {
        "boot" -> lobos.services.power.KernelPower.boot(ctx)
        "shutdown", "reboot" -> {
            val reboot = key == "reboot"
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(if (reboot) "重启系统" else "关闭系统")
                .setMessage("将停止所有运行时，管理界面保持在线。")
                .setPositiveButton(if (reboot) "重启" else "关机") { _, _ ->
                    if (reboot) lobos.services.power.KernelPower.reboot(ctx)
                    else lobos.services.power.KernelPower.shutdown(ctx)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
