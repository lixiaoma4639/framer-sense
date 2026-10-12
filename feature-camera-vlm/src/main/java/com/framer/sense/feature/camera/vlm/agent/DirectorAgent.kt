package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 渲染工具的结构化观察；无 GPU 的测试可以省略 bounds。 */
data class PreviewInspection(
    val bounds: CropRect? = null,
    val messages: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val appliedAvatar: AvatarPlacement? = null,
    val appliedSpec: ResolvedPhotoSpec? = null,
    /** 实际渲染采用的完整方案，包含裁切、空间站位及尺度估算来源。 */
    val appliedPlan: CompositionPlan? = null
)

/** 导演任务向界面报告的当前执行阶段；仅描述真实运行中的一步，不保存历史。 */
sealed interface DirectorProgress {
    /** 正在整理冻结画面、用户要求和已有校验结果。 */
    data object PreparingRequest : DirectorProgress
    data object WaitingPreviousTask : DirectorProgress

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
    suspend fun photographyActions(input: DirectorInput): List<PhotographyActionCapability>? = null
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
        if (input.photographyEnabled && settings.mode == ModelMode.OFFLINE) {
            try {
                return withLocalPhotographyDeadline { runSession(input, settings, providerFactory, inspector, onProgress) }
            } catch (failure: ModelFailure) {
                if (failure.code == "LOCAL_PHOTOGRAPHY_DEADLINE") logger.warn("离线摄影整轮达到时限 limitMs=$LOCAL_PHOTOGRAPHY_DEADLINE_MS image=${input.scene.id.take(8)}")
                throw failure
            }
        }
        return runSession(input, settings, providerFactory, inspector, onProgress)
    }

    private suspend fun runSession(
        input: DirectorInput,
        settings: ModelSettings,
        providerFactory: (ProviderId) -> VisionModelProvider,
        inspector: PreviewInspector,
        onProgress: (DirectorProgress) -> Unit
    ): CompositionResult {
        logger.info("开始导演任务 image=${input.scene.id.take(8)} mode=${settings.mode} region=${settings.region} existing=${input.existing.size}")
        val traces = mutableListOf<CallTrace>()
        val observations = mutableListOf<ToolObservation>()
        val outputs = mutableListOf<String>()
        val failedProviders = mutableSetOf<ProviderId>()
        var lastRaw: String? = null
        var localAttempted = false
        var localPhotography = false
        val sessionId = newVlmId().take(8)
        var activeRequest = "none"
        var diagnosticPhase = "CAPABILITIES"
        var validationFailure: Throwable? = null
        fun context() = "session=$sessionId image=${input.scene.id.take(8)} request=$activeRequest phase=$diagnosticPhase"
        logger.info("PHOTO_DIAG ${context()} start mode=${settings.mode} photography=${input.photographyEnabled} role=${input.avatarId} expectedPlans=${if(input.selectedId == null) 3 else 1} scene=${input.scene.width}x${input.scene.height} zoom=${input.scene.currentZoom}")
        try {
            val photographyInput = if(input.photographyEnabled) input.copy(photographyActions=inspector.photographyActions(input)) else input
            logger.info("PHOTO_DIAG ${context()} availableActions=${PhotographyProtocol.actionIds(photographyInput)} capabilities=${photographyInput.photographyActions}")
            repeat(2) { turn ->
                currentCoroutineContext().ensureActive()
                if (localAttempted) {
                    logger.warn("离线单次结果未通过校验，不启动第二次推理 image=${input.scene.id.take(8)} issues=${observations.lastOrNull()?.issues}")
                    throw ModelFailure(if (localPhotography) "LOCAL_PHOTO_INVALID_OUTPUT" else "LOCAL_INVALID_OUTPUT", "", false).apply {
                        validationFailure?.let { initCause(it) }
                    }
                }
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
                        if (photographyInput.photographyEnabled && PhotographyProtocol.actionIds(photographyInput).isEmpty()) throw ModelFailure("PHOTO_ACTION_UNAVAILABLE", "", false)
                        val prompt = DirectorPrompt.build(photographyInput, observations, lastRaw, turn == 1, offline = id == ProviderId.LOCAL)
                        activeRequest = newVlmId()
                        diagnosticPhase = "MODEL_INPUT"
                        logger.info("PHOTO_DIAG ${context()} provider=$id turn=${turn+1} promptChars=${prompt.length} promptBytes=${prompt.toByteArray(Charsets.UTF_8).size}")
                        if (id == ProviderId.LOCAL && input.photographyEnabled) logger.payload(context(), "PROMPT", prompt)
                        logger.info("调用模型 turn=${turn + 1} provider=$id fallback=$switchingProvider")
                        if (id == ProviderId.LOCAL) {
                            localAttempted = true
                            logger.info("离线构图单次调用 image=${input.scene.id.take(8)} maxCalls=1 deadlineMs=$LOCAL_PHOTOGRAPHY_DEADLINE_MS")
                        }
                        diagnosticPhase = "MODEL_INFERENCE"
                        val answer = providerFactory(id).step(ModelCall(photographyInput, prompt, activeRequest,
                            if (input.photographyEnabled || PhotoReferenceDecisionPolicy.enabled(input)) DirectorPrompt.build(input.copy(photoReferences = false,photographyEnabled=false), observations, lastRaw, turn == 1, offline = id == ProviderId.LOCAL) else null,
                            if (input.photographyEnabled) DirectorPrompt.build(input.copy(photographyEnabled=false),observations,lastRaw,turn == 1) else null))
                        validationFailure = null
                        traces += CallTrace(id, answer.model, (System.nanoTime() - start) / 1_000_000, "决策 ${turn + 1}")
                        logger.info("模型返回 turn=${turn + 1} provider=$id model=${answer.model} elapsedMs=${traces.last().elapsedMs} outputBytes=${answer.raw.toByteArray().size}")
                        diagnosticPhase = "MODEL_OUTPUT"
                        logger.info("PHOTO_DIAG ${context()} model=${answer.model} photographyProtocol=${answer.photographyProtocol} outputChars=${answer.raw.length} elapsedMs=${traces.last().elapsedMs}")
                        if (id == ProviderId.LOCAL && input.photographyEnabled) logger.payload(context(), "RAW", answer.raw)
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
                answer.sceneObservation?.takeIf { it.imageId == input.scene.id }?.let {
                    observations += ToolObservation("describe_selected_background", listOf(it.text))
                }
                onProgress(DirectorProgress.ValidatingResponse(turn + 1))
                val localReply = traces.last().provider == ProviderId.LOCAL
                localPhotography = localReply && answer.photographyProtocol
                val photoProtocol = PhotoReferenceDecisionPolicy.enabled(input) && (localReply || answer.photoProtocol)
                val decision = if(answer.photographyProtocol) {
                    val plans = try {
                        val expanded = if (localReply) OfflinePhotographyProtocol.expand(answer.raw, photographyInput) { phase, detail ->
                            diagnosticPhase = phase
                            if (phase in listOf("NORMALIZED_JSON", "EXPANDED_V3")) logger.payload(context(), phase, detail)
                            else logger.info("PHOTO_DIAG ${context()} $detail")
                        } else answer.raw
                        diagnosticPhase = "PHOTOGRAPHY_VALIDATE"
                        PhotographyProtocol.decode(expanded, photographyInput, allowLayoutFallback=!localReply && turn == 1)
                    }
                    catch(failure: IllegalArgumentException) {
                        validationFailure = failure
                        logger.warn("PHOTO_DIAG ${context()} failureType=${failure::class.java.simpleName} reason=${VlmJson.encodeToString(failure.message.orEmpty().take(1000))}")
                        logger.warn("摄影决策校验失败 turn=${turn+1} reason=${failure.message}")
                        logger.diagnostic { "摄影格式诊断 turn=${turn+1} local=$localReply chars=${answer.raw.length} prefix=${VlmJson.encodeToString(answer.raw.take(160))} suffix=${VlmJson.encodeToString(answer.raw.takeLast(160))}" }
                        observations += ToolObservation("validate_photography",listOf(failure.message.orEmpty()),
                            listOf(ToolIssue("PHOTOGRAPHY_INVALID",failure.message.orEmpty())))
                        return@repeat
                    }
                    logger.info("摄影决策接入 version=3 plans=${plans.joinToString { "${it.id}:crop=${it.crop},head=${it.photography?.head},foot=${it.photography?.foot},fallback=${it.photography?.layoutFallback}" }}")
                    DirectorDecision(ActionKind.FINAL,plans,imageId=input.scene.id)
                } else if (photoProtocol) {
                    val plans = try { PhotoReferenceDecisionPolicy.decode(answer.raw, input, localReply) }
                    catch (failure: IllegalArgumentException) {
                        observations += ToolObservation("validate_photo_reference", listOf(failure.message.orEmpty()),
                            issues = listOf(ToolIssue("PHOTO_REFERENCE_INVALID", failure.message.orEmpty())))
                        if (localReply) throw ModelFailure("LOCAL_PHOTO_INVALID_OUTPUT", "", false)
                        return@repeat
                    }
                    observations += ToolObservation("photo_reference_catalog", listOf("PREVIEW_ONLY; HAND_HIP/WALK_PAUSE excluded"))
                    DirectorDecision(ActionKind.FINAL, plans, imageId = input.scene.id)
                } else {
                val intentDecision = answer.decision?.let(CompositionIntentMapper::fromLegacy) ?: try {
                    if (localReply) OfflineDirectorProtocol.decodeIntent(answer.raw, input)
                    else decodeGatewayIntent(answer.raw)
                } catch (error: Exception) {
                    logger.warn("导演构图意图解析失败 type=${error::class.java.simpleName}")
                    logger.diagnostic { "导演构图意图解析详情 turn=${turn + 1} type=${error::class.java.name} reason=${error.message}" }
                    null
                }
                if (intentDecision == null) {
                    logger.warn("模型输出不是有效构图意图 turn=${turn + 1} outputBytes=${answer.raw.toByteArray().size}")
                    val errors = DirectorOutput.repairErrors(answer.raw, input.scene.id)
                    observations += ToolObservation("validate_plan", errors, errors.map { ToolIssue("INVALID_OUTPUT", it) })
                    logger.warn("导演输出修正要求 turn=${turn + 1} issues=${errors.joinToString("；")}")
                    return@repeat
                }
                if (intentDecision.version != 1 || intentDecision.imageId != input.scene.id) {
                    logger.warn("模型意图 imageId/version 不匹配 turn=${turn + 1} version=${intentDecision.version} imageMatches=${intentDecision.imageId == input.scene.id}")
                    observations += ToolObservation("validate_plan", listOf("协议版本必须为 1，imageId 必须等于 ${input.scene.id}"))
                    return@repeat
                }
                if (intentDecision.action == ActionKind.CAPABILITIES) {
                    observations += ToolObservation("list_avatar_options", listOf(DirectorPrompt.capabilities()))
                    return@repeat
                }
                val mapped = try {
                    // 旧本地协议的合法中间动作仍可一次完成；云端保持 FINAL 提交约束。
                    val executable = if (localReply && intentDecision.action in listOf(ActionKind.VALIDATE, ActionKind.RENDER))
                        intentDecision.copy(action = ActionKind.FINAL) else intentDecision
                    CompositionIntentMapper.map(executable, input)
                } catch (error: IllegalArgumentException) {
                    logger.warn("构图意图安全映射失败 turn=${turn + 1} reason=${error.message}")
                    observations += ToolObservation("map_composition_intents", listOf(error.message.orEmpty()), listOf(ToolIssue("INVALID_INTENT", error.message.orEmpty())))
                    return@repeat
                }
                logger.info("构图意图映射完成 turn=${turn + 1} intents=${intentDecision.intents.joinToString { "${it.shot}/${it.zone}/${it.pose}/${it.facing}/${it.expression}" }} plans=${mapped.plans.joinToString { "${it.id}:${it.shot}/${it.avatar.pose}/${it.avatar.expression}" }}")
                observations += ToolObservation(
                    "map_composition_intents",
                    mapped.messages.ifEmpty { listOf("已将模型构图意图映射为安全的坐标、裁剪与倍率") }
                )
                // 人像身份只来自拍摄前的用户选择，映射器不会接受或推断模型指定的角色。
                DirectorDecision(intentDecision.action, mapped.plans, intentDecision.version, intentDecision.imageId)
                }
                diagnosticPhase = "PLAN_VALIDATE"
                val errors = validator.validate(decision.plans, input)
                logger.info("PHOTO_DIAG ${context()} plans=${decision.plans.size} errors=${VlmJson.encodeToString(errors)}")
                logger.info("校验模型输出 turn=${turn + 1} action=${decision.action} plans=${decision.plans.size} errors=${errors.size}")
                if (errors.isNotEmpty()) logger.warn("模型输出校验详情 turn=${turn + 1} errors=${errors.joinToString("；")}")
                observations += ToolObservation("validate_plan", errors.ifEmpty { listOf("基础协议和几何约束通过") }, errors.map { ToolIssue("INVALID_PLAN", it) })
                if (errors.isNotEmpty()) return@repeat
                val inspections = try {
                    decision.plans.mapIndexed { index, plan ->
                        if (input.selectedId != null && plan.id != input.selectedId)
                            return@mapIndexed plan.id to PreviewInspection(appliedPlan = plan)
                        onProgress(DirectorProgress.InspectingProjection(index + 1, decision.plans.size))
                        diagnosticPhase = "RENDER_${plan.id}"
                        logger.info("PHOTO_DIAG ${context()} start revision=${plan.revision} action=${plan.resolvedSpec?.action?.id} foot=${plan.avatar.foot} height=${plan.avatar.height}")
                        logger.debug("检查人偶投影 turn=${turn + 1} plan=${plan.id} index=${index + 1}/${decision.plans.size}")
                        plan.id to inspector.inspect(plan, input.scene).also {
                            logger.info("PHOTO_DIAG ${context()} complete bounds=${it.bounds} errors=${VlmJson.encodeToString(it.errors)} actualFoot=${it.appliedPlan?.avatar?.foot ?: it.appliedAvatar?.foot} actualHeight=${it.appliedPlan?.avatar?.height ?: it.appliedAvatar?.height}")
                        }
                    }.toMap()
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { throw ModelFailure("RENDER_FAILED", "人偶预览失败：${failure.message.orEmpty().take(180)}", false) }
                val issues = inspections.flatMap { (id, value) -> value.errors.map { ToolIssue("PROJECTION_OUT_OF_FRAME", it, id) } }
                observations += ToolObservation("render_preview", inspections.flatMap { (id, value) -> value.messages.map { "$id: $it" } }, issues,
                    inspections.mapNotNull { (id, value) -> value.bounds?.let { id to it } }.toMap())
                if (issues.isNotEmpty()) {
                    logger.warn("人偶投影校验失败 turn=${turn + 1} issues=${issues.joinToString { "${it.planId}: ${it.message}" }}")
                    return@repeat
                }
                val mismatchedPlans = decision.plans.filter { plan ->
                    inspections[plan.id]?.appliedPlan?.let { actual ->
                        actual.id != plan.id || actual.revision != plan.revision || actual.viewBinding != plan.viewBinding
                    } == true
                }
                if (mismatchedPlans.isNotEmpty()) {
                    val errors = mismatchedPlans.map { "${it.id}: RENDER_SOURCE_MISMATCH" }
                    logger.warn("渲染来源校验失败 turn=${turn + 1} errors=${errors.joinToString("；")}")
                    observations += ToolObservation("validate_rendered_plan", errors, errors.map { ToolIssue("INVALID_RENDERED_PLAN", it) })
                    return@repeat
                }
                val resolved = decision.plans.map { plan ->
                    // 修改仅影响选中方案；完整回传避免最终规格与尺度/裁切字段脱节。
                    if (input.selectedId != null && plan.id != input.selectedId) return@map plan
                    val inspection = inspections[plan.id]
                    inspection?.appliedPlan ?: plan.withActualAvatar(inspection?.appliedAvatar ?: plan.avatar).let {
                        it.copy(resolvedSpec = inspection?.appliedSpec ?: it.resolvedSpec)
                    }
                }
                // 渲染调整后仍须通过同一业务校验，修改任务仍只能改变选中方案。
                diagnosticPhase = "RESOLVED_VALIDATE"
                val resolvedErrors = validator.validate(resolved, input)
                logger.info("PHOTO_DIAG ${context()} errors=${VlmJson.encodeToString(resolvedErrors)}")
                if (resolved.any { it.avatar.avatarId != input.avatarId }) {
                    logger.warn("渲染角色校验失败 turn=${turn + 1} expected=${input.avatarId}")
                    observations += ToolObservation("validate_rendered_plan", emptyList(), issues = listOf(ToolIssue("AVATAR_ID_CHANGED", "AVATAR_ID_CHANGED")))
                    return@repeat
                }
                if (resolvedErrors.isNotEmpty()) {
                    logger.warn("渲染方案校验失败 turn=${turn + 1} errors=${resolvedErrors.joinToString("；")}")
                    observations += ToolObservation("validate_rendered_plan", resolvedErrors, resolvedErrors.map { ToolIssue("INVALID_RENDERED_PLAN", it) })
                    return@repeat
                }
                if (decision.action == ActionKind.FINAL || localReply) {
                    val revised = OfflineSceneDescription.attach(resolved, input, answer.sceneObservation).map { plan ->
                        val old = input.existing.find { it.id == plan.id }
                        if (plan.id == input.selectedId && old != null) plan.withRevision(old.revision + 1) else plan
                    }
                    logger.info("导演任务完成 image=${input.scene.id.take(8)} turns=${turn + 1} plans=${revised.size}")
                    logger.info("PHOTO_DIAG ${context()} result=SUCCESS plans=${revised.size}")
                    return CompositionResult(revised, traces, observations, outputs,
                        photographyProtocolVersion=if(answer.photographyProtocol) 3 else if(photoProtocol) 2 else 1)
                }
            }
            if (localAttempted) throw ModelFailure(if(localPhotography) "LOCAL_PHOTO_INVALID_OUTPUT" else "LOCAL_INVALID_OUTPUT", "", false)
            throw ModelFailure("INVALID_OUTPUT", "导演在两轮内未提交有效最终方案，请修改要求或更换模型后重试", false)
        } catch (failure: ModelFailure) {
            val failedCheck = if (failure.code.endsWith("INVALID_OUTPUT")) observations.lastOrNull { it.issues.isNotEmpty() } else null
            val reasons = failedCheck?.issues?.joinToString("; ") { "${it.planId.orEmpty()}:${it.code}:${it.message}" }
                ?: validationFailure?.message ?: failure.message.orEmpty()
            logger.error("导演任务失败 ${context()} code=${failure.code} retryable=${failure.retryable} failedCheck=${failedCheck?.tool} reason=${VlmJson.encodeToString(reasons.take(1000))}", failure)
            logger.payload(context(), "FAILURE_DETAILS", reasons)
            failure.diagnostics = CompositionResult(emptyList(), traces.toList(), observations.toList(), outputs.toList())
            throw failure
        }
    }

    /** 新网关协议优先读取意图；旧网关完整方案会转成意图后走同一安全映射。 */
    private fun decodeGatewayIntent(raw: String): CompositionIntentDecision {
        val text = DirectorOutput.unwrap(raw)
        return runCatching { VlmJson.decodeFromString<CompositionIntentDecision>(text) }
            .getOrElse { CompositionIntentMapper.fromLegacy(DirectorOutput.decode(text)) }
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
