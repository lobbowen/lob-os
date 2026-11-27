package lobos.android.app
import lobos.android.terminal.TerminalRoute

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import lobos.ui.apps.AppItem
import lobos.ui.apps.AppsEvent
import lobos.ui.apps.AppsScreen
import lobos.ui.apps.AppsUiState
import lobos.ui.home.HomeEvent
import lobos.ui.home.HomeScreen
import lobos.ui.more.MoreEvent
import lobos.ui.more.MoreScreen
import lobos.ui.more.MoreUiState
import lobos.ui.nav.LobosNavigationBar
import lobos.ui.nav.LobosTab
import lobos.ui.space.SpaceScreen
import lobos.ui.theme.LobosTheme

sealed interface Destination {
    data object Home : Destination
    data object Terminal : Destination
    data object Space : Destination
    data object Apps : Destination
    data object More : Destination
    data object Check : Destination
    data object Recovery : Destination
}

@Composable
fun LobosNavHost(modifier: Modifier = Modifier) {
    LobosTheme {
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }
        val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
        // The selected tab is the top-level destination at the root of the stack.
        val root = backStack.firstOrNull() ?: Destination.Home
        val selectedTab = when (root) {
            Destination.Terminal -> LobosTab.Terminal
            Destination.Space -> LobosTab.Space
            Destination.Apps -> LobosTab.Apps
            Destination.More -> LobosTab.More
            else -> LobosTab.Home
        }
        val selectTab: (LobosTab) -> Unit = { tab ->
            val dest = when (tab) {
                LobosTab.Home -> Destination.Home
                LobosTab.Terminal -> Destination.Terminal
                LobosTab.Space -> Destination.Space
                LobosTab.Apps -> Destination.Apps
                LobosTab.More -> Destination.More
            }
            // Tab switch resets the stack to that top-level destination.
            backStack.clear()
            backStack.add(dest)
        }
        Scaffold(
            modifier = modifier,
            bottomBar = { if (!imeVisible) LobosNavigationBar(selected = selectedTab, onSelect = selectTab) },
        ) { padding ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().padding(padding),
                onBack = pop,
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                entryProvider = { key -> NavEntry(key) { Route(key, backStack, pop, selectTab) } },
            )
        }
    }
}

@Composable
private fun Route(
    key: Destination,
    backStack: MutableList<Destination>,
    pop: () -> Unit,
    selectTab: (LobosTab) -> Unit,
) {
    when (key) {
        Destination.Home -> {
            val ctx = LocalContext.current
            val vm: HomeViewModel = viewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.load(ctx) }
            HomeScreen(state = state, onEvent = { e -> vm.onEvent(ctx, e) })
        }
        Destination.Terminal -> TerminalRoute()
        Destination.Space -> {
            val ctx = LocalContext.current
            val vm: SpaceViewModel = viewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.load(ctx) }
            SpaceScreen(state = state, onEvent = { e -> vm.onEvent(ctx, e) })
        }
        Destination.Apps -> {
            val ctx = LocalContext.current
            AppsScreen(state = AppsUiState.Ready(
                listOf(
                    AppItem("markdown", "Markdown 阅读器", "系统工具", "1.2 MB", false, "M", 0xFF0B6BCB),
                    AppItem("logviewer", "日志查看器", "开发工具", "820 KB", false, "L", 0xFF6B4FD8),
                    AppItem("process", "进程面板", "系统工具", "1.8 MB", false, "P", 0xFF1B7F4B),
                    AppItem("notes", "文件便签", "效率工具", "640 KB", true, "F", 0xFFD8830B),
                ),
            ), onEvent = { e ->
                when (e) {
                    is AppsEvent.Install -> android.widget.Toast.makeText(ctx, "应用系统尚未就绪", android.widget.Toast.LENGTH_SHORT).show()
                    is AppsEvent.AddApp -> android.widget.Toast.makeText(ctx, "应用系统尚未就绪", android.widget.Toast.LENGTH_SHORT).show()
                }
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
        "boot" -> lobos.android.services.power.KernelPower.boot(ctx)
        "shutdown", "reboot" -> {
            val reboot = key == "reboot"
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(if (reboot) "重启系统" else "关闭系统")
                .setMessage("将停止所有运行时，管理界面保持在线。")
                .setPositiveButton(if (reboot) "重启" else "关机") { _, _ ->
                    if (reboot) lobos.android.services.power.KernelPower.reboot(ctx)
                    else lobos.android.services.power.KernelPower.shutdown(ctx)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
