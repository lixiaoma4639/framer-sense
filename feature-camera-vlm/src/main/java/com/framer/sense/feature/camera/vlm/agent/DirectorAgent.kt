package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 渲染工具的结构化观察；无 GPU 的测试可以省略 bounds。 */
data class PreviewInspection(val bounds: CropRect? = null, val messages: List<String> = emptyList(), val errors: List<String> = emptyList())

/** 导演任务向界面报告的当前执行阶段；仅描述真实运行中的一步，不保存历史。 */
sealed interface DirectorProgress {
    /** 正在整理冻结画面、用户要求和已有校验结果。 */
    data object PreparingRequest : DirectorProgress

    /** 正在等待模型分析或根据上一轮校验修正方案。 */
    data class AnalyzingScene(val turn: Int, val revisingFromFeedback: Boolean) : DirectorProgress

    /** 自动路由时，正在改用下一个可用模型继续当前轮次。 */
    data class SwitchingProvider(val turn: Int) : DirectorProgress

    /** 正在解析并检查模型的结构化输出。 */
    data class ValidatingResponse(val turn: Int) : DirectorProgress

    /** 正在逐个检查方案的人偶投影是否落在可用画面内。 */
    data class InspectingProjection(val planIndex: Int, val planCount: Int) : DirectorProgress

    /** 正在按顺序生成可展示的方案预览。 */
    data class RenderingPreview(val planIndex: Int, val planCount: Int) : DirectorProgress
}

/** 导演运行日志的可替换出口，使纯 JVM 测试无需依赖 Android Logcat。 */
interface DirectorLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)
    fun error(message: String, error: Throwable? = null)
    /** 可能包含模型原文的诊断，仅由 Debug 日志实现按需求值和输出。 */
    fun diagnostic(message: () -> String) = Unit
}

/** 单元测试和非 Android 环境的默认日志出口。 */
object NoOpDirectorLogger : DirectorLogger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String, error: Throwable?) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}

/** 渲染工具契约，可在测试中注入不依赖 GPU 的实现。 */
fun interface PreviewInspector {
    /** 渲染并返回可观察的投影信息。
     * @param plan 已通过基础校验的候选。
     * @param scene 冻结图片信息。
     * @return 可供导演修正的投影边界与可见性信息。
     */
    suspend fun inspect(plan: CompositionPlan, scene: SceneSnapshot): PreviewInspection
}

