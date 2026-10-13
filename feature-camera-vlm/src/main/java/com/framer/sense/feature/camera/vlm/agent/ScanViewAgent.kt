package com.framer.sense.feature.camera.vlm.agent

import android.util.Base64
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import java.io.File

interface MultiViewProvider {
    suspend fun capabilities(): MultiImageCapabilities
    suspend fun select(request: ViewSelectionRequest): ViewSelectionReply
}
class MultiViewGateway(settings: ModelSettings, provider: ProviderId) : MultiViewProvider {
    private val gateway = GatewayProvider(settings, provider)
    override suspend fun capabilities(): MultiImageCapabilities =
        VlmJson.decodeFromString(gateway.execute(CloudModelConfig.capabilitiesV2Path, null))
    override suspend fun select(request: ViewSelectionRequest): ViewSelectionReply =
        VlmJson.decodeFromString(gateway.execute(CloudModelConfig.viewsPath, VlmJson.encodeToString(request)))
}
enum class ScanSelectionFallback { OFFLINE, CAPABILITY_UNAVAILABLE, NO_MULTIVIEW_MODEL, ANALYSIS_UNAVAILABLE }
data class ScanSelectionResult(val selection: ViewSelection?, val comparedFrames: Int = 0, val traces: List<CallTrace> = emptyList(), val fallback: ScanSelectionFallback? = null)

