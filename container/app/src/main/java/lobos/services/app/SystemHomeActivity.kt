package lobos.services.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import lobos.ui.home.HomeEvent
import lobos.ui.home.HomeScreen
import lobos.ui.home.HomeUiState
import lobos.ui.theme.LobosTheme

class SystemHomeActivity : AppCompatActivity() {
    private lateinit var vm: HomeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        vm = ViewModelProvider(this)[HomeViewModel::class.java]
        setContent {
            LobosTheme {
                val state by vm.state.collectAsState()
                HomeScreen(state = state, onEvent = { handle(it) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.load(this)
    }

    private fun handle(event: HomeEvent) {
        when (event) {
            HomeEvent.TogglePower -> showPowerDialog()
            HomeEvent.AddApp -> android.widget.Toast.makeText(
                this, "应用系统尚未就绪", android.widget.Toast.LENGTH_SHORT,
            ).show()
            is HomeEvent.OpenEntry -> {
                val target = when (event.key) {
                    "terminal" -> SystemTerminalActivity::class.java
                    "components" -> SystemComponentsActivity::class.java
                    "space" -> SystemSpaceActivity::class.java
                    "apps" -> SystemApplicationsActivity::class.java
                    "more" -> SystemAboutActivity::class.java
                    else -> null
                }
                target?.let { startActivity(Intent(this, it)) }
            }
        }
    }

    private fun showPowerDialog() {
        val running = when (val s = vm.state.value) {
            is HomeUiState.Ready -> s.running
            else -> !lobos.services.power.KernelPower.isHalted(this)
        }
        val title = if (running) "停止系统" else "启动系统"
        val message = if (running) "停止后所有组件都会停下，已安装的东西不会删除。"
        else "启动会按已安装的组件拉起系统运行环境。"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(title) { _, _ ->
                if (running) lobos.services.power.KernelPower.shutdown(this)
                else lobos.services.power.KernelPower.boot(this)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) vm.load(this)
                }, 500L)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
