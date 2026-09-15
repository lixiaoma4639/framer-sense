package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

/** 验证导演的真实控制流程；固定模型输出仅存在于测试代码。 */
class DirectorAgentTest {
    /** 创建合法的三种景别；无参数，半身人物脚部位于画面之外。 */
    private fun plans(): List<CompositionPlan> = listOf(
        CompositionPlan("p1", "环境", ShotType.ENVIRONMENT, CropRect(), 1f, AvatarPlacement(height = .3f), "保留建筑", "环境为主"),
        CompositionPlan("p2", "全身", ShotType.FULL, CropRect(), 1f, AvatarPlacement(height = .65f), "自然站立", "全身完整"),
        CompositionPlan("p3", "半身", ShotType.HALF, CropRect(), 1f, AvatarPlacement(foot = Point2(.5f, 1.4f), height = 1.25f), "看向镜头", "突出表情")
    )

    /** 创建输入约束。@param existing 原有方案；@param selected 待修改方案编号。 */
    private fun input(existing: List<CompositionPlan> = emptyList(), selected: String? = null): DirectorInput = DirectorInput(
        SceneSnapshot("frame", 600, 800, 1f, "/unused", "/unused"), CameraCapabilities(1f, 3f, listOf(1f, 2f, 3f)), "自然", existing, selected
    )

    /** 编码测试供应商输出。@param decision 测试决策。 */
    private fun reply(decision: DirectorDecision): StepReply = StepReply("test-model", VlmJson.encodeToString(decision), decision)

    /** 来自实际失败输出：缺外层、仅一项、漏表情强度、不确定项字段名错误。 */
    private val singlePlanOutput = """
        {"id":"p1","title":"环境人像","shot":"ENVIRONMENT",
         "crop":{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0},"zoom":1.0,
         "avatar":{"foot":{"x":0.3,"y":0.9},"height":0.3,"yaw":0.0,"pose":"RELAXED","expression":"SMILE"},
         "guidance":"站在空地，侧身看镜头","reason":"保留建筑主体","needsRetake":false,
         "uncertainty":["地板","墙壁"],"revision":0}
    """.trimIndent()

    /** 提示词必须提供当前画面的完整三方案协议，而非单个方案和省略号。 */
    @Test fun promptContainsCompleteThreePlanEnvelope() {
        val sceneInput = input().copy(scene = input().scene.copy(id = "new-frame", currentZoom = 2f))
        val prompt = DirectorPrompt.build(sceneInput, emptyList(), null, false, offline = false)
        val example = prompt.lineSequence().first { it.trimStart().startsWith("完整输出结构示例=") }.substringAfter('=')
        val decision = DirectorOutput.decode(example)
        assertEquals("new-frame", decision.imageId)
        assertEquals(ActionKind.FINAL, decision.action)
        assertEquals(3, decision.plans.size)
        assertEquals(3, decision.plans.map { it.id }.distinct().size)
        assertTrue(decision.plans.all { it.zoom == 2f })
        assertTrue(prompt.contains("不能写 uncertainty"))
        assertFalse(prompt.contains("\"plans\":[...]"))
    }

    /** 真实失败样本的所有已知修正点必须进入第二轮提示，修正后才能渲染。 */
    @Test fun repairsReportedSinglePlanWithSpecificFeedback() = runTest {
        var calls = 0
        var rendered = 0
        val result = DirectorAgent().run(input(), ModelSettings(mode = ModelMode.CLOUD), { _ -> object : VisionModelProvider {
            override suspend fun step(call: ModelCall): StepReply {
                if (++calls == 1) return StepReply("fake", singlePlanOutput)
                listOf("仅返回单个方案", "version, imageId, action, plans", "另外两个不同方案", "expressionIntensity", "将 uncertainty 改为 uncertainties").forEach {
                    assertTrue("缺少修正反馈：$it", call.prompt.contains(it))
                }
                return StepReply("fake", VlmJson.encodeToString(DirectorDecision(ActionKind.FINAL, plans(), imageId = "frame")))
            }
        } }, PreviewInspector { _, _ -> rendered++; PreviewInspection() })
        assertEquals(2, calls)
        assertEquals(3, rendered)
        assertEquals(3, result.plans.size)
        assertEquals(singlePlanOutput, result.rawOutputs.first())
    }

