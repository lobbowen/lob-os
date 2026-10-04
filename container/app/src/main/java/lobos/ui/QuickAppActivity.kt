package lobos.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import lobos.os.ProgramIndex

class QuickAppActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = QuickAppIcons.idOf(intent)
        if (id == null) {
            finish()
            return
        }
        val entry = runCatching { ProgramIndex.get(this, id) }.getOrNull()
        if (entry == null) {
            show("快应用不存在", id)
            return
        }
        show(entryName(entry), id, entry.uiPackage.ifBlank { null }, File(entry.stateDir))
    }

    private fun entryName(e: ProgramIndex.IndexEntry): String = e.id

    private fun show(title: String, id: String, uiPackage: String?, dir: File) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "id=" + id
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.GRAY)
        })
        root.addView(TextView(this).apply {
            text = "ui.package=" + (uiPackage ?: "（未声明）")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.GRAY)
        })
        root.addView(TextView(this).apply {
            text = "目录=" + dir.absolutePath + "\n存在=" + dir.isDirectory
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.DKGRAY)
        })
        val body = ScrollView(this)
        body.addView(root)
        setContentView(body, ViewGroup.LayoutParams(-1, -1))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
