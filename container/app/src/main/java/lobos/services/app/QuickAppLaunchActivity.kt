package lobos.services.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class QuickAppLaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            finish()
            return
        }
        // 打开前端之前先确保后端在跑 ——
        // 一个完整程序是「前端 + 后端」，不该出现只有界面的空壳
        // （systemd 的 unit 启动时进程与依赖是一起 activate 的）
        val why = lobos.services.app.ProgramGroup.ensureBackendRunning(this, id)
        val err = QuickAppHost.open(this, id)
        if (err.isNotEmpty()) {
            // 打开失败要有反馈 —— 此前直接 finish()，用户点了图标什么也没发生
            android.widget.Toast.makeText(
                this,
                if (why != null) "$id：$why" else "$id：$err",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_ID = "lobos.services.app.id"
        const val ACTION_LAUNCH = "lobos.services.app.LAUNCH"

        fun intent(base: Context, id: String): Intent =
            Intent(base, QuickAppLaunchActivity::class.java).apply {
                action = ACTION_LAUNCH
                putExtra(EXTRA_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
