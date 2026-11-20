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
    var lines by remember { mutableStateOf(listOf("正在启动终端会话…")) }

    fun start() {
        TerminalProfile.ensureBashrc(ctx)
        TerminalSessions.close("nav-terminal")
        runCatching { TerminalSessions.open(ctx, "nav-terminal") }
            .onSuccess { t ->
                t.onOutput = { d ->
                    val text = String(d, Charsets.UTF_8)
                    val split = text.replace("\r\n", "\n").split("\n").filter { it.isNotEmpty() }
                    if (split.isNotEmpty()) lines = lines + split
                }
                t.onClosed = { status, signal ->
                    lines = lines + "[会话退出 status=$status signal=$signal]"
                }
                t.resize(24, 80)
            }
            .onFailure { e -> lines = lines + "终端启动失败：${e.javaClass.simpleName}: ${e.message}" }
    }

    DisposableEffect(Unit) {
        start()
        onDispose { TerminalSessions.close("nav-terminal") }
    }

    TerminalScreen(
        state = TerminalUiState.Ready(lines = lines),
        onEvent = { e ->
            if (e is TerminalEvent.Submit) {
                val t = TerminalSessions.get("nav-terminal")
                if (t != null && t.alive) t.write(e.cmd + "\n") else start()
            }
        },
    )
}
