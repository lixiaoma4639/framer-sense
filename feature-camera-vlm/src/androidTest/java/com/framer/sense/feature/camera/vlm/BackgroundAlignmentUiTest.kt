package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.avatar.PlanPreview
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 验证真实匹配状态的 UI 门控、四方向顶部提示及转向后的人工确认点击。 */
class BackgroundAlignmentUiTest {
    @get:Rule val compose = createComposeRule()
    private val plan = CompositionPlan("p", "参考", ShotType.FULL, CropRect(), 1f,
        AvatarPlacement(), "站立", "环境", viewBinding = PhotoViewBinding("scan", "frame", 1))

    @Test fun directionHintsAndAutoOrManualAlignmentShareTheTopPanel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val state = mutableStateOf(VlmUiState(referencePlan = plan,
            reference = PlanPreview(bitmap, bitmap, CropRect(), plan)))
        compose.setContent { MaterialTheme {
            Box(Modifier.size(280.dp, 360.dp)) {
                LiveReferenceAvatar(state.value, Modifier.fillMaxSize())
                BackgroundAlignmentOverlay(state.value, { event ->
                    if (event == VlmIntent.ConfirmBackground)
                        state.value = state.value.copy(background = state.value.background.copy(status = BackgroundStatus.ALIGNED_MANUAL))
                })
            }
        } }
        listOf(
            BackgroundGuidance(headingError = 10f) to R.string.vlm_background_right,
            BackgroundGuidance(headingError = -10f) to R.string.vlm_background_left,
            BackgroundGuidance(pitchError = 10f) to R.string.vlm_background_up,
            BackgroundGuidance(pitchError = -10f) to R.string.vlm_background_down,
            // 初次匹配成功还要经过稳定性验证，不能提前声明已对齐或显示人像。
            BackgroundGuidance(matched = true) to R.string.vlm_background_waiting
        ).forEach { (guidance, resource) ->
            compose.runOnIdle { state.value = state.value.copy(background = guidance) }
            compose.onNodeWithTag("vlm_background_status").assertTextEquals(context.getString(resource))
            compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
            compose.onNodeWithTag("vlm_background_confirm").assertIsEnabled()
                .assertTextEquals(context.getString(R.string.vlm_background_confirm))
                .assert(hasAnyAncestor(hasTestTag("vlm_background_confirmation_group")))
            compose.onNodeWithTag("vlm_background_instructions")
                .assertTextEquals(context.getString(R.string.vlm_background_searching))
                .assert(hasAnyAncestor(hasTestTag("vlm_background_confirmation_group")))
        }
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO)) }
        compose.onNodeWithTag("vlm_background_status").assertTextEquals(context.getString(R.string.vlm_reference_ready))
        compose.onNodeWithTag("vlm_live_reference_avatar").assertExists()
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance()) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_confirm").performTouchInput { click() }
        compose.onNodeWithTag("vlm_background_status").assertTextEquals(context.getString(R.string.vlm_reference_ready))
        compose.onNodeWithTag("vlm_live_reference_avatar").assertExists()
    }

    @Test fun topPanelTurnsInFourDirectionsWithoutMovingThePreviewAndConfirmationStillWorks() {
        val phone = mutableFloatStateOf(0f)
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Box(Modifier.size(280.dp, 360.dp).testTag("fixed_preview")) {
                    BackgroundAlignmentOverlay(VlmUiState(referencePlan = plan), events::add)
                }
            }
        } }
        val original = compose.onNodeWithTag("fixed_preview").getUnclippedBoundsInRoot()
        listOf(0f, 90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            val rotation = compose.onNode(SemanticsMatcher.keyIsDefined(CameraDialogDegrees) and
                hasAnyAncestor(hasTestTag("vlm_background_viewport")), useUnmergedTree = true)
                .fetchSemanticsNode().config[CameraDialogDegrees]
            assertEquals(0f, shortestIconRotation(rotation, direction), .1f)
            assertEquals(original, compose.onNodeWithTag("fixed_preview").getUnclippedBoundsInRoot())
            val panel = compose.onNodeWithTag("vlm_background_panel").getUnclippedBoundsInRoot()
            val edge = when (direction) {
                90f -> original.right.value - panel.right.value
                180f -> original.bottom.value - panel.bottom.value
                270f -> panel.left.value - original.left.value
                else -> panel.top.value - original.top.value
            }
            assertEquals(12f, edge, 1f)
            compose.onNodeWithTag("vlm_background_confirm").performTouchInput { click() }
        }
        assertEquals(List(5) { VlmIntent.ConfirmBackground }, events)
    }

    @Test fun automaticAndManualConfirmationShowOnlyTheSameRotatingCapturePrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phone = mutableFloatStateOf(0f)
        val state = mutableStateOf(VlmUiState(referencePlan = plan,
            background = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO)))
        lateinit var typography: androidx.compose.material3.Typography
        compose.setContent { MaterialTheme {
            typography = MaterialTheme.typography
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Box(Modifier.size(280.dp, 360.dp)) {
                    BackgroundAlignmentOverlay(state.value, {})
                    Controls(state.value, {}, {}, Modifier.align(Alignment.BottomCenter))
                }
            }
        } }
        listOf(0f, 90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            val rotation = compose.onNode(SemanticsMatcher.keyIsDefined(CameraDialogDegrees) and
                hasAnyAncestor(hasTestTag("vlm_background_viewport")), useUnmergedTree = true)
                .fetchSemanticsNode().config[CameraDialogDegrees]
            assertEquals(0f, shortestIconRotation(rotation, direction), .1f)
            listOf(BackgroundStatus.ALIGNED_AUTO, BackgroundStatus.ALIGNED_MANUAL).forEach { status ->
                compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance(status)) }
                val node = compose.onNodeWithTag("vlm_background_status")
                node.assertTextEquals(context.getString(R.string.vlm_reference_ready))
                node.assert(hasAnyAncestor(hasTestTag("vlm_background_panel")))
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                    hasAnyAncestor(hasTestTag("vlm_background_panel")), useUnmergedTree = true).assertCountEquals(1)
                compose.onNodeWithTag("vlm_reference_notice").assertDoesNotExist()
                compose.onNodeWithTag("vlm_reference_guidance").assertDoesNotExist()
                compose.onNodeWithTag("vlm_background_confirm").assertDoesNotExist()
                compose.onNodeWithTag("vlm_ar_screen_reference").assertDoesNotExist()
                compose.onNodeWithText("按参考调整站位；移动机位后请重新构图").assertDoesNotExist()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(typography.bodyMedium.fontSize, layouts.single().layoutInput.style.fontSize)
                assertFalse(layouts.single().hasVisualOverflow)
            }
        }
        compose.runOnIdle { state.value = state.value.copy(compositionDetailsVisible = true) }
        compose.onNodeWithTag("vlm_background_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_reference_guidance").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.vlm_reference_ready)).assertDoesNotExist()
        // 无背景绑定的旧单图参考也不重新展示长指导。
        compose.runOnIdle { state.value = state.value.copy(compositionDetailsVisible = false,
            referencePlan = plan.copy(viewBinding = null), background = BackgroundGuidance()) }
        compose.onNodeWithTag("vlm_background_status").assertTextEquals(context.getString(R.string.vlm_reference_ready))
        compose.onNodeWithTag("vlm_reference_guidance").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_confirm").assertDoesNotExist()
    }
}
