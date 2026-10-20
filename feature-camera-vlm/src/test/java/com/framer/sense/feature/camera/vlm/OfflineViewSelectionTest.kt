package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.data.ModelFailure
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 不加载权重，验证多帧编号、来源及不依赖云端预算的推理契约。 */
class OfflineViewSelectionTest {
    private fun frames(count: Int = 8) = (1..count).map { i -> ScanFrame("f$i", "scan", i.toLong(), i.toLong(),
        ScanCameraConfig(1, 0, 1f, CropRect(), false), ScanPose("basis", i.toLong(), (i - 1) * 360f / count, 0f), i.toFloat(),
        SceneSnapshot("f$i", 640, 480, 1f, "/scene$i", "/input$i"), 100, i == 1) }
    private fun snapshot() = ScanSnapshot(ScanSession("scan", 0, 10), frames(), 0f)
    @Test fun nativeRankingBindsEachPositionToItsOwnSubmittedBackground() = runBlocking {
        var calls = 0
        val submitted = OfflineScanViewAgent().representativeFrames(frames())
        val result = OfflineScanViewAgent().select(snapshot(), "", OfflineViewInference { images, _ ->
            calls++
            assertEquals(submitted.map { it.scene.modelImagePath }, images)
            StepReply("fake-local", "4,2,1")
        })
        val ranked = result.selection!!
        assertEquals(listOf(submitted[3].id, submitted[1].id, submitted[0].id),
            (listOf(ranked.primary) + ranked.alternatives).map { it.frameId })
        assertEquals(1, calls)
        assertEquals(4, result.comparedFrames)
        assertEquals(1, result.traces.size)
        val two = OfflineViewSelectionProtocol.parse("2,1", "scan", frames(2))!!
        assertEquals(listOf("f2", "f1"), (listOf(two.primary) + two.alternatives).map { it.frameId })
    }

    @Test fun duplicateOutOfRangeOrMalformedRankingsCannotChooseAnyBackground() {
        listOf("1,1,2", "1,2,5", "0,2,3", "1,2,3,4", "2, 1", "2,1\n3", "2,", "SELECT=2,1")
            .forEach { assertNull(it, OfflineViewSelectionProtocol.parse(it, "scan", frames(4))) }
        assertNull(OfflineViewSelectionProtocol.parse("1,2,3", "scan", frames(2)))
    }

