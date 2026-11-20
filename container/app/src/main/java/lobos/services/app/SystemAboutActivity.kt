package lobos.services.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import lobos.ui.more.MoreEvent
import lobos.ui.more.MoreScreen
import lobos.ui.more.MoreUiState
import lobos.ui.theme.LobosTheme

class SystemAboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LobosTheme {
                MoreScreen(
                    state = MoreUiState.Ready,
                    onEvent = { event -> handle(event) },
                )
            }
        }
    }

    private fun handle(event: MoreEvent) {
        if (event !is MoreEvent.Open) return
        val target = when (event.itemKey) {
            "check" -> SystemCheckActivity::class.java
            "diagnosis" -> SystemRecoveryActivity::class.java
            else -> null
        }
        target?.let { startActivity(Intent(this, it)) }
    }
}
