package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.framer.sense.core.data.CameraHistoryEntry
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cameraTopBarKeepsItsPositionWhenSystemStatusInsetsChange() {
        var host: android.view.View? = null
        compose.setContent { MaterialTheme {
            host = LocalView.current
            Box(Modifier.fillMaxSize().windowInsetsPadding(rememberCameraSafeInsets())) {
                CameraTopBar(VlmUiState(), {}, onExit = {})
            }
        } }
        val topBar = compose.onNodeWithTag("vlm_camera_top_bar").fetchSemanticsNode().boundsInRoot
        val ai = compose.onNodeWithTag("vlm_ai_instruction_toggle").fetchSemanticsNode().boundsInRoot
        val original = ViewCompat.getRootWindowInsets(requireNotNull(host))
        try {
            listOf(0, 64, 0, 32).forEach { reportedTop ->
                compose.runOnIdle {
                    val changed = WindowInsetsCompat.Builder(original ?: WindowInsetsCompat.CONSUMED)
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, reportedTop, 0, 0))
                        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, reportedTop, 0, 0))
                        .setVisible(WindowInsetsCompat.Type.statusBars(), reportedTop > 0).build()
                    ViewCompat.dispatchApplyWindowInsets(requireNotNull(host), changed)
                }
                assertEquals(topBar, compose.onNodeWithTag("vlm_camera_top_bar").fetchSemanticsNode().boundsInRoot)
                assertEquals(ai, compose.onNodeWithTag("vlm_ai_instruction_toggle").fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            compose.runOnIdle { original?.let { ViewCompat.dispatchApplyWindowInsets(requireNotNull(host), it) } }
        }
    }

    @Test fun topBarHasHistoryAtLeftAndCloseAtRightWithSettingsOnlyInDrawer() {
        compose.setContent { MaterialTheme { CameraTopBar(VlmUiState(), {}, onExit = {}) } }
        val history = compose.onNodeWithTag("vlm_history_open").fetchSemanticsNode().boundsInRoot
        val aspect = compose.onNodeWithTag("vlm_aspect_ratio").fetchSemanticsNode().boundsInRoot
        val ai = compose.onNodeWithTag("vlm_ai_instruction_toggle").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("vlm_camera_exit").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("vlm_model_settings").assertDoesNotExist()
        compose.onNodeWithTag("vlm_photo_settings").assertDoesNotExist()
        assertTrue(history.right <= aspect.left + 1)
        assertTrue(aspect.right <= ai.left + 1)
        assertTrue(ai.right <= close.left + 1)
        val bar = compose.onNodeWithTag("vlm_camera_top_bar").fetchSemanticsNode().boundsInRoot
        assertEquals(bar.center.x, ai.center.x, 1f)
    }

    @Test fun drawerHeaderAlignsTitleAndSettingsWithoutClosingDrawer() {
        val state = mutableStateOf(VlmUiState(historyDrawerVisible = true))
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            CameraHistoryDrawer(state.value, events::add) { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) }
        } }
        val header = compose.onNodeWithTag("vlm_history_header").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("vlm_history_title").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithTag("vlm_model_settings").fetchSemanticsNode().boundsInRoot
        val photo = compose.onNodeWithTag("vlm_photo_settings").fetchSemanticsNode().boundsInRoot
        assertEquals(header.top, model.top, 1f)
        assertEquals(header.height, model.height, 1f)
        assertEquals(title.center.y, model.center.y, 1f)
        assertEquals(title.center.y, photo.center.y, 1f)
        assertTrue(title.right <= model.left)
        assertEquals(header.right, photo.right, 1f)
        compose.runOnIdle { events.clear() }
        compose.onNodeWithTag("vlm_photo_settings").performClick()
        assertEquals(listOf(VlmIntent.PhotoSettingsVisible(true)), events)
        compose.onNodeWithTag("vlm_history_header").assertIsDisplayed()
        compose.runOnIdle { events.clear() }
        compose.onNodeWithTag("vlm_model_settings").performClick()
        assertEquals(listOf(VlmIntent.SettingsVisible(true)), events)
        compose.onNodeWithTag("vlm_history_header").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(historySaving = true) }
        compose.onNodeWithTag("vlm_photo_settings").assertIsNotEnabled()
        compose.onNodeWithTag("vlm_model_settings").assertIsNotEnabled()
    }

    @Test fun drawerHasGroupsLongPressPinDeleteAndBusyRestoreProtection() {
        val recent = CameraHistoryEntry("recent", "prompt", "Qwen-Image-2.1 Turbo", "missing", 1, 2, null)
        val pinned = recent.copy(id = "pinned", pinnedAt = 3)
        val state = mutableStateOf(VlmUiState(historyDrawerVisible = true, historyEntries = listOf(pinned, recent)))
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            CameraHistoryDrawer(state.value, { event ->
                events += event
                when (event) {
                    is VlmIntent.HistoryDrawerVisible -> state.value = state.value.copy(historyDrawerVisible = event.visible)
                    is VlmIntent.DeleteHistoryRequested -> state.value = state.value.copy(historyDeleteId = event.id)
                    else -> Unit
                }
            }) { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) }
        } }
        compose.onNodeWithText("最近").assertIsDisplayed()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val drawer = compose.onNodeWithTag("vlm_history_drawer").fetchSemanticsNode().boundsInRoot
        assertEquals(screen.width * .75f, drawer.width, 1f)
        compose.onNodeWithTag("vlm_history_item_pinned").performTouchInput { longClick() }
        compose.onNodeWithText("取消置顶").performClick()
        assertTrue(VlmIntent.PinHistory("pinned", false) in events)
        compose.onNodeWithTag("vlm_history_item_recent").performTouchInput { longClick() }
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.onNodeWithTag("vlm_history_delete_confirm").assertIsDisplayed()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.runOnIdle { state.value = state.value.copy(historySaving = true) }
        compose.onNodeWithTag("vlm_history_item_recent").performClick()
        assertFalse(events.any { it is VlmIntent.RestoreHistory })
        assertTrue(events.any { it is VlmIntent.UserMessage })
        compose.runOnIdle { state.value = state.value.copy(historySaving = false) }
        compose.onNodeWithTag("vlm_history_item_recent").performClick()
        assertTrue(VlmIntent.RestoreHistory("recent") in events)
    }
}