/** 有明确决策预算的导演；云端最多两轮，本地最多一次模型调用，不执行任意代码。 */
class DirectorAgent(
    private val validator: PlanValidator = PlanValidator(),
    private val logger: DirectorLogger = NoOpDirectorLogger
) {
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
        inspector: PreviewInspector,
        onProgress: (DirectorProgress) -> Unit = {}
    ): CompositionResult {
        logger.info("开始导演任务 image=${input.scene.id.take(8)} mode=${settings.mode} region=${settings.region} existing=${input.existing.size}")
        val traces = mutableListOf<CallTrace>()
        val observations = mutableListOf<ToolObservation>()
        val outputs = mutableListOf<String>()
        val failedProviders = mutableSetOf<ProviderId>()
        var lastRaw: String? = null
        var localAttempted = false
        try {
            repeat(2) { turn ->
                currentCoroutineContext().ensureActive()
                if (localAttempted) throw ModelFailure("LOCAL_INVALID_OUTPUT", "", false)
                onProgress(DirectorProgress.PreparingRequest)
                var reply: StepReply? = null
                var lastError: ModelFailure? = null
                var switchingProvider = false
                for (id in ModelRouting.candidates(settings).filterNot { it in failedProviders }) {
                    onProgress(
                        if (switchingProvider) DirectorProgress.SwitchingProvider(turn + 1)
                        else DirectorProgress.AnalyzingScene(turn + 1, revisingFromFeedback = turn > 0 && id != ProviderId.LOCAL)
                    )
                    val start = System.nanoTime()
                    try {
                        val prompt = DirectorPrompt.build(input, observations, lastRaw, turn == 1, offline = id == ProviderId.LOCAL)
                        logger.info("调用模型 turn=${turn + 1} provider=$id fallback=$switchingProvider")
                        if (id == ProviderId.LOCAL) localAttempted = true
                        val answer = providerFactory(id).step(ModelCall(input, prompt, newVlmId()))
                        traces += CallTrace(id, answer.model, (System.nanoTime() - start) / 1_000_000, "决策 ${turn + 1}")
                        logger.info("模型返回 turn=${turn + 1} provider=$id model=${answer.model} elapsedMs=${traces.last().elapsedMs} outputBytes=${answer.raw.toByteArray().size}")
                        reply = answer
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: ModelFailure) {
                        traces += CallTrace(id, "", (System.nanoTime() - start) / 1_000_000, failure.message.orEmpty())
                        logger.warn("模型失败 turn=${turn + 1} provider=$id code=${failure.code} retryable=${failure.retryable} elapsedMs=${traces.last().elapsedMs}", failure)
                        if (!failure.retryable || settings.mode != ModelMode.AUTO) throw failure
                        failedProviders += id
                        lastError = failure
                        switchingProvider = true
                    }
                }
                val answer = reply ?: throw (lastError ?: ModelFailure("UNAVAILABLE", "没有可用模型，请导入离线模型或配置网关", false))
                lastRaw = answer.raw
                outputs += answer.raw
                onProgress(DirectorProgress.ValidatingResponse(turn + 1))
                val localReply = traces.last().provider == ProviderId.LOCAL
                val decision = answer.decision ?: try {
                    if (localReply) OfflineDirectorProtocol.decode(answer.raw, input) else DirectorOutput.decode(answer.raw)
                } catch (error: IllegalArgumentException) {
                    logger.warn("导演 JSON 解析失败 type=${error::class.java.simpleName}")
                    logger.diagnostic { "导演 JSON 解析详情 turn=${turn + 1} type=${error::class.java.name} reason=${error.message}" }
                    null
                }
                if (decision == null) {
                    logger.warn("模型输出不是有效导演 JSON turn=${turn + 1} outputBytes=${answer.raw.toByteArray().size}")
                    val errors = DirectorOutput.repairErrors(answer.raw, input.scene.id)
                    observations += ToolObservation("validate_plan", errors, errors.map { ToolIssue("INVALID_OUTPUT", it) })
                    logger.warn("导演输出修正要求 turn=${turn + 1} issues=${errors.joinToString("；")}")
                    return@repeat
                }
                if (decision.version != 1 || decision.imageId != input.scene.id) {
                    logger.warn("模型输出 imageId/version 不匹配 turn=${turn + 1} version=${decision.version} imageMatches=${decision.imageId == input.scene.id}")
                    observations += ToolObservation("validate_plan", listOf("协议版本必须为 1，imageId 必须等于 ${input.scene.id}"))
                    return@repeat
                }
                if (decision.action == ActionKind.CAPABILITIES) {
                    observations += ToolObservation("list_avatar_options", listOf(DirectorPrompt.capabilities()))
                    return@repeat
                }
                val errors = validator.validate(decision.plans, input)
                logger.info("校验模型输出 turn=${turn + 1} action=${decision.action} plans=${decision.plans.size} errors=${errors.size}")
                if (errors.isNotEmpty()) logger.warn("模型输出校验详情 turn=${turn + 1} errors=${errors.joinToString("；")}")
                observations += ToolObservation("validate_plan", errors.ifEmpty { listOf("基础协议和几何约束通过") }, errors.map { ToolIssue("INVALID_PLAN", it) })
                if (errors.isNotEmpty()) return@repeat
                val inspections = try {
                    decision.plans.mapIndexed { index, plan ->
                        onProgress(DirectorProgress.InspectingProjection(index + 1, decision.plans.size))
                        logger.debug("检查人偶投影 turn=${turn + 1} plan=${plan.id} index=${index + 1}/${decision.plans.size}")
                        plan.id to inspector.inspect(plan, input.scene)
                    }.toMap()
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { throw ModelFailure("RENDER_FAILED", "人偶预览失败：${failure.message.orEmpty().take(180)}", false) }
                val issues = inspections.flatMap { (id, value) -> value.errors.map { ToolIssue("PROJECTION_OUT_OF_FRAME", it, id) } }
                observations += ToolObservation("render_preview", inspections.flatMap { (id, value) -> value.messages.map { "$id: $it" } }, issues,
                    inspections.mapNotNull { (id, value) -> value.bounds?.let { id to it } }.toMap())
                if (issues.isNotEmpty()) return@repeat
                if (decision.action == ActionKind.FINAL || localReply) {
                    val revised = decision.plans.map { plan ->
                        val old = input.existing.find { it.id == plan.id }
                        if (plan.id == input.selectedId && old != null) plan.copy(revision = old.revision + 1) else plan
                    }
                    logger.info("导演任务完成 image=${input.scene.id.take(8)} turns=${turn + 1} plans=${revised.size}")
                    return CompositionResult(revised, traces, observations, outputs)
                }
            }
            if (localAttempted) throw ModelFailure("LOCAL_INVALID_OUTPUT", "", false)
            throw ModelFailure("INVALID_OUTPUT", "导演在两轮内未提交有效最终方案，请修改要求或更换模型后重试", false)
        } catch (failure: ModelFailure) {
            logger.error("导演任务失败 image=${input.scene.id.take(8)} code=${failure.code} retryable=${failure.retryable}", failure)
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
    suspend fun compose(
        input: DirectorInput,
        settings: ModelSettings,
        inspector: PreviewInspector,
        onProgress: (DirectorProgress) -> Unit = {}
    ): CompositionResult = agent.run(
        input,
        settings,
        { id -> if (id == ProviderId.LOCAL) local else GatewayProvider(settings, id) },
        inspector,
        onProgress
    )
}
