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

/**
 * 内置终端窗口 —— 给「程序可依赖的系统能力」一个能用的人机界面。
 *
 * ── 它不做什么 ──
 * 它不是「交互式外壳」，不做命令补全、不做历史、不做快捷键配置。
 * 终端的全部语义在 PTY 那侧（行规程、信号、窗口大小）；这个窗口只负责
 * 显示与转发。把它做薄，才不会变成第二套终端实现。
 *
 * ── 与 [PtySession] 的关系 ──
 * 复用**同一个**常驻宿主（`PtySession.openSession`），不另起进程。
 * `shell.exec` 与终端窗口各开各的会话（sid 不同），互不干扰 ——
 * 终端里跑着 `npm install` 时，程序仍能用 `shell.exec` 跑别的命令。
 *
 * ── 输入为什么需要 EditText ──
 * 手机没有物理键盘。View 能收到 `onKeyDown` 的只有系统合成键与输入法，
 * 所以要一个输入框承接用户打的字，再点「发送」喂给 PTY。
 * 这不是为了迁就，是「终端能用」在触屏上的必要条件。
 */
class TerminalActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TerminalActivity"
        const val EXTRA_SHELL = "shell"          // 起什么：bash / sh / 某个程序路径
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
            // 回车 = 发送 + 换行；不要 IME 的「搜索」动作吞掉它
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

    /**
     * 起会话。
     *
     * 起哪个程序：默认底座的 bash（对齐 Linux 的登录 shell）；拿不到就明说
     * 失败并把原因显示出来 —— **不假装在工作**（终端里一片黑、什么都不发生，
     * 是最难查的一类故障）。
     */
    private fun startSession() {
        val want = intent.getStringExtra(EXTRA_SHELL) ?: "bash"
        val bash = lobos.runtime.PrefixProvisioner.bashBin(this)
        val argv = when {
            want == "bash" && bash != null -> listOf(bash.absolutePath)
            want == "bash" -> listOf("/system/bin/sh")     // 底座缺 bash 的退路
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
        /** 从别处拉起终端窗口（控制面板的「终端」按钮）。 */
        fun intentFor(ctx: android.content.Context, shell: String? = null) =
            android.content.Intent(ctx, TerminalActivity::class.java).apply {
                putExtra(EXTRA_SHELL, shell ?: "bash")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}