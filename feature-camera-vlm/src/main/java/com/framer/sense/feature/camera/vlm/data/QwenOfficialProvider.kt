package com.framer.sense.feature.camera.vlm.data

import android.util.Base64
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.agent.MultiViewProvider
import com.framer.sense.feature.camera.vlm.agent.MultiViewGateway
import com.framer.sense.feature.camera.vlm.agent.PhotoReferenceDecisionPolicy
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 单图导演和多图选景共用官方图像协议；不读取网关配置，不记录密钥或上游错误原文。 */
class QwenOfficialProvider(
    private val settings: ModelSettings,
    private val client: OkHttpClient = officialClient,
    private val encodeImage: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }
) : VisionModelProvider, MultiViewProvider {
    override suspend fun step(call: ModelCall): StepReply = withContext(Dispatchers.IO) {
        QwenOfficialProtocol.validate(settings)
        val bytes = File(call.input.scene.modelImagePath).readBytes()
        try { call.input.sessionBudget?.consume(bytes.size.toLong()) }
        catch (_: IllegalStateException) { throw ModelFailure("SESSION_BUDGET_EXHAUSTED", "", false) }
        val image = ViewImage(call.input.scene.id, encodeImage(bytes), false, call.input.scene.width, call.input.scene.height)
        val start = System.nanoTime()
        val (model, raw) = post(call.prompt, listOf(image))
        StepReply(model, raw, elapsedMs = elapsed(start), photoProtocol = PhotoReferenceDecisionPolicy.enabled(call.input))
    }

    override suspend fun capabilities(): MultiImageCapabilities {
        val configured = settings.qwenApiKey.isNotBlank() && settings.qwenModel.isNotBlank()
        QwenOfficialProtocol.validate(settings)
        // 应用只提交最多八张静态图片；真实结果仍由选景校验器校验，不从能力声明推断选景成功。
        return MultiImageCapabilities(listOf(MultiImageCapability(ProviderId.QWEN, settings.qwenModel.trim(),
            configured, configured, 8, directorV2 = configured)))
    }

    override suspend fun select(request: ViewSelectionRequest): ViewSelectionReply {
        check(request.provider == ProviderId.QWEN)
        require(request.images.size in 1..8)
        val start = System.nanoTime()
        val (model, raw) = post(request.prompt, request.images)
        val selection = runCatching { VlmJson.decodeFromString<ViewSelection>(raw) }.getOrNull()
        return ViewSelectionReply(model, raw, selection, elapsed(start))
    }

    /** 用户主动检查时只发送少量文本，不上传照片，也不消耗扫描会话额度。 */
    suspend fun checkConfiguration(): String {
        val (model, raw) = post("Return JSON with exactly one field: {\"ok\":true}.", emptyList(), 32)
        val valid = runCatching { VlmJson.parseToJsonElement(raw).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true }.getOrDefault(false)
        if (!valid) throw ModelFailure("QWEN_OUTPUT", "", false)
        return model
    }

    private suspend fun post(prompt: String, images: List<ViewImage>, maxTokens: Int = 4096): Pair<String, String> {
        val url = QwenOfficialProtocol.validate(settings)
        val payload = QwenOfficialProtocol.payload(settings.qwenModel.trim(), prompt, images, maxTokens)
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${settings.qwenApiKey.trim()}")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        ModelFailure(if (e is SocketTimeoutException) "QWEN_TIMEOUT" else "QWEN_NETWORK", "", true))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            // 仅检查稳定错误标识；绝不将上游正文、请求或鉴权头放入异常和日志。
                            val body = response.body?.string().orEmpty()
                            val result = QwenOfficialProtocol.response(response.code, body, settings.qwenModel.trim())
                            if (continuation.isActive) continuation.resume(result)
                        } catch (failure: ModelFailure) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } catch (_: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(ModelFailure("QWEN_NETWORK", "", true))
                        }
                    }
                }
            })
        }
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000

    companion object {
        private val officialClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}

/** 纯协议转换，便于验证官方图片通道、错误脱敏和地址约束。 */
internal object QwenOfficialProtocol {
    private val officialErrors = mapOf(
        "Arrearage" to "QWEN_ACCOUNT",
        "AllocationQuota.FreeTierOnly" to "QWEN_FREE_QUOTA",
        "AccessDenied.Unpurchased" to "QWEN_NOT_ACTIVATED",
        "Model.AccessDenied" to "QWEN_MODEL_PERMISSION",
        "Workspace.AccessDenied" to "QWEN_WORKSPACE",
        "App.AccessDenied" to "QWEN_PERMISSION",
        "Endpoint.AccessDenied" to "QWEN_MODEL",
        "AccessDenied" to "QWEN_PERMISSION",
        "access_denied" to "QWEN_PERMISSION",
        "InvalidApiKey" to "QWEN_AUTH",
        "invalid_api_key" to "QWEN_AUTH",
        "DataInspectionFailed" to "QWEN_REFUSED",
        "DataInspectionFailed.Input" to "QWEN_REFUSED",
        "DataInspectionFailed.Output" to "QWEN_REFUSED"
    )
    fun validate(settings: ModelSettings): HttpUrl {
        if (settings.qwenApiKey.isBlank() || settings.qwenApiKey.trim().any { it.code !in 33..126 }) throw ModelFailure("QWEN_KEY", "", true)
        if (settings.qwenModel.isBlank()) throw ModelFailure("QWEN_MODEL", "", false)
        val url = settings.qwenBaseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        val expectedBasePath = CloudModelConfig.qwenBaseUrl.toHttpUrlOrNull()?.encodedPath?.trimEnd('/')
        if (url == null || url.scheme != "https" || url.port != 443 ||
            (url.host !in CloudModelConfig.qwenAllowedHosts && !url.host.endsWith(CloudModelConfig.qwenWorkspaceHostSuffix)) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            url.encodedPath != expectedBasePath) throw ModelFailure("QWEN_URL", "", false)
        return url.newBuilder().addPathSegments(CloudModelConfig.qwenVisionPath.trimStart('/')).build()
    }

