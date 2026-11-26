package lobos.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import lobos.ui.theme.LobosColors
import lobos.ui.theme.MonoTextStyle

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onEvent: (TerminalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(cs.background)) {
        when (state) {
            is TerminalUiState.Ready -> TerminalReady(state, onEvent)
            is TerminalUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.msg, color = cs.error)
            }
        }
    }
}

@Composable
private fun TerminalReady(state: TerminalUiState.Ready, onEvent: (TerminalEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pal = LobosColors.current
    val shape = RoundedCornerShape(20.dp)
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var lastValue by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotEmpty()) { onEvent(TerminalEvent.Input(input + "\n")); input = "" }
        else onEvent(TerminalEvent.Input("\n"))
    }
    fun keyName(code: Int): String? = when (code) {
        AndroidKeyEvent.KEYCODE_DPAD_UP -> "UP"
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> "HOME"
        AndroidKeyEvent.KEYCODE_MOVE_END -> "END"
        AndroidKeyEvent.KEYCODE_INSERT -> "INSERT"
        AndroidKeyEvent.KEYCODE_DEL -> "BACKSPACE"
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> "DELETE"
        AndroidKeyEvent.KEYCODE_PAGE_UP -> "PAGE_UP"
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> "PAGE_DOWN"
        AndroidKeyEvent.KEYCODE_TAB -> "TAB"
        AndroidKeyEvent.KEYCODE_ESCAPE -> "ESCAPE"
        AndroidKeyEvent.KEYCODE_ENTER -> "ENTER"
        AndroidKeyEvent.KEYCODE_F1 -> "F1"; AndroidKeyEvent.KEYCODE_F2 -> "F2"
        AndroidKeyEvent.KEYCODE_F3 -> "F3"; AndroidKeyEvent.KEYCODE_F4 -> "F4"
        AndroidKeyEvent.KEYCODE_F5 -> "F5"; AndroidKeyEvent.KEYCODE_F6 -> "F6"
        AndroidKeyEvent.KEYCODE_F7 -> "F7"; AndroidKeyEvent.KEYCODE_F8 -> "F8"
        AndroidKeyEvent.KEYCODE_F9 -> "F9"; AndroidKeyEvent.KEYCODE_F10 -> "F10"
        AndroidKeyEvent.KEYCODE_F11 -> "F11"; AndroidKeyEvent.KEYCODE_F12 -> "F12"
        else -> null
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text("终端", style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
        Spacer(Modifier.height(5.dp))
        Text(
            if (state.connected) "在 LobOS 中运行命令" else "会话已断开",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))

        Column(
            Modifier.fillMaxWidth().weight(1f).clip(shape).background(pal.terminalBg)
                .border(1.dp, pal.terminalBorder, shape),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEach { c ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.size(7.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("bash · aarch64", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = pal.terminalMuted)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.terminalDivider))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                itemsIndexed(state.screen, key = { i, _ -> i }) { _, line ->
                    Text(text = renderLine(line, pal.terminalText), style = MonoTextStyle)
                }
            }
            // 输出增长时跟随到最后一行（snapshot 末尾不再有多余空行）。
            LaunchedEffect(state.screen.size, state.screen.lastOrNull()?.text) {
                if (state.screen.isNotEmpty()) listState.scrollToItem(state.screen.size - 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.terminalDivider))
            // 命令行：TextField 持有真实输入文本并可见，输入法 composition 正常工作。
            // 打字只更新本地文本；回车时把整行送给 PTY（shell 负责回显与执行）。
            // Terminal input: hardware keys via VT-aware encoder; soft text
            // sent char-by-char so shell readline owns echo and line editing.
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = input,
                    onValueChange = { v ->
                        // v is the full IME value; send only the newly appended
                        // tail. Keep lastValue as baseline; keep the visible
                        // field empty so the shell's own echo is the only text.
                        val tail = if (v.startsWith(lastValue)) v.removePrefix(lastValue) else v
                        if (tail.isNotEmpty()) onEvent(TerminalEvent.Input(tail))
                        lastValue = v
                        input = ""
                    },
                    modifier = Modifier.weight(1f).height(52.dp).onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val name = keyName(e.key.nativeKeyCode) ?: return@onKeyEvent false
                        onEvent(TerminalEvent.Key(name, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed))
                        true
                    },
                    singleLine = true,
                    textStyle = MonoTextStyle.copy(color = pal.terminalText),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }, onSend = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = pal.terminalText,
                        focusedTextColor = pal.terminalText,
                        unfocusedTextColor = pal.terminalText,
                    ),
                    placeholder = { Text("输入命令", style = MonoTextStyle, color = pal.terminalMuted.copy(alpha = 0.6f)) },
                )
                for (ctrl in listOf("^C" to "\u0003", "^Z" to "\u001a", "^D" to "\u0004")) {
                    Text(ctrl.first, style = MonoTextStyle.copy(color = pal.terminalText),
                        modifier = Modifier.padding(start = 8.dp).size(40.dp, 52.dp)
                            .clickable { onEvent(TerminalEvent.Input(ctrl.second.toString())) })
                }
            }
        }
    }
}

private fun renderLine(line: TerminalLine, defaultColor: Color): AnnotatedString = buildAnnotatedString {
    line.chars.forEach { sc ->
        withStyle(SpanStyle(color = sc.color?.let { Color(it) } ?: defaultColor, fontWeight = if (sc.bold) FontWeight.Bold else FontWeight.Normal)) {
            append(sc.c.toString())
        }
    }
}
