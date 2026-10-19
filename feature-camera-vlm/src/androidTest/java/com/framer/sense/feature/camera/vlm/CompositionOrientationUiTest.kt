package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 固定卡片／面板位置，仅内容转正；不访问摄像头、模型或 GPU。 */
class CompositionOrientationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixtureState() = VlmUiState(stage = VlmStage.READY, selectedId = "p1", compositionDetailsVisible = true,
        scene = SceneSnapshot("landscape", 640, 480, 1f, "/scene", "/input", orientation = PhotoOrientation(1, 0)),
        result = CompositionResult((1..3).map {
            CompositionPlan("p$it", "测试方案$it", ShotType.FULL, CropRect(), 1f, AvatarPlacement(), "自然站立", "保留建筑")
        }, emptyList(), emptyList(), emptyList()))

    @Test fun cardsAndDetailsTurnInPlaceAndKeepSelectionAndClickTargets() {
        val phone = mutableFloatStateOf(0f)
        val state = mutableStateOf(fixtureState())
        val events = mutableListOf<VlmIntent>()
        val onEvent: (VlmIntent) -> Unit = { event ->
            events += event
            if (event is VlmIntent.Select) state.value = state.value.copy(selectedId = event.id)
        }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Box(Modifier.size(360.dp, 620.dp).testTag("fixed_preview")) {
                    CameraBottomBar(state.value, onEvent, {}, true, Modifier.fillMaxSize())
                }
            }
        } }
        val fixedTags = listOf("fixed_preview", "vlm_shutter", "vlm_composition_choices", "vlm_composition_details_panel") +
            (1..3).map { "vlm_composition_p$it" }
        val bounds = fixedTags.associateWith { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        val rotatingTags = listOf("vlm_composition_details_content") + (1..3).map { "vlm_composition_content_p$it" }
        listOf(0f, 90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            fixedTags.forEach { tag -> assertEquals("$tag 转向不能改变位置或尺寸", bounds[tag], compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()) }
            rotatingTags.forEach { tag ->
                val degrees = compose.onNode(SemanticsMatcher.keyIsDefined(CameraDialogDegrees) and
                    hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNode().config[CameraDialogDegrees]
                assertEquals(0f, shortestIconRotation(degrees, direction), .1f)
            }
            compose.onNodeWithTag("vlm_composition_p3").performTouchInput { click() }
            compose.runOnIdle { assertEquals("p3", state.value.selectedId) }
            compose.onNodeWithTag("vlm_apply").performScrollTo().performTouchInput { click() }
        }
        assertEquals(5, events.count { it is VlmIntent.Select && it.id == "p3" })
        assertEquals(5, events.count { it == VlmIntent.Apply })
    }

    @Test fun landscapeDetailsUseNormalDragAndFlingAndKeepTheRevisionDraft() {
        val phone = mutableFloatStateOf(90f)
        val state = mutableStateOf(fixtureState())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Controls(state.value, { event ->
                    if (event is VlmIntent.InstructionChanged) state.value = state.value.copy(instruction = event.text)
                }, {}, Modifier.size(360.dp, 620.dp))
            }
        } }
        fun scrollPosition() = compose.onNodeWithTag("vlm_composition_details_scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        listOf(90f, 270f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            compose.onNodeWithText("测试方案1", substring = true).performScrollTo()
            val before = scrollPosition()
            val panel = compose.onNodeWithTag("vlm_composition_details_content")
            panel.performTouchInput {
                val start = Offset(width * (if (direction == 90f) .2f else .8f), height * .35f)
                val end = Offset(width * (if (direction == 90f) .8f else .2f), height * .65f)
                down(start)
                repeat(12) { step -> moveTo(start + (end - start) * ((step + 1) / 12f), delayMillis = 16) }
            }
            val dragged = scrollPosition()
            assertTrue(dragged > before)
            panel.performTouchInput { up() }
            assertTrue("松手不能反向回顶", scrollPosition() >= dragged)
        }
        compose.onNodeWithTag("vlm_director_revision_input").performScrollTo().performTextReplacement("人物靠右，保留建筑")
        listOf(0f, 90f, 180f, 270f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            compose.onNodeWithTag("vlm_director_revision_input").assertTextContains("人物靠右，保留建筑")
            assertEquals("p1", state.value.selectedId)
        }
    }
}
