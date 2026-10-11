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
        const val ACTION_LAUNCH = "lobos.services.app.LAUNCH"

        fun intent(base: Context, id: String): Intent =
            Intent(base, QuickAppLaunchActivity::class.java).apply {
                action = ACTION_LAUNCH
                putExtra(EXTRA_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
