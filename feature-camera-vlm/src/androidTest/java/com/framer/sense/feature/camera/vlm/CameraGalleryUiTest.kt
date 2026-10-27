package com.framer.sense.feature.camera.vlm

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class CameraGalleryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fixedGalleryIconAlignsWithTimelineWithoutMovingShutter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT }
        val angle = mutableFloatStateOf(0f)
        val width = mutableStateOf(320.dp)
        val state = mutableStateOf(VlmUiState(cameraReady = true))
        val events = mutableListOf<VlmIntent>()
        var photos = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalCameraIconAngle provides angle.floatValue) {
                Box(Modifier.width(width.value).fillMaxHeight().background(Color.Black).testTag("camera_bounds")) {
                    Box(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
                        CameraBottomBar(state.value, events::add, { photos++ }, true, Modifier.align(Alignment.BottomCenter))
                    }
                    CameraWorkflowFooter(state.value, Modifier.align(Alignment.BottomCenter))
                }
            }
        } }
        fun checkAlignment() {
            val screen = compose.onNodeWithTag("camera_bounds").getUnclippedBoundsInRoot()
            val shutter = compose.onNodeWithTag("vlm_shutter").getUnclippedBoundsInRoot()
            val entry = compose.onNodeWithTag("vlm_gallery_entry").getUnclippedBoundsInRoot()
            val icon = compose.onNodeWithTag("vlm_gallery_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val first = compose.onNodeWithTag("vlm_workflow_icon_SCAN", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(68f, shutter.width.value, 1f)
            assertEquals(screen.bottom.value - 30f, shutter.bottom.value, 1f)
            assertEquals((screen.left.value + screen.right.value) / 2f, (shutter.left.value + shutter.right.value) / 2f, 1f)
            assertTrue(entry.right < shutter.left)
            assertEquals((shutter.top.value + shutter.bottom.value) / 2f, (entry.top.value + entry.bottom.value) / 2f, 1f)
            assertEquals(40f, icon.width.value, 1f)
            assertEquals(icon.width, icon.height)
            assertEquals(first.left.value, icon.left.value, 1f)
        }
        val shutter = compose.onNodeWithTag("vlm_shutter").getUnclippedBoundsInRoot()
        val entry = compose.onNodeWithTag("vlm_gallery_entry").getUnclippedBoundsInRoot()
        listOf(0f, 90f, 180f, 270f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            compose.onNodeWithTag("vlm_gallery_icon", useUnmergedTree = true).assert(SemanticsMatcher("固定相册图标转正") {
                abs(shortestIconRotation(it.config[CameraIconDegrees], direction)) < .1f
            })
            checkAlignment()
            assertEquals(entry, compose.onNodeWithTag("vlm_gallery_entry").getUnclippedBoundsInRoot())
            assertEquals(shutter, compose.onNodeWithTag("vlm_shutter").getUnclippedBoundsInRoot())
            compose.onNodeWithTag("vlm_gallery_entry").performClick()
            compose.onNodeWithTag("vlm_shutter").performClick()
        }
        assertEquals(List(4) { VlmIntent.OpenGallery }, events)
        assertEquals(4, photos)
        compose.runOnIdle { width.value = 600.dp }
        checkAlignment()
        compose.runOnIdle { state.value = state.value.copy(saving = true) }
        compose.onNodeWithTag("vlm_gallery_entry").assertIsNotEnabled()
        compose.onNodeWithTag("vlm_gallery_thumbnail", useUnmergedTree = true).assertDoesNotExist()
    }
}