    @Test fun shortProtocolBindsNumberToSubmittedFrameAndNeverGuesses() {
        val frames = frames().take(4)
        val result = OfflineViewSelectionProtocol.parse("SELECT=2\nSUBJECT=山体\nREASON=背景层次清晰", "scan", frames)!!
        assertEquals("f2", result.primary.frameId); assertEquals("scan", result.scanId)
        assertTrue(result.alternatives.isEmpty()); assertTrue(result.primary.backgroundRegions.isEmpty())
        for (raw in listOf("SELECT=0", "SELECT=5", "SELECT=2\nSELECT=3", "推荐第2张", "SELECT=2\nSUBJECT=\nREASON=好", "SELECT=2 or 3"))
            assertNull(OfflineViewSelectionProtocol.parse(raw, "scan", frames))
    }
    @Test fun promptKeepsPreferencesAndRequestsOnlyOneWinner() {
        val text = OfflineViewSelectionProtocol.prompt(frames().take(4), "保留山体")
        assertTrue(text.contains("图片1到4")); assertTrue(text.contains("偏爱图片编号=[1]"))
        assertTrue(text.contains("答案只能是一个数字：1、2、3、4")); assertTrue(text.contains("保留山体"))
        assertTrue(text.contains("人物尚未入镜")); assertTrue(text.contains("不能因无人而拒绝选景"))
        assertTrue(text.contains("禁止0")); assertFalse(text.contains("SELECT="))
    }
    @Test fun diagnosticsDistinguishFrameSelectionFromInvalidOptionalDescriptionFields() {
        val frames = frames().take(2)
        val cases = mapOf(
            "推荐第2张" to OfflineViewSelectionProtocol.ParseFailure.SELECT_LINE_COUNT_INVALID,
            "SELECT=2 or 3" to OfflineViewSelectionProtocol.ParseFailure.SELECT_FORMAT_INVALID,
            "SELECT=3\nSUBJECT=山体\nREASON=好" to OfflineViewSelectionProtocol.ParseFailure.SELECT_OUT_OF_RANGE,
            "SELECT=2\nSUBJECT=" to OfflineViewSelectionProtocol.ParseFailure.SUBJECT_INVALID,
            "SELECT=2\nREASON=" to OfflineViewSelectionProtocol.ParseFailure.REASON_INVALID
        )
        cases.forEach { (raw, expected) ->
            val parsed = OfflineViewSelectionProtocol.inspect(raw, "scan", frames)
            assertEquals(expected, parsed.failure); assertNull(parsed.selection)
            assertNull(OfflineViewSelectionProtocol.parse(raw, "scan", frames))
        }
        val parsed = OfflineViewSelectionProtocol.inspect("SELECT=2\nSUBJECT=山体\nREASON=背景清晰", "scan", frames)
        assertNull(parsed.failure); assertEquals("f2", parsed.selection!!.primary.frameId)
    }
    @Test fun failedSelectionLogsCauseWithoutReportingSuccessfulSelection() = runBlocking {
        val warnings = mutableListOf<String>(); val info = mutableListOf<String>()
        val logger = object : DirectorLogger by NoOpDirectorLogger {
            override fun warn(message: String, error: Throwable?) { warnings += message }
            override fun info(message: String) { info += message }
        }
        try {
            OfflineScanViewAgent(logger).select(snapshot(), "", OfflineViewInference { _, _ -> StepReply("fake", "SELECT=2\nREASON=") })
            fail()
        } catch (failure: ModelFailure) { assertEquals("LOCAL_VIEW_INVALID_OUTPUT", failure.code) }
        assertTrue(warnings.single().contains("reason=REASON_INVALID"))
        assertTrue(info.none { it.contains("离线选景解析成功") })
    }
    @Test fun onlySelectedNumberIsEnoughAndMissingDescriptionsStayUnknown() = runBlocking {
        val result = OfflineScanViewAgent().select(snapshot(), "", OfflineViewInference { _, prompt ->
            assertTrue(prompt.contains("人物尚未入镜"))
            StepReply("fake-local", "2")
        })
        val submitted = OfflineScanViewAgent().representativeFrames(frames())
        val primary = result.selection!!.primary
        assertEquals(submitted[1].id, primary.frameId)
        assertEquals("", primary.subject); assertEquals("", primary.reason)
        assertTrue(primary.backgroundRegions.isEmpty()); assertEquals(4, result.comparedFrames)
    }
    @Test fun observedNoPersonRefusalMustNotFreezeFirstFrame() {
        val raw = """SELECT=0

The image is a photo of an empty room with large sliding glass doors.
I can not select any picture because none contains person who could serve as subject for portrait photography.
This response has been completed according to your request."""
        val parsed = OfflineViewSelectionProtocol.inspect(raw, "scan", frames().take(4))
        assertNull(parsed.selection)
        assertEquals(OfflineViewSelectionProtocol.ParseFailure.SELECT_OUT_OF_RANGE, parsed.failure)
    }
    @Test fun multiImageInputHasFourPathsAndSelectedSourceIsFromThosePaths() = runBlocking {
        var count = 0
        val selected = OfflineScanViewAgent().select(snapshot(), "", OfflineViewInference { images, _ ->
            count++; assertEquals(4, images.size); assertEquals(4, images.distinct().size)
            assertTrue(images.contains("/input1"))
            StepReply("fake-local", "SELECT=2\nSUBJECT=山体\nREASON=适合人像", elapsedMs = 10)
        })
        val submitted = OfflineScanViewAgent().representativeFrames(frames())
        assertEquals(submitted[1].id, selected.selection!!.primary.frameId)
        assertEquals(4, selected.comparedFrames); assertEquals(1, count)
        assertEquals(ProviderId.LOCAL, selected.traces.single().provider)
    }
    @Test fun fourthSubmittedFrameCanWinAndFiveOrZeroCannotBeMappedToAnotherFrame() = runBlocking {
        val submitted = OfflineScanViewAgent().representativeFrames(frames())
        val result = OfflineScanViewAgent().select(snapshot(), "", OfflineViewInference { images, prompt ->
            assertEquals(submitted.map { it.scene.modelImagePath }, images)
            assertTrue(prompt.contains("图片1到4"))
            StepReply("fake-local", "4")
        })
        assertEquals(submitted.last().id, result.selection!!.primary.frameId)
        assertNull(OfflineViewSelectionProtocol.parse("5", "scan", submitted))
        assertNull(OfflineViewSelectionProtocol.parse("0", "scan", submitted))
        assertTrue(runCatching { OfflineViewSelectionProtocol.prompt(frames(5), "") }.isFailure)
    }
    @Test fun largerScanPreservesPreferencesAndDiversityWithChronologicalPresentation() {
        val frames = frames(32).mapIndexed { i, f -> f.copy(preferred = i in listOf(0, 11, 22)) }
        val submitted = OfflineScanViewAgent().representativeFrames(frames)
        assertEquals(4, submitted.size); assertEquals(4, submitted.map { it.id }.distinct().size)
        assertTrue(submitted.containsAll(frames.filter { it.preferred }))
        assertEquals(submitted.map { it.receivedNs }.sorted(), submitted.map { it.receivedNs })
        assertTrue(submitted.any { it.receivedNs > 16 })
        assertEquals("f1", submitted.first().id)
    }
    @Test fun photographyRubricPrioritizesPortraitSpaceAndBackgroundOverSharpnessAlone() {
        val prompt = OfflineViewSelectionProtocol.prompt(frames().take(4), "保留山体")
        assertTrue(prompt.contains("人物空间")); assertTrue(prompt.contains("人物能与背景分离"))
        assertTrue(prompt.contains("前中后景有层次")); assertTrue(prompt.contains("杂物和高亮干扰少"))
        assertTrue(prompt.contains("人物入镜后仍能保留主体"))
        assertTrue(prompt.contains("不按图片序号或单独清晰度决定"))
        assertTrue(prompt.contains("摄影条件相近时优先"))
    }
    @Test fun malformedObservedOutputAndAmbiguousDigitsMustNeverBecomeSelection() {
        val frames = frames().take(4)
        for (raw in listOf("SELECT(SELECTING)", "1\n2", "第4张", "4 or 1", "", "0", "5")) {
            assertNull(OfflineViewSelectionProtocol.parse(raw, "scan", frames))
        }
        assertEquals("f4", OfflineViewSelectionProtocol.parse("\n 4 \n", "scan", frames)!!.primary.frameId)
        assertEquals("f4", OfflineViewSelectionProtocol.parse("SELECT=4", "scan", frames)!!.primary.frameId)
    }
    @Test fun invalidOutputDoesNotSelectByQualityOrRunExtraInference() = runBlocking {
        var count = 0
        try {
            OfflineScanViewAgent().select(snapshot(), "", OfflineViewInference { _, _ ->
                count++; StepReply("fake", "图片很美")
            }); fail()
        } catch (failure: ModelFailure) { assertEquals("LOCAL_VIEW_INVALID_OUTPUT", failure.code) }
        assertEquals(1, count)
    }
    @Test fun cancellationPropagatesAndDoesNotSelectFrame() = runBlocking {
        try { OfflineScanViewAgent().select(snapshot(), "", OfflineViewInference { _, _ -> throw CancellationException() }); fail() }
        catch (_: CancellationException) { }
    }
    @Test fun emptyInputNeverRunsInference() = runBlocking {
        try { OfflineScanViewAgent().select(snapshot().copy(frames = emptyList()), "", OfflineViewInference { _, _ -> error("不能推理") }); fail() }
        catch (failure: ModelFailure) { assertEquals("LOCAL_VIEW_INPUT", failure.code) }
    }
    @Test fun repeatedOfflineSelectionIsNotLimitedByCloudCallOrSelectionBudget() = runBlocking {
        val cloudBudget = PhotoSessionBudget()
        repeat(ScanLimits.MAX_MODEL_CALLS) { cloudBudget.consume(1, selection = it < ScanLimits.MAX_SELECTION_CALLS) }
        assertTrue(runCatching { cloudBudget.consume(0) }.isFailure)
        var calls = 0
        repeat(ScanLimits.MAX_MODEL_CALLS + 2) {
            val result = OfflineScanViewAgent().select(snapshot(), "", OfflineViewInference { _, _ ->
                calls++; StepReply("fake", "SELECT=1\nSUBJECT=山体\nREASON=适合人像")
            })
            assertNotNull(result.selection)
        }
        assertEquals(ScanLimits.MAX_MODEL_CALLS + 2, calls)
        assertEquals(ScanLimits.MAX_MODEL_CALLS, cloudBudget.calls)
        assertEquals(ScanLimits.MAX_SELECTION_CALLS, cloudBudget.selectionCalls)
        assertEquals(ScanLimits.MAX_MODEL_CALLS.toLong(), cloudBudget.uploadedBytes)
    }
}
