package com.framer.sense.feature.camera.vlm

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.ui.VlmLoadingOverlay
import com.framer.sense.feature.camera.vlm.ui.VlmStage
import com.framer.sense.feature.camera.vlm.ui.CompositionDescriptions
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 验证导演加载遮罩展示当前真实步骤，而不是旧的通用固定文案。 */
class VlmCameraScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun loadingOverlayShowsCurrentDirectorStepInsteadOfGenericCopy() {
        composeRule.setContent {
            MaterialTheme {
                VlmLoadingOverlay(
                    VlmStage.GENERATING,
                    DirectorProgress.ValidatingResponse(2),
                    onCancel = {}
                )
            }
        }

        composeRule.onNodeWithTag("vlm_director_progress").assertTextContains("正在解析并校验模型结果（第 2 轮）")
        composeRule.onNodeWithText("导演正在分析与校验").assertDoesNotExist()
    }

    @Test fun guidanceFollowsFinalPoseAndPlacementInsteadOfOriginalText() {
        val plan = mutableStateOf(CompositionPlan("p1", "全身", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(foot = Point2(.3f, .92f), poseDirective = AvatarPoseDirective(arms = ArmPose.WAVE), expression = ExpressionId.SMILE),
            "原文建议双手叉腰", "背景是花园"))
        composeRule.setContent { MaterialTheme { CompositionDescriptions(CompositionResult(listOf(plan.value), emptyList(), emptyList(), emptyList()), "p1") } }
        composeRule.onNodeWithTag("vlm_resolved_guidance").assertTextContains("画面左侧", substring = true)
            .assertTextContains("轻抬手", substring = true)
            .assertTextContains("自然微笑", substring = true)
        composeRule.runOnIdle {
            plan.value = plan.value.copy(avatar = plan.value.avatar.copy(foot = Point2(.7f, .92f),
                poseDirective = AvatarPoseDirective(arms = ArmPose.HANDS_FRONT), expressionIntensity = 0f))
        }
        composeRule.onNodeWithTag("vlm_resolved_guidance").assertTextContains("画面右侧", substring = true)
            .assertTextContains("双手身前交叠", substring = true)
            .assertTextContains("平静", substring = true)
        composeRule.onNodeWithText("原文建议双手叉腰", substring = true).assertDoesNotExist()
    }

    @Test fun actionControlsEmitLocalEventsAndDisableDuringGeneration() {
        val plan = PhotoActionResolver().resolve(CompositionPlan("p1", "全身", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(height = .6f), "原文", "背景"))
        val actions = PhotoActionCatalog().candidates(plan.avatar.avatarId, plan.shot).filter { it.selectable }
        val events = mutableListOf<VlmIntent>()
        val busy = mutableStateOf(false)
        composeRule.setContent { MaterialTheme { Column {
            PhotoActionControls(VlmUiState(availableActions = mapOf("p1" to actions), availableExpressions = mapOf("p1" to ExpressionId.entries)),
                plan, busy.value) { events += it }
        } } }
        composeRule.onNodeWithTag("vlm_action_basic.relaxed").performClick()
        composeRule.onNodeWithTag("vlm_expression_NEUTRAL").performClick()
        composeRule.onNodeWithTag("vlm_show_face").performClick()
        assertEquals(listOf(VlmIntent.ActionSelected("basic.relaxed"), VlmIntent.ExpressionSelected(ExpressionId.NEUTRAL), VlmIntent.FaceVisible(true)), events)
        composeRule.runOnIdle { busy.value = true }
        composeRule.onNodeWithTag("vlm_action_basic.relaxed").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_show_face").assertIsNotEnabled()
    }
}
