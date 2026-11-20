package lobos.services.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import lobos.ui.apps.AppsEvent
import lobos.ui.apps.AppsScreen
import lobos.ui.apps.AppsUiState
import lobos.ui.theme.LobosTheme

class SystemApplicationsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LobosTheme {
                AppsScreen(
                    state = AppsUiState.Ready(apps = emptyList()),
                    onEvent = { event ->
                        if (event is AppsEvent.AddApp) {
                            Toast.makeText(
                                this@SystemApplicationsActivity,
                                "应用系统尚未就绪，系统底座完成后才能安装社区应用",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}