/** 多图排序与单图构图分离；失败保留扫描集供用户明确选帧。 */
class ScanViewAgent(
    private val logger: DirectorLogger = NoOpDirectorLogger,
    private val encodeImage: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }
) {
    suspend fun select(scan: ScanSnapshot, settings: ModelSettings, instruction: String, budget: PhotoSessionBudget,
        factory: (ProviderId) -> MultiViewProvider = { CloudProviders.views(settings, it) }): ScanSelectionResult = withContext(Dispatchers.IO) {
        if (settings.mode == ModelMode.OFFLINE) return@withContext ScanSelectionResult(null, fallback = ScanSelectionFallback.OFFLINE)
        if (budget.selectionCalls >= ScanLimits.MAX_SELECTION_CALLS) {
            logger.warn("模型使用 stage=选景任务 result=未调用 code=SELECTION_ATTEMPTS_EXHAUSTED selectionCalls=${budget.selectionCalls} scan=${scan.session.id.take(8)} reason=App同一扫描选景次数限制，与云端账户额度无关")
            throw ModelFailure("SELECTION_ATTEMPTS_EXHAUSTED", "", false)
        }
        val providers = ModelRouting.candidates(settings).filter { it != ProviderId.LOCAL }
        var capabilityAvailable = false
        var candidateAvailable = false
        val traces = mutableListOf<CallTrace>()
        var attempt = 0
        var activeProvider: ProviderId? = null
        var activeModel: String? = null
        try {
            // 按首选顺序惰性查询：官方选景成功时，不查询不相关网关，也不等待其超时。
            for (provider in providers) {
                val previousProvider = activeProvider
                activeProvider = provider
                activeModel = null
                if (previousProvider != null)
                    logger.info("模型使用 stage=选景切换 result=切换 from=$previousProvider ${ModelUsageLog.identity(provider, settings)} scan=${scan.session.id.take(8)}")
                logger.info("模型使用 stage=选景能力 result=检查 ${ModelUsageLog.identity(provider, settings)} scan=${scan.session.id.take(8)}")
                val capabilities = try { factory(provider).capabilities().also { capabilityAvailable = true } }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: ModelFailure) {
                    logger.warn("模型使用 stage=选景能力 result=失败 ${ModelUsageLog.identity(provider, settings)} ${ModelUsageLog.failure(failure)} retryable=${failure.retryable}")
                    if (!failure.retryable || (settings.mode == ModelMode.CLOUD && CloudProviders.officialQwen(settings, provider))) throw failure
                    continue
                }
                val capability = capabilities.providers.firstOrNull { it.provider == provider && it.configured && it.multiImage && it.maxImages > 0 }
                if (capabilities.version != 2 || capability == null) {
                    logger.warn("模型使用 stage=选景能力 result=跳过 ${ModelUsageLog.identity(provider, settings)} reason=未声明可用多图能力")
                    continue
                }
                logger.info("模型使用 stage=选景能力 result=声明可用 ${ModelUsageLog.identity(provider, settings, capability.model, "能力声明")} actualCallSucceeded=false")
                candidateAvailable = true
                val limit = capability.maxImages.coerceIn(1, ScanLimits.MAX_UPLOAD_FRAMES)
                val frames = representativeFrames(scan.frames, limit)
                val images = mutableListOf<ViewImage>(); var bytes = 0L
                for (frame in frames) {
                    val file = File(frame.scene.modelImagePath)
                    val size = file.length()
                    if (size <= 0 || bytes + size > ScanLimits.MAX_REQUEST_BYTES) continue
                    val data = file.readBytes(); bytes += data.size
                    val ratio = minOf(1f, 768f / maxOf(frame.scene.width, frame.scene.height))
                    images += ViewImage(frame.id, encodeImage(data), frame.preferred,
                        maxOf(1, (frame.scene.width * ratio).toInt()), maxOf(1, (frame.scene.height * ratio).toInt()))
                }
                if (images.isEmpty()) continue
                var feedback = ""
                while (attempt < ScanLimits.MAX_SELECTION_CALLS) {
                    attempt++
                    val prompt = CloudViewSelectionProtocol.prompt(scan.session.id, images, instruction, feedback)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    try { budget.consume(bytes, selection = true) }
                    catch (_: IllegalStateException) { throw ModelFailure("SESSION_BUDGET_EXHAUSTED", "", false) }
                    logger.info("模型使用 stage=选景调用 result=开始 ${ModelUsageLog.identity(provider, settings, capability.model, "能力声明")} scan=${scan.session.id.take(8)} attempt=$attempt images=${images.size}")
                    val response = try { factory(provider).select(ViewSelectionRequest(provider, newVlmId(), scan.session.id, images, prompt)) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: ModelFailure) {
                        logger.warn("模型使用 stage=选景调用 result=失败 ${ModelUsageLog.identity(provider, settings, capability.model, "能力声明")} ${ModelUsageLog.failure(failure)} attempt=$attempt retryable=${failure.retryable}")
                        if (!failure.retryable) throw failure
                        break
                    }
                    traces += CallTrace(provider, response.model, response.elapsedMs, "SELECT_VIEWS")
                    activeModel = response.model
                    logger.info("模型使用 stage=选景调用 result=返回成功 ${ModelUsageLog.identity(provider, settings, response.model)} scan=${scan.session.id.take(8)} attempt=$attempt elapsedMs=${response.elapsedMs} awaitingValidation=true")
                    val inspection = CloudViewSelectionProtocol.inspect(response, scan.session.id, images.map { it.frameId }.toSet())
                    val selection = inspection.selection
                    if (selection != null) {
                        logger.info("模型使用 stage=选景任务 result=成功 ${ModelUsageLog.identity(provider, settings, response.model)} scan=${scan.session.id.take(8)} attempt=$attempt")
                        return@withContext ScanSelectionResult(selection, images.size, traces)
                    }
                    logger.warn("模型使用 stage=选景校验 result=失败 ${ModelUsageLog.identity(provider, settings, response.model)} scan=${scan.session.id.take(8)} attempt=$attempt code=INVALID_OUTPUT reason=${inspection.reason}")
                    feedback = "Previous result failed validation: ${inspection.reason}. Correct that error and return the complete JSON structure. Use only the supplied original frame IDs."
                }
                if (attempt >= ScanLimits.MAX_SELECTION_CALLS) break
            }
            ScanSelectionResult(null, traces = traces, fallback = when {
                candidateAvailable -> ScanSelectionFallback.ANALYSIS_UNAVAILABLE
                capabilityAvailable -> ScanSelectionFallback.NO_MULTIVIEW_MODEL
                else -> ScanSelectionFallback.CAPABILITY_UNAVAILABLE
            }).also {
                logger.warn("模型使用 stage=选景任务 result=转人工选景 ${ModelUsageLog.identity(activeProvider, settings, activeModel)} scan=${scan.session.id.take(8)} reason=${it.fallback}")
            }
        } catch (cancel: CancellationException) {
            logger.info("模型使用 stage=选景任务 result=取消 ${ModelUsageLog.identity(activeProvider, settings, activeModel)} scan=${scan.session.id.take(8)}")
            throw cancel
        } catch (error: Exception) {
            logger.error("模型使用 stage=选景任务 result=失败 ${ModelUsageLog.identity(activeProvider, settings, activeModel)} scan=${scan.session.id.take(8)} ${ModelUsageLog.failure(error)}")
            throw error
        }
    }

    /** 偏爱优先，其余用最远方向/时间距离补足，避免只上传扫描开头。 */
    fun representativeFrames(frames: List<ScanFrame>, limit: Int): List<ScanFrame> {
        val selected = frames.filter { it.preferred }.sortedByDescending { it.quality }.take(limit).toMutableList()
        if (selected.isEmpty()) frames.maxByOrNull { it.quality }?.let(selected::add)
        while (selected.size < limit) {
            val next = frames.filter { it !in selected }.maxByOrNull { frame -> selected.minOf { old ->
                val a = frame.pose; val b = old.pose
                if (a?.heading != null && b?.heading != null && a.basisId == b.basisId)
                    kotlin.math.abs(angleDelta(a.heading, b.heading)) + kotlin.math.abs(a.pitch - b.pitch)
                else kotlin.math.abs(frame.receivedNs - old.receivedNs).toFloat() / 1_000_000_000f
            } } ?: break
            selected += next
        }
        return selected
    }
}