    /** 连续返回同样的单方案必须失败，不得由客户端补成三个方案。 */
    @Test fun neverAcceptsOrFabricatesMissingPlansFromReportedOutput() = runTest {
        var calls = 0
        try {
            DirectorAgent().run(input(), ModelSettings(mode = ModelMode.OFFLINE), { _ -> object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply { calls++; return StepReply("fake", singlePlanOutput) }
            } }, PreviewInspector { _, _ -> error("非法单方案不应进入渲染") })
            fail("缺少方案不能成功")
        } catch (failure: ModelFailure) {
            assertEquals("LOCAL_INVALID_OUTPUT", failure.code)
            assertEquals(listOf(singlePlanOutput), failure.diagnostics?.rawOutputs)
            assertTrue(failure.diagnostics!!.plans.isEmpty())
        }
        assertEquals(1, calls)
    }

    /** 完整 JSON 的展示包装不应浪费一轮昂贵的本地推理。 */
    @Test fun acceptsWrappedFinalJsonWithoutAnotherModelCall() = runTest {
        val json = VlmJson.encodeToString(DirectorDecision(ActionKind.FINAL, plans(), imageId = "frame"))
        for (raw in listOf(json, "```json\n$json\n```", "<think>先分析画面。</think>\n```json\n$json\n```")) {
            var calls = 0
            val result = DirectorAgent().run(input(), ModelSettings(mode = ModelMode.OFFLINE), { _ -> object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++
                    assertTrue(call.prompt.contains("只输出紧凑JSON数组"))
                    return StepReply("fake", raw)
                }
            } }, PreviewInspector { _, _ -> PreviewInspection() })
            assertEquals(1, calls)
            assertEquals(3, result.plans.size)
            assertEquals(listOf(raw), result.rawOutputs)
        }
    }

    /** 包装兼容不能掩盖截断、缺字段、未知动作或拼接多份 JSON。 */
    @Test fun wrappedOutputStillRequiresCompleteStrictProtocol() {
        val json = VlmJson.encodeToString(DirectorDecision(ActionKind.FINAL, plans(), imageId = "frame"))
        val invalid = listOf(
            "```json\n${json.dropLast(1)}\n```",
            "```json\n$json",
            "<think>未闭合\n$json",
            "```json\n${json.replace("FINAL", "SHELL")}\n```",
            "```json\n${json.dropLast(1)},\"exec\":\"x\"}\n```",
            "```json\n{\"action\":\"FINAL\",\"plans\":[]}\n```",
            "$json\n$json"
        )
        invalid.forEach { assertTrue(runCatching { DirectorOutput.decode(it) }.isFailure) }
    }

    /** 验证半身合法出画、全身出画错误以及裁剪与倍率一致性；无参数。 */
    @Test fun validatesShotSpecificCroppingAndZoom() {
        val validator = PlanValidator()
        assertTrue(validator.validate(plans(), input()).isEmpty())
        val bad = plans().toMutableList()
        bad[1] = bad[1].copy(avatar = bad[1].avatar.copy(foot = Point2(.5f, 1.2f)))
        assertTrue(validator.validate(bad, input()).any { "头脚" in it })
        bad[1] = plans()[1].copy(zoom = 3f)
        assertTrue(validator.validate(bad, input()).any { "倍率不一致" in it })
    }

    /** 验证首次非法输出经过工具反馈后只修正一次；无参数。 */
    @Test fun repairsInvalidDecisionUsingToolFeedback() = runTest {
        var calls = 0
        var rendered = 0
        val provider = object : VisionModelProvider {
            /** 模拟先错后对的结果。@param call 检查第二轮确实带有校验观察。 */
            override suspend fun step(call: ModelCall): StepReply {
                calls++
                return if (calls == 1) StepReply("fake", "not-json") else {
                    assertTrue(call.prompt.contains("输出不是完整"))
                    reply(DirectorDecision(ActionKind.FINAL, plans(), imageId = "frame"))
                }
            }
        }
        val result = DirectorAgent().run(input(), ModelSettings(mode = ModelMode.CLOUD), { id ->
            assertEquals(ProviderId.QWEN, id); provider
        }, PreviewInspector { _, _ -> rendered++; PreviewInspection(messages = listOf("可见")) })
        assertEquals(2, calls)
        assertEquals(3, rendered)
        assertEquals(3, result.plans.size)
    }

    /** 验证暂时不可用时只在区域内切换，并保留实际供应商；无参数。 */
    @Test fun routesWithinRegionWithoutRepeatingFailedProvider() = runTest {
        val called = mutableListOf<ProviderId>()
        val result = DirectorAgent().run(input(), ModelSettings(), { id ->
            object : VisionModelProvider {
                /** 模拟千问故障及 Seed 成功。@param call 当前请求。 */
                override suspend fun step(call: ModelCall): StepReply {
                    called += id
                    if (id == ProviderId.QWEN) throw ModelFailure("UNAVAILABLE", "不可用", true)
                    return reply(DirectorDecision(ActionKind.FINAL, plans(), imageId = "frame"))
                }
            }
        }, PreviewInspector { _, _ -> PreviewInspection() })
        assertEquals(listOf(ProviderId.QWEN, ProviderId.SEED), called)
        assertEquals(ProviderId.SEED, result.traces.last().provider)
    }

    /** 自动切换、两轮修正、结构校验和逐方案投影检验都应按真实执行顺序报告当前步骤。 */
    @Test fun reportsProgressForFallbackRepairValidationAndProjectionInspection() = runTest {
        val progress = mutableListOf<DirectorProgress>()
        var seedCalls = 0
        val result = DirectorAgent().run(input(), ModelSettings(), { id -> object : VisionModelProvider {
            /** 千问不可用后由 Seed 首轮返回非法输出、第二轮返回最终方案。 */
            override suspend fun step(call: ModelCall): StepReply = when (id) {
                ProviderId.QWEN -> throw ModelFailure("TIMEOUT", "超时", true)
                ProviderId.SEED -> if (++seedCalls == 1) StepReply("fake", "not-json") else reply(DirectorDecision(ActionKind.FINAL, plans(), imageId = "frame"))
                else -> error("不应选择其他供应商")
            }
        } }, PreviewInspector { _, _ -> PreviewInspection() }) { progress += it }

        assertEquals(3, result.plans.size)
        assertEquals(
            listOf(
                DirectorProgress.PreparingRequest,
                DirectorProgress.AnalyzingScene(1, false),
                DirectorProgress.SwitchingProvider(1),
                DirectorProgress.ValidatingResponse(1),
                DirectorProgress.PreparingRequest,
                DirectorProgress.AnalyzingScene(2, true),
                DirectorProgress.ValidatingResponse(2),
                DirectorProgress.InspectingProjection(1, 3),
                DirectorProgress.InspectingProjection(2, 3),
                DirectorProgress.InspectingProjection(3, 3)
            ),
            progress
        )
    }

    /** 验证拒绝不能触发更换模型；无参数。 */
    @Test fun refusalDoesNotFailOver() = runTest {
        var calls = 0
        try {
            DirectorAgent().run(input(), ModelSettings(), { _ -> object : VisionModelProvider {
                /** 返回不可重试拒绝。@param call 当前请求。 */
                override suspend fun step(call: ModelCall): StepReply { calls++; throw ModelFailure("REFUSED", "拒绝", false) }
            } }, PreviewInspector { _, _ -> error("不应渲染") })
            fail("拒绝必须返回错误")
        } catch (failure: ModelFailure) { assertEquals("REFUSED", failure.code) }
        assertEquals(1, calls)
    }

    /** 验证取消在模型等待期间不会触发备用模型或渲染；无参数。 */
    @Test fun cancellationDoesNotFailOverOrRender() = runTest {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            DirectorAgent().run(input(), ModelSettings(), { _ -> object : VisionModelProvider {
                /** 模拟正在等待的模型调用。@param call 当前请求。 */
                override suspend fun step(call: ModelCall): StepReply { calls++; entered.complete(Unit); awaitCancellation() }
            } }, PreviewInspector { _, _ -> error("取消后不应渲染") })
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }

    /** 验证修改不能覆盖未选中方案；无参数。 */
    @Test fun revisionPreservesUnselectedPlans() {
        val original = plans()
        val changed = original.map { it.copy(title = "全部改掉") }
        assertTrue(PlanValidator().validate(changed, input(original, "p2")).any { "只能修改" in it })
    }

    /** 验证工具查询不能绕过两轮预算；无参数。 */
    @Test fun capabilitiesCannotCreateInfiniteLoop() = runTest {
        var calls = 0
        try {
            DirectorAgent().run(input(), ModelSettings(mode = ModelMode.CLOUD), { _ -> object : VisionModelProvider {
                /** 反复请求能力。@param call 当前请求。 */
                override suspend fun step(call: ModelCall): StepReply { calls++; return reply(DirectorDecision(ActionKind.CAPABILITIES, imageId = "frame")) }
            } }, PreviewInspector { _, _ -> PreviewInspection() })
            fail("应在预算耗尽后终止")
        } catch (failure: ModelFailure) { assertEquals("INVALID_OUTPUT", failure.code) }
        assertEquals(2, calls)
    }

    /** 验证协议拒绝未知动作与字段，避免执行模型臆造工具；无参数。 */
    @Test fun rejectsUnknownActionsAndFields() {
        val invalidPose = VlmJson.encodeToString(DirectorDecision(ActionKind.FINAL, plans(), imageId = "frame")).replace("RELAXED", "FLYING")
        listOf(invalidPose, """{"action":"SHELL","plans":[]}""", """{"action":"FINAL","plans":[],"exec":"x"}""").forEach { text ->
            assertTrue(runCatching { VlmJson.decodeFromString<DirectorDecision>(text) }.isFailure)
        }
    }
    /** 自动切换不会重置已使用的修正预算，故障供应商也不会重复调用；无参数。 */
    @Test fun failoverKeepsCorrectionBudget() = runTest {
        val calls = mutableListOf<ProviderId>()
        var qwenCalls = 0
        try {
            DirectorAgent().run(input(), ModelSettings(), { id -> object : VisionModelProvider {
                /** 首轮查询能力，第二轮千问故障后 Seed 再次查询能力。@param call 当前请求。 */
                override suspend fun step(call: ModelCall): StepReply {
                    calls += id
                    if (id == ProviderId.QWEN && ++qwenCalls == 2) throw ModelFailure("TIMEOUT", "超时", true)
                    return reply(DirectorDecision(ActionKind.CAPABILITIES, imageId = "frame"))
                }
            } }, PreviewInspector { _, _ -> error("不应渲染") })
            fail("两轮已用尽")
        } catch (failure: ModelFailure) { assertEquals("INVALID_OUTPUT", failure.code) }
        assertEquals(listOf(ProviderId.QWEN, ProviderId.QWEN, ProviderId.SEED), calls)
    }

    /** 指定云端失败后不能自动改用其他模型；无参数。 */
    @Test fun pinnedCloudNeverFallsBack() = runTest {
        val calls = mutableListOf<ProviderId>()
        try {
            DirectorAgent().run(input(), ModelSettings(mode = ModelMode.CLOUD), { id -> object : VisionModelProvider {
                /** 固定供应商返回暂时故障。@param call 当前请求。 */
                override suspend fun step(call: ModelCall): StepReply { calls += id; throw ModelFailure("TIMEOUT", "超时", true) }
            } }, PreviewInspector { _, _ -> error("不应渲染") })
            fail("应直接报告故障")
        } catch (failure: ModelFailure) { assertEquals("TIMEOUT", failure.code) }
        assertEquals(listOf(ProviderId.QWEN), calls)
    }

    /** 旧画面结果不能进入渲染和最终方案；无参数。 */
    @Test fun rejectsDifferentImageId() = runTest {
        try {
            DirectorAgent().run(input(), ModelSettings(mode = ModelMode.OFFLINE), { _ -> object : VisionModelProvider {
                /** 返回其他画面的结果。@param call 当前请求。 */
                override suspend fun step(call: ModelCall) = reply(DirectorDecision(ActionKind.FINAL, plans(), imageId = "old-frame"))
            } }, PreviewInspector { _, _ -> error("旧结果不应渲染") })
            fail("应拒绝错误画面编号")
        } catch (failure: ModelFailure) { assertEquals("LOCAL_INVALID_OUTPUT", failure.code) }
    }

}
