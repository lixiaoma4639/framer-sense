package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 同名 p1/p2/p3 只在各自背景内唯一，切换不合并或覆盖当前三方案协议。 */
class BackgroundCompositionsTest {
    private fun group(id: String, rotation: Int = 0): BackgroundComposition {
        val scene = SceneSnapshot("scene-$id", 640, 480, 1f, "/$id.jpg", "/model-$id.jpg",
            orientation = PhotoOrientation(rotation, 0))
        val binding = PhotoViewBinding("scan", "frame-$id", 7)
        val result = CompositionResult((1..3).map {
            CompositionPlan("p$it", "$id-$it", ShotType.FULL, CropRect(), 1f, AvatarPlacement(),
                "自然站立", "保留背景", viewBinding = binding)
        }, emptyList(), emptyList(), emptyList())
        return BackgroundComposition(scene, "frame-$id", result, result, emptyMap(),
            mapOf("p1" to emptyList()), mapOf("p1" to listOf(ExpressionId.SMILE)), "p2")
    }

    private fun state(group: BackgroundComposition) = VlmUiState(stage = VlmStage.READY,
        scene = group.scene, selectedFrameId = group.frameId, result = group.result,
        selectedId = group.selectedId, compositionBatchId = group.batchId, diagnostics = group.diagnostics, previews = group.previews,
        availableActions = group.actions, availableExpressions = group.expressions)

    @Test fun manualFallbackExposesEveryScanViewAndKeepsTheirOrderAfterGeneration() {
        val groups = (1..6).map { group("$it") }
        val frames = groups.mapIndexed { index, group -> ScanFrame(group.frameId!!, "scan", index.toLong(), index.toLong(),
            ScanCameraConfig(7, 0, 1f, CropRect(), false), null, 1f, group.scene, 100) }
        val fallback = VlmUiState(stage = VlmStage.SCAN_REVIEW,
            scanSnapshot = ScanSnapshot(ScanSession("scan", 0, 10), frames, 0f))
        assertEquals(frames, fallback.rankedBackgroundFrames)
        assertEquals((1..6).toList(), fallback.compositionChoices.map { it.rank })
        assertTrue(fallback.compositionChoices.all { it is CompositionChoice.PendingBackground })
        assertNull(fallback.scene)
        assertNull(fallback.selectedFrameId)
        val preview = fallback.previewBackground(frames[4])
        assertEquals(frames[4].scene, preview.displayScene)
        assertNull(preview.result)
        assertEquals(6, preview.compositionChoices.size)
        val generated = preview.restoreBackground(groups[4])
        assertEquals(listOf(1, 2, 3, 4, 5, 5, 5, 6), generated.compositionChoices.map { it.rank })
        assertEquals(3, generated.compositionChoices.filterIsInstance<CompositionChoice.Plan>().size)
        // 自动比较成功时仍只列出推荐背景，不额外混入全部扫描帧。
        val ranked = fallback.copy(viewCandidates = frames.reversed().map {
            ViewCandidate(it.id, "", "", emptyList(), emptyList(), emptyList())
        })
        assertEquals(frames.reversed().take(ScanLimits.MAX_COMPOSITION_BACKGROUNDS), ranked.rankedBackgroundFrames)
    }

