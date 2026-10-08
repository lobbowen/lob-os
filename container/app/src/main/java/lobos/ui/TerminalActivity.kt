package lobos.ui

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import lobos.RuntimeDiagnostics
import lobos.runtime.PtySession

class TerminalActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TerminalActivity"
        const val EXTRA_SHELL = "shell"

    /** 系统里没有命令解释器时的回落 —— Android 自带的 sh，不是我们的件 */
    private const val DEFAULT_SHELL_FALLBACK = "/system/bin/sh"
    }

    private lateinit var term: TerminalView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private var session: PtySession.Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        status = TextView(this).apply {
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 11f
            setPadding(8, 6, 8, 6)
        }
        root.addView(status)

        term = TerminalView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            onInput = { bytes -> session?.write(bytes) }
            onResize = { rows, cols -> session?.resize(rows, cols) }
        }
        root.addView(term)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 6, 6, 6)
        }
        input = EditText(this).apply {
            hint = "输入命令（回车发送；^C 中断）"
            setTextColor(0xFFE0E0E0.toInt())
            setHintTextColor(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ ->
                send()
                true
            }
        }
        bar.addView(input)
        bar.addView(Button(this).apply {
            text = "发送"
            setOnClickListener { send() }
        })
        bar.addView(Button(this).apply {
            text = "^C"
            setOnClickListener { session?.write(byteArrayOf(0x03)) }
        })
        bar.addView(Button(this).apply {
            text = "^D"
            setOnClickListener { session?.write(byteArrayOf(0x04)) }
        })
        bar.addView(Button(this).apply {
            text = "清屏"
            setOnClickListener {
                term.screen.reset()
                term.invalidate()
            }
        })
        root.addView(bar)

        setContentView(root)
        startSession()
    }

    private fun startSession() {
        val shellBin = lobos.os.SystemRoles.shellBin(this)
        val want = intent.getStringExtra(EXTRA_SHELL)
            ?: shellBin?.name
            ?: DEFAULT_SHELL_FALLBACK
        val argv = when {
            want.isBlank() -> listOf(shellBin?.absolutePath ?: DEFAULT_SHELL_FALLBACK)
            else -> listOf(want)
            else -> listOf(want)
        }
        try {
            val s = PtySession.openSession(this, argv, term.screen.rows, term.screen.cols)
            session = s
            term.connected = true
            s.onData = { data -> view.post { term.appendOutput(data) } }
            s.onExit = {
                view.post {
                    term.connected = false
                    status.text = "会话已结束（pid 已退出）"
                    RuntimeDiagnostics.append(this@TerminalActivity, "terminal", null, "终端会话结束", argv.joinToString(" "))
                }
            }
            status.text = "$want · ${term.screen.cols}×${term.screen.rows} · sid=${s.sid}"
        } catch (e: Throwable) {
            term.connected = false
            status.text = "起会话失败：${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, "起 PTY 会话失败", e)
            RuntimeDiagnostics.append(
                this, "terminal", false, "终端起不来",
                e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun send() {
        val text = input.text?.toString().orEmpty()
        if (text.isEmpty()) return
        session?.write(text + "\r")
        input.setText("")
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    companion object {
        fun intentFor(ctx: android.content.Context, shell: String? = null) =
            android.content.Intent(ctx, TerminalActivity::class.java).apply {
                putExtra(EXTRA_SHELL, shell ?: "")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}