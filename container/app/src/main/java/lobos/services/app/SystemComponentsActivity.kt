package lobos.services.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import lobos.ui.components.ComponentsEvent
import lobos.ui.components.ComponentsScreen
import lobos.ui.theme.LobosTheme

class SystemComponentsActivity : AppCompatActivity() {
    private lateinit var vm: ComponentsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[ComponentsViewModel::class.java]
        setContent {
            LobosTheme {
                val state by vm.state.collectAsState()
                ComponentsScreen(state = state, onEvent = { event ->
                    when (event) {
                        is ComponentsEvent.Filter -> vm.onEvent(this, event)
                        else -> Unit
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.load(this)
    }
}
