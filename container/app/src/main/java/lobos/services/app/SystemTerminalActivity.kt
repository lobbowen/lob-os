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
        var lines by mutableStateOf(
            listOf(
                "lobos@os:~$ python3 --version",
                "Python 3.14.8",
                "lobos@os:~$ ls /usr/bin",
                "bash  busybox  cmake  git  python3  rg",
            )
        )
        setContent {
            LobosTheme {
                TerminalScreen(
                    state = TerminalUiState.Ready(lines = lines),
                    onEvent = { event ->
                        if (event is TerminalEvent.Submit) {
                            lines = lines + listOf("lobos@os:~$ ${event.cmd}", execHint(event.cmd))
                        }
                    },
                )
            }
        }
    }

    private fun execHint(cmd: String): String = when {
        cmd.startsWith("python3 --version") -> "Python 3.14.8"
        cmd == "ls" || cmd.startsWith("ls ") -> "bin  opt  usr  var"
        cmd == "help" -> "可用: python3, ls, cat, help"
        cmd.isBlank() -> ""
        else -> "命令已提交: $cmd"
    }
}
