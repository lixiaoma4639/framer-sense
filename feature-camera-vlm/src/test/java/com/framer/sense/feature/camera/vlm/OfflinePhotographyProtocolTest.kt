package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePhotographyProtocolTest {
    private val input = DirectorInput(SceneSnapshot("selected", 600, 800, 2f, "/unused", "/unused"),
        CameraCapabilities(1f, 4f, listOf(1f, 2f)), "环境优先", photographyEnabled = true)
    private fun compact(count: Int = 3, half: Boolean = false): String {
        val plans = (1..count).joinToString(",") { i ->
            """{"t":"入口留白$i","q":${if (half) 2 else 1},"c":[0,0,1,1],"b":[${.15f + i * .2f},0.15,${if (half) 1.55 else .9},0.2],"a":1,"e":[1,0.8],"r":"门框引导视线，人物避开边缘杂物。","g":"保持垂直线，让人物与门框错开。"}"""
        }
        return """{"s":"入口有纵深，暖色墙面可衬托人物。\n\n注意右侧高亮。","p":[$plans]}"""
    }

    @Test fun compactKeepsIndependentModelGeometryAndScenePunctuation() {
        val plans = PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(compact(), input), input)
        assertEquals(3, plans.size)
        assertEquals(3, plans.map { it.photography!!.foot!!.x }.distinct().size)
        assertTrue(plans.all { it.zoom == 2f && it.resolvedSpec!!.matches(it) })
        plans.forEach { assertEquals(.75f, it.avatar.height, .0001f) }
        assertTrue(plans.all { it.avatar.expression == ExpressionId.SMILE && it.avatar.expressionIntensity == .8f })
        assertEquals("入口有纵深，暖色墙面可衬托人物。\n注意右侧高亮。", plans.first().sceneDescription)
        assertTrue(PlanValidator().validate(plans, input).isEmpty())
        val half = PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(compact(half = true), input), input)
        assertTrue(half.all { it.photography!!.foot!!.y > 1f && it.crop == CropRect() })
    }

    @Test fun completeJsonDoesNotRequireMarkdownClosingFence() {
        val raw = compact()
        val expected = OfflinePhotographyProtocol.expand(raw, input)
        for (opening in listOf("```json\n", "```JSON\r\n", "```\n", "````json\n", "```json ")) {
            for (closing in listOf("", "\n```", "\n````\n")) {
                val expanded = OfflinePhotographyProtocol.expand(opening + raw + closing, input)
                assertEquals(expected, expanded)
                assertEquals(3, PhotographyProtocol.decode(expanded, input).size)
            }
        }
        assertEquals(expected, OfflinePhotographyProtocol.expand("<think>先分析。</think>\n```json\n$raw", input))
        // 云端与旧导演解析仍要求完整展示包装，不扩大它们的兼容范围。
        assertTrue(runCatching { DirectorOutput.unwrap("```json\n$raw") }.isFailure)
    }

    @Test fun normalizesOnlyStructuralChinesePunctuationAndRejectsLoggedObjectList() {
        val normal = compact()
        val smart = buildString {
            var quoted = false
            var escaped = false
            normal.forEach { c ->
                when {
                    escaped -> { append(c); escaped = false }
                    c == '\\' -> { append(c); escaped = true }
                    c == '"' -> { append(if (quoted) '”' else '“'); quoted = !quoted }
                    !quoted && c == ':' -> append('：')
                    !quoted && c == ',' -> append('，')
                    else -> append(c)
                }
            }
        }
        assertEquals(OfflinePhotographyProtocol.expand(normal, input), OfflinePhotographyProtocol.expand(smart, input))
        assertEquals(OfflinePhotographyProtocol.expand(normal, input), OfflinePhotographyProtocol.expand("```json\n$smart", input))
        val logged = """{
            “s”: “室内空间中的一件家具”，“p”:[“椅子”, "桌子", "凳子"]，“t”：“现代简约风格的房间内”。}
        """.trimIndent()
        val error = runCatching { OfflinePhotographyProtocol.expand(logged, input) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.startsWith("PHOTO_PLAN_OBJECT_REQUIRED"))
        val content = """{"s":"保留“门框”：色彩，层次。\n头部不碰线。","p":[]}"""
        assertEquals(content, OfflineJsonSyntax.normalize(content))
        assertTrue(runCatching { OfflinePhotographyProtocol.expand(smart.dropLast(1), input) }.isFailure)
    }

    @Test fun constrainedRowsRetainModelDecisionsAndBindTheCurrentImage() {
        val raw = """{"s":"暖墙形成层次，入口亮部衬托人物。","p":[
            ["入口留白",1,0,0,100,25,15,90,20,1,1,80,0,"门框引导视线，保留空间。","保持垂直线，人物避开门框。"],
            ["侧身呼应",1,0,0,100,50,10,95,25,2,0,50,15,"暖墙与人物轮廓相互衬托。","略向侧面取景，避开亮斑。"],
            ["近景层次",2,0,0,100,75,15,155,20,3,2,70,-10,"保留入口，突出人物与层次。","降低机位，避免头部碰线。"]]}""".trimIndent()
        val plans = PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(raw, input), input)
        assertEquals(3, plans.size)
        assertEquals(listOf(.25f,.50f,.75f), plans.map { it.photography!!.head!!.x })
        assertEquals(15f, plans[1].avatar.yaw)
        assertEquals(.85f, plans[1].avatar.height, .0001f)
        assertEquals(1.55f, plans[2].photography!!.foot!!.y, .0001f)
        assertTrue(plans.all { it.resolvedSpec!!.matches(it) && it.crop == CropRect() })
        assertTrue(PlanValidator().validate(plans, input).isEmpty())
        for (bad in listOf(raw.replace("100,25", "100.5,25"), raw.replace("100,25", "100,125"),
            raw.replace("20,1,1,80", "20,99,1,80"), raw.replace(""近景层次",2", ""近景层次",9"))) {
            assertTrue(runCatching { PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(bad,input),input) }.isFailure)
        }
    }

    @Test fun promptCarriesNativeConstraintsAndOneOrThreePlansWithoutFixedLayouts() {
        for (source in listOf(input, input.copy(selectedId = "p2"))) {
            val prompt = OfflinePhotographyProtocol.prompt(source, emptyList())
            assertTrue(prompt.startsWith("PHOTO_ROWS_V1 ${if(source.selectedId == null) 3 else 1} "))
            assertTrue(prompt.contains("每行15项"))
            assertTrue(prompt.contains("不要小数"))
            assertFalse(prompt.contains("0.25,0.15,0.9"))
        }
    }

    @Test fun loggedMixedQuoteIsRepairedButSinglePlanIsNeverDuplicated() {
        val logged = """{
            "s":"空房间内拍摄的人像作品中，利用地板作为前景来增强画面的空间感，并将白色凳子放置于角落处以突出其视觉焦点。",
            "p":[{"t":"全场景视角下的室内肖像", "q":1, "c":[0.0,0.0,1.0,1.0], "b":[0.25,0.15,0.9,0.2], "a":1, "e":[0,0.8], "r":"从近处到远处平视角度选择最合适的镜头焦距，避免了对家具的选择性忽略，使用纯色墙壁进行对比度提升效果最大化。该设置符合原始图像信息中的全部内容以及需求。”,"g":"直接进入全景模式捕捉整个空间范围内的光线变化并保持稳定曝光值。确保被摄者面部特征清晰可见同时兼顾整体氛围营造。","v":{"title":"无指定","type":"string","value":""},"z":{"width":0.9,"height":0.7,"top":-0.1,"left":0.0}}]}
        """.trimIndent()
        VlmJson.parseToJsonElement(OfflineJsonSyntax.normalize(logged)) // 修标点，不补字段或方案。
        val error = runCatching { OfflinePhotographyProtocol.expand(logged,input) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("PHOTO_PLAN_IDS_INVALID:expected=3 actual=1"))
        val good = """{"s":"保留“门框”，不要删除标点。\n色彩应衬托人物。","p":[]}"""
        assertEquals(good, OfflineJsonSyntax.normalize(good))
    }

    @Test fun failureLogsCarryOriginalCausePhaseFullPayloadAndSameRequest() = runTest {
        val summaries = mutableListOf<String>()
        val payloads = mutableListOf<String>()
        val logger = object : DirectorLogger by NoOpDirectorLogger {
            override fun info(message: String) { summaries += message }
            override fun warn(message: String, error: Throwable?) { summaries += message }
            override fun error(message: String, error: Throwable?) { summaries += message }
            override fun diagnostic(message: () -> String) { payloads += message() }
        }
        var request = ""
        var calls = 0
        val raw = """{“s”:“室内有家具。”，“p”:[“椅子”,“桌子”,“凳子”]}"""
        try {
            DirectorAgent(logger = logger).run(input, ModelSettings(mode = ModelMode.OFFLINE), { object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    request = call.requestId; calls++
                    return StepReply("fake", raw, photographyProtocol = true)
                }
            } }, PreviewInspector { _, _ -> error("无效输出不得渲染") })
            fail("应失败")
        } catch (failure: ModelFailure) {
            assertEquals("LOCAL_PHOTO_INVALID_OUTPUT", failure.code)
            assertTrue(failure.cause is IllegalArgumentException)
            assertTrue(failure.cause!!.message!!.contains("PHOTO_PLAN_OBJECT_REQUIRED"))
        }
        assertEquals(1, calls)
        assertTrue(summaries.any { it.contains("导演任务失败") && it.contains("request=$request") &&
            it.contains("phase=FIELD_VALIDATION") && it.contains("failedCheck=validate_photography") && it.contains("PHOTO_PLAN_OBJECT_REQUIRED") })
        assertTrue(payloads.any { it.contains("request=$request") && it.contains("payload=PROMPT") })
        assertTrue(payloads.any { it.contains("request=$request") && it.contains("payload=RAW") && it.contains(raw) })
        assertTrue(payloads.any { it.contains("payload=NORMALIZED_JSON") && it.contains("室内有家具。") })
        assertTrue(payloads.any { it.contains("payload=FAILURE_DETAILS") && it.contains("PHOTO_PLAN_OBJECT_REQUIRED") })
    }

    @Test fun truncatedJsonExtraObjectAndUnsupportedFenceStillFail() {
        for (raw in listOf("```json\n" + compact().dropLast(1), "```json\n" + compact().dropLast(1) + "\n```",
            "```json\n" + compact() + "{}", "```json\n" + compact() + "\n```\n说明",
            "```python\n" + compact() + "\n```", "<think>未完成\n" + compact())) {
            assertTrue(runCatching { OfflinePhotographyProtocol.expand(raw, input) }.isFailure)
        }
    }

    @Test fun fencedCompleteModelResultDoesNotSpendCorrectionCall() = runTest {
        var calls = 0
        val result = DirectorAgent().run(input, ModelSettings(mode = ModelMode.OFFLINE), { object : VisionModelProvider {
            override suspend fun step(call: ModelCall): StepReply {
                calls++
                return StepReply("fake", "```json\n" + compact(), photographyProtocol = true)
            }
        } }, PreviewInspector { p, _ -> PreviewInspection(appliedPlan = p) })
        assertEquals(1, calls)
        assertEquals(3, result.plans.size)
    }

    @Test fun invalidActionExpressionAndArrayCannotBypassV3Validation() {
        for (raw in listOf(compact(2), compact().replace("\"a\":1", "\"a\":99"),
            compact().replace("[1,0.8]", "[1.5,0.8]"), compact().replace("[0,0,1,1]", "[0,1]"))) {
            assertTrue(runCatching { PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(raw, input), input) }.isFailure)
        }
        val noSmile = input.copy(photographyActions = listOf(PhotographyActionCapability(
            PhotographyProtocol.actionIds(input).first(), listOf(ExpressionId.NEUTRAL))))
        assertTrue(runCatching { PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(compact(), noSmile), noSmile) }.isFailure)
    }

    @Test fun editChangesOnlySelectedPlanAndOfflinePromptOmitsVerboseHistory() {
        val original = PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(compact(), input), input)
        val editing = input.copy(existing = original, selectedId = "p2")
        val changed = PhotographyProtocol.decode(OfflinePhotographyProtocol.expand(compact(1), editing), editing)
        assertSame(original[0], changed[0]); assertSame(original[2], changed[2])
        assertEquals("p2", changed[1].id)
        val offline = DirectorPrompt.build(input, emptyList(), "OLD_OUTPUT_SENTINEL", false, offline = true)
        val cloud = DirectorPrompt.build(input, emptyList(), "OLD_OUTPUT_SENTINEL", false)
        assertFalse(offline.contains("OLD_OUTPUT_SENTINEL"))
        assertFalse(offline.contains("投影矩阵"))
        assertTrue(cloud.contains("OLD_OUTPUT_SENTINEL"))
        assertTrue(offline.length < cloud.length)
    }

    @Test fun wholeDeadlineIncludesFirstTokenAndNotifiesCancellation() = runTest {
        var stopped = false
        try {
            withLocalPhotographyDeadline<Unit> {
                try { awaitCancellation() } finally { stopped = true }
            }
            fail("应在两分钟内退出等待")
        } catch (failure: ModelFailure) { assertEquals("LOCAL_PHOTOGRAPHY_DEADLINE", failure.code) }
        assertEquals(120_000L, currentTime)
        assertTrue(stopped)
    }

    @Test fun invalidCompletedOutputFailsImmediatelyWithoutSecondCall() = runTest {
        var calls = 0
        val start = currentTime
        try {
            DirectorAgent().run(input, ModelSettings(mode = ModelMode.OFFLINE), { object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++
                    delay(40_000)
                    return StepReply("fake", "{}", photographyProtocol = true)
                }
            } }, PreviewInspector { _, _ -> error("无合法输出不能渲染") })
            fail("不允许补造结果或再次推理")
        } catch (failure: ModelFailure) { assertEquals("LOCAL_PHOTO_INVALID_OUTPUT", failure.code) }
        assertEquals(1, calls)
        assertEquals(40_000L, currentTime - start)
        assertNull(withTimeoutOrNull(1_000) {
            withLocalPhotographyDeadline<Unit> { throwAfterWait() }
        })
    }

    @Test fun slowSingleModelCallIsCancelledWithoutStartingAnotherOne() = runTest {
        var calls = 0
        var cancelled = false
        try {
            DirectorAgent().run(input, ModelSettings(mode = ModelMode.OFFLINE), { object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++
                    try { awaitCancellation() } finally { cancelled = true }
                }
            } }, PreviewInspector { _, _ -> error("模型未完成不能渲染") })
            fail("未完成必须失败")
        } catch (failure: ModelFailure) { assertEquals("LOCAL_PHOTOGRAPHY_DEADLINE", failure.code) }
        assertEquals(1, calls)
        assertEquals(120_000L, currentTime)
        assertTrue(cancelled)
    }

    @Test fun autoRouteAlsoCallsLocalOnlyOnceOnInvalidOutput() = runTest {
        var localCalls = 0
        try {
            DirectorAgent().run(input, ModelSettings(mode = ModelMode.AUTO), { id -> object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    if (id != ProviderId.LOCAL) throw ModelFailure("NETWORK", "", true)
                    localCalls++
                    return StepReply("fake-local", "{}", photographyProtocol = true)
                }
            } }, PreviewInspector { _, _ -> error("无效结果不能渲染") })
            fail("应报告失败")
        } catch (failure: ModelFailure) { assertEquals("LOCAL_PHOTO_INVALID_OUTPUT", failure.code) }
        assertEquals(1, localCalls)
    }

    @Test fun cloudStillUsesFullProtocolAndHasNoLocalWholeDeadline() = runTest {
        val expanded = OfflinePhotographyProtocol.expand(compact(), input)
        val result = DirectorAgent().run(input, ModelSettings(mode = ModelMode.CLOUD), { object : VisionModelProvider {
            override suspend fun step(call: ModelCall): StepReply {
                assertTrue(call.prompt.contains("plans每项必须包含"))
                delay(130_000)
                return StepReply("fake-cloud", expanded, photographyProtocol = true)
            }
        } }, PreviewInspector { p, _ -> PreviewInspection(appliedPlan = p) })
        assertEquals(3, result.plans.size)
        assertEquals(130_000L, currentTime)
    }

    private suspend fun throwAfterWait() {
        try { awaitCancellation() } catch (cancelled: CancellationException) { throw cancelled }
    }
}
