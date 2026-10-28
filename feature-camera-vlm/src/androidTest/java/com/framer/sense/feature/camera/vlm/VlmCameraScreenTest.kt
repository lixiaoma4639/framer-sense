package com.framer.sense.feature.camera.vlm

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 验证导演加载遮罩展示当前真实步骤，而不是旧的通用固定文案。 */
class VlmCameraScreenTest {
    @Test fun topAiEntryExpandsDraftAndOnlyConfirmsWhenRequested() {
        val state = mutableStateOf(VlmUiState(cameraReady = true, instruction = "保留环境"))
        val events = mutableListOf<VlmIntent>()
        val onEvent: (VlmIntent) -> Unit = { event ->
                events += event
                state.value = when (event) {
                    is VlmIntent.InstructionEditorVisible -> state.value.copy(instructionEditorVisible = event.visible,
                        instructionDraft = state.value.instruction)
                    is VlmIntent.InstructionDraftChanged -> state.value.copy(instructionDraft = event.text)
                    VlmIntent.SubmitInstruction -> state.value.copy(instruction = state.value.instructionDraft,
                        instructionEditorVisible = false)
                    else -> state.value
                }
        }
        composeRule.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                CameraTopBar(state.value, onEvent)
                CompositionInstructionOverlay(state.value, onEvent, Modifier.fillMaxSize())
            }
        } }
        composeRule.onNodeWithTag("vlm_ai_instruction_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_ai_instruction_toggle").performClick()
        composeRule.onNodeWithTag("vlm_ai_instruction_toggle").assertIsSelected()
        composeRule.onNodeWithTag("vlm_ai_instruction_input").assertIsNotFocused()
        assertEquals(TextRange(state.value.instruction.length), composeRule.onNodeWithTag("vlm_ai_instruction_input")
            .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        composeRule.onNodeWithTag("vlm_ai_instruction_input").performClick().assertIsFocused()
            .performTextReplacement("人物靠右")
        assertEquals("保留环境", state.value.instruction)
        assertTrue(events.none { it == VlmIntent.SubmitInstruction || it is VlmIntent.InstructionChanged })
        composeRule.onNodeWithTag("vlm_ai_instruction_confirm").performClick()
        composeRule.onNodeWithTag("vlm_ai_instruction_panel").assertDoesNotExist()
        assertEquals("人物靠右", state.value.instruction)
        composeRule.onNodeWithTag("vlm_ai_instruction_toggle").performClick()
        composeRule.onNodeWithTag("vlm_ai_instruction_input").assertTextContains("人物靠右")
        composeRule.onNodeWithTag("vlm_ai_instruction_panel").performTouchInput {
            click(Offset(width - 2f, height - 2f))
        }
        composeRule.onNodeWithTag("vlm_ai_instruction_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("vlm_ai_instruction_dismiss").performTouchInput { click(Offset(2f, 2f)) }
        composeRule.onNodeWithTag("vlm_ai_instruction_panel").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(stage = VlmStage.GENERATING) }
        composeRule.onNodeWithTag("vlm_ai_instruction_toggle").assertIsNotEnabled()
    }

    @Test fun instructionModelChoiceShowsShortLabelAndFullMenuWithoutChangingText() {
        val state = mutableStateOf(VlmUiState(settings = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.SEED),
            instructionEditorVisible = true, instructionDraft = "人物靠右"))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            CompositionInstructionBar(state.value) { event ->
                events += event
                if (event is VlmIntent.SeedreamModelSelected) state.value = state.value.copy(
                    settings = state.value.settings.copy(seedreamModel = event.model))
            }
        } }
        composeRule.onNodeWithTag("vlm_generated_model_choice").assertTextContains("快速")
        val input = composeRule.onNodeWithTag("vlm_ai_instruction_input").fetchSemanticsNode().boundsInRoot
        val choice = composeRule.onNodeWithTag("vlm_generated_model_choice").fetchSemanticsNode().boundsInRoot
        assertTrue(input.contains(choice.center))
        assertTrue(choice.center.x > input.center.x && choice.center.y > input.center.y)
        composeRule.onNodeWithTag("vlm_generated_model_choice").performClick()
        composeRule.onNodeWithTag("vlm_seedream_model_FLASH").assertTextContains("Seedream 5.0 Flash (快速)").assertIsSelected()
        composeRule.onNodeWithTag("vlm_seedream_model_PRO").assertTextContains("Seedream 5.0 Pro (专家)").performClick()
        composeRule.onNodeWithTag("vlm_generated_model_choice").assertTextContains("专家")
        composeRule.onNodeWithTag("vlm_ai_instruction_input").assertTextContains("人物靠右")
        assertEquals(listOf(VlmIntent.SeedreamModelSelected(SeedreamModel.PRO)), events)
    }

    @Test fun qwenImageMenuUsesOfficialNamesAndDoesNotChangeSeedSelectionOrInstruction() {
        val state = mutableStateOf(VlmUiState(settings = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.QWEN,
            seedreamModel = SeedreamModel.PRO), instructionEditorVisible = true, instructionDraft = "舞姿"))
        composeRule.setContent { MaterialTheme {
            CompositionInstructionBar(state.value) { event ->
                if (event is VlmIntent.QwenImageModelSelected) state.value = state.value.copy(
                    settings = state.value.settings.copy(qwenImageModel = event.model))
            }
        } }
        composeRule.onNodeWithTag("vlm_generated_model_choice").assertTextContains("极速").performClick()
        composeRule.onNodeWithTag("vlm_qwen_image_model_V21_TURBO").assertTextContains("Qwen-Image-2.1 Turbo (极速)").assertIsSelected()
        composeRule.onNodeWithTag("vlm_qwen_image_model_V21_PRO").assertTextContains("Qwen-Image-2.1 Pro (专业)")
        composeRule.onNodeWithTag("vlm_qwen_image_model_V30_PRO").assertTextContains("Qwen-Image-3.0 Pro (专业)")
        composeRule.onNodeWithTag("vlm_seedream_model_FLASH").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_qwen_image_model_V30").assertTextContains("Qwen-Image-3.0 (标准)").performClick()
        composeRule.onNodeWithTag("vlm_generated_model_choice").assertTextContains("标准")
        composeRule.onNodeWithTag("vlm_ai_instruction_input").assertTextContains("舞姿")
        assertEquals(SeedreamModel.PRO, state.value.settings.seedreamModel)
    }

    @Test fun opaqueQwenPreviewExplainsWhyApplyIsDisabled() {
        val scene = SceneSnapshot("opaque-scene", 960, 1280, 1f, "unused", "unused")
        val candidate = GeneratedCandidate("opaque", "batch", scene, QwenImageModel.V21_TURBO, "", 0, null,
            status = GeneratedCandidateStatus.READY,
            assets = GeneratedReferenceAssets("unused-preview", "", 960, 1280, "qwen-image-2.1-turbo", 1,
                portraitIssue = GeneratedPortraitIssue.NO_TRANSPARENCY))
        val state = VlmUiState(stage = VlmStage.READY, scene = scene,
            settings = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.QWEN),
            generated = GeneratedReferenceState(candidates = listOf(candidate), selectedId = candidate.id),
            compositionDetailsVisible = true, compositionDetailsExpanded = true)
        composeRule.setContent { MaterialTheme { Controls(state, {}, {}, Modifier.fillMaxWidth()) } }
        composeRule.onNodeWithTag("vlm_generated_unavailable_reason").assertTextContains("白色背景不等于透明背景")
        composeRule.onNodeWithTag("vlm_apply").assertIsNotEnabled()
    }

    @Test fun generatedReferenceReusesOriginalActionsAndGenerationConfirmation() {
        val scene = SceneSnapshot("fixture-scene", 600, 800, 1f, "unused", "unused")
        val candidate = GeneratedCandidate("fixture-candidate", "fixture-batch", scene, SeedreamModel.FLASH,
            "自然", 0, null, status = GeneratedCandidateStatus.READY,
            assets = GeneratedReferenceAssets("unused-full", "unused-person", 600, 800, "fixture", 2))
        val state = VlmUiState(stage = VlmStage.READY, scene = scene,
            settings = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.SEED),
            generated = GeneratedReferenceState(candidates = listOf(candidate), selectedId = candidate.id), compositionDetailsVisible = true)
        val rendered = mutableStateOf(state)
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { Controls(rendered.value, events::add, {}, Modifier.fillMaxWidth()) } }
        composeRule.onNodeWithTag("vlm_camera_actions").assertExists()
        composeRule.onNodeWithTag("vlm_composition_choices").assertExists()
        composeRule.onNodeWithTag("vlm_composition_info_toggle").assertExists()
        composeRule.onNodeWithTag("vlm_apply").assertExists()
        composeRule.onNodeWithTag("vlm_generated_generate").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_apply").assertDoesNotExist()
        val restart = composeRule.onNodeWithTag("vlm_restart_flow").fetchSemanticsNode().boundsInRoot
        val generate = composeRule.onNodeWithTag("vlm_generate").fetchSemanticsNode().boundsInRoot
        assertTrue(restart.left < generate.left)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("vlm_generate_label").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color.White, layouts.single().layoutInput.style.color)
        composeRule.onNodeWithTag("vlm_generate").performClick()
        composeRule.onNodeWithTag("vlm_background_generate_dialog").assertExists()
        composeRule.onNodeWithText("为备选 1 生成一张构图？已生成的构图会保留。").assertExists()
        assertTrue(events.isEmpty())
        composeRule.onNodeWithTag("vlm_background_generate_confirm").performClick()
        assertEquals(listOf(VlmIntent.Generate), events)
        composeRule.onNodeWithTag("vlm_apply").performClick()
        assertEquals(VlmIntent.Apply, events.last())
        composeRule.runOnIdle {
            rendered.value = state.copy(generated = state.generated.copy(candidates = listOf(candidate,
                candidate.copy(id = "pending-candidate", status = GeneratedCandidateStatus.RUNNING, assets = null))))
        }
        composeRule.onNodeWithTag("vlm_apply").assertIsEnabled()
        composeRule.onNodeWithTag("vlm_generate").assertIsNotEnabled()
        composeRule.runOnIdle {
            rendered.value = state.copy(compositionDetailsExpanded = true,
                generated = state.generated.copy(candidates = listOf(candidate.copy(
                    assets = candidate.assets!!.copy(portraitPath = "", sourceLayerCount = 3)))))
        }
        composeRule.onNodeWithTag("vlm_apply").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_generated_enlarge").assertDoesNotExist()
        composeRule.onNodeWithText("已合成全部 3 张图层（含底图）").assertDoesNotExist()
        composeRule.onNodeWithText("Seedream 5.0 Flash (快速)").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_replace").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_actual").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_guidance").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_environment").assertDoesNotExist()
        val described = candidate.copy(assets = candidate.assets!!.copy(descriptions = GeneratedReferenceDescriptions(
            actualComposition = "女子侧身回望", shootingGuidance = "略微放低机位", environment = "灯笼下的街道")))
        composeRule.runOnIdle {
            rendered.value = state.copy(compositionDetailsExpanded = true,
                generated = state.generated.copy(candidates = listOf(described)))
        }
        composeRule.onNodeWithTag("vlm_generated_actual").assertTextContains("实际构图：女子侧身回望")
        composeRule.onNodeWithTag("vlm_generated_guidance").assertTextContains("拍摄指导：略微放低机位")
        composeRule.onNodeWithTag("vlm_generated_environment").assertTextContains("环境场景：灯笼下的街道")
        composeRule.onNodeWithText("自然", substring = false).assertDoesNotExist()
        listOf("构图详情", "应用构图").forEach { label ->
            val buttonLayouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(buttonLayouts) }
            assertEquals(Color.White, buttonLayouts.single().layoutInput.style.color)
        }
        composeRule.runOnIdle {
            val current = rendered.value
            rendered.value = current.copy(generated = current.generated.copy(candidates = current.generated.candidates.map {
                it.copy(assets = it.assets!!.copy(descriptions = it.assets!!.descriptions.copy(shootingGuidance = "  ")))
            }))
        }
        composeRule.onNodeWithTag("vlm_generated_guidance").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_actual").assertExists()
        composeRule.onNodeWithTag("vlm_generated_environment").assertExists()
    }

    @Test fun instructionModelChoiceMarksOtherBrandsUnsupportedAndHidesOfflineChoice() {
        val state = mutableStateOf(VlmUiState(settings = ModelSettings(mode = ModelMode.AUTO), instructionEditorVisible = true))
        composeRule.setContent { MaterialTheme { CompositionInstructionBar(state.value, {}) } }
        listOf(ProviderId.GPT, ProviderId.GEMINI).forEach { brand ->
            composeRule.runOnIdle { state.value = state.value.copy(settings = state.value.settings.copy(preferred = brand)) }
            composeRule.onNodeWithTag("vlm_generated_model_choice").assertTextContains("不支持").assertIsNotEnabled()
            composeRule.onNodeWithTag("vlm_seedream_model_FLASH").assertDoesNotExist()
        }
        composeRule.runOnIdle { state.value = state.value.copy(settings = state.value.settings.copy(mode = ModelMode.OFFLINE)) }
        composeRule.onNodeWithTag("vlm_generated_model_choice").assertDoesNotExist()
    }

    @Test fun historyDeleteDoesNotSelectRowAndRecommendationsMoveCursorToEnd() {
        val state = mutableStateOf(VlmUiState(instructionEditorVisible = true, instructionDraft = "保留建筑",
            instructionHistory = listOf("保留建筑")))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            CompositionInstructionBar(state.value) { event ->
                events += event
                if (event is VlmIntent.InstructionRecommendationSelected) state.value = state.value.copy(
                    instructionDraft = event.text, instructionDraftFromRecommendation = true,
                    instructionDraftRevision = state.value.instructionDraftRevision + 1)
            }
        } }
        composeRule.onNodeWithTag("vlm_ai_history_0").assertIsSelected()
        composeRule.onNodeWithTag("vlm_ai_history_delete_0").performClick()
        assertEquals(listOf(VlmIntent.DeleteInstructionHistory("保留建筑")), events)
        composeRule.onNodeWithTag("vlm_ai_history_0").performClick()
        assertEquals(VlmIntent.InstructionHistorySelected("保留建筑"), events.last())
        composeRule.onNodeWithTag("vlm_ai_instruction_choices").performScrollToNode(hasTestTag("vlm_ai_recommendation_0"))
        composeRule.onNodeWithTag("vlm_ai_recommendation_0").performClick()
        composeRule.onNodeWithTag("vlm_ai_recommendation_0").assertIsSelected()
        composeRule.onNodeWithTag("vlm_ai_instruction_input").assertIsNotFocused()
        assertEquals(TextRange(state.value.instructionDraft.length), composeRule.onNodeWithTag("vlm_ai_instruction_input")
            .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @get:Rule val composeRule = createComposeRule()

    @Test fun topBarIconsAlignWithInstructionPanelMarginsAndAiEntryStaysCentered() {
        val state = mutableStateOf(VlmUiState())
        composeRule.setContent { MaterialTheme { CameraTopBar(state.value, {}, onExit = {}) } }
        fun assertPositions() {
            val bar = composeRule.onNodeWithTag("vlm_camera_top_bar").getUnclippedBoundsInRoot()
            val back = composeRule.onNodeWithTag("vlm_camera_exit").getUnclippedBoundsInRoot()
            val history = composeRule.onNodeWithTag("vlm_history_open").getUnclippedBoundsInRoot()
            val backIcon = composeRule.onNodeWithTag("vlm_camera_exit_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val ai = composeRule.onNodeWithTag("vlm_ai_instruction_toggle").getUnclippedBoundsInRoot()
            assertEquals(0f, (history.left - bar.left).value, .1f)
            assertEquals(0f, (bar.right - back.right).value, .1f)
            assertEquals(12f, (bar.right - backIcon.right).value, .1f)
            val aspect = composeRule.onNodeWithTag("vlm_aspect_ratio").getUnclippedBoundsInRoot()
            assertEquals((bar.left.value + bar.right.value) / 2, (ai.left.value + ai.right.value) / 2, .1f)
            assertTrue(aspect.right <= ai.left)
            assertTrue(ai.right <= back.left)
            composeRule.onNodeWithTag("vlm_model_settings").assertDoesNotExist()
            composeRule.onNodeWithTag("vlm_photo_settings").assertDoesNotExist()
        }
        assertPositions()
        composeRule.runOnIdle { state.value = state.value.copy(cameraMode = CameraMode.AR) }
        assertPositions()
    }

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
            CameraSettingsButtons(state.value, { events += it })
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

    @Test fun cloudPhotoSettingsSharesPortraitOptionsAndKeepsTestImageWithoutGlbControls() {
        val state = mutableStateOf(VlmUiState(settings = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.QWEN)))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { PhotoSettingsDialog(state.value, events::add, {}) } }
        composeRule.onNodeWithTag("vlm_avatar_choice_ADULT_MALE").performClick()
        composeRule.onNodeWithTag("vlm_preferred_ethnicity_ASIAN").performClick()
        assertEquals(listOf(VlmIntent.AvatarSelected(AvatarId.ADULT_MALE), VlmIntent.PreferredEthnicity(AvatarEthnicity.ASIAN)), events)
        listOf("vlm_face_setting", "vlm_ar_setting", "vlm_sample_open").forEach {
            composeRule.onNodeWithTag(it).assertDoesNotExist()
        }
        composeRule.onNodeWithText("三种卡通外观初稿，姿势与表情保持一致，外观效果待验收。").assertDoesNotExist()
        if (BuildConfig.DEBUG) composeRule.onNodeWithTag("vlm_import_image").assertExists()
        composeRule.runOnIdle { state.value = state.value.copy(settings = state.value.settings.copy(preferred = ProviderId.SEED)) }
        composeRule.onNodeWithTag("vlm_avatar_choice_ADULT_MALE").assertExists()
        composeRule.onNodeWithTag("vlm_preferred_ethnicity_ASIAN").assertExists()
        composeRule.onNodeWithTag("vlm_ar_setting").assertDoesNotExist()
    }

    @Test fun qwenDetailsShowsOnlyItsAvailableDescriptionOrAnEmptyHint() {
        val scene = SceneSnapshot("qwen-detail", 960, 1280, 1f, "unused", "unused")
        val candidate = GeneratedCandidate("qwen-detail", "batch", scene, QwenImageModel.V21_TURBO, "不要冒充结果说明", 0, null,
            status = GeneratedCandidateStatus.READY, assets = GeneratedReferenceAssets("full", "person", 960, 1280, "qwen", 1))
        val state = mutableStateOf(VlmUiState(settings = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.QWEN),
            generated = GeneratedReferenceState(candidates = listOf(candidate), selectedId = candidate.id)))
        composeRule.setContent { MaterialTheme { GeneratedReferenceDetails(state.value) } }
        composeRule.onNodeWithTag("vlm_description_instruction").assertTextContains("AI构图对话：不要冒充结果说明")
        composeRule.onNodeWithTag("vlm_generated_descriptions_empty").assertExists()
        composeRule.onNodeWithTag("vlm_generated_actual").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_guidance").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(generated = state.value.generated.copy(
            candidates = listOf(candidate.copy(sceneDescription = "该千问构图对应的现场描述")))) }
        composeRule.onNodeWithTag("vlm_generated_descriptions_empty").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_generated_environment").assertTextContains("该千问构图对应的现场描述")
        composeRule.runOnIdle { state.value = state.value.copy(instruction = "下一张使用的新要求") }
        composeRule.onNodeWithTag("vlm_description_instruction").assertTextContains("不要冒充结果说明")
        composeRule.runOnIdle { state.value = state.value.copy(generated = state.value.generated.copy(
            candidates = listOf(candidate.copy(instruction = "  ")))) }
        composeRule.onNodeWithTag("vlm_description_instruction").assertTextEquals("AI构图对话：无")
        composeRule.onNodeWithText("不要冒充结果说明").assertDoesNotExist()
    }

    @Test fun modelSettingsRetainsItsOwnDialog() {
        composeRule.setContent { MaterialTheme { ModelSettingsDialog(VlmUiState(), {}, {}, {}) } }
        composeRule.onNodeWithTag("vlm_model_settings_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("vlm_photo_settings_dialog").assertDoesNotExist()
    }

    @Test fun modelSettingsShowsOnlySelectedModeConfigurationAndKeepsHiddenGatewayDraft() {
        val initial = ModelSettings(gateway = "https://example.invalid/vlm", gatewayToken = "test-token", qwenAccess = QwenAccess.GATEWAY)
        val editedGateway = "https://example.invalid/edited"
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = initial), events::add, {}, {})
        } }
        composeRule.onNodeWithTag("vlm_model_mode_OFFLINE").assertIsSelected()
        composeRule.onNodeWithTag("vlm_offline_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_download_local_divider").assertExists()
        composeRule.onNodeWithTag("vlm_cloud_offline_divider").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_cloud_configuration").assertDoesNotExist()
        composeRule.onNodeWithText("网关地址").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_LEGACY_GLB").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_GENERATED_IMAGE").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_seedream_model_FLASH").assertDoesNotExist()

        composeRule.onNodeWithTag("vlm_model_mode_CLOUD").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_cloud_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_offline_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_download_local_divider").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_LEGACY_GLB").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_GENERATED_IMAGE").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_seedream_model_FLASH").assertDoesNotExist()
        composeRule.onNodeWithText(initial.gateway).performScrollTo().performTextReplacement(editedGateway)

        composeRule.onNodeWithTag("vlm_model_mode_AUTO").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_cloud_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_offline_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_cloud_offline_divider").assertExists()
        composeRule.onNodeWithTag("vlm_download_local_divider").assertExists()
        composeRule.onNodeWithTag("vlm_model_mode_OFFLINE").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_cloud_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_offline_configuration").assertExists()
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(VlmIntent.SaveSettings(initial.copy(gateway = editedGateway))), events)
    }

    @Test fun cloudBrandsKeepKeysAndHideSeparateSeedreamConfigurationAcrossModeSwitches() {
        val initial = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.SEED,
            seedApiKey = "seed-original", qwenApiKey = "qwen-original", seedreamModel = SeedreamModel.PRO)
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = initial), events::add, {}, {})
        } }
        composeRule.onNodeWithText("摄影参考方式").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_LEGACY_GLB").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_GENERATED_IMAGE").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_seedream_model_PRO").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_seedream_api_key").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_seed_api_key").performScrollTo().performTextReplacement("seed-edited")
        composeRule.onNodeWithTag("vlm_cloud_brand_QWEN").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_seed_api_key").assertDoesNotExist()
        assertKeyCanBeRevealedAndMasked("vlm_qwen_api_key", initial.qwenApiKey)
        composeRule.onNodeWithTag("vlm_cloud_brand_SEED").performScrollTo().performClick()
        assertKeyCanBeRevealedAndMasked("vlm_seed_api_key", "seed-edited")
        composeRule.onNodeWithTag("vlm_model_mode_OFFLINE").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_seedream_model_PRO").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_offline_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_model_mode_CLOUD").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_seedream_model_PRO").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_cloud_brand_SEED").assertIsSelected()
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(VlmIntent.SaveSettings(initial.copy(referenceSource = ReferenceSource.GENERATED_IMAGE,
            seedApiKey = "seed-edited"))), events)
    }

    @Test fun modelModeButtonsShareOneRowAndShowFullLabelsWithLargerSystemFont() {
        composeRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, fontScale = 1.5f)) {
                MaterialTheme { ModelSettingsDialog(VlmUiState(), {}, {}, {}) }
            }
        }
        val bounds = ModelMode.entries.map { mode ->
            val node = composeRule.onNodeWithTag("vlm_model_mode_${mode.name}")
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertFalse(layouts.single().hasVisualOverflow)
            node.getUnclippedBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertEquals(left.top, right.top)
            assertEquals(left.bottom, right.bottom)
            org.junit.Assert.assertTrue(kotlin.math.abs(left.width.value - right.width.value) <= 1f)
            org.junit.Assert.assertTrue(left.right <= right.left)
        }
    }

    @Test fun modelSettingsUsesPreviouslySavedModeWhenOpened() {
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = ModelSettings(mode = ModelMode.CLOUD)), {}, {}, {})
        } }
        composeRule.onNodeWithTag("vlm_model_mode_CLOUD").assertIsSelected()
        composeRule.onNodeWithTag("vlm_open_model_market").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_cloud_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_offline_configuration").assertDoesNotExist()
        composeRule.onNodeWithText("中国").assertDoesNotExist()
        composeRule.onNodeWithText("国际").assertDoesNotExist()
        listOf(ProviderId.QWEN, ProviderId.SEED, ProviderId.GPT, ProviderId.GEMINI).forEach {
            composeRule.onNodeWithTag("vlm_cloud_brand_${it.name}").assertExists()
        }
        composeRule.onNodeWithTag("vlm_cloud_brand_QWEN").assertIsSelected()
        composeRule.onNodeWithTag("vlm_cloud_brand_SEED").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("vlm_seed_official_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_qwen_official_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_cloud_brand_GPT").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("vlm_gateway_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_seed_official_configuration").assertDoesNotExist()
    }

    @Test fun officialQwenSettingsUseJsonAddressAndModelAndSaveUserKey() {
        val initial = ModelSettings(mode = ModelMode.CLOUD, qwenBaseUrl = "https://old.example.invalid/v1", qwenModel = "old-model")
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = initial), events::add, {}, {})
        } }
        composeRule.onNodeWithTag("vlm_qwen_access_OFFICIAL").assertIsSelected()
        composeRule.onNodeWithTag("vlm_qwen_official_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_gateway_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_qwen_base_url").assertTextContains(CloudModelConfig.qwenBaseUrl)
        composeRule.onNodeWithTag("vlm_qwen_model").assertTextContains(CloudModelConfig.qwenModel)
        composeRule.onNodeWithTag("vlm_qwen_api_key").performScrollTo().performTextReplacement("sk-fake-qwen-key")
        assertKeyCanBeRevealedAndMasked("vlm_qwen_api_key", "sk-fake-qwen-key")
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(VlmIntent.SaveSettings(CloudModelConfig.applyQwenConfiguration(initial).normalizeReferenceSource().copy(qwenApiKey = "sk-fake-qwen-key"))), events)
        composeRule.onNodeWithTag("vlm_qwen_check").performScrollTo().performClick()
        assertEquals(VlmIntent.CheckQwen, events.last())
        composeRule.onNodeWithTag("vlm_qwen_access_GATEWAY").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_qwen_official_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_gateway_configuration").assertExists()
    }

    @Test fun seedOfficialSettingsUseJsonAndKeepQwenKeyWhileSavingAndSwitchingAccess() {
        val initial = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.SEED, qwenApiKey = "sk-existing-qwen-key")
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = initial), events::add, {}, {})
        } }
        composeRule.onNodeWithTag("vlm_seed_access_OFFICIAL").assertIsSelected()
        composeRule.onNodeWithTag("vlm_seed_official_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_qwen_official_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_gateway_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_seed_base_url").assertTextContains(CloudModelConfig.seedBaseUrl)
        composeRule.onNodeWithTag("vlm_seed_model").assertTextContains(CloudModelConfig.seedModel)
        composeRule.onNodeWithTag("vlm_seed_api_key").performScrollTo().performTextReplacement("fake-seed-key")
        assertKeyCanBeRevealedAndMasked("vlm_seed_api_key", "fake-seed-key")
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(VlmIntent.SaveSettings(initial.normalizeReferenceSource().copy(seedApiKey = "fake-seed-key"))), events)
        composeRule.onNodeWithTag("vlm_seed_check").performScrollTo().performClick()
        assertEquals(VlmIntent.CheckSeed, events.last())
        composeRule.onNodeWithTag("vlm_seed_access_GATEWAY").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_seed_official_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_gateway_configuration").assertExists()
    }

    @Test fun gatewayTokenVisibilityDoesNotChangeTheSavedValue() {
        val initial = ModelSettings(mode = ModelMode.CLOUD, region = Region.GLOBAL, preferred = ProviderId.GPT,
            gatewayToken = "fake-gateway-token")
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = initial), events::add, {}, {})
        } }
        assertKeyCanBeRevealedAndMasked("vlm_gateway_token", initial.gatewayToken)
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(VlmIntent.SaveSettings(CloudModelConfig.applyQwenConfiguration(initial).normalizeReferenceSource())), events)
    }

    private fun assertKeyCanBeRevealedAndMasked(tag: String, value: String) {
        val field = composeRule.onNodeWithTag(tag)
        val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        field.performScrollTo().assert(password)
        composeRule.onNodeWithTag("${tag}_visibility").performScrollTo().performClick()
        field.assert(!password).assertTextContains(value)
        composeRule.onNodeWithTag("${tag}_visibility").performClick()
        field.assert(password)
    }

    @Test fun autoModeShowsSelectedBrandAndOfflineWithoutRequiringGateway() {
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = ModelSettings(mode = ModelMode.AUTO)), {}, {}, {})
        } }
        composeRule.onNodeWithTag("vlm_qwen_official_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_seed_official_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_offline_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_gateway_configuration").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_LEGACY_GLB").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_reference_source_GENERATED_IMAGE").assertDoesNotExist()
        composeRule.onNodeWithText("摄影参考方式").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_seedream_model_FLASH").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_cloud_brand_SEED").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_seed_official_configuration").assertExists()
        composeRule.onNodeWithTag("vlm_qwen_official_configuration").assertDoesNotExist()
    }

    @Test fun marketRoundTripRetainsUnsavedModelSettingsDraft() {
        val initial = ModelSettings(qwenAccess = QwenAccess.GATEWAY)
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            ModelSettingsDialog(VlmUiState(settings = initial), events::add, {}, {}, marketContent = { close ->
                androidx.compose.material3.TextButton(onClick = close, modifier = Modifier.testTag("market_test_back")) {
                    androidx.compose.material3.Text("返回市场测试")
                }
            })
        } }
        composeRule.onNodeWithTag("vlm_model_mode_AUTO").performClick()
        composeRule.onNodeWithTag("vlm_open_model_market").performScrollTo().performClick()
        composeRule.onNodeWithTag("vlm_model_settings_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("market_test_back").performClick()
        composeRule.onNodeWithTag("vlm_model_mode_AUTO").assertIsSelected()
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(VlmIntent.SaveSettings(initial.copy(mode = ModelMode.AUTO).normalizeReferenceSource())), events)
    }

    @Test fun unsupportedVisionModelsCannotDownload() {
        val unsupported = com.framer.sense.feature.camera.vlm.market.MarketModel("unreviewed-VL-MNN", "Example", listOf("Vision"),
            mapOf("ModelScope" to "MNN/unreviewed-VL-MNN"))
        val state = com.framer.sense.feature.camera.vlm.market.MarketUiState(models = listOf(unsupported), allModels = listOf(unsupported))
        composeRule.setContent { MaterialTheme {
            com.framer.sense.feature.camera.vlm.market.ModelMarketContent(state, false, ModelMode.OFFLINE, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        composeRule.onNodeWithTag("vlm_market_download_unreviewed-VL-MNN").assertIsNotEnabled()
    }

    @Test fun marketUpdatesDownloadedLabelWhenRuntimeLoadsAndUnloads() {
        val model = com.framer.sense.feature.camera.vlm.market.MarketModel(
            "Qwen3-VL-4B-Instruct-MNN", "Qwen", listOf("Vision"), emptyMap())
        val path = "/models/4b"
        val state = androidx.compose.runtime.mutableStateOf(com.framer.sense.feature.camera.vlm.market.MarketUiState(
            models = listOf(model), allModels = listOf(model), activeModel = model.id,
            installed = listOf(com.framer.sense.feature.camera.vlm.data.InstalledModel(model.id, path)),
            downloads = mapOf(model.id to com.framer.sense.feature.camera.vlm.data.ModelDownloadState(
                modelId = model.id, phase = com.framer.sense.feature.camera.vlm.data.DownloadPhase.DOWNLOADED))))
        composeRule.setContent { MaterialTheme {
            com.framer.sense.feature.camera.vlm.market.ModelMarketContent(state.value, false, ModelMode.OFFLINE,
                {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        val status = composeRule.onNodeWithTag("vlm_market_status_${model.id}")
        status.assertTextEquals("模型已下载")
        composeRule.runOnIdle { state.value = state.value.copy(runtime =
            com.framer.sense.feature.camera.vlm.data.LocalModelLoadState(com.framer.sense.feature.camera.vlm.data.LocalModelPhase.READY, path)) }
        status.assertTextEquals(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vlm_model_loaded))
        composeRule.runOnIdle { state.value = state.value.copy(runtime = com.framer.sense.feature.camera.vlm.data.LocalModelLoadState()) }
        status.assertTextEquals("模型已下载")
    }

    @Test fun marketConfirmsEveryMobileDownloadAndStartsWifiWithoutConfirmation() {
        val model = com.framer.sense.feature.camera.vlm.market.MarketModel(
            com.framer.sense.feature.camera.vlm.market.MarketCatalog.DEFAULT_MODEL, "Qwen", listOf("Vision"),
            mapOf("ModelScope" to "MNN/Qwen3-VL-2B-Instruct-MNN"))
        val state = com.framer.sense.feature.camera.vlm.market.MarketUiState(models = listOf(model), allModels = listOf(model))
        val requests = mutableListOf<Pair<String, Boolean>>()
        var mobile = true
        composeRule.setContent { MaterialTheme {
            com.framer.sense.feature.camera.vlm.market.ModelMarketContent(state, false, ModelMode.CLOUD,
                {}, {}, {}, {}, { id, allowed -> requests += id to allowed }, {}, {}, {}, {},
                requiresMobileConfirmation = { mobile })
        } }
        composeRule.onNodeWithText("当前已保存为指定云端：下载可以继续，离线模型不会自动加载。").assertDoesNotExist()
        composeRule.onNodeWithText("允许使用计费网络（包括移动流量）").assertDoesNotExist()
        repeat(2) {
            composeRule.onNodeWithTag("vlm_market_download_${model.id}").performClick()
            composeRule.onNodeWithTag("vlm_market_mobile_confirmation").assertIsDisplayed()
            assertEquals(it, requests.size)
            composeRule.onNodeWithText("允许本次下载").performClick()
        }
        assertEquals(listOf(model.id to true, model.id to true), requests)
        composeRule.onNodeWithTag("vlm_market_download_${model.id}").performClick()
        composeRule.onNodeWithText("暂不允许").performClick()
        assertEquals(2, requests.size)
        composeRule.runOnIdle { mobile = false }
        composeRule.onNodeWithTag("vlm_market_download_${model.id}").performClick()
        composeRule.onNodeWithTag("vlm_market_mobile_confirmation").assertDoesNotExist()
        assertEquals(model.id to false, requests.last())
    }

    @Test fun marketFilterUsesExpandedBottomSheetWithPriorityVendorsAndSourceSpacing() {
        val models = listOf("Other", "Gemma", "DeepSeek", "Qwen").map { vendor ->
            com.framer.sense.feature.camera.vlm.market.MarketModel("$vendor-VL", vendor, listOf("Vision"), emptyMap())
        }
        val state = com.framer.sense.feature.camera.vlm.market.MarketUiState(models = models, allModels = models)
        composeRule.setContent { MaterialTheme {
            com.framer.sense.feature.camera.vlm.market.ModelMarketContent(state, false, ModelMode.OFFLINE,
                {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        val china = composeRule.onNodeWithTag("vlm_market_source_MODELSCOPE").getUnclippedBoundsInRoot()
        val global = composeRule.onNodeWithTag("vlm_market_source_HUGGING_FACE").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(global.left.value - china.right.value >= 11f)
        composeRule.onNodeWithTag("vlm_market_filter_open").performClick()
        composeRule.onNodeWithTag("vlm_market_filter_sheet").assertIsDisplayed()
        val bounds = listOf("qwen", "deepseek", "gemma", "gpt", "other").map { vendor ->
            composeRule.onNodeWithTag("vlm_market_vendor_$vendor").assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (before, after) ->
            org.junit.Assert.assertTrue(before.top < after.top || before.top == after.top && before.right <= after.left)
        }
    }

    @Test fun downloadedViewIsIndependentAndFilterButtonReflectsSelection() {
        val qwen = com.framer.sense.feature.camera.vlm.market.MarketModel("Qwen3-VL-4B-Instruct-MNN", "Qwen", listOf("Vision"), emptyMap())
        val gemma = com.framer.sense.feature.camera.vlm.market.MarketModel("gemma-4-E2B-it-MNN", "Gemma", listOf("Vision"), emptyMap())
        val models = listOf(qwen, gemma)
        val installed = listOf(com.framer.sense.feature.camera.vlm.data.InstalledModel(gemma.id, "download-gemma"))
        val filter = mutableStateOf(com.framer.sense.feature.camera.vlm.market.MarketFilter())
        composeRule.setContent { MaterialTheme {
            val state = com.framer.sense.feature.camera.vlm.market.MarketUiState(
                models = com.framer.sense.feature.camera.vlm.market.MarketCatalog.filter(models, filter.value, setOf(gemma.id)),
                allModels = models, installed = installed, filter = filter.value)
            com.framer.sense.feature.camera.vlm.market.ModelMarketContent(state, false, ModelMode.OFFLINE,
                {}, { filter.value = it }, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        val advanced = composeRule.onNodeWithTag("vlm_market_filter_open")
        val downloaded = composeRule.onNodeWithTag("vlm_market_installed")
        assertTrue(advanced.getUnclippedBoundsInRoot().left < downloaded.getUnclippedBoundsInRoot().left)
        downloaded.performClick().assertIsSelected()
        advanced.assertIsNotSelected()
        composeRule.onNodeWithTag("vlm_market_model_${qwen.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_market_model_${gemma.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("vlm_market_tag_Vision").performClick()
        downloaded.assertIsNotSelected()
        advanced.assertIsSelected()
        downloaded.performClick()
        composeRule.onNodeWithTag("vlm_market_tag_Vision").assertIsNotSelected()
        advanced.performClick()
        composeRule.onNodeWithTag("vlm_market_vendor_qwen").performClick()
        composeRule.onNodeWithText("完成").performClick()
        downloaded.assertIsNotSelected()
        advanced.assertIsSelected()
        downloaded.performClick()
        composeRule.onNodeWithTag("vlm_market_search").performTextReplacement("qwen")
        downloaded.assertIsNotSelected()
        advanced.assertIsSelected()
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

    @Test fun laterWorkflowStepsOnlyOfferRestartFlowAndKeepSinglePhotoInTheFirstStep() {
        val plans = (1..3).map { CompositionPlan("p$it", "测试标题$it", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(), "指导", "背景") }
        val state = mutableStateOf(VlmUiState(cameraReady = true))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { Controls(state.value, events::add, {}, Modifier.fillMaxWidth()) } }
        fun assertNoCancelActions() {
            listOf("vlm_resume", "vlm_cancel", "vlm_scan_cancel").forEach { tag ->
                composeRule.onNodeWithTag(tag).assertDoesNotExist()
            }
        }
        assertNoCancelActions()
        composeRule.onNodeWithTag("vlm_scan_start").assertTextContains("开始扫描")
        composeRule.onNodeWithTag("vlm_start").assertTextContains("单张构图")
        listOf(VlmStage.SCAN_PROCESSING, VlmStage.SCAN_REVIEW, VlmStage.VIEW_SELECTING,
            VlmStage.FROZEN, VlmStage.GENERATING, VlmStage.READY, VlmStage.MODIFYING, VlmStage.LIVE).forEach { stage ->
            composeRule.runOnIdle { state.value = VlmUiState(stage = stage, cameraReady = true,
                scene = SceneSnapshot("scene", 640, 480, 1f, "/scene", "/input"),
                result = CompositionResult(plans, emptyList(), emptyList(), emptyList()),
                referencePlan = if (stage == VlmStage.LIVE) plans.first() else null) }
            composeRule.onNodeWithTag("vlm_start").assertDoesNotExist()
            composeRule.onNodeWithTag("vlm_scan_start").assertDoesNotExist()
            composeRule.onNodeWithTag("vlm_scan_restart_active").assertDoesNotExist()
            assertNoCancelActions()
            val restartBounds = composeRule.onNodeWithTag("vlm_restart_flow").getUnclippedBoundsInRoot()
            listOf("vlm_generate", "vlm_details", "vlm_scan_analyze", "vlm_scan_manual_open").forEach { tag ->
                val other = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
                if (other.isNotEmpty()) {
                    val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                    org.junit.Assert.assertTrue("$stage 的重新开始应排在 $tag 左侧", restartBounds.right <= bounds.left)
                }
            }
            composeRule.onNodeWithTag("vlm_restart_flow").assertTextContains("重新开始").performScrollTo().performClick()
            assertEquals(VlmIntent.RestartFlow, events.last())
        }
        composeRule.runOnIdle { state.value = VlmUiState(stage = VlmStage.SCANNING, cameraReady = true) }
        assertNoCancelActions()
        composeRule.onNodeWithTag("vlm_scan_restart_active").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_restart_flow").assertTextContains("重新开始").performClick()
        assertEquals(VlmIntent.RestartFlow, events.last())
        composeRule.runOnIdle { state.value = VlmUiState(stage = VlmStage.FREEZING, cameraReady = true) }
        assertNoCancelActions()
        composeRule.onNodeWithTag("vlm_scan_restart_active").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_restart_flow").assertTextContains("重新开始").performClick()
        assertEquals(VlmIntent.RestartFlow, events.last())
    }

    @Test fun restartActionsRemainAvailableDuringGenerationButNotModelManagement() {
        val state = mutableStateOf(VlmUiState(stage = VlmStage.GENERATING,
            scene = SceneSnapshot("scene",640,480,1f,"/scene","/input")))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { Controls(state.value,{ events += it },{},Modifier.fillMaxWidth()) } }
        val tags = listOf("vlm_restart_flow", "vlm_generate")
        val bounds = tags.map { composeRule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEach { (left, right) ->
            assertEquals(left.top, right.top)
            org.junit.Assert.assertTrue(left.right <= right.left)
        }
        composeRule.onNodeWithTag("vlm_generate").assertTextContains("继续生成")
        composeRule.onNodeWithTag("vlm_generate").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("vlm_background_generate_confirm").performClick()
        composeRule.onNodeWithTag("vlm_restart_flow").assertTextContains("重新开始").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("vlm_cancel").assertDoesNotExist()
        assertEquals(listOf(VlmIntent.Generate,VlmIntent.RestartFlow), events)
        composeRule.runOnIdle { state.value = state.value.copy(modelBusy = true) }
        composeRule.onNodeWithTag("vlm_generate").assertIsNotEnabled()
        composeRule.onNodeWithTag("vlm_restart_flow").assertIsNotEnabled()
    }

    @Test fun zoomDefaultsToOneAndHighlightsActualSelectedRatioWithLargerText() {
        val state = mutableStateOf(VlmUiState(cameraReady = true,
            camera = CameraCapabilities(.6f, 4f, listOf(.6f, 1f, 2f))))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            CameraBottomBar(state.value, events::add, {}, true)
        } }
        fun assertZoomStyle(ratio: Float, selected: Boolean) {
            val node = composeRule.onNodeWithTag("vlm_zoom_$ratio")
            if (selected) node.assertIsSelected() else node.assertIsNotSelected()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(if (selected) 16.sp else 14.sp, layouts.single().layoutInput.style.fontSize)
            assertEquals(if (selected) Color(0xFFFFD66A) else Color.White, layouts.single().layoutInput.style.color)
        }
        assertEquals(1f, state.value.zoom, 0f)
        assertEquals(1f, state.value.actualZoom, 0f)
        assertZoomStyle(1f, true)
        assertZoomStyle(.6f, false)
        assertZoomStyle(2f, false)
        composeRule.onNodeWithTag("vlm_zoom_2.0").performClick()
        assertEquals(listOf(VlmIntent.ZoomChanged(2f)), events)
        composeRule.runOnIdle { state.value = state.value.copy(zoom = 2f, actualZoom = 2f) }
        assertZoomStyle(1f, false)
        assertZoomStyle(2f, true)
    }

    @Test fun zoomButtonsFollowUpdatedDeviceCapabilities() {
        val state = mutableStateOf(VlmUiState(cameraReady = true,
            camera = CameraCapabilities(1f, 2f, CameraZoomPolicy.presets(1f, 2f))))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme { CameraBottomBar(state.value, events::add, {}, true) } }
        composeRule.onNodeWithTag("vlm_zoom_3.0").assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = state.value.copy(camera = CameraCapabilities(1f, 8f,
                CameraZoomPolicy.presets(1f, 8f)))
        }
        composeRule.onNodeWithTag("vlm_zoom_2.0").assertExists()
        composeRule.onNodeWithTag("vlm_zoom_3.0").assertExists()
        composeRule.onNodeWithTag("vlm_zoom_5.0").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_zoom_8.0").performScrollTo().performClick()
        assertEquals(listOf(VlmIntent.ZoomChanged(8f)), events)
        // 选中态只跟随相机回报，点击目标尚未执行时仍保持 1×。
        composeRule.onNodeWithTag("vlm_zoom_1.0").assertIsSelected()
        composeRule.runOnIdle { state.value = state.value.copy(zoom = 8f, actualZoom = 8f) }
        composeRule.onNodeWithTag("vlm_zoom_8.0").assertIsSelected()
    }

    @Test fun compositionSelectionCollapsesTogetherAndDetailsFollowSelectedPlan() {
        val plans = (1..3).map { CompositionPlan("p$it", "测试标题$it", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(), "指导", "背景") }
        val state = mutableStateOf(VlmUiState(stage = VlmStage.READY, selectedId = "p1",
            scene = SceneSnapshot("scene", 480, 640, 1f, "/scene", "/input"),
            compositionDetailsVisible = true, compositionDetailsExpanded = true,
            result = CompositionResult(plans, emptyList(), emptyList(), emptyList())))
        val events = mutableListOf<VlmIntent>()
        val onEvent: (VlmIntent) -> Unit = { event ->
            events += event
            when (event) {
                is VlmIntent.Select -> state.value = state.value.copy(selectedId = event.id)
                is VlmIntent.CompositionDetailsVisible -> state.value = state.value.copy(compositionDetailsVisible = event.visible, compositionDetailsExpanded = false)
                is VlmIntent.CompositionDetailsExpanded -> state.value = state.value.copy(compositionDetailsExpanded = event.expanded)
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
        composeRule.onNodeWithTag("vlm_apply").assertIsDisplayed().performClick()
        assertEquals(listOf(VlmIntent.Select("p3"), VlmIntent.Apply), events)
        composeRule.onNodeWithTag("vlm_details").performClick()
        composeRule.onNodeWithTag("vlm_composition_choices").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_apply").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_details").performClick()
        composeRule.onNodeWithTag("vlm_composition_p3").assertIsDisplayed()
        composeRule.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_composition_info_toggle").performClick()
        composeRule.onNodeWithText("测试标题3", substring = true).assertExists()
    }

    @Test fun generatedAndAppliedCompositionActionsKeepOrderWithoutCancelActions() {
        val plans = (1..3).map { CompositionPlan("p$it", "测试标题$it", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(), "指导", "背景") }
        val state = mutableStateOf(VlmUiState(stage = VlmStage.READY, selectedId = "p1", cameraReady = true,
            scene = SceneSnapshot("scene", 640, 480, 1f, "/scene", "/input"),
            result = CompositionResult(plans, emptyList(), emptyList(), emptyList())))
        val events = mutableListOf<VlmIntent>()
        composeRule.setContent { MaterialTheme {
            Controls(state.value, events::add, {}, Modifier.fillMaxWidth())
        } }

        fun assertActionOrder(tags: List<String>) {
            val bounds = tags.map { composeRule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
            bounds.zipWithNext().forEach { (left, right) ->
                assertEquals(left.top, right.top)
                org.junit.Assert.assertTrue(left.right <= right.left)
            }
        }

        val generatedTags = listOf("vlm_restart_flow", "vlm_generate", "vlm_details")
        assertActionOrder(generatedTags)
        composeRule.onNodeWithTag("vlm_generate").assertTextContains("继续生成")
        composeRule.onNodeWithTag("vlm_resume").assertDoesNotExist()
        generatedTags.forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            if (tag == "vlm_generate") composeRule.onNodeWithTag("vlm_background_generate_confirm").performClick()
        }
        assertEquals(listOf(VlmIntent.RestartFlow, VlmIntent.Generate,
            VlmIntent.CompositionDetailsVisible(true)), events)

        composeRule.runOnIdle {
            events.clear()
            state.value = state.value.copy(stage = VlmStage.LIVE, referencePlan = plans.first())
        }
        assertActionOrder(listOf("vlm_restart_flow", "vlm_details"))
        composeRule.onNodeWithTag("vlm_restart_flow").assertTextContains("重新开始")
        composeRule.onNodeWithTag("vlm_start").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_scan_start").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_resume").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_restart_flow").performClick()
        assertEquals(listOf(VlmIntent.RestartFlow), events)

        composeRule.runOnIdle { state.value = VlmUiState(cameraReady = true) }
        composeRule.onNodeWithTag("vlm_resume").assertDoesNotExist()
        composeRule.onNodeWithTag("vlm_scan_start").assertTextContains("开始扫描")
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
