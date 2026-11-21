package lobos.services.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import lobos.ui.terminal.TerminalEvent
import lobos.ui.terminal.TerminalScreen
import lobos.ui.terminal.TerminalUiState

@Composable
fun TerminalRoute() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }

    val listener: () -> Unit = remember { { tick++; Unit } }
    DisposableEffect(listener) {
        TerminalRuntime.subscribe(listener)
        onDispose { TerminalRuntime.unsubscribe(listener) }
    }

    DisposableEffect(Unit) {
        TerminalRuntime.ensure(ctx)
        onDispose { Unit }
    }

    @Suppress("UNUSED_VARIABLE") val r = tick
    TerminalScreen(
        state = TerminalUiState.Ready(screen = TerminalRuntime.buffer.snapshot, connected = TerminalRuntime.connected),
        onEvent = { e -> if (e is TerminalEvent.Input) TerminalRuntime.input(e.bytes) },
    )
}
