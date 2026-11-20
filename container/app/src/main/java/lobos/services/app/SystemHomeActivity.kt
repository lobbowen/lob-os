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
            HomeEvent.TogglePower -> Unit
            HomeEvent.AddApp -> Unit
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
}
