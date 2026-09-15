package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OfflineDirectorProtocolTest {
    private val input = DirectorInput(SceneSnapshot("frame", 600, 800, 1f, "/unused", "/unused"), CameraCapabilities(1f, 3f), "自然")
    private val first = """[0,[0,0,1,1],1,[0.5,0.9,0.3,0,0,1,0.6],"环境","保留建筑","突出空间",false,["地面未知"]]"""
    private val second = """[1,[0,0,1,1],1,[0.5,0.92,0.65,0,1,0,0.5],"全身","侧身站立","展示姿态",false,[]]"""
    private val third = """[2,[0,0,1,1],1,[0.5,1.4,1.25,0,0,1,0.6],"半身","看向镜头","突出表情",false,[]]"""
    private val raw = "[$first,$second,$third]"

    @Test fun mapsEveryFieldWithoutLosingGeometryOrUncertainties() {
        val result = OfflineDirectorProtocol.decode(raw, input)
        assertEquals("frame", result.imageId)
        assertEquals(ActionKind.FINAL, result.action)
        assertEquals(listOf("p1", "p2", "p3"), result.plans.map { it.id })
        assertEquals(listOf("地面未知"), result.plans[0].uncertainties)
        assertEquals(PoseId.SIDE, result.plans[1].avatar.pose)
        assertEquals(.5f, result.plans[1].avatar.expressionIntensity, 0f)
        assertTrue(PlanValidator().validate(result.plans, input).isEmpty())
    }

    @Test fun restoresOnlyTheMnnMissingOuterArrayToken() {
        val missingOuterArray = raw.removePrefix("[")
        val result = OfflineDirectorProtocol.decode(missingOuterArray, input)
        assertEquals(listOf("p1", "p2", "p3"), result.plans.map { it.id })
        assertTrue(PlanValidator().validate(result.plans, input).isEmpty())
    }

    @Test fun clampsOnlyInvalidWholePersonGeometryCopiedFromHalfBodyExample() {
        val output = OfflineDirectorProtocol.decode(raw.replace("[0.5,0.92,0.65,0,1,0,0.5]", "[0.5,1.4,1.25,0,1,0,0.5]"), input)
        assertEquals(ShotType.FULL, output.plans[1].shot)
        assertEquals(1f, output.plans[1].avatar.foot.y, 0f)
        assertEquals(1f, output.plans[1].avatar.height, 0f)
        assertTrue(PlanValidator().validate(output.plans, input).isEmpty())
    }

    @Test fun missingOuterArrayContinuesThroughProjectionAndCompletes() = runTest {
        var calls = 0
        val inspected = mutableListOf<String>()
        val result = DirectorAgent().run(
            input = input,
            settings = ModelSettings(mode = ModelMode.OFFLINE),
            providerFactory = { object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++
                    return StepReply("fake", raw.removePrefix("["))
                }
            } },
            inspector = PreviewInspector { plan, _ ->
                inspected += plan.id
                PreviewInspection()
            }
        )

        assertEquals(1, calls)
        assertEquals(listOf("p1", "p2", "p3"), inspected)
        assertEquals(listOf("p1", "p2", "p3"), result.plans.map { it.id })
    }

    @Test fun revisionOnlyGeneratesSelectedPlanAndKeepsOthersExactly() {
        val original = OfflineDirectorProtocol.decode(raw, input).plans
        val editing = input.copy(existing = original, selectedId = "p2")
        val revised = OfflineDirectorProtocol.decode("[${second.replace("侧身站立", "自然侧身")}]", editing).plans
        assertEquals(original[0], revised[0])
        assertEquals(original[2], revised[2])
        assertEquals("p2", revised[1].id)
        assertEquals("自然侧身", revised[1].guidance)
        val prompt = OfflineDirectorProtocol.prompt(editing)
        assertTrue(prompt.contains("仅输出1项"))
        assertFalse(prompt.contains("保留建筑"))
    }

    @Test fun rejectsMalformedAndIncompleteRowsWithoutFillingPlans() {
        listOf("[$first]", raw.dropLast(1), raw.replace("false", "0"), raw.replace("[0.5,0.9,0.3,0,0,1,0.6]", "[0.5,0.9]"), raw.replace("[0,[", "[99,[")).forEach {
            assertTrue(runCatching { OfflineDirectorProtocol.decode(it, input) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun inlineFenceWithExtraClosingTickStillRequiresValidPayload() {
        assertEquals(3, OfflineDirectorProtocol.decode("```json $raw````", input).plans.size)
        assertTrue(runCatching { OfflineDirectorProtocol.decode("```json [$first]````", input) }.isFailure)
    }

    @Test fun offlineNeverRetriesAfterProjectionFailureIncludingAutoFallback() = runTest {
        for (mode in listOf(ModelMode.OFFLINE, ModelMode.AUTO)) {
            var calls = 0
            try {
                DirectorAgent().run(input, ModelSettings(mode = mode), { id -> object : VisionModelProvider {
                    override suspend fun step(call: ModelCall): StepReply {
                        if (id != ProviderId.LOCAL) throw ModelFailure("UNAVAILABLE", "", true)
                        calls++
                        return StepReply("fake", raw)
                    }
                } }, PreviewInspector { _, _ -> PreviewInspection(errors = listOf("越界")) })
                fail("投影失败必须结束")
            } catch (failure: ModelFailure) { assertEquals("LOCAL_INVALID_OUTPUT", failure.code) }
            assertEquals(1, calls)
        }
    }

    @Test fun compactPromptDoesNotIncludeEarlierOutputOrRepairHistory() {
        val prompt = DirectorPrompt.build(input, listOf(ToolObservation("test", listOf("旧错误"))), "旧候选".repeat(1000), true, offline = true)
        assertFalse(prompt.contains("旧候选"))
        assertFalse(prompt.contains("旧错误"))
        assertTrue(prompt.toByteArray().size < 2200)
    }

    @Test fun validLocalIntermediateActionDoesNotTriggerAnotherInference() = runTest {
        var calls = 0
        val decision = OfflineDirectorProtocol.decode(raw, input).copy(action = ActionKind.VALIDATE)
        val result = DirectorAgent().run(input, ModelSettings(mode = ModelMode.OFFLINE), { _ -> object : VisionModelProvider {
            override suspend fun step(call: ModelCall): StepReply { calls++; return StepReply("fake", raw, decision) }
        } }, PreviewInspector { _, _ -> PreviewInspection() })
        assertEquals(1, calls)
        assertEquals(3, result.plans.size)
    }

    @Test fun invalidCompactGeometryFailsWithoutRetry() = runTest {
        var calls = 0
        try {
            DirectorAgent().run(input, ModelSettings(mode = ModelMode.OFFLINE), { _ -> object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++
                    return StepReply("fake", raw.replace("[0,0,1,1]", "[-1,0,1,1]"))
                }
            } }, PreviewInspector { _, _ -> error("几何非法不得渲染") })
            fail("应校验失败")
        } catch (failure: ModelFailure) { assertEquals("LOCAL_INVALID_OUTPUT", failure.code) }
        assertEquals(1, calls)
    }
}
