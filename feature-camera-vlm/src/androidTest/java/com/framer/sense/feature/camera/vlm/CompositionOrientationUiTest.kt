package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
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

    private fun fixtureState() = VlmUiState(stage = VlmStage.READY, selectedId = "p1", compositionDetailsVisible = true, compositionDetailsExpanded = true,
        scene = SceneSnapshot("landscape", 640, 480, 1f, "/scene", "/input", orientation = PhotoOrientation(1, 0)),
        result = CompositionResult((1..3).map {
            CompositionPlan("p$it", "测试方案$it", ShotType.FULL, CropRect(), 1f, AvatarPlacement(), "自然站立", "保留建筑")
        }, emptyList(), emptyList(), emptyList()))

    @Test fun detailsDefaultToHiddenAndJoinedRectangularActionsKeepOneDpGapAndRemainUsable() {
        val state = mutableStateOf(fixtureState().copy(compositionDetailsExpanded = false))
        val phone = mutableFloatStateOf(0f)
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Column(Modifier.size(360.dp, 620.dp)) {
                    CameraTopBar(state.value, {})
                    Controls(state.value, { event ->
                        events += event
                        if (event is VlmIntent.CompositionDetailsExpanded)
                            state.value = state.value.copy(compositionDetailsExpanded = event.expanded)
                    }, {}, Modifier.weight(1f).fillMaxWidth())
                }
            }
        } }
        val toggle = compose.onNodeWithTag("vlm_composition_info_toggle")
        val apply = compose.onNodeWithTag("vlm_apply")
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_composition_choices").assertIsDisplayed()
        toggle.assertIsDisplayed().assertIsNotSelected()
        apply.assertIsDisplayed()
        listOf(0f, 90f, 180f, 270f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            val left = toggle.getUnclippedBoundsInRoot()
            val right = apply.getUnclippedBoundsInRoot()
            assertEquals(1f, (right.left - left.right).value, .1f)
            assertEquals(left.width.value, right.width.value, .1f)
            assertEquals(30f, left.height.value, .1f)
            assertTrue("按钮组不再横向铺满", left.width + right.width + 1.dp < 200.dp)
            assertEquals(left.top, right.top)
            assertEquals(left.bottom, right.bottom)
            val infoText = compose.onNodeWithText("构图详情", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val infoIcon = compose.onNodeWithTag("vlm_composition_info_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val applyText = compose.onNodeWithText("应用构图", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val applyIcon = compose.onNodeWithTag("vlm_apply_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(infoIcon.left > infoText.right)
            assertTrue(applyIcon.left > applyText.right)
        }
        toggle.performClick().assertIsSelected()
        compose.onNodeWithTag("vlm_composition_details_panel").assertIsDisplayed()
        apply.assertIsDisplayed()
        toggle.performClick().assertIsNotSelected()
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_composition_choices").assertIsDisplayed()
        apply.performClick()
        assertEquals(listOf(VlmIntent.CompositionDetailsExpanded(true), VlmIntent.CompositionDetailsExpanded(false), VlmIntent.Apply), events)
        compose.runOnIdle { state.value = state.value.copy(modelBusy = true) }
        toggle.assertIsNotEnabled()
        apply.assertIsNotEnabled()
    }

    @Test fun pendingBackgroundSharesTheCompositionRowAndRequiresConfirmationBeforeGeneration() {
        val fixture = fixtureState()
        val first = fixture.currentBackgroundComposition()!!
        val groups = listOf(first) + (2..3).map { rank -> first.copy(scene = first.scene.copy(id = "background$rank"), frameId = "frame$rank") }
        val frames = groups.mapIndexed { index, group -> ScanFrame(group.frameId ?: "frame1", "scan", index.toLong(), index.toLong(),
            ScanCameraConfig(7, 0, 1f, CropRect(), false), null, 1f, group.scene, 100) }
        val state = mutableStateOf(fixture.copy(compositionCache = mapOf(first.scene.id to first),
            scanSnapshot = ScanSnapshot(ScanSession("scan", 0, 10), frames, 0f),
            viewCandidates = frames.map { ViewCandidate(it.id, "", "", emptyList(), emptyList(), emptyList()) }))
        val events = mutableListOf<VlmIntent>()
        val phone = mutableFloatStateOf(0f)
        val onEvent: (VlmIntent) -> Unit = { event ->
            events += event
            when (event) {
                is VlmIntent.PreviewScanView -> state.value = state.value.previewBackground(frames.first { it.id == event.frameId })
                is VlmIntent.Select -> state.value = state.value.restoreBackground(
                    state.value.rememberedBackgrounds().getValue(event.sceneId ?: state.value.scene!!.id), event.id)
                is VlmIntent.CompositionDetailsExpanded -> state.value = state.value.copy(compositionDetailsExpanded = event.expanded)
                else -> Unit
            }
        }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Controls(state.value, onEvent, {}, Modifier.size(300.dp, 620.dp))
            }
        } }
        val row = compose.onNodeWithTag("vlm_composition_choices")
        compose.onNodeWithTag("vlm_background_choices").assertDoesNotExist()
        compose.onNodeWithText("构图 1-1").assertExists()
        compose.onNodeWithTag("vlm_composition_details_panel").assertExists()
        // 两行背景标签最后一行与构图标签共用字号、起始位置和底边距。
        fun captionStyle(tag: String): androidx.compose.ui.text.TextStyle {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().layoutInput.style
        }
        val planStyle = captionStyle("vlm_composition_label_p1")
        val pendingStyle = captionStyle("vlm_background_label_frame2")
        assertEquals(planStyle.fontSize, pendingStyle.fontSize)
        assertEquals(TextAlign.Start, planStyle.textAlign)
        assertEquals(TextAlign.Start, pendingStyle.textAlign)
        val planLabel = compose.onNodeWithTag("vlm_composition_label_p1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val pendingLabel = compose.onNodeWithTag("vlm_background_label_frame2", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val planCard = compose.onNodeWithTag("vlm_composition_p1").getUnclippedBoundsInRoot()
        val pendingCard = compose.onNodeWithTag("vlm_background_candidate_frame2").getUnclippedBoundsInRoot()
        assertEquals(planLabel.left - planCard.left, pendingLabel.left - pendingCard.left)
        assertEquals(planCard.bottom - planLabel.bottom, pendingCard.bottom - pendingLabel.bottom)
        row.performScrollToNode(hasTestTag("vlm_background_candidate_frame3"))
        compose.onNodeWithText("点击生成\n背景 3").assertExists()
        compose.onNodeWithTag("vlm_background_candidate_frame3").performClick()
        compose.onNodeWithTag("vlm_background_generate_dialog").assertIsDisplayed()
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(VlmIntent.PreviewScanView("frame3")), events)
            assertEquals(frames[2].scene, state.value.displayScene)
            assertNull(state.value.result)
            phone.floatValue = 90f
        }
        compose.onNodeWithTag("vlm_background_generate_cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("vlm_background_generate_dialog").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_candidate_frame3").assertIsSelected()
        compose.runOnIdle { assertTrue(events.none { it is VlmIntent.ScanViewSelected }) }
        // 取消只放弃生成，保留背景预览；仍能选回旧构图并重新打开详情。
        row.performScrollToNode(hasTestTag("vlm_composition_landscape_p2"))
        compose.onNodeWithTag("vlm_composition_landscape_p2").performClick()
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_composition_info_toggle").performClick()
        compose.onNodeWithTag("vlm_composition_details_panel").assertIsDisplayed()
        compose.onNodeWithTag("vlm_composition_p2").assertIsSelected()
        row.performScrollToNode(hasTestTag("vlm_background_candidate_frame3"))
        compose.onNodeWithTag("vlm_background_candidate_frame3").performClick()
        compose.onNodeWithTag("vlm_background_generate_confirm").performClick()
        compose.onNodeWithTag("vlm_background_generate_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(VlmIntent.ScanViewSelected("frame3")), events.filterIsInstance<VlmIntent.ScanViewSelected>())
            state.value = state.value.copy(compositionCache = state.value.rememberedBackgrounds() + (groups[2].scene.id to groups[2]))
                .restoreBackground(groups[2])
        }
        compose.onNodeWithTag("vlm_background_candidate_frame3").assertDoesNotExist()
        row.performScrollToNode(hasTestTag("vlm_background_candidate_frame2"))
        compose.onNodeWithTag("vlm_background_candidate_frame2").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(1, 1, 1, 2, 3, 3, 3), state.value.compositionChoices.map { it.rank })
            assertEquals(first.result, state.value.compositionCache.getValue(first.scene.id).result)
        }
    }

    @Test fun continuedBatchesKeepSequentialLabelsAndOldCardsSelectTheirOwnBatch() {
        val fixture = fixtureState()
        val first = fixture.currentBackgroundComposition()!!
        val state = mutableStateOf(fixture.copy(compositionBatchId = "next-round",
            result = fixture.result!!.copy(plans = fixture.result!!.plans.map {
                it.withActualAvatar(it.avatar.copy(height = .8f, expressionIntensity = .9f))
            }),
            compositionCache = mapOf(first.scene.id to first)))
        val events = mutableListOf<VlmIntent>()
        val onEvent: (VlmIntent) -> Unit = { event ->
            events += event
            if (event is VlmIntent.Select) {
                val group = state.value.rememberedBackgrounds().getValue(event.sceneId ?: state.value.scene!!.id)
                state.value = state.value.restoreBackground(group.withBatch(event.batchId ?: group.batchId)!!, event.id)
            }
        }
        compose.setContent { MaterialTheme { Controls(state.value, onEvent, {}, Modifier.size(300.dp, 620.dp)) } }
        fun sliderValue(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(.8f, sliderValue("vlm_avatar_size_slider"), .001f)
        assertEquals(.9f, sliderValue("vlm_expression_intensity"), .001f)
        compose.onNodeWithText("继续生成").assertExists()
        val row = compose.onNodeWithTag("vlm_composition_choices")
        compose.onNodeWithText("构图 1-4").assertExists()
        row.performScrollToNode(hasTestTag("vlm_composition_landscape_initial_p1"))
        compose.onNodeWithTag("vlm_composition_landscape_initial_p1").performClick()
        compose.runOnIdle {
            assertEquals(VlmIntent.Select("p1", first.scene.id, INITIAL_COMPOSITION_BATCH), events.last())
            assertEquals(INITIAL_COMPOSITION_BATCH, state.value.compositionBatchId)
            assertEquals(6, state.value.compositionChoices.size)
        }
        compose.onNodeWithTag("vlm_composition_p1").assertIsSelected()
        compose.onNodeWithText("构图 1-1").assertExists()
        assertEquals(first.result.plans.first().avatar.height, sliderValue("vlm_avatar_size_slider"), .001f)
        assertEquals(first.result.plans.first().avatar.expressionIntensity, sliderValue("vlm_expression_intensity"), .001f)
        row.performScrollToNode(hasTestTag("vlm_composition_landscape_next-round_p1"))
        compose.onNodeWithTag("vlm_composition_landscape_next-round_p1").performClick()
        compose.runOnIdle { assertEquals("next-round", state.value.compositionBatchId) }
        compose.onNodeWithTag("vlm_composition_p1").assertIsSelected()
        assertEquals(.8f, sliderValue("vlm_avatar_size_slider"), .001f)
    }

    @Test fun nineChoicesScrollHorizontallyAndSamePlanIdsSelectTheirOwnBackground() {
        val groups = (1..3).map { index ->
            val fixture = fixtureState()
            BackgroundComposition(fixture.scene!!.copy(id = "background$index", orientation = PhotoOrientation(index - 1, 0)),
                "frame$index", fixture.result!!, null, emptyMap(), emptyMap(), emptyMap(), "p1")
        }
        val frames = groups.mapIndexed { index, group -> ScanFrame(group.frameId!!, "scan", index.toLong(), index.toLong(),
            ScanCameraConfig(7, 0, 1f, CropRect(), false), null, 1f, group.scene, 100) }
        val state = mutableStateOf(fixtureState().copy(scene = groups[0].scene, selectedFrameId = frames[0].id,
            compositionCache = groups.associateBy { it.scene.id },
            scanSnapshot = ScanSnapshot(ScanSession("scan", 0, 10), frames, 0f),
            viewCandidates = frames.map { ViewCandidate(it.id, "", "", emptyList(), emptyList(), emptyList()) }))
        val phone = mutableFloatStateOf(0f)
        val events = mutableListOf<VlmIntent>()
        val onEvent: (VlmIntent) -> Unit = { event ->
            events += event
            if (event is VlmIntent.Select) {
                val group = state.value.rememberedBackgrounds().getValue(event.sceneId ?: state.value.scene!!.id)
                state.value = state.value.restoreBackground(group, event.id)
            }
        }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                Box(Modifier.width(300.dp)) { CompositionCards(state.value, onEvent) }
            }
        } }
        compose.onNodeWithTag("vlm_background_choices").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_candidate_frame2").assertDoesNotExist()
        val row = compose.onNodeWithTag("vlm_composition_choices")
        assertTrue(row.fetchSemanticsNode().config.contains(SemanticsProperties.HorizontalScrollAxisRange))
        val rowBounds = row.getUnclippedBoundsInRoot()
        row.performScrollToNode(hasTestTag("vlm_composition_background3_p3"))
        compose.onNodeWithTag("vlm_composition_background3_p3").performClick()
        assertEquals(VlmIntent.Select("p3", "background3"), events.last())
        compose.runOnIdle {
            assertEquals("background3", state.value.scene!!.id)
            assertEquals("frame3", state.value.selectedFrameId)
            assertEquals(9, state.value.compositionBackgrounds.sumOf { it.result.plans.size })
        }
        listOf(90f, 180f, 270f, 0f).forEach { angle ->
            compose.runOnIdle { phone.floatValue = angle }
            assertEquals(rowBounds, row.getUnclippedBoundsInRoot())
            compose.onNodeWithTag("vlm_composition_p3").assertIsSelected().assertIsDisplayed()
        }
        row.performScrollToNode(hasTestTag("vlm_composition_background1_p2"))
        compose.onNodeWithTag("vlm_composition_background1_p2").performClick()
        assertEquals(VlmIntent.Select("p2", "background1"), events.last())
    }

    @Test fun compactActionsFloatFourDpAboveTheBottomWithoutReducingDetailBounds() {
        val phone = mutableFloatStateOf(0f)
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                CompositionDetailsPanel(fixtureState(), events::add, Modifier.size(300.dp, 420.dp))
            }
        } }
        listOf(0f, 90f, 180f, 270f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            val panel = compose.onNodeWithTag("vlm_composition_details_overlay").getUnclippedBoundsInRoot()
            assertEquals("详情占满面板，操作区不得占用正文高度", panel,
                compose.onNodeWithTag("vlm_composition_details_panel").getUnclippedBoundsInRoot())
            val button = compose.onNodeWithTag("vlm_apply")
            button.assertIsDisplayed()
            val beforeScroll = button.getUnclippedBoundsInRoot()
            val bottomGap = panel.bottom - beforeScroll.bottom
            assertEquals("$direction 度面板底边距", 4f, bottomGap.value, .1f)
            compose.onNodeWithTag("vlm_director_revision_input").performScrollTo()
            assertEquals(beforeScroll, button.getUnclippedBoundsInRoot())
            // 正文就在按钮后方，滚动或转向后仍由悬浮层接收点击。
            button.assertIsDisplayed().performTouchInput { click() }
        }
        assertEquals(List(4) { VlmIntent.Apply }, events)
    }

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
            compose.onNodeWithTag("vlm_apply").assertIsDisplayed().performTouchInput { click() }
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
