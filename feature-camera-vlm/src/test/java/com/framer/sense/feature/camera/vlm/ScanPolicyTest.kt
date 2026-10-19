package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.ScanViewAgent
import com.framer.sense.feature.camera.vlm.agent.MultiViewProvider
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

/** 回放采集窗口、方向轨迹、资源限制和跨视角来源；不访问相机、文件或网络。 */
class ScanPolicyTest {
    private val config = ScanCameraConfig(1, 0, 1f, CropRect(), true)
    private fun collector() = ScanCollector(ScanSession("scan", 100)).apply { configure(1) }
    private fun frame(id: String = "f", quality: Float = 1f, bytes: Long = 100, preferred: Boolean = false, heading: Float = 0f) =
        ScanFrame(id, "scan", 200, 200, config, ScanPose("basis", 200, heading, 0f), quality,
            SceneSnapshot(id, 640, 480, 1f, "/scene", "/input"), bytes, preferred)
    private fun candidate(id: String = "f", region: CropRect = CropRect()) =
        ViewCandidate(id, "山", "保留山体", listOf(region), emptyList(), listOf("地面待确认"))

    @Test fun rejectsBeforeWindowWrongSessionAndOldConfig() {
        val c = collector()
        assertFalse(c.accepts("old", 200, 200, config))
        assertFalse(c.accepts("scan", 99, 200, config))
        assertFalse(c.accepts("scan", 200, 99, config))
        c.configure(2)
        assertFalse(c.accepts("scan", 200, 200, config))
        assertTrue(c.accepts("scan", 200, 200, config.copy(version = 2)))
    }
    @Test fun endingIsIdempotentAndOnlyDrainsAcceptedWindow() {
        val c = collector(); c.stop(300); c.stop(400)
        assertTrue(c.accepts("scan", 250, 250, config))
        assertFalse(c.accepts("scan", 301, 250, config))
        assertFalse(c.accepts("scan", 250, 301, config))
        assertEquals(300L, c.snapshot().session.endedNs)
    }
    @Test fun unknownClockUsesReceiptWindowWithoutInventingPose() {
        assertTrue(collector().accepts("scan", 1, 200, config.copy(realtimeTimestamp = false)))
        assertFalse(sameDirection(null, null))
    }
    @Test fun maximumTimePausesWithoutEndingSession() {
        val c = collector()
        assertFalse(c.accepts("scan", 200, 100 + ScanLimits.MAX_DURATION_NS + 1, config))
        c.pause(); assertTrue(c.isPaused())
        assertThrows(IllegalArgumentException::class.java) { c.snapshot() }
    }
    @Test fun crossing180AndReversalCountTravelNotCoverage() {
        val c = collector()
        listOf(170f, -170f, 170f, 160f).forEach { c.observePose(ScanPose("b", 0, it, 0f)) }
        assertEquals(50f, c.progress(200).totalTurn, .001f)
        assertEquals(2f, angleDelta(-179f, 179f), .001f)
        assertEquals(0f, angleDelta(-1080f, 720f), .001f)
    }
    @Test fun twoFullCirclesAreSeparateFromRetainedViews() {
        val c = collector()
        (0..24).forEach { c.observePose(ScanPose("b", it.toLong(), ((it * 30 + 180) % 360 - 180).toFloat(), 0f)) }
        assertEquals(720f, c.progress(200).totalTurn, .001f)
        assertEquals(0, c.frames().size)
    }
    @Test fun sensorTravelPreservesFastTurnsBetweenImageSamples() {
        val c = collector()
        c.observePose(ScanPose("b", 0, 0f, 0f, 20f))
        c.observePose(ScanPose("b", 1, 0f, 0f, 740f))
        assertEquals(720f, c.progress(200).totalTurn, .001f)
    }
    @Test fun verticalAxisAndChangedBasisDoNotInventHeadingTravel() {
        val c = collector()
        c.observePose(ScanPose("b", 0, 10f, 0f)); c.observePose(ScanPose("b", 1, null, 89f))
        c.observePose(ScanPose("b", 2, 170f, 0f)); c.observePose(ScanPose("new", 3, -20f, 0f))
        assertEquals(0f, c.progress(200).totalTurn, .001f)
        assertFalse(sameDirection(ScanPose("b", 0, 0f, 0f), ScanPose("b", 0, 0f, 30f)))
    }
    @Test fun duplicateImprovementReplacesOldFrame() {
        val c = collector(); c.add(frame("old"), null)
        assertEquals("old", c.add(frame("better", 2f), "old")?.id)
        assertEquals(listOf("better"), c.frames().map { it.id })
    }
    @Test fun preferredFrameCannotBeReplacedBySharperDuplicate() {
        val c = collector(); c.add(frame("favorite", preferred = true), null)
        assertEquals("new", c.add(frame("new", 9f), "favorite")?.id)
        assertEquals("favorite", c.frames().single().id)
    }
    @Test fun frameCapacityRetainsFavoritesAndReplacesWeakest() {
        val c = collector()
        repeat(32) { c.add(frame("$it", it.toFloat(), preferred = it < 3), null) }
        assertEquals("3", c.add(frame("new", 99f), null)?.id)
        assertEquals(32, c.frames().size); assertEquals(3, c.frames().count { it.preferred })
    }
    @Test fun threeFavoritesMaximumAndStaleMarkRejected() {
        val c = collector()
        repeat(3) { c.add(frame("$it", preferred = true), null) }
        c.add(frame("extra"), null); assertFalse(c.preferLatest(200))
        val stale = collector(); stale.add(frame(), null)
        assertFalse(stale.preferLatest(600_000_201))
    }
    @Test fun byteLimitRejectsAndPreservesExistingFiles() {
        val c = collector(); c.add(frame("full", bytes = ScanLimits.MAX_FILE_BYTES - 1), null)
        assertEquals("extra", c.add(frame("extra", bytes = 2), null)?.id)
        assertTrue(c.isPaused()); assertEquals("full", c.frames().single().id)
    }
    @Test fun frozenInputDoesNotChangeAfterCollectorMutations() {
        val c = collector(); c.add(frame(), null); c.stop(300); val frozen = c.snapshot()
        c.add(frame("new"), null); c.preferLatest(200)
        assertEquals(1, frozen.frames.size); assertFalse(frozen.frames.first().preferred)
        assertThrows(UnsupportedOperationException::class.java) { (frozen.frames as MutableList).clear() }
    }
    @Test fun sixActualCallsIncludeRetriesAndDoNotReset() {
        val b = PhotoSessionBudget(); repeat(6) { b.consume(10) }
        assertThrows(IllegalStateException::class.java) { b.consume(0) }
        assertEquals(6, b.calls); assertEquals(60L, b.uploadedBytes)
    }
    @Test fun uploadLimitAndRejectedConsumptionAreAtomic() {
        val b = PhotoSessionBudget(); b.consume(ScanLimits.MAX_SESSION_BYTES)
        assertThrows(IllegalStateException::class.java) { b.consume(1) }
        assertEquals(1, b.calls); assertEquals(ScanLimits.MAX_SESSION_BYTES, b.uploadedBytes)
    }
    @Test fun viewSelectionCannotReferToAnotherScanOrFrame() {
        val good = ViewSelection(scanId = "scan", primary = candidate(), alternatives = emptyList())
        ViewSelectionValidator.validate(good, "scan", setOf("f"))
        assertThrows(IllegalArgumentException::class.java) { ViewSelectionValidator.validate(good, "other", setOf("f")) }
        assertThrows(IllegalArgumentException::class.java) { ViewSelectionValidator.validate(good, "scan", setOf("other")) }
    }
    @Test fun duplicateReferenceAndInvalidRegionsAreRejected() {
        val selection = ViewSelection(scanId = "scan", primary = candidate(), alternatives = listOf(candidate()))
        assertThrows(IllegalArgumentException::class.java) { ViewSelectionValidator.validate(selection, "scan", setOf("f")) }
        val invalid = selection.copy(primary = candidate(region = CropRect(.7f, 0f, .2f, 1f)), alternatives = emptyList())
        assertThrows(IllegalArgumentException::class.java) { ViewSelectionValidator.validate(invalid, "scan", setOf("f")) }
    }
    @Test fun representativeFramesKeepPreferencesAndDirectionDiversity() {
        val frames = (0..31).map { frame("$it", quality = it.toFloat(), preferred = it < 3, heading = it * 10f) }
        val chosen = ScanViewAgent().representativeFrames(frames, 8)
        assertEquals(8, chosen.size); assertEquals(3, chosen.count { it.preferred })
        assertTrue(chosen.any { it.pose!!.heading!! in 150f..210f })
    }
    @Test fun offlineSelectionNeverContactsGateway() = runBlocking {
        val scan = ScanSnapshot(ScanSession("scan", 100, 300), listOf(frame()), 0f)
        val result = ScanViewAgent().select(scan, ModelSettings(mode = ModelMode.OFFLINE), "", PhotoSessionBudget()) { error("不能联网") }
        assertNull(result.selection)
        assertEquals(com.framer.sense.feature.camera.vlm.agent.ScanSelectionFallback.OFFLINE, result.fallback)
    }
    @Test fun undeclaredMultiImageCapabilityFallsBackBeforeUploading() = runBlocking {
        val b = PhotoSessionBudget()
        val scan = ScanSnapshot(ScanSession("scan", 100, 300), listOf(frame()), 0f)
        val result = ScanViewAgent().select(scan, ModelSettings(mode = ModelMode.AUTO), "", b) {
            object : MultiViewProvider {
                override suspend fun capabilities() = MultiImageCapabilities(emptyList())
                override suspend fun select(request: ViewSelectionRequest): ViewSelectionReply = error("不能上传")
            }
        }
        assertNull(result.selection); assertEquals(0, b.calls)
        assertEquals(com.framer.sense.feature.camera.vlm.agent.ScanSelectionFallback.NO_MULTIVIEW_MODEL, result.fallback)
    }
    @Test fun sourceBindingDoesNotAlterV1Serialization() {
        val p = CompositionPlan("p", "环境", ShotType.ENVIRONMENT, CropRect(), 1f, AvatarPlacement(), "背景", "指导")
        assertEquals(VlmJson.encodeToString(p), VlmJson.encodeToString(p.copy(viewBinding = PhotoViewBinding("scan", "f", 1))))
        assertNull(VlmJson.decodeFromString<CompositionPlan>(VlmJson.encodeToString(p)).viewBinding)
    }
    @Test fun matchingRequiresDistributedEvidenceAndGeometry() {
        val m = MatchMetrics(40, 30, .6f, 4, .01f, 1f, 1.02f, true)
        assertTrue(BackgroundMatchPolicy.aligned(m))
        assertFalse(BackgroundMatchPolicy.confident(m.copy(quadrants = 1)))
        assertFalse(BackgroundMatchPolicy.confident(m.copy(inliers = 14)))
        assertFalse(BackgroundMatchPolicy.aligned(m.copy(cornerError = .04f)))
        assertFalse(BackgroundMatchPolicy.aligned(m.copy(rotation = 4f)))
        assertFalse(BackgroundMatchPolicy.aligned(m.copy(scale = 1.09f)))
        assertFalse(BackgroundMatchPolicy.aligned(m.copy(valid = false)))
    }
    @Test fun guidanceAllowsHandheldOffsetsWithoutLooseningScanDeduplication() {
        val near = MatchMetrics(40, 30, .6f, 4, .05f, 5f, 1.12f, true)
        assertTrue(BackgroundMatchPolicy.alignedForGuidance(near))
        assertTrue(BackgroundMatchPolicy.alignedForGuidance(near.copy(rotation = -5f, scale = .88f)))
        assertFalse(BackgroundMatchPolicy.aligned(near))
        assertFalse(BackgroundMatchPolicy.alignedForGuidance(near.copy(cornerError = .061f)))
        assertFalse(BackgroundMatchPolicy.alignedForGuidance(near.copy(rotation = 6.1f)))
        assertFalse(BackgroundMatchPolicy.alignedForGuidance(near.copy(rotation = -6.1f)))
        assertFalse(BackgroundMatchPolicy.alignedForGuidance(near.copy(scale = 1.151f)))
        assertFalse(BackgroundMatchPolicy.alignedForGuidance(near.copy(scale = .849f)))
    }
    @Test fun widerGuidanceStillRequiresReliableDistributedMatching() {
        val near = MatchMetrics(40, 30, .6f, 4, .05f, 5f, 1.12f, true)
        for (unreliable in listOf(near.copy(valid = false), near.copy(matches = 29),
            near.copy(inliers = 14), near.copy(inliers = 19), near.copy(coverage = .19f), near.copy(quadrants = 2))) {
            assertFalse(BackgroundMatchPolicy.alignedForGuidance(unreliable))
        }
    }
    @Test fun strongPartialEvidenceFromTheFailedPlanCanConfirmWithoutChangingScanDeduplication() {
        // 用户日志：大量可靠内点，但裁剪／排除人物后只分布在两个象限。
        val frames = listOf(
            MatchMetrics(129, 99, .43177503f, 3, .043330453f, -5.697964f, 1.0258524f, true),
            MatchMetrics(141, 116, .22345309f, 2, .037426952f, -4.6844883f, 1.0205429f, true),
            MatchMetrics(137, 112, .22437337f, 2, .04266153f, -5.4642687f, 1.0150462f, true),
            MatchMetrics(121, 102, .1673298f, 2, .051066786f, -5.456021f, 1.0533407f, true)
        )
        val stability = BackgroundStability()
        frames.forEachIndexed { index, frame ->
            assertTrue(BackgroundMatchPolicy.alignedForGuidance(frame))
            assertNull(BackgroundMatchPolicy.guidanceRejection(frame))
            // 按正常采样间隔回放这些指标，不将日志输出间隔当成采样间隔。
            assertEquals(index == 3, stability.update(true, index * 200_000_000L))
        }
        assertFalse(BackgroundMatchPolicy.confident(frames.last()))
        assertFalse(BackgroundMatchPolicy.aligned(frames.last()))
    }
    @Test fun partialDistributionCannotPassWithoutStrongIndependentEvidence() {
        val strong = MatchMetrics(80, 64, .18f, 2, .04f, 2f, 1.02f, true)
        assertTrue(BackgroundMatchPolicy.confidentForGuidance(strong))
        for (weak in listOf(strong.copy(matches = 44, inliers = 35), strong.copy(inliers = 29),
            strong.copy(inliers = 51), strong.copy(coverage = .149f), strong.copy(quadrants = 1),
            strong.copy(valid = false), strong.copy(cornerError = .2f))) {
            assertFalse(BackgroundMatchPolicy.alignedForGuidance(weak))
            assertNotNull(BackgroundMatchPolicy.guidanceRejection(weak))
        }
    }
    @Test fun oneBorderlineFrameCanBeBridgedButCannotItselfConfirm() {
        val good = MatchMetrics(80, 64, .3f, 3, .04f, 2f, 1.02f, true)
        val jitter = good.copy(rotation = 6.2f)
        assertFalse(BackgroundMatchPolicy.alignedForGuidance(jitter))
        assertTrue(BackgroundMatchPolicy.canBridgeGuidance(jitter))
        val stability = BackgroundStability()
        assertFalse(stability.update(true, 0))
        assertFalse(stability.update(true, 200_000_000))
        assertFalse(stability.update(false, 400_000_000, allowBriefGap = true))
        assertEquals(2, stability.candidateFrames)
        assertTrue(stability.update(true, 600_000_000))
        assertEquals(3, stability.candidateFrames)
        assertEquals(1, stability.briefGaps)
        assertEquals(0, stability.resetCount)
        assertFalse(BackgroundMatchPolicy.canBridgeGuidance(good.copy(cornerError = .2f)))
        assertFalse(BackgroundMatchPolicy.canBridgeGuidance(good.copy(rotation = 20f)))
        assertFalse(BackgroundMatchPolicy.canBridgeGuidance(good.copy(inliers = 5)))
    }
    @Test fun consecutiveBorderlineOrUnmatchedFramesResetTheCandidate() {
        for (bridgeable in listOf(false, true)) {
            val stability = BackgroundStability()
            assertFalse(stability.update(true, 0))
            assertFalse(stability.update(false, 200_000_000, allowBriefGap = bridgeable))
            assertFalse(stability.update(false, 400_000_000, allowBriefGap = bridgeable))
            assertEquals(0, stability.candidateFrames)
            assertFalse(stability.update(true, 600_000_000))
            assertEquals(1, stability.candidateFrames)
        }
    }
    @Test fun longSampleGapsAndOnlyTwoGoodFramesCannotConfirm() {
        val stability = BackgroundStability()
        assertFalse(stability.update(false, 0, allowBriefGap = true))
        assertFalse(stability.update(true, 200_000_000))
        assertFalse(stability.update(true, 700_000_000))
        assertEquals(2, stability.candidateFrames)
        assertFalse(stability.update(true, 1_300_000_000))
        assertEquals(1, stability.candidateFrames)
        assertEquals(0L, stability.stableNs)
        assertFalse(stability.update(false, 1_900_000_000, allowBriefGap = true))
        assertEquals(0, stability.candidateFrames)
    }
    @Test fun alignmentNeeds500msAndResetsOnFailure() {
        val s = BackgroundStability()
        assertFalse(s.update(true, 100)); assertFalse(s.update(true, 499_000_100))
        assertEquals(2, s.candidateFrames)
        assertEquals(499_000_000L, s.stableNs)
        assertTrue(s.update(true, 500_000_100)); assertFalse(s.update(false, 600_000_100))
        assertEquals(0, s.candidateFrames)
        assertEquals(0L, s.stableNs)
        assertEquals(1, s.resetCount)
        assertFalse(s.update(true, 700_000_100)); assertFalse(s.update(true, 1_199_000_100))
        assertTrue(s.update(true, 1_200_000_100))
        s.reset(); assertFalse(s.update(true, 2_000_000_100))
    }
    @Test fun diagnosticsIdentifyMatchingAndGeometryRejections() {
        val accepted = MatchMetrics(40, 30, .6f, 4, .01f, 1f, 1.02f, true)
        assertNull(BackgroundMatchPolicy.guidanceRejection(accepted))
        assertEquals("INLIER_RATIO", BackgroundMatchPolicy.guidanceRejection(accepted.copy(inliers = 19)))
        assertEquals("COVERAGE", BackgroundMatchPolicy.guidanceRejection(accepted.copy(coverage = .14f)))
        assertEquals("QUADRANTS", BackgroundMatchPolicy.guidanceRejection(accepted.copy(quadrants = 1)))
        assertEquals("PARTIAL_EVIDENCE", BackgroundMatchPolicy.guidanceRejection(accepted.copy(quadrants = 2)))
        assertEquals("CORNER_OFFSET", BackgroundMatchPolicy.guidanceRejection(accepted.copy(cornerError = .07f)))
        assertEquals("ROTATION", BackgroundMatchPolicy.guidanceRejection(accepted.copy(rotation = 7f)))
        assertEquals("SCALE", BackgroundMatchPolicy.guidanceRejection(accepted.copy(scale = 1.2f)))
    }
    @Test fun unknownPoseKeepsTemporalDiversityAfterCapacity() {
        val c = collector()
        repeat(100) { index ->
            c.add(frame("$index", quality = 0f).copy(pose = null, receivedNs = index * 250_000_000L), null)
        }
        assertEquals(32, c.frames().size)
        assertTrue(c.frames().any { it.receivedNs < 2_000_000_000L })
        assertTrue(c.frames().any { it.receivedNs > 23_000_000_000L })
    }

