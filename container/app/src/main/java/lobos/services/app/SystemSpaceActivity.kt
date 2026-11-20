package lobos.services.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import lobos.ui.space.SpaceEvent
import lobos.ui.space.SpaceScreen
import lobos.ui.theme.LobosTheme

class SystemSpaceActivity : AppCompatActivity() {
    private lateinit var vm: SpaceViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[SpaceViewModel::class.java]
        setContent {
            LobosTheme {
                val state by vm.state.collectAsState()
                SpaceScreen(state = state, onEvent = { event ->
                    when (event) {
                        is SpaceEvent.Clean -> vm.onEvent(this, event)
                        is SpaceEvent.OpenDir -> Unit
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
