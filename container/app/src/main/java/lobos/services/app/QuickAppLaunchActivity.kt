package lobos.services.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import lobos.services.log.RuntimeDiagnostics

class QuickAppLaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(EXTRA_ID)
        val probe = intent?.getStringExtra(EXTRA_EXEC)
        if (!probe.isNullOrBlank()) {
            val cmd = intent.getStringExtra(EXTRA_EXEC_CMD).orEmpty()
            val r = lobos.api.CapabilityBroker.live()?.invokeLocal(
                lobos.api.CapabilityBroker.INSTALLER_ID,
                "sys.tty.exec",
                org.json.JSONObject().apply { put("cmd", cmd) },
            )
            RuntimeDiagnostics.append(
                this, "shell-exec", r?.optBoolean("ok") == true,
                "在 Lob OS 的终端里执行了一条命令",
                "经由=" + r?.optString("via") +
                    " tty=" + r?.optBoolean("tty") +
                    "\n退出码=" + r?.optInt("exitCode") +
                    "\n输出=" + r?.optString("stdout") +
                    (r?.optString("detail")?.let { "\n原因=" + it } ?: ""),
            )
            finish()
            return
        }
        if (id.isNullOrBlank()) {
            finish()
            return
        }
        val why = lobos.services.app.ProgramGroup.ensureBackendRunning(this, id)
        val err = QuickAppHost.open(this, id)
        if (err.isNotEmpty()) {
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
        const val EXTRA_EXEC = "lobos.services.app.exec"
        const val EXTRA_EXEC_CMD = "lobos.services.app.exec.cmd"
        const val ACTION_LAUNCH = "lobos.services.app.LAUNCH"

        fun intent(base: Context, id: String): Intent =
            Intent(base, QuickAppLaunchActivity::class.java).apply {
                action = ACTION_LAUNCH
                putExtra(EXTRA_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
