package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 渲染工具的结构化观察；无 GPU 的测试可以省略 bounds。 */
data class PreviewInspection(val bounds: CropRect? = null, val messages: List<String> = emptyList(), val errors: List<String> = emptyList())

/** 渲染工具契约，可在测试中注入不依赖 GPU 的实现。 */
fun interface PreviewInspector {
    /** 渲染并返回可观察的投影信息。
     * @param plan 已通过基础校验的候选。
     * @param scene 冻结图片信息。
     * @return 可供导演修正的投影边界与可见性信息。
     */
    suspend fun inspect(plan: CompositionPlan, scene: SceneSnapshot): PreviewInspection
}

/** 有明确决策预算的导演；最多两次模型输出，不执行任意代码。 */
class DirectorAgent(private val validator: PlanValidator = PlanValidator()) {
    /** 执行生成或修改会话。
     * @param input 原始图像、用户要求和修改范围。
     * @param settings 模型模式与区域路由设置。
     * @param providerFactory 创建真实模型适配器的工厂。
     * @param inspector 实际渲染工具。
     * @return 三个有效方案及完整的有限工具记录。
     */
    suspend fun run(
        input: DirectorInput,
        settings: ModelSettings,
        providerFactory: (ProviderId) -> VisionModelProvider,
        inspector: PreviewInspector
    ): CompositionResult {
        val traces = mutableListOf<CallTrace>()
        val observations = mutableListOf<ToolObservation>()
        val outputs = mutableListOf<String>()
        val failedProviders = mutableSetOf<ProviderId>()
        var lastRaw: String? = null
        try {
            repeat(2) { turn ->
                currentCoroutineContext().ensureActive()
                val prompt = DirectorPrompt.build(input, observations, lastRaw, turn == 1)
                var reply: StepReply? = null
                var lastError: ModelFailure? = null
                for (id in ModelRouting.candidates(settings).filterNot { it in failedProviders }) {
                    val start = System.nanoTime()
                    try {
                        val answer = providerFactory(id).step(ModelCall(input, prompt, newVlmId()))
                        traces += CallTrace(id, answer.model, (System.nanoTime() - start) / 1_000_000, "决策 ${turn + 1}")
                        reply = answer
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: ModelFailure) {
                        traces += CallTrace(id, "", (System.nanoTime() - start) / 1_000_000, failure.message.orEmpty())
                        if (!failure.retryable || settings.mode != ModelMode.AUTO) throw failure
                        failedProviders += id
                        lastError = failure
                    }
                }
                val answer = reply ?: throw (lastError ?: ModelFailure("UNAVAILABLE", "没有可用模型，请导入离线模型或配置网关", false))
                lastRaw = answer.raw
                outputs += answer.raw
                val decision = answer.decision ?: try { VlmJson.decodeFromString<DirectorDecision>(answer.raw.trim()) } catch (_: IllegalArgumentException) { null }
                if (decision == null) {
                    observations += ToolObservation("validate_plan", listOf("输出不是完整的导演 JSON，请修正字段并以 FINAL 返回三个方案"))
                    return@repeat
                }
                if (decision.version != 1 || decision.imageId != input.scene.id) {
                    observations += ToolObservation("validate_plan", listOf("协议版本必须为 1，imageId 必须等于 ${input.scene.id}"))
                    return@repeat
                }
                if (decision.action == ActionKind.CAPABILITIES) {
                    observations += ToolObservation("list_avatar_options", listOf(DirectorPrompt.capabilities()))
                    return@repeat
                }
                val errors = validator.validate(decision.plans, input)
                observations += ToolObservation("validate_plan", errors.ifEmpty { listOf("基础协议和几何约束通过") }, errors.map { ToolIssue("INVALID_PLAN", it) })
                if (errors.isNotEmpty()) return@repeat
                val inspections = try {
                    decision.plans.associate { it.id to inspector.inspect(it, input.scene) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { throw ModelFailure("RENDER_FAILED", "人偶预览失败：${failure.message.orEmpty().take(180)}", false) }
                val issues = inspections.flatMap { (id, value) -> value.errors.map { ToolIssue("PROJECTION_OUT_OF_FRAME", it, id) } }
                observations += ToolObservation("render_preview", inspections.flatMap { (id, value) -> value.messages.map { "$id: $it" } }, issues,
                    inspections.mapNotNull { (id, value) -> value.bounds?.let { id to it } }.toMap())
                if (issues.isNotEmpty()) return@repeat
                if (decision.action == ActionKind.FINAL) {
                    val revised = decision.plans.map { plan ->
                        val old = input.existing.find { it.id == plan.id }
                        if (plan.id == input.selectedId && old != null) plan.copy(revision = old.revision + 1) else plan
                    }
                    return CompositionResult(revised, traces, observations, outputs)
                }
            }
            throw ModelFailure("INVALID_OUTPUT", "导演在两轮内未提交有效最终方案，请修改要求或更换模型后重试", false)
        } catch (failure: ModelFailure) {
            failure.diagnostics = CompositionResult(emptyList(), traces.toList(), observations.toList(), outputs.toList())
            throw failure
        }
    }
}

/** 对上层暴露业务操作，不向 ViewModel 暴露供应商协议。 */
class CompositionRepository(private val local: VisionModelProvider, private val agent: DirectorAgent = DirectorAgent()) {
    /** 生成或修正构图。
     * @param input 当前会话输入。
     * @param settings 路由与网关配置。
     * @param inspector 渲染工具。
     * @return 有效方案和诊断记录。
     */
    suspend fun compose(input: DirectorInput, settings: ModelSettings, inspector: PreviewInspector): CompositionResult =
        agent.run(input, settings, { id -> if (id == ProviderId.LOCAL) local else GatewayProvider(settings, id) }, inspector)
}
