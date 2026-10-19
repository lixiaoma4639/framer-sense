package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTrackerAddPointsFix
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.market.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 只验证弹窗布局、输入状态和点击映射，不访问相机、网络或 GPU。 */
class CameraAdaptiveDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rotatedSettingsDragAndFlingKeepTheSameDirection() {
        checkDragAndFling(lazy = false)
    }

    @Test fun rotatedModelListDragAndFlingKeepTheSameDirection() {
        checkDragAndFling(lazy = true)
    }

    /** 使用真实触摸并在松手前后比较位置，语义 ScrollTo 无法暴露惯性坐标错误。 */
    private fun checkDragAndFling(lazy: Boolean) {
        val angle = mutableFloatStateOf(0f)
        val scroll = ScrollState(0)
        val list = LazyListState()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                CameraRotatingViewport(Modifier.size(300.dp, 500.dp).testTag("gesture_viewport")) {
                    if (lazy) {
                        LazyColumn(Modifier.fillMaxSize(), state = list) {
                            items(100) { index -> Text("测试模型 $index", Modifier.fillMaxWidth().height(60.dp)) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            repeat(100) { index -> Text("测试设置 $index", Modifier.fillMaxWidth().height(60.dp)) }
                        }
                    }
                }
            }
        } }
        fun position() = if (lazy) list.firstVisibleItemIndex * 100_000 + list.firstVisibleItemScrollOffset else scroll.value
        // 有意保留少量横向移动：旧算法会把它当成横屏列表的纵向惯性并反向甩回顶部。
        listOf(0f, 90f, 180f, 270f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            val viewport = compose.onNodeWithTag("gesture_viewport")
            var before = 0
            compose.runOnIdle { before = position() }
            viewport.performTouchInput {
                val start: Offset
                val end: Offset
                when (direction) {
                    90f -> { start = Offset(width * .2f, height * .35f); end = Offset(width * .8f, height * .65f) }
                    180f -> { start = Offset(width * .6f, height * .2f); end = Offset(width * .4f, height * .8f) }
                    270f -> { start = Offset(width * .8f, height * .35f); end = Offset(width * .2f, height * .65f) }
                    else -> { start = Offset(width * .4f, height * .8f); end = Offset(width * .6f, height * .2f) }
                }
                down(start)
                repeat(12) { step -> moveTo(start + (end - start) * ((step + 1) / 12f), delayMillis = 16) }
            }
            var dragged = 0
            compose.runOnIdle {
                dragged = position()
                assertTrue("$direction 度拖动应向列表下方移动", dragged > before)
            }
            viewport.performTouchInput { up() }
            compose.runOnIdle {
                assertTrue("$direction 度松手惯性应延续拖动方向，不应反向回顶", position() > dragged)
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnIdle { assertTrue("静置后不应自动回顶", position() >= dragged) }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun velocityCompatibilityRestoresOriginalFlagAfterLastViewportDisposes() {
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        val original = VelocityTrackerAddPointsFix
        try {
            // 分别验证原开关为 true / false，且先退出第一个弹窗时不能提前恢复。
            compose.setContent {
                Column {
                    if (first.value) CameraRotatingViewport(Modifier.size(100.dp)) {}
                    if (second.value) CameraRotatingViewport(Modifier.size(100.dp)) {}
                }
            }
            listOf(true, false).forEach { previous ->
                compose.runOnIdle { VelocityTrackerAddPointsFix = previous; first.value = true }
                compose.runOnIdle { assertFalse(VelocityTrackerAddPointsFix); second.value = true }
                compose.runOnIdle { first.value = false }
                compose.runOnIdle { assertFalse(VelocityTrackerAddPointsFix); second.value = false }
                compose.runOnIdle { assertEquals(previous, VelocityTrackerAddPointsFix) }
            }
        } finally {
            compose.runOnIdle { first.value = false; second.value = false }
            compose.runOnIdle { VelocityTrackerAddPointsFix = original }
        }
    }

    @Test fun rotatingViewportSwapsLogicalDimensionsAndKeepsTheSameContentAndTouchTarget() {
        val angle = mutableFloatStateOf(0f)
        var measured = IntSize.Zero
        val instances = mutableSetOf<Any>()
        var clicks = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                CameraRotatingViewport(Modifier.size(200.dp, 300.dp)) {
                    val instance = remember { Any() }
                    SideEffect { instances += instance }
                    Box(Modifier.fillMaxSize().onSizeChanged { measured = it }) {
                        Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.TopStart).testTag("corner_action")) {
                            Text("测试操作")
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val original = measured
        listOf(0f, 90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            compose.onNodeWithTag("corner_action").performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(if (direction % 180f == 0f) original else IntSize(original.height, original.width), measured)
                assertEquals(1, instances.size)
            }
        }
        assertEquals(5, clicks)
    }

    @Test fun dialogRotationPreservesDraftSelectionFooterAndBackgroundBounds() {
        val angle = mutableFloatStateOf(0f)
        var confirmed = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().testTag("fixed_camera_background"))
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                CameraAdaptiveAlertDialog(onDismissRequest = {}, title = { Text("测试设置") }, text = {
                    var draft by remember { mutableStateOf(TextFieldValue()) }
                    OutlinedTextField(draft, { draft = it }, modifier = Modifier.testTag("dialog_draft"))
                }, confirmButton = {
                    TextButton(onClick = { confirmed++ }, modifier = Modifier.testTag("dialog_confirm")) { Text("确认") }
                })
            }
        } }
        compose.onNodeWithTag("dialog_draft").performTextInput("保留建筑与环境")
            .performTextInputSelection(TextRange(1, 3))
        val background = compose.onNodeWithTag("fixed_camera_background").getUnclippedBoundsInRoot()
        listOf(90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            compose.onNodeWithTag("dialog_draft").assertTextContains("保留建筑与环境")
            assertEquals(TextRange(1, 3), compose.onNodeWithTag("dialog_draft").fetchSemanticsNode()
                .config[SemanticsProperties.TextSelectionRange])
            compose.onNodeWithTag("dialog_confirm").assertIsDisplayed()
            assertEquals(background, compose.onNodeWithTag("fixed_camera_background").getUnclippedBoundsInRoot())
        }
        compose.onNodeWithTag("dialog_confirm").performTouchInput { click() }
        assertEquals(1, confirmed)
    }

    @Test fun nestedFilterFollowsTheSameDirectionAndRetainsSelection() {
        val angle = mutableFloatStateOf(0f)
        val filter = mutableStateOf(MarketFilter())
        val models = listOf(MarketModel("Qwen-VL", "Qwen", listOf("Vision"), emptyMap()))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                CameraAdaptiveDialog(onDismissRequest = {}, fullScreen = true) {
                    ModelMarketContent(MarketUiState(models = models, allModels = models, filter = filter.value),
                        false, ModelMode.OFFLINE, {}, { filter.value = it }, {}, {}, { _, _ -> }, {}, {}, {}, {})
                }
            }
        } }
        compose.onNodeWithTag("vlm_market_filter_open").performClick()
        compose.onNodeWithTag("vlm_market_vendor_qwen").performClick().assertIsSelected()
        listOf(90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            compose.onNodeWithTag("vlm_market_vendor_qwen").assertIsSelected()
            val panels = compose.onAllNodes(SemanticsMatcher.keyIsDefined(CameraDialogDegrees), useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue(panels.isNotEmpty())
            panels.forEach { assertEquals(0f, shortestIconRotation(it.config[CameraDialogDegrees], direction), .1f) }
        }
        compose.onNodeWithText("完成").performTouchInput { click() }
        compose.onNodeWithTag("vlm_market_filter_sheet").assertDoesNotExist()
        assertEquals("Qwen", filter.value.vendor)
    }

    @Test fun unsavedModelModeSurvivesRotationAndStillSavesTheSameDraft() {
        val angle = mutableFloatStateOf(0f)
        val initial = ModelSettings(qwenAccess = QwenAccess.GATEWAY)
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                ModelSettingsDialog(VlmUiState(settings = initial), events::add, {}, {})
            }
        } }
        compose.onNodeWithTag("vlm_model_mode_AUTO").performClick()
        listOf(90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            compose.onNodeWithTag("vlm_model_mode_AUTO").assertIsSelected()
        }
        compose.onNodeWithText("保存").assertIsDisplayed().performTouchInput { click() }
        assertEquals(listOf(VlmIntent.SaveSettings(initial.copy(mode = ModelMode.AUTO))), events)
    }

    @Test fun dialogIconsAreNotRotatedTwice() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides 90f) {
                CameraAdaptiveDialog(onDismissRequest = {}) {
                    Box(Modifier.size(48.dp).testTag("inside_icon").cameraIconRotation())
                }
            }
        } }
        compose.onNodeWithTag("inside_icon").assert(SemanticsMatcher.expectValue(CameraIconDegrees, 0f))
        compose.onNode(SemanticsMatcher.keyIsDefined(CameraDialogDegrees), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(CameraDialogDegrees, 90f))
    }

    @Test fun aiEditorAndItsDeleteIconKeepTheOriginalDirectionAndDraft() {
        val angle = mutableFloatStateOf(0f)
        val state = mutableStateOf(VlmUiState(instructionEditorVisible = true, instructionHistory = listOf("保留环境")))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides angle.floatValue) {
                CompositionInstructionOverlay(state.value, { event ->
                    if (event is VlmIntent.InstructionDraftChanged) state.value = state.value.copy(instructionDraft = event.text)
                }, Modifier.fillMaxSize())
            }
        } }
        compose.onNodeWithTag("vlm_ai_instruction_input").performTextReplacement("人物靠右")
        val bounds = compose.onNodeWithTag("vlm_ai_instruction_panel").getUnclippedBoundsInRoot()
        listOf(90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { angle.floatValue = direction }
            compose.onNodeWithTag("vlm_ai_instruction_input").assertTextContains("人物靠右")
            assertEquals(bounds, compose.onNodeWithTag("vlm_ai_instruction_panel").getUnclippedBoundsInRoot())
            compose.onNode(SemanticsMatcher.keyIsDefined(CameraIconDegrees) and
                hasAnyAncestor(hasTestTag("vlm_ai_history_delete_0")), useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(CameraIconDegrees, 0f))
        }
    }

    @Test fun compactLandscapeMarketCanScrollToModelsAndOpenRotatedDetails() {
        val filter = mutableStateOf(MarketFilter())
        val model = MarketModel("Qwen-test-VL", "Qwen", listOf("Vision"), emptyMap())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides 90f) {
                CameraRotatingViewport(Modifier.size(300.dp, 500.dp)) {
                    ModelMarketContent(MarketUiState(models = listOf(model), allModels = listOf(model), filter = filter.value),
                        false, ModelMode.OFFLINE, {}, { filter.value = it }, {}, {}, { _, _ -> }, {}, {}, {}, {})
                }
            }
        } }
        compose.onNodeWithTag("vlm_market_search").performTextReplacement("Qwen")
        compose.onNodeWithTag("vlm_market_list").performScrollToNode(hasTestTag("vlm_market_model_${model.id}"))
        compose.onNodeWithTag("vlm_market_model_${model.id}").assertIsDisplayed().performTouchInput { click() }
        assertEquals("Qwen", filter.value.search)
        val panels = compose.onAllNodes(SemanticsMatcher.keyIsDefined(CameraDialogDegrees), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(panels.size >= 2)
        panels.forEach { assertEquals(0f, shortestIconRotation(it.config[CameraDialogDegrees], 90f), .1f) }
    }
}
