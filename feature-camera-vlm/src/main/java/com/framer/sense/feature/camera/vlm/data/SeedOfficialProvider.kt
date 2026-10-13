package com.framer.sense.feature.camera.vlm.data

import android.util.Base64
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.agent.MultiViewProvider
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
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 火山方舟独立适配器；复用现有 Agent 和预算，不依赖 QWEN 或 Python 网关。 */
class SeedOfficialProvider(
    private val settings: ModelSettings,
    private val client: OkHttpClient = officialClient,
    private val encodeImage: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }
) : VisionModelProvider, MultiViewProvider {
    override suspend fun step(call: ModelCall): StepReply = withContext(Dispatchers.IO) {
        SeedOfficialProtocol.validate(settings)
        val bytes = File(call.input.scene.modelImagePath).readBytes()
        try { call.input.sessionBudget?.consume(bytes.size.toLong()) }
        catch (_: IllegalStateException) { throw ModelFailure("SESSION_BUDGET_EXHAUSTED", "", false) }
        val image = ViewImage(call.input.scene.id, encodeImage(bytes), false, call.input.scene.width, call.input.scene.height)
        val start = System.nanoTime()
        val (model, raw) = post(call.prompt, listOf(image))
        StepReply(model, raw, elapsedMs = elapsed(start), photoProtocol = PhotoReferenceDecisionPolicy.enabled(call.input))
    }

    override suspend fun capabilities(): MultiImageCapabilities {
        SeedOfficialProtocol.validate(settings)
        // 仅声明应用支持的图片通道；实际选景仍需校验，不代表账号调用已验收。
        return MultiImageCapabilities(listOf(MultiImageCapability(ProviderId.SEED, CloudModelConfig.seedModel,
            configured = true, multiImage = true, maxImages = ScanLimits.MAX_UPLOAD_FRAMES, directorV2 = true)))
    }

    override suspend fun select(request: ViewSelectionRequest): ViewSelectionReply {
        require(request.provider == ProviderId.SEED)
        require(request.images.size in 1..ScanLimits.MAX_UPLOAD_FRAMES)
        val start = System.nanoTime()
        val (model, raw) = post(request.prompt, request.images)
        val selection = runCatching { VlmJson.decodeFromString<ViewSelection>(raw) }.getOrNull()
        // 多图预算已在 ScanViewAgent 扣减，此处不重复扣减。
        return ViewSelectionReply(model, raw, selection, elapsed(start))
    }

    /** 只检查少量文本调用，不上传照片，不使用扫描会话预算。 */
    suspend fun checkConfiguration(): String {
        val (model, raw) = post("请返回 json 对象，且只有一个字段：{\"ok\":true}。", emptyList(), 64)
        val valid = runCatching { VlmJson.parseToJsonElement(raw).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true }.getOrDefault(false)
        if (!valid) throw ModelFailure("SEED_OUTPUT", "", false)
        return model
    }

    private suspend fun post(prompt: String, images: List<ViewImage>, maxTokens: Int = 4096): Pair<String, String> {
        val request = Request.Builder().url(SeedOfficialProtocol.validate(settings))
            .header("Authorization", "Bearer ${settings.seedApiKey.trim()}")
            .post(SeedOfficialProtocol.payload(CloudModelConfig.seedModel.trim(), prompt, images, maxTokens)
                .toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val pending = client.newCall(request)
            continuation.invokeOnCancellation { pending.cancel() }
            pending.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ModelFailure(
                        if (error is InterruptedIOException) "SEED_TIMEOUT" else "SEED_NETWORK", "", true))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            val result = SeedOfficialProtocol.response(response.code, response.body?.string().orEmpty(), CloudModelConfig.seedModel.trim())
                            if (continuation.isActive) continuation.resume(result)
                        } catch (failure: ModelFailure) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(ModelFailure(
                                if (error is InterruptedIOException) "SEED_TIMEOUT" else "SEED_NETWORK", "", true))
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

/** Seed 专用协议，避免将百炼参数和错误码套到方舟。 */
internal object SeedOfficialProtocol {
    private val refusalCodes = setOf("SensitiveContentDetected", "InputTextSensitiveContentDetected",
        "InputImageSensitiveContentDetected", "OutputTextSensitiveContentDetected", "InputTextRiskDetection",
        "InputImageRiskDetection", "OutputTextRiskDetection")

    fun validate(settings: ModelSettings, baseUrl: String = CloudModelConfig.seedBaseUrl,
        model: String = CloudModelConfig.seedModel): HttpUrl {
        if (settings.seedApiKey.isBlank() || settings.seedApiKey.trim().any { it.code !in 33..126 })
            throw ModelFailure("SEED_KEY", "", true)
        if (model.isBlank()) throw ModelFailure("SEED_MODEL", "", false)
        val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        val path = CloudModelConfig.seedBaseUrl.toHttpUrlOrNull()?.encodedPath?.trimEnd('/')
        if (url == null || url.scheme != "https" || url.port != 443 || url.host !in CloudModelConfig.seedAllowedHosts ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || url.encodedPath != path)
            throw ModelFailure("SEED_URL", "", false)
        return url.newBuilder().addPathSegments(CloudModelConfig.seedVisionPath.trimStart('/')).build()
    }

    fun payload(model: String, prompt: String, images: List<ViewImage>, maxTokens: Int): JsonObject = buildJsonObject {
        put("model", model)
        put("thinking", buildJsonObject { put("type", "disabled") })
        put("response_format", buildJsonObject { put("type", "json_object") })
        put("max_tokens", maxTokens)
        put("messages", buildJsonArray { add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "请返回合法 json 对象。\n$prompt") })
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
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty()
        // 只保留固定的已识别错误标识，未知 code 和所有 message 均不回显。
        val refusal = refusalCodes.firstOrNull { code.equals(it, true) || code.startsWith("$it.", true) }
        fun failure(code: String, retryable: Boolean = false) = ModelFailure(code, "", retryable).apply {
            upstreamHttpStatus = status
            upstreamErrorCode = refusal
        }
        if (refusal != null) throw failure("SEED_REFUSED")
        if (status !in 200..299) throw failure(when (status) {
            401 -> "SEED_AUTH"
            403 -> "SEED_PERMISSION"
            404 -> "SEED_MODEL"
            429 -> "SEED_LIMIT"
            in 500..599 -> "SEED_SERVICE"
            else -> "SEED_REQUEST"
        }, status == 429 || status >= 500)
        return try {
            val choice = requireNotNull(json)["choices"]!!.jsonArray.first().jsonObject
            val message = choice["message"]!!.jsonObject
            val refused = message["refusal"]?.let { it != JsonNull && (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() != false } == true
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            if (refused || finish == "content_filter") throw failure("SEED_REFUSED")
            val content = message["content"] as? JsonPrimitive
            val raw = content?.takeIf { it.isString }?.contentOrNull ?: throw failure("SEED_OUTPUT")
            if (raw.isBlank() || raw.length > 100_000 || finish == "length") throw failure("SEED_OUTPUT")
            val model = (json["model"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: requestedModel
            model to raw
        } catch (failure: ModelFailure) { throw failure }
        catch (_: Exception) { throw failure("SEED_OUTPUT") }
    }
}

internal fun seedErrorResource(failure: Exception): Int? = when ((failure as? ModelFailure)?.code) {
    "SEED_KEY" -> R.string.vlm_seed_key_missing
    "SEED_MODEL" -> R.string.vlm_seed_model_invalid
    "SEED_URL" -> R.string.vlm_seed_url_invalid
    "SEED_AUTH" -> R.string.vlm_seed_auth_failed
    "SEED_PERMISSION" -> R.string.vlm_seed_permission_failed
    "SEED_LIMIT" -> R.string.vlm_seed_rate_limit
    "SEED_SERVICE" -> R.string.vlm_seed_service_failed
    "SEED_REQUEST" -> R.string.vlm_seed_request_failed
    "SEED_REFUSED" -> R.string.vlm_seed_refused
    "SEED_OUTPUT" -> R.string.vlm_seed_output_invalid
    "SEED_NETWORK", "SEED_TIMEOUT" -> R.string.vlm_seed_network_failed
    else -> null
}
