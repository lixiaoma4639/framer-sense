package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.model.SceneSnapshot
import com.framer.sense.feature.camera.vlm.model.AvatarPlacement
import com.framer.sense.feature.camera.vlm.model.CompositionPlan
import com.framer.sense.feature.camera.vlm.model.CompositionResult
import com.framer.sense.feature.camera.vlm.model.CropRect
import com.framer.sense.feature.camera.vlm.model.ShotType
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class CameraWorkflowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionsStayCenteredForEveryCountWithSelectionIconOnRight() {
        val state = mutableStateOf(VlmUiState(cameraReady = true))
        val width = mutableStateOf(320.dp)
        val angle = mutableFloatStateOf(0f)
        val plan = CompositionPlan("p1", "测试构图", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(), "指导", "背景")
        val result = CompositionResult(listOf(plan), emptyList(), emptyList(), emptyList())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                Box(Modifier.width(width.value).fillMaxHeight().testTag("camera_bounds")) {
                    CameraBottomBar(state.value, {}, {}, true, Modifier.align(Alignment.BottomCenter))
                    CameraWorkflowFooter(state.value, 24.dp, Modifier.align(Alignment.BottomCenter))
                }
            }
        } }
        fun assertCentered(first: String, last: String) {
            val parent = compose.onNodeWithTag("camera_bounds").getUnclippedBoundsInRoot()
            val firstButton = compose.onNodeWithTag(first).getUnclippedBoundsInRoot()
            val lastButton = compose.onNodeWithTag(last).getUnclippedBoundsInRoot()
            assertEquals((parent.left.value + parent.right.value) / 2f,
                (firstButton.left.value + lastButton.right.value) / 2f, 1f)
        }
        listOf(320.dp, 600.dp).forEach { availableWidth ->
            compose.runOnIdle {
                width.value = availableWidth
                state.value = VlmUiState(cameraReady = true)
            }
            assertCentered("vlm_scan_start", "vlm_start")
            // 构图生成后保留重新开始与构图选择，仍然整体居中。
            compose.runOnIdle { state.value = state.value.copy(result = result) }
            listOf(0f, 90f, 180f, 270f).forEach { direction ->
                compose.runOnIdle { angle.floatValue = direction }
                assertCentered("vlm_restart_flow", "vlm_details")
                compose.onNodeWithTag("vlm_start").assertDoesNotExist()
                val label = compose.onNodeWithTag("vlm_details_label", useUnmergedTree = true).getUnclippedBoundsInRoot()
                val icon = compose.onNodeWithTag("vlm_details_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("构图选择图标位于文字右侧", icon.left >= label.right)
            }
            compose.runOnIdle { state.value = state.value.copy(referencePlan = plan) }
            assertCentered("vlm_restart_flow", "vlm_details")
            compose.runOnIdle { state.value = VlmUiState(stage = VlmStage.SCANNING) }
            assertCentered("vlm_restart_flow", "vlm_scan_stop")
        }
    }

    @Test fun crowdedActionRowStillScrollsToEveryAction() {
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            Box(Modifier.width(220.dp)) {
                Controls(VlmUiState(stage = VlmStage.SCAN_REVIEW), events::add, {}, Modifier.fillMaxWidth())
            }
        } }
        compose.onNodeWithTag("vlm_restart_flow").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("vlm_scan_manual_open").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(VlmIntent.RestartFlow, VlmIntent.CompositionDetailsVisible(true)), events)
    }

    @Test fun timelineStaysBelowShutterAndTurnsIconsWithoutMovingControls() {
        val angle = mutableFloatStateOf(0f)
        val showTimeline = mutableStateOf(false)
        val bottomSpace = mutableStateOf(24.dp)
        var photos = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                Box(Modifier.size(320.dp, 640.dp).background(Color.Black).testTag("camera_bounds")) {
                    Box(Modifier.fillMaxSize().padding(bottom = bottomSpace.value)) {
                        CameraBottomBar(VlmUiState(cameraReady = true), {}, { photos++ }, true,
                            Modifier.align(Alignment.BottomCenter))
                        if (showTimeline.value) CameraWorkflowFooter(VlmUiState(), bottomSpace.value,
                            Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        } }
        val shutter = compose.onNodeWithTag("vlm_shutter").getUnclippedBoundsInRoot()
        compose.runOnIdle { showTimeline.value = true }
        // 与未添加时间线的原快门位置直接比较，不能只检查旋转前后位置一致。
        assertEquals(shutter, compose.onNodeWithTag("vlm_shutter").getUnclippedBoundsInRoot())
        val timeline = compose.onNodeWithTag("vlm_workflow_timeline").getUnclippedBoundsInRoot()
        val screen = compose.onNodeWithTag("camera_bounds").getUnclippedBoundsInRoot()
        assertTrue(timeline.top >= shutter.bottom)
        assertTrue(timeline.bottom <= screen.bottom)
        assertEquals(screen.bottom.value - 1f, timeline.bottom.value, 1f)
        val nodeBounds = CameraWorkflowStep.entries.associateWith {
            compose.onNodeWithTag("vlm_workflow_${it.name}").getUnclippedBoundsInRoot()
        }
        listOf(90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            CameraWorkflowStep.entries.forEach { step ->
                compose.onNodeWithTag("vlm_workflow_icon_${step.name}", useUnmergedTree = true)
                    .assert(SemanticsMatcher("流程图标四方向转正") {
                        abs(shortestIconRotation(it.config[CameraIconDegrees], direction)) < .1f
                    })
                assertEquals(nodeBounds[step], compose.onNodeWithTag("vlm_workflow_${step.name}").getUnclippedBoundsInRoot())
            }
            assertEquals(shutter, compose.onNodeWithTag("vlm_shutter").getUnclippedBoundsInRoot())
            assertEquals(timeline, compose.onNodeWithTag("vlm_workflow_timeline").getUnclippedBoundsInRoot())
            compose.onNodeWithTag("vlm_shutter").performClick()
        }
        assertEquals(4, photos)
        // 底部留白变化时仍在原快门与屏幕底部之间，不能反过来顶高快门。
        listOf(0.dp, 16.dp, 48.dp).forEach { space ->
            compose.runOnIdle { bottomSpace.value = space }
            val currentShutter = compose.onNodeWithTag("vlm_shutter").getUnclippedBoundsInRoot()
            val currentTimeline = compose.onNodeWithTag("vlm_workflow_timeline").getUnclippedBoundsInRoot()
            assertEquals(screen.bottom.value - space.value - 6f, currentShutter.bottom.value, 1f)
            assertTrue(currentTimeline.top >= currentShutter.bottom)
            assertTrue(currentTimeline.bottom <= screen.bottom)
        }
    }

    @Test fun scanSpinnerBecomesGreenAndGenerationSpinnerIsWhite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = mutableStateOf(VlmUiState(stage = VlmStage.SCANNING))
        compose.setContent { MaterialTheme {
            CameraWorkflowTimeline(state.value, Modifier.background(Color.Black))
        } }
        compose.onNodeWithTag("vlm_workflow_SCAN").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, context.getString(R.string.vlm_workflow_running)))
        compose.onNodeWithTag("vlm_workflow_loading_SCAN", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("vlm_workflow_icon_SCAN", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            state.value = VlmUiState(stage = VlmStage.GENERATING,
                scene = SceneSnapshot("scene", 480, 640, 1f, "photo", "model"))
        }
        compose.onNodeWithTag("vlm_workflow_SCAN").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, context.getString(R.string.vlm_workflow_complete)))
        val green = compose.onNodeWithTag("vlm_workflow_icon_SCAN", useUnmergedTree = true).captureToImage().toPixelMap()
        var greenPixels = 0
        for (y in 0 until green.height) for (x in 0 until green.width) {
            val color = green[x, y]
            if (color.green > .5f && color.green > color.red + .1f) greenPixels++
        }
        assertTrue("扫描完成图标应为绿色", greenPixels > 0)
        val white = compose.onNodeWithTag("vlm_workflow_loading_GENERATE", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        var whitePixels = 0
        for (y in 0 until white.height) for (x in 0 until white.width) {
            val color = white[x, y]
            assertTrue(abs(color.red - color.green) < .03f && abs(color.green - color.blue) < .03f)
            if (color.red > .85f) whitePixels++
        }
        assertTrue("执行中的进度圈应为白色", whitePixels > 0)
        compose.onNodeWithTag("vlm_workflow_SELECT").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, context.getString(R.string.vlm_workflow_pending)))
        compose.runOnIdle { state.value = VlmUiState() }
        compose.onNodeWithTag("vlm_workflow_loading_GENERATE", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("vlm_workflow_SCAN").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, context.getString(R.string.vlm_workflow_pending)))
    }
}