    @Test fun continuedGenerationKeepsBatchOrderAndEditingAnOlderBatchDoesNotReplaceLaterBatches() {
        val first = group("a").copy(batchId = "round1")
        val initial = state(first).copy(compositionCache = mapOf(first.scene.id to first))
        val secondResult = first.result.copy(plans = first.result.plans.map { it.copy(title = "新构图-${it.id}") })
        val second = initial.copy(compositionBatchId = "round2", result = secondResult)
        val savedSecond = second.copy(compositionCache = second.rememberedBackgrounds())
        assertEquals(listOf("round1", "round2"), savedSecond.currentBackgroundComposition()!!.allBatches.map { it.id })
        assertEquals((0..5).toList(), savedSecond.compositionChoices.filterIsInstance<CompositionChoice.Plan>().map { it.index })
        assertEquals(6, savedSecond.compositionChoices.map { it.key }.distinct().size)
        val old = savedSecond.compositionCache.getValue(first.scene.id).withBatch("round1")!!
        val restored = savedSecond.restoreBackground(old, "p1")
        assertEquals(first.result, restored.result)
        val changed = first.result.plans.first().withRevision(3)
        val edited = restored.copy(result = first.result.copy(plans = listOf(changed) + first.result.plans.drop(1)))
        val third = edited.copy(compositionCache = edited.rememberedBackgrounds(), compositionBatchId = "round3", result = secondResult)
        val history = third.currentBackgroundComposition()!!.allBatches
        assertEquals(listOf("round1", "round2", "round3"), history.map { it.id })
        assertEquals(changed, history[0].result.plans.first())
        assertEquals(secondResult, history[1].result)
        assertEquals((0..8).toList(), third.compositionChoices.filterIsInstance<CompositionChoice.Plan>().map { it.index })
        assertEquals(3, third.result!!.plans.size)
        assertNull(third.currentBackgroundComposition()!!.withBatch("missing"))
    }

    @Test fun previewingPendingBackgroundShowsOnlyItsOriginalAndRetainsEditedCompositionsForReselection() {
        val first = group("a")
        val pending = group("b", 1)
        val frame = ScanFrame(pending.frameId!!, "scan", 1, 1, ScanCameraConfig(7, 0, 1f, CropRect(), false),
            null, 1f, pending.scene, 100)
        val changed = first.result.plans.first().withRevision(4)
        val before = state(first).copy(result = first.result.copy(plans = listOf(changed) + first.result.plans.drop(1)),
            compositionDetailsVisible = true, referencePlan = changed,
            capturedWorkflow = CameraWorkflowKey(first.scene.id, changed.id, changed.revision))
        val preview = before.previewBackground(frame)
        assertEquals(VlmStage.FROZEN, preview.stage)
        assertEquals(pending.scene, preview.displayScene)
        assertEquals(pending.scene.orientation, preview.scene!!.orientation)
        assertEquals(pending.frameId, preview.selectedFrameId)
        assertNull(preview.selectedId)
        assertNull(preview.result)
        assertTrue(preview.previews.isEmpty())
        assertNull(preview.referencePlan)
        assertNull(preview.capturedWorkflow)
        assertTrue(preview.compositionDetailsVisible)
        assertEquals(changed, preview.compositionCache.getValue(first.scene.id).result.plans.first())
        val restored = preview.restoreBackground(preview.compositionCache.getValue(first.scene.id), "p1")
        assertEquals(first.scene, restored.displayScene)
        assertEquals(changed, restored.result!!.plans.first())
        assertEquals("p1", restored.selectedId)
    }

