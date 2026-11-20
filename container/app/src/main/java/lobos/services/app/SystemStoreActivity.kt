package lobos.services.app

import android.os.Bundle
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import lobos.ui.store.StoreEvent
import lobos.ui.store.StoreScreen

class SystemStoreActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var vm: StoreViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[StoreViewModel::class.java]

        val (body, _) = Ui.scaffold(
            this,
            "应用商店",
            "选装运行时与工具",
            listOf(
                Triple("home", "首页", SystemHomeActivity::class.java),
                Triple("store", "商店", null),
                Triple("grid", "组件", SystemComponentsActivity::class.java),
                Triple("disk", "空间", SystemSpaceActivity::class.java),
                Triple("user", "我的", SystemAboutActivity::class.java),
            ),
            1,
        )
        body.addView(ComposeView(this).apply {
            id = android.view.View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            setContent {
                val state = vm.state.collectAsState().value
                StoreScreen(
                    state = state,
                    onEvent = { event ->
                        when (event) {
                            StoreEvent.Refresh -> vm.onEvent(this@SystemStoreActivity, event)
                            is StoreEvent.OpenDetail ->
                                runCatching {
                                    startActivity(SystemInstallActivity.intentOf(this@SystemStoreActivity, event.name))
                                }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.load(this)
    }
}