    @Test fun resolvedActionAndLayoutKeepFrameBindingAndValidatorRejectsDifferentSource() {
        val binding = PhotoViewBinding("scan", "f", 1)
        val old = CompositionPlan("p", "环境", ShotType.ENVIRONMENT, CropRect(), 1f,
            AvatarPlacement(height = .3f), "背景", "指导", viewBinding = binding)
        val resolved = com.framer.sense.feature.camera.vlm.avatar.PhotoActionResolver().resolve(old)
        val moved = resolved.withActualAvatar(resolved.avatar.copy(foot = Point2(.7f, .9f))).withRevision(2)
        assertEquals(binding, moved.resolvedSpec!!.viewBinding)
        assertTrue(moved.resolvedSpec.matches(moved))
        assertFalse(moved.resolvedSpec.matches(moved.copy(viewBinding = binding.copy(frameId = "other"))))
        val input = DirectorInput(frame().scene, CameraCapabilities(), "", viewBinding = binding)
        val errors = com.framer.sense.feature.camera.vlm.agent.PlanValidator().validate(listOf(old.copy(viewBinding = null)), input)
        assertTrue(errors.any { "VIEW_BINDING_MISMATCH" in it })
    }

    @Test fun selectionRetryLimitPersistsAcrossExplicitResubmission() {
        val budget = PhotoSessionBudget()
        budget.consume(10, selection = true); budget.consume(10, selection = true)
        assertThrows(IllegalStateException::class.java) { budget.consume(10, selection = true) }
        budget.consume(10)
        assertEquals(2, budget.selectionCalls); assertEquals(3, budget.calls)
    }

}