    @Test fun pendingBackgroundsExpandAtTheirRankAfterConfirmationEvenWhenThirdFinishesBeforeSecond() {
        val groups = listOf(group("a"), group("b"), group("c"))
        val frames = groups.mapIndexed { index, group -> ScanFrame(group.frameId!!, "scan", index.toLong(), index.toLong(),
            ScanCameraConfig(7, 0, 1f, CropRect(), false), null, 1f, group.scene, 100) }
        val first = state(groups[0]).copy(compositionCache = mapOf(groups[0].scene.id to groups[0]),
            scanSnapshot = ScanSnapshot(ScanSession("scan", 0, 10), frames, 0f),
            viewCandidates = frames.map { ViewCandidate(it.id, "", "", emptyList(), emptyList(), emptyList()) })
        val expectedFirst = groups[0].result.plans.map { "plan:${groups[0].scene.id}:${groups[0].batchId}:${it.id}" }
        assertEquals(expectedFirst + listOf("background:frame-b", "background:frame-c"), first.compositionChoices.map { it.key })
        val thirdReady = first.copy(compositionCache = first.rememberedBackgrounds() + (groups[2].scene.id to groups[2]))
            .restoreBackground(groups[2])
        assertEquals(expectedFirst + "background:frame-b" + groups[2].result.plans.map { "plan:${groups[2].scene.id}:${groups[2].batchId}:${it.id}" },
            thirdReady.compositionChoices.map { it.key })
        assertEquals(listOf(1, 1, 1, 2, 3, 3, 3), thirdReady.compositionChoices.map { it.rank })
        val allReady = thirdReady.copy(compositionCache = thirdReady.rememberedBackgrounds() + (groups[1].scene.id to groups[1]))
            .restoreBackground(groups[1])
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3, 3, 3), allReady.compositionChoices.map { it.rank })
        assertTrue(allReady.compositionChoices.none { it is CompositionChoice.PendingBackground })
        assertEquals(9, allReady.compositionChoices.map { it.key }.distinct().size)
        assertEquals(groups[0].result, allReady.compositionCache.getValue(groups[0].scene.id).result)
    }

    @Test fun switchingBackgroundsKeepsSamePlanIdsAndCurrentEditsWithoutOverwritingOtherGroups() {
        val a = group("a")
        val b = group("b", 1)
        val revised = a.result.plans.first().withRevision(2)
        val editing = state(a).copy(compositionCache = mapOf(a.scene.id to a, b.scene.id to b),
            result = a.result.copy(plans = listOf(revised) + a.result.plans.drop(1)), selectedId = "p3")
        val switched = editing.restoreBackground(b, "p1")
        assertEquals(b.scene, switched.scene)
        assertEquals(b.result, switched.result)
        assertEquals(b.expressions, switched.availableExpressions)
        assertEquals(b.actions, switched.availableActions)
        assertEquals("p1", switched.selectedId)
        assertEquals(2, switched.compositionBackgrounds.size)
        assertEquals(6, switched.compositionBackgrounds.sumOf { it.result.plans.size })
        assertEquals(b.result, switched.compositionCache.getValue(b.scene.id).result)
        val restored = switched.restoreBackground(switched.compositionCache.getValue(a.scene.id))
        assertEquals(revised, restored.result!!.plans.first())
        assertEquals("p3", restored.selectedId)
        assertEquals(a.scene, restored.scene)
        assertEquals(a.frameId, restored.selectedFrameId)
        assertEquals(a.result.plans.first().viewBinding, restored.result!!.plans.first().viewBinding)
    }

    @Test fun canceledOrFailedNewBackgroundStillExposesEarlierChoicesAndRestoresTheirSource() {
        val a = group("a", 3)
        val pending = VlmUiState(stage = VlmStage.FROZEN, scene = group("b").scene,
            result = null, compositionCache = mapOf(a.scene.id to a), error = "生成失败")
        assertTrue(pending.hasCompositionChoices)
        assertEquals(listOf(a), pending.compositionBackgrounds)
        val restored = pending.restoreBackground(a)
        assertEquals(VlmStage.READY, restored.stage)
        assertEquals(a.scene.orientation, restored.scene!!.orientation)
        assertEquals(a.frameId, restored.selectedFrameId)
        assertEquals(a.diagnostics, restored.diagnostics)
        assertTrue(restored.compositionDetailsVisible)
        assertNull(restored.reference)
        assertNull(restored.referencePlan)
        assertNull(restored.capturedWorkflow)
        assertNull(restored.error)
    }

    @Test fun choicesFollowRankRatherThanGenerationOrderAndRemainThreePlansPerBackground() {
        val groups = listOf(group("a"), group("b"), group("c"))
        val ranked = groups.map { ViewCandidate(it.frameId!!, "", "", emptyList(), emptyList(), emptyList()) }
        val state = state(groups[2]).copy(viewCandidates = ranked,
            compositionCache = listOf(groups[2], groups[0], groups[1]).associateBy { it.scene.id })
        assertEquals(groups.map { it.scene.id }, state.compositionBackgrounds.map { it.scene.id })
        assertEquals(9, state.compositionBackgrounds.sumOf { it.result.plans.size })
        assertEquals(3, state.result!!.plans.size)
        assertEquals(9, state.compositionBackgrounds.flatMap { group ->
            group.result.plans.map { "${group.scene.id}:${it.id}" }
        }.distinct().size)
    }
}
