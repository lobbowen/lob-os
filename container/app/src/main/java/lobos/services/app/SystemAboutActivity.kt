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
        when (event.itemKey) {
            "shutdown", "reboot" -> confirmPower(event.itemKey)
            "boot" -> lobos.services.power.KernelPower.boot(this)
            "check" -> startActivity(Intent(this, SystemCheckActivity::class.java))
            "diagnosis" -> startActivity(Intent(this, SystemRecoveryActivity::class.java))
        }
    }

    private fun confirmPower(action: String) {
        val reboot = action == "reboot"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (reboot) "重启系统" else "关闭系统")
            .setMessage(if (reboot) "将先停止所有运行时再重新开机，管理界面保持在线。"
            else "将停止所有运行时，管理界面保持在线，之后可从这里或首页重新开机。")
            .setPositiveButton(if (reboot) "重启" else "关机") { _, _ ->
                if (reboot) lobos.services.power.KernelPower.reboot(this)
                else lobos.services.power.KernelPower.shutdown(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
