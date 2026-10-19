package com.framer.sense.feature.camera.vlm

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.model.CameraCapabilities
import com.framer.sense.feature.camera.vlm.model.CaptureAspectRatio
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraIconRotationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourDirectionsRotateIconsRatioAndZoomWithoutMovingButtons() {
        val angle = mutableFloatStateOf(0f)
        val events = mutableListOf<VlmIntent>()
        var photos = 0
        var exits = 0
        var aiLabel = ""
        val state = VlmUiState(cameraReady = true, actualZoom = 2f, zoom = 2f,
            camera = CameraCapabilities(1f, 8f, listOf(1f, 2f, 3f, 8f)))
        compose.setContent { MaterialTheme {
            aiLabel = LocalContext.current.getString(R.string.vlm_ai_instruction_entry)
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                Column(Modifier.fillMaxSize()) {
                    CameraTopBar(state, events::add, onExit = { exits++ })
                    CameraBottomBar(state, events::add, { photos++ }, true)
                }
            }
        } }
        val buttons = listOf("vlm_camera_exit", "vlm_aspect_ratio", "vlm_model_settings", "vlm_photo_settings",
            "vlm_ai_instruction_toggle", "vlm_shutter", "vlm_zoom_1.0", "vlm_zoom_2.0", "vlm_zoom_3.0", "vlm_zoom_8.0")
        val originalBounds = buttons.associateWith { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        val textTags = listOf("vlm_aspect_ratio", "vlm_zoom_1.0", "vlm_zoom_2.0", "vlm_zoom_3.0", "vlm_zoom_8.0")
        val originalText = textTags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text }
        listOf(90f, 180f, 270f, 0f).forEach { degrees ->
            compose.runOnIdle { angle.floatValue = degrees }
            listOf("vlm_camera_exit_icon", "vlm_model_settings_icon", "vlm_photo_settings_icon", "vlm_ai_instruction_icon",
                "vlm_aspect_ratio_label", "vlm_zoom_label_1.0", "vlm_zoom_label_2.0", "vlm_zoom_label_3.0", "vlm_zoom_label_8.0")
                .forEach { tag ->
                    compose.onNodeWithTag(tag, useUnmergedTree = true).assert(SemanticsMatcher("控件内容四方向转正") {
                        kotlin.math.abs(shortestIconRotation(it.config[CameraIconDegrees], degrees)) < .1f
                    })
                }
            buttons.forEach { assertEquals(originalBounds[it], compose.onNodeWithTag(it).getUnclippedBoundsInRoot()) }
            originalText.forEach { (tag, text) -> compose.onNodeWithTag(tag).assertTextContains(text) }
            compose.onNodeWithTag("vlm_ai_instruction_toggle").assertTextContains(aiLabel)
            compose.onNodeWithTag("vlm_zoom_2.0").assertIsSelected()
            compose.onNodeWithTag("vlm_zoom_1.0").assertIsNotSelected()
            compose.onNodeWithTag("vlm_photo_settings").performClick()
            compose.onNodeWithTag("vlm_zoom_3.0").performClick()
            compose.onNodeWithTag("vlm_camera_exit").performClick()
            compose.onNodeWithTag("vlm_shutter").performClick()
        }
        compose.onNodeWithTag("vlm_aspect_ratio").performClick()
        compose.onNodeWithTag("vlm_ratio_NINE_SIXTEEN").performClick()
        assertEquals(List(4) { listOf(VlmIntent.PhotoSettingsVisible(true), VlmIntent.ZoomChanged(3f)) }.flatten() +
            VlmIntent.AspectRatioSelected(CaptureAspectRatio.NINE_SIXTEEN), events)
        assertEquals(4, photos)
        assertEquals(4, exits)
    }

    @Test fun wraparoundDoesNotAnimateBackwardsThroughAnEntireCircle() {
        val angle = mutableFloatStateOf(270f)
        compose.setContent {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                Box(Modifier.size(48.dp).cameraIconRotation().testTag("icon"))
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { angle.floatValue = 0f }
            compose.mainClock.advanceTimeBy(400)
            assertEquals(360f, compose.onNodeWithTag("icon").fetchSemanticsNode().config[CameraIconDegrees], .1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun directionChangeAnimatesThroughIntermediateAnglesAndReversesWithoutJumping() {
        val target = mutableFloatStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(LocalCameraIconAngle provides target.floatValue) {
                Box(Modifier.size(48.dp).cameraIconRotation().testTag("animated_icon"))
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("animated_icon").getUnclippedBoundsInRoot()
        fun visibleAngle() = compose.onNodeWithTag("animated_icon").fetchSemanticsNode().config[CameraIconDegrees]
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { target.floatValue = 90f }
            compose.mainClock.advanceTimeBy(100)
            val intermediate = visibleAngle()
            assertTrue(intermediate > 0f && intermediate < 90f)
            // 动画尚未结束就转回原方向，从当前可见角度接续，不能跳到端点。
            compose.runOnIdle { target.floatValue = 0f }
            assertEquals(intermediate, visibleAngle(), .1f)
            compose.mainClock.advanceTimeBy(100)
            assertTrue(visibleAngle() in 0f..intermediate)
            compose.mainClock.advanceTimeBy(400)
            assertEquals(0f, visibleAngle(), .1f)
            assertEquals(bounds, compose.onNodeWithTag("animated_icon").getUnclippedBoundsInRoot())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun cameraKeepsCurrentDisplayDirectionAndRestoresHostPolicyOnExit() {
        val visible = mutableStateOf(false)
        var activity: Activity? = null
        var displayRotation: Int? = null
        compose.setContent {
            activity = LocalContext.current.hostActivity()
            displayRotation = LocalView.current.display?.rotation
            if (visible.value) CameraIconRotationScope { Box(Modifier.fillMaxSize()) }
        }
        compose.waitForIdle()
        val host = requireNotNull(activity)
        val previousPolicy = host.requestedOrientation
        val previousDisplay = displayRotation
        try {
            compose.runOnIdle { visible.value = true }
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, host.requestedOrientation)
                assertEquals(previousDisplay, displayRotation)
                visible.value = false
            }
            compose.runOnIdle { assertEquals(previousPolicy, host.requestedOrientation) }
        } finally { compose.runOnIdle { visible.value = false } }
    }

    private tailrec fun Context.hostActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.hostActivity()
        else -> null
    }
}
