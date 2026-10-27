package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.framer.sense.core.data.CameraHistoryEntry
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun topBarHasHistoryAtLeftAndCloseAfterPhotoSettingsWithoutOverlappingAiEntry() {
        compose.setContent { MaterialTheme { CameraTopBar(VlmUiState(), {}, onExit = {}) } }
        val history = compose.onNodeWithTag("vlm_history_open").fetchSemanticsNode().boundsInRoot
        val aspect = compose.onNodeWithTag("vlm_aspect_ratio").fetchSemanticsNode().boundsInRoot
        val ai = compose.onNodeWithTag("vlm_ai_instruction_toggle").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithTag("vlm_model_settings").fetchSemanticsNode().boundsInRoot
        val photo = compose.onNodeWithTag("vlm_photo_settings").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("vlm_camera_exit").fetchSemanticsNode().boundsInRoot
        assertTrue(history.right <= aspect.left + 1)
        assertTrue(aspect.right <= ai.left + 1)
        assertTrue(ai.right <= model.left + 1)
        assertTrue(photo.right <= close.left + 1)
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
