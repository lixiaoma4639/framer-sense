package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** UI 回归，不访问摄像头、模型或 GPU 人像渲染。 */
class CameraLoadingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scanSelectionAndDirectorProgressShareOneBlackPanel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stage = mutableStateOf(VlmStage.SCAN_PROCESSING)
        val progress = DirectorProgress.ValidatingResponse(2)
        var cancellations = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VlmLoadingOverlay(stage.value, progress, { cancellations++ })
                Controls(VlmUiState(stage = stage.value), {}, {}, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        } }
        listOf(
            VlmStage.SCAN_PROCESSING to context.getString(R.string.vlm_scan_finishing),
            VlmStage.VIEW_SELECTING to context.getString(R.string.vlm_scan_selecting),
            VlmStage.FREEZING to context.getString(R.string.vlm_camera_freezing),
            VlmStage.GENERATING to context.getString(R.string.vlm_director_validating, 2)
        ).forEach { (nextStage, label) ->
            compose.runOnIdle { stage.value = nextStage }
            compose.onAllNodesWithText(label).assertCountEquals(1)
            compose.onNodeWithTag("vlm_director_progress").assertTextEquals(label)
                .assert(hasAnyAncestor(hasTestTag("vlm_loading_panel")))
            compose.onNodeWithTag("vlm_loading_cancel").performTouchInput { click() }
        }
        assertEquals(4, cancellations)
    }

    @Test fun loadingTurnsInFourDirectionsWithWhiteSpinnerAndCancelAndFixedBackground() {
        val phone = mutableFloatStateOf(0f)
        var cancellations = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Box(Modifier.size(320.dp, 480.dp).background(Color.Black).testTag("fixed_loading_background")) {
                    VlmLoadingOverlay(VlmStage.VIEW_SELECTING, null, { cancellations++ })
                }
            }
        } }
        val viewport = compose.onNodeWithTag("vlm_loading_viewport")
        val viewportBounds = viewport.getUnclippedBoundsInRoot()
        val background = compose.onNodeWithTag("fixed_loading_background").getUnclippedBoundsInRoot()
        listOf(0f, 90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            val rotation = compose.onNode(SemanticsMatcher.keyIsDefined(CameraDialogDegrees) and
                hasAnyAncestor(hasTestTag("vlm_loading_viewport")), useUnmergedTree = true)
                .fetchSemanticsNode().config[CameraDialogDegrees]
            assertEquals(0f, shortestIconRotation(rotation, direction), .1f)
            assertEquals(viewportBounds, viewport.getUnclippedBoundsInRoot())
            assertEquals(background, compose.onNodeWithTag("fixed_loading_background").getUnclippedBoundsInRoot())
            listOf("vlm_loading_spinner", "vlm_loading_cancel").forEach { tag ->
                val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                var whitePixels = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    assertTrue("$tag 不应显示主题彩色", abs(color.red - color.green) < .03f && abs(color.green - color.blue) < .03f)
                    if (color.red > .85f) whitePixels++
                }
                assertTrue("$tag 应有白色像素", whitePixels > 0)
            }
            compose.onNodeWithTag("vlm_loading_cancel").performTouchInput { click() }
        }
        assertEquals(5, cancellations)
    }

    @Test fun compositionToggleIconStaysFixedWithItsTextAndStillToggles() {
        val phone = mutableFloatStateOf(0f)
        val plan = CompositionPlan("p", "构图", ShotType.FULL, CropRect(), 1f, AvatarPlacement(), "自然站立", "背景")
        val state = VlmUiState(stage = VlmStage.READY, selectedId = "p",
            result = CompositionResult(listOf(plan), emptyList(), emptyList(), emptyList()))
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Box(Modifier.fillMaxWidth().background(Color.Black)) {
                    Controls(state, events::add, {}, Modifier.fillMaxWidth())
                }
            }
        } }
        val icon = compose.onNodeWithTag("vlm_details_icon", useUnmergedTree = true)
        fun pixels(): List<Color> {
            val image = icon.captureToImage().toPixelMap()
            return List(image.width * image.height) { image[it % image.width, it / image.width] }
        }
        val originalPixels = pixels()
        val bounds = compose.onNodeWithTag("vlm_details").getUnclippedBoundsInRoot()
        listOf(90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            assertEquals(originalPixels, pixels())
            assertEquals(bounds, compose.onNodeWithTag("vlm_details").getUnclippedBoundsInRoot())
        }
        compose.onNodeWithTag("vlm_details").performTouchInput { click() }
        assertEquals(listOf(VlmIntent.CompositionDetailsVisible(true)), events)
    }
}