    fun payload(model: String, prompt: String, images: List<ViewImage>, maxTokens: Int): JsonObject = buildJsonObject {
        put("model", model)
        put("enable_thinking", false)
        put("response_format", buildJsonObject { put("type", "json_object") })
        put("max_tokens", maxTokens)
        put("messages", buildJsonArray { add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "Return valid JSON.\n$prompt") })
                images.forEach { image ->
                    add(buildJsonObject { put("type", "text"); put("text", "frameId=${image.frameId}") })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,${image.imageBase64}") })
                    })
                }
            })
        }) })
    }

    fun response(status: Int, body: String, requestedModel: String): Pair<String, String> {
        val json = runCatching { VlmJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = json?.get("error") as? JsonObject
        // 兼容 OpenAI 嵌套 error、DashScope 顶层 code 以及只提供 type 的错误。
        val rawCodes = listOf(error?.get("code"), json?.get("code"), error?.get("type"))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
        val recognized = rawCodes.firstNotNullOfOrNull { raw -> officialErrors.entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } }
        fun failure(code: String, retryable: Boolean = false) = ModelFailure(code, "", retryable).apply {
            upstreamHttpStatus = status
            upstreamErrorCode = recognized?.key
        }
        val errorCode = rawCodes.joinToString(" ").lowercase()
        if (errorCode.contains("data_inspection") || errorCode.contains("datainspection") || errorCode.contains("content_filter") || errorCode.contains("sensitive"))
            throw failure("QWEN_REFUSED")
        if (status !in 200..299) throw failure(recognized?.value ?: when (status) {
            401 -> "QWEN_AUTH"
            403 -> "QWEN_PERMISSION"
            404 -> "QWEN_MODEL"
            429 -> "QWEN_LIMIT"
            in 500..599 -> "QWEN_SERVICE"
            else -> "QWEN_REQUEST"
        }, recognized == null && (status == 429 || status >= 500))
        return try {
            val choice = requireNotNull(json)["choices"]!!.jsonArray.first().jsonObject
            val message = choice["message"]!!.jsonObject
            if (message["refusal"]?.let { it != JsonNull && (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() != false } == true ||
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "content_filter") throw failure("QWEN_REFUSED")
            val raw = message["content"]!!.jsonPrimitive.content
            if (raw.isBlank() || raw.length > 100_000 || choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length")
                throw failure("QWEN_OUTPUT")
            val model = json["model"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: requestedModel
            model to raw
        } catch (failure: ModelFailure) { throw failure }
        catch (_: Exception) { throw failure("QWEN_OUTPUT") }
    }
}

object CloudProviders {
    fun officialQwen(settings: ModelSettings, provider: ProviderId) = provider == ProviderId.QWEN && settings.qwenAccess == QwenAccess.OFFICIAL
    fun vision(settings: ModelSettings, provider: ProviderId): VisionModelProvider =
        if (officialQwen(settings, provider)) QwenOfficialProvider(settings) else GatewayProvider(settings, provider)
    fun views(settings: ModelSettings, provider: ProviderId): MultiViewProvider =
        if (officialQwen(settings, provider)) QwenOfficialProvider(settings) else MultiViewGateway(settings, provider)
}

/** 错误文案只由资源提供，不把供应商返回正文展示给用户。 */
internal fun qwenErrorResource(failure: Exception): Int? = when ((failure as? ModelFailure)?.code) {
    "QWEN_KEY" -> R.string.vlm_qwen_key_missing
    "QWEN_MODEL" -> R.string.vlm_qwen_model_invalid
    "QWEN_URL" -> R.string.vlm_qwen_url_invalid
    "QWEN_AUTH" -> R.string.vlm_qwen_auth_failed
    "QWEN_PERMISSION" -> R.string.vlm_qwen_permission_failed
    "QWEN_ACCOUNT" -> R.string.vlm_qwen_account_failed
    "QWEN_FREE_QUOTA" -> R.string.vlm_qwen_free_quota_failed
    "QWEN_NOT_ACTIVATED" -> R.string.vlm_qwen_not_activated
    "QWEN_MODEL_PERMISSION" -> R.string.vlm_qwen_model_permission_failed
    "QWEN_WORKSPACE" -> R.string.vlm_qwen_workspace_failed
    "QWEN_LIMIT" -> R.string.vlm_qwen_rate_limit
    "QWEN_SERVICE" -> R.string.vlm_qwen_service_failed
    "QWEN_REQUEST" -> R.string.vlm_qwen_request_failed
    "QWEN_REFUSED" -> R.string.vlm_qwen_refused
    "QWEN_OUTPUT" -> R.string.vlm_qwen_output_invalid
    "QWEN_NETWORK", "QWEN_TIMEOUT" -> R.string.vlm_qwen_network_failed
    else -> null
}
