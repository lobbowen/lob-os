package lobos.services.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import lobos.ui.terminal.TerminalEvent
import lobos.ui.terminal.TerminalScreen
import lobos.ui.terminal.TerminalUiState
import lobos.ui.theme.LobosTheme

class SystemTerminalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var lines by mutableStateOf(listOf("正在启动终端会话…"))

        setContent {
            LobosTheme {
                TerminalScreen(
                    state = TerminalUiState.Ready(lines = lines),
                    onEvent = { event ->
                        if (event is TerminalEvent.Submit) {
                            val t = TerminalSessions.get(SESSION_ID)
                            if (t == null || !t.alive) {
                                lines = lines + "会话未就绪，正在重试…"
                                startSession { out -> lines = lines + out }
                            } else {
                                t.write(event.cmd + "\n")
                            }
                        }
                    },
                )
            }
        }
        startSession { out -> lines = lines + out }
    }

    private fun startSession(onOutput: (List<String>) -> Unit) {
        TerminalProfile.ensureBashrc(this)
        TerminalSessions.close(SESSION_ID)
        runCatching { TerminalSessions.open(this, SESSION_ID) }
            .onSuccess { t ->
                t.onOutput = { d ->
                    val text = String(d, Charsets.UTF_8)
                    runOnUiThread { onOutput(splitLines(text)) }
                }
                t.onClosed = { status, signal ->
                    runOnUiThread { onOutput(listOf("[会话退出 status=$status signal=$signal]")) }
                }
                t.resize(24, 80)
                onOutput(listOf("lobos 终端已就绪（sid=${t.session.sid}）"))
            }
            .onFailure { e -> onOutput(listOf("终端启动失败：${e.javaClass.simpleName}: ${e.message}")) }
    }

    private fun splitLines(text: String): List<String> =
        text.replace("\r\n", "\n").split("\n").filter { it.isNotEmpty() }

    override fun onDestroy() {
        TerminalSessions.close(SESSION_ID)
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "ui-terminal"
    }
}
