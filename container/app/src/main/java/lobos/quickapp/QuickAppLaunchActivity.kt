package lobos.quickapp

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
        QuickAppHost.open(this, id)
        finish()
    }

    companion object {
        const val EXTRA_ID = "lobos.quickapp.id"
        const val ACTION_LAUNCH = "lobos.quickapp.LAUNCH"

        fun intent(base: Context, id: String): Intent =
            Intent(base, QuickAppLaunchActivity::class.java).apply {
                action = ACTION_LAUNCH
                putExtra(EXTRA_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
