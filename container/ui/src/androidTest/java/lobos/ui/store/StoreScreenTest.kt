package lobos.ui.store

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StoreScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clickingAppCardSendsOpenDetail() {
        val events = mutableListOf<StoreEvent>()
        composeRule.setContent {
            StoreScreen(
                state = StoreUiState.Ready(
                    listOf(StoreAppItem("python", "3.12.1", "已安装", installed = true)),
                ),
                onEvent = { events.add(it) },
            )
        }

        composeRule.onNodeWithText("python").performClick()

        assertEquals(listOf(StoreEvent.OpenDetail("python")), events)
    }

    @Test
    fun clickingRefreshSendsRefresh() {
        val events = mutableListOf<StoreEvent>()
        composeRule.setContent {
            StoreScreen(
                state = StoreUiState.Ready(emptyList()),
                onEvent = { events.add(it) },
            )
        }

        composeRule.onNodeWithText("还没有取到应用清单").assertIsDisplayed()
        composeRule.onNodeWithText("刷新").performClick()

        assertEquals(listOf(StoreEvent.Refresh), events)
    }

    @Test
    fun clickingErrorRetrySendsRefresh() {
        val events = mutableListOf<StoreEvent>()
        composeRule.setContent {
            StoreScreen(
                state = StoreUiState.Error("获取应用列表失败"),
                onEvent = { events.add(it) },
            )
        }

        composeRule.onNodeWithText("重试").performClick()

        assertEquals(listOf(StoreEvent.Refresh), events)
    }
}
