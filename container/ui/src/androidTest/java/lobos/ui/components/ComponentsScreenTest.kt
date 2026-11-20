package lobos.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComponentsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val runtime = listOf(
        ComponentItem("python", "Python", "3.14.8 · 3 个版本 · 100 MB", "使用中", installed = true, inUse = true),
        ComponentItem("nodejs", "Node.js", "v24.21.0 · 未安装", "未安装", installed = false),
    )
    private val tools = listOf(
        ComponentItem("cmake", "CMake", "4.4.4 · 已安装", "已安装", installed = true, kind = "tool"),
    )
    private val libraries = listOf(
        ComponentItem("libssl", "libssl · libcrypto · libc++", "系统共享库", "随 APK", installed = true, kind = "library"),
    )

    @Test
    fun clickingComponentRowSendsOpenDetail() {
        val events = mutableListOf<ComponentsEvent>()
        composeRule.setContent {
            ComponentsScreen(
                state = ComponentsUiState.Ready(runtime, tools, libraries),
                onEvent = { events.add(it) },
            )
        }

        composeRule.onNodeWithText("Python").performClick()

        assertEquals(listOf(ComponentsEvent.OpenDetail("python")), events)
    }

    @Test
    fun clickingFilterSendsFilter() {
        val events = mutableListOf<ComponentsEvent>()
        composeRule.setContent {
            ComponentsScreen(
                state = ComponentsUiState.Ready(runtime, tools, libraries),
                onEvent = { events.add(it) },
            )
        }

        composeRule.onNodeWithText("可安装").performClick()

        assertEquals(listOf(ComponentsEvent.Filter(1)), events)
    }

    @Test
    fun clickingSwitchVersionSendsSwitchVersion() {
        val events = mutableListOf<ComponentsEvent>()
        composeRule.setContent {
            ComponentDetailScreen(
                name = "Python",
                versions = listOf(
                    VersionItem("3.14.8", "当前稳定版", active = true),
                    VersionItem("3.13.2", "已安装", active = false),
                ),
                deps = listOf("libssl >= 3.0"),
                capabilities = "脚本执行与包管理",
                onEvent = { events.add(it) },
            )
        }

        composeRule.onNodeWithText("3.13.2").assertIsDisplayed()
        composeRule.onNodeWithText("切换").performClick()

        assertEquals(listOf(ComponentsEvent.SwitchVersion("3.13.2")), events)
    }
}
