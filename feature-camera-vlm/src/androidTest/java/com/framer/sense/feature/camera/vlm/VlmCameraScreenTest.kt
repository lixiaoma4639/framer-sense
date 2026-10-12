package com.framer.sense.feature.camera.vlm

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
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

    @Test fun exitButtonWorksDuringGenerationAndIsDisabledWhileSaving() {
        val state = mutableStateOf(VlmUiState(stage = VlmStage.GENERATING))
        var exits = 0
        composeRule.setContent { MaterialTheme { CameraTopBar(state.value, {}, onExit = { exits++ }) } }
        composeRule.onNodeWithTag("vlm_camera_exit").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, exits); state.value = state.value.copy(saving = true) }
        composeRule.onNodeWithTag("vlm_camera_exit").assertIsNotEnabled()
    }

    @Test fun systemBackCancelsBeforeExitingAndCannotInterruptSaving() {
        val state = mutableStateOf(VlmUiState(stage = VlmStage.GENERATING))
        val events = mutableListOf<VlmIntent>()
        var exits = 0
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CameraBackHandler(state.value, { events += it }, { exits++ })
        }
        composeRule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        assertEquals(listOf(VlmIntent.Cancel), events)
        assertEquals(0, exits)
        composeRule.runOnIdle { state.value = VlmUiState(saving = true) }
        composeRule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        assertEquals(0, exits)
        composeRule.runOnIdle { state.value = VlmUiState() }
        composeRule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        assertEquals(1, exits)
    }

    @Test fun independentShutterAndTwoSettingsUseDedicatedEvents() {
        val state = mutableStateOf(VlmUiState(cameraReady = true))
        val events = mutableListOf<VlmIntent>()
        var photos = 0
        composeRule.setContent { MaterialTheme { Column {
            CameraTopBar(state.value, { events += it })
            CameraBottomBar(state.value, { events += it }, { photos++ }, true)
        } } }
        composeRule.onNodeWithTag("vlm_actual_zoom").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_shutter").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("vlm_model_settings").performClick()
        composeRule.onNodeWithTag("vlm_photo_settings").performClick()
        assertEquals(1, photos)
        assertEquals(listOf(VlmIntent.SettingsVisible(true), VlmIntent.PhotoSettingsVisible(true)), events)
        composeRule.onNodeWithText("AI 构图导演").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_sample_open").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(stage = VlmStage.SCANNING) }
        composeRule.onNodeWithTag("vlm_shutter").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_photo_settings").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_aspect_ratio").assertIsNotEnabled()
    }

    @Test fun photoSettingsOwnsRoleSampleAndFaceOptions() {
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { PhotoSettingsDialog(VlmUiState(), { events += it }, {}) } }
        composeRule.onNodeWithTag("vlm_photo_settings_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("vlm_preferred_ethnicity_ASIAN").performClick()
        assertEquals(listOf(VlmIntent.PreferredEthnicity(AvatarEthnicity.ASIAN)), events)
        composeRule.onNodeWithText("AI 构图导演").assertDoesNotExist()
    }
    @Test fun arSwitchCanBeEnabledAfterCompositionIsReady() {
        val events=mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { PhotoSettingsDialog(VlmUiState(stage=VlmStage.READY),{ events+=it },{}) } }
        composeRule.onNodeWithTag("vlm_ar_setting").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(VlmIntent.CameraModeSelected(CameraMode.AR)),events)
    }

    @Test fun modelSettingsRetainsItsOwnDialog() {
        composeRule.setContent { MaterialTheme { ModelSettingsDialog(VlmUiState(), {}, {}, {}) } }
        composeRule.onNodeWithTag("vlm_model_settings_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("vlm_photo_settings_dialog").assertDoesNotExist()
    }

    @Test fun ratioLabelFollowsLandscapeWithoutChangingPreference() {
        val configuration = android.content.res.Configuration().apply { orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides configuration) {
                MaterialTheme { CameraTopBar(VlmUiState(photoPreferences = PhotoPreferences(CaptureAspectRatio.NINE_SIXTEEN)), {}) }
            }
        }
        composeRule.onNodeWithText("16:9").assertIsDisplayed()
    }

    @Test fun aspectMenuUsesMviAndDoesNotOpenPhotoSettings() {
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { CameraTopBar(VlmUiState(), { events += it }) } }
        composeRule.onNodeWithTag("vlm_aspect_ratio").performClick()
        composeRule.onNodeWithTag("vlm_ratio_NINE_SIXTEEN").performClick()
        assertEquals(listOf(VlmIntent.AspectRatioSelected(CaptureAspectRatio.NINE_SIXTEEN)), events)
        composeRule.onNodeWithTag("vlm_photo_settings_dialog").assertDoesNotExist()
    }

    @Test fun restartActionsRemainAvailableDuringGenerationButNotModelManagement() {
        val state = mutableStateOf(VlmUiState(stage = VlmStage.GENERATING,
            scene = SceneSnapshot("scene",640,480,1f,"/scene","/input")))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { Controls(state.value,{ events += it },{},Modifier.fillMaxWidth()) } }
        composeRule.onNodeWithTag("vlm_generate").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("vlm_scan_restart_active").assertIsEnabled().performClick()
        assertEquals(listOf(VlmIntent.Generate,VlmIntent.StartScan), events)
        composeRule.runOnIdle { state.value = state.value.copy(modelBusy = true) }
        composeRule.onNodeWithTag("vlm_generate").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_scan_restart_active").assertIsNotEnabled()
    }

    @Test fun compositionSelectionCollapsesTogetherAndDetailsFollowSelectedPlan() {
        val plans = (1..3).map { CompositionPlan("p$it", "测试标题$it", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(), "指导", "背景") }
        val state = mutableStateOf(VlmUiState(stage = VlmStage.READY, selectedId = "p1",
            compositionDetailsVisible = true, result = CompositionResult(plans, emptyList(), emptyList(), emptyList())))
        val events = mutableListOf<VlmIntent>()
        val onEvent: (VlmIntent) -> Unit = { event ->
            events += event
            when (event) {
                is VlmIntent.Select -> state.value = state.value.copy(selectedId = event.id)
                is VlmIntent.CompositionDetailsVisible -> state.value = state.value.copy(compositionDetailsVisible = event.visible)
                else -> Unit
            }
        }
        composeRule.setContent { MaterialTheme {
            Controls(state.value, onEvent, {}, Modifier.fillMaxWidth().height(620.dp))
        } }
        plans.forEach { composeRule.onNodeWithTag("vlm_composition_${it.id}").assertIsDisplayed() }
        val rowWidth = composeRule.onNodeWithTag("vlm_composition_choices").getUnclippedBoundsInRoot().width
        val card = composeRule.onNodeWithTag("vlm_composition_p1").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(card.width < rowWidth / 3)
        org.junit.Assert.assertTrue(card.height == 88.dp || card.height == 52.dp)
        composeRule.onNodeWithTag("vlm_composition_p3").performClick()
        composeRule.onNodeWithText("测试标题3", substring = true).assertExists()
        composeRule.onNodeWithText("测试标题1", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_apply").performScrollTo().performClick()
        assertEquals(listOf(VlmIntent.Select("p3"), VlmIntent.Apply), events)
        composeRule.onNodeWithTag("vlm_details").performClick()
        composeRule.onNodeWithTag("vlm_composition_choices").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_apply").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_details").performClick()
        composeRule.onNodeWithTag("vlm_composition_p3").assertIsDisplayed()
        composeRule.onNodeWithText("测试标题3", substring = true).assertExists()
    }

    @Test fun sampleDialogUsesDedicatedEventsAndDisablesExpressionWhileRendering() {
        val sample = mutableStateOf(PhotoSampleState("sample", loading = true))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { PhotoSampleDialog(sample.value) { events += it } } }
        composeRule.onNodeWithTag("vlm_sample_expression_NEUTRAL").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_sample_expression_HAPPY").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_sample_ethnicity_ASIAN").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_sample_action_${PhotoActionCatalog.SAMPLE_ID}").assertIsNotEnabled()
        composeRule.runOnIdle { sample.value = sample.value.copy(loading = false) }
        composeRule.onNodeWithTag("vlm_sample_expression_NEUTRAL").performClick()
        composeRule.onNodeWithTag("vlm_sample_expression_HAPPY").performClick()
        composeRule.onNodeWithTag("vlm_sample_ethnicity_ASIAN").performClick()
        composeRule.onNodeWithTag("vlm_sample_close").performClick()
        assertEquals(listOf(VlmIntent.PhotoSampleExpression(ExpressionId.NEUTRAL),
            VlmIntent.PhotoSampleExpression(ExpressionId.HAPPY), VlmIntent.PhotoSampleEthnicity(AvatarEthnicity.ASIAN),
            VlmIntent.ClosePhotoSample), events)
    }

    @Test fun samplePoseSelectionUsesIndependentEventAndKeepsExpressionAndAppearance() {
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { PhotoSampleDialog(PhotoSampleState("pose",
            expression = ExpressionId.HAPPY, loading = false, ethnicity = AvatarEthnicity.ASIAN)) { events += it } } }
        val tag = "vlm_sample_action_${PhotoActionCatalog.WALK_PAUSE_ID}"
        composeRule.onNodeWithTag("vlm_sample_actions").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
        assertEquals(listOf(VlmIntent.PhotoSampleAction(PhotoActionCatalog.WALK_PAUSE_ID)), events)
    }

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
        composeRule.onNodeWithTag("vlm_scene_description").assertTextContains("背景是花园", substring = true)
        composeRule.onNodeWithTag("vlm_pose_description").assertTextContains(";  ", substring = true)
        composeRule.onNodeWithTag("vlm_resolved_guidance").assertTextContains("\n", substring = true)
        composeRule.onNodeWithTag("vlm_resolved_guidance").assertTextContains("画面左侧", substring = true)
            .assertTextContains("轻抬手", substring = true)
            .assertTextContains("自然微笑", substring = true)
        composeRule.runOnIdle {
            plan.value = plan.value.copy(localCombination = 1, sceneDescription = "右侧草坪开阔，远处有树木。", avatar = plan.value.avatar.copy(foot = Point2(.7f, .92f),
                poseDirective = AvatarPoseDirective(arms = ArmPose.HANDS_FRONT), expressionIntensity = 0f))
        }
        composeRule.onNodeWithTag("vlm_resolved_guidance").assertTextContains("画面右侧", substring = true)
            .assertTextContains("双手身前交叠", substring = true)
            .assertTextContains("平静", substring = true)
        composeRule.onNodeWithTag("vlm_scene_description").assertTextContains("右侧草坪开阔", substring = true)
        val environment = composeRule.onNodeWithTag("vlm_scene_description").getUnclippedBoundsInRoot()
        val guidance = composeRule.onNodeWithTag("vlm_resolved_guidance").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(environment.top >= guidance.bottom)
        composeRule.onNodeWithText("原文建议双手叉腰", substring = true).assertDoesNotExist()
    }

    @Test fun environmentDoesNotUseAppGuidanceWhenModelDescriptionIsAbsent() {
        val plan = CompositionPlan("p1", "", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(), "App 固定动作指导", "", localCombination = 1)
        composeRule.setContent { MaterialTheme {
            CompositionDescriptions(CompositionResult(listOf(plan), emptyList(), emptyList(), emptyList()), "p1")
        } }
        composeRule.onNodeWithTag("vlm_scene_description").assertDoesNotExist()
        composeRule.onNodeWithText("App 固定动作指导", substring = true).assertDoesNotExist()
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
