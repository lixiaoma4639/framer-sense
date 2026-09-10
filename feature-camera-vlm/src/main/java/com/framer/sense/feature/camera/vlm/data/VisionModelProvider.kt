package com.framer.sense.feature.camera.vlm.data

import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.util.Base64
import com.framer.sense.feature.camera.vlm.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 统一图文模型接口；图片必须通过视觉通道输入。 */
interface VisionModelProvider {
    /** 执行一次决策。
     * @param call 图片、上下文与本轮提示词。
     * @return 实际模型和原始输出；不执行任何应用工具。
     */
    suspend fun step(call: ModelCall): StepReply
}

class ModelFailure(val code: String, message: String, val retryable: Boolean) : Exception(message) {
    var diagnostics: CompositionResult? = null
}

/** 使用客户端明确选择的供应商访问自有网关。 */
class GatewayProvider(
    private val settings: ModelSettings,
    private val provider: ProviderId,
    private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(70, TimeUnit.SECONDS).build()
) : VisionModelProvider {
    /** 发送一次真实图像请求。
     * @param call 指向设备私有图片的请求及导演提示词。
     * @return 网关解析后的供应商结果。
     */
    override suspend fun step(call: ModelCall): StepReply = withContext(Dispatchers.IO) {
        val bytes = File(call.input.scene.modelImagePath).readBytes()
        val body = GatewayRequest(provider, call.requestId, Base64.encodeToString(bytes, Base64.NO_WRAP), call.prompt, call.input.scene.id)
        val text = execute("/v1/director/step", VlmJson.encodeToString(body))
        try { VlmJson.decodeFromString<StepReply>(text) }
        catch (e: IllegalArgumentException) { throw ModelFailure("INVALID_OUTPUT", "网关响应不符合协议", true) }
    }

    /** 查询网关实际配置的模型。
     * @return 供应商列表；不返回密钥。
     */
    suspend fun capabilities(): GatewayCapabilities = VlmJson.decodeFromString(execute("/v1/capabilities", null))

    /** 发起可取消的 HTTP 请求。
     * @param path 固定业务接口路径。
     * @param body JSON 请求体；null 表示 GET。
     * @return 脱离网络资源生命周期的响应文本。
     */
    private suspend fun execute(path: String, body: String?): String {
        val base = settings.gateway.trimEnd('/')
        if (settings.gatewayToken.isBlank()) throw ModelFailure("UNAVAILABLE", "请先配置网关访问令牌", true)
        if (!base.startsWith("https://") && !(BuildConfig.DEBUG && base.startsWith("http://"))) throw ModelFailure("BAD_REQUEST", "正式版本必须使用 HTTPS 网关", false)
        val builder = Request.Builder().url(base + path).header("Authorization", "Bearer ${settings.gatewayToken}")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val pending = client.newCall(builder.build())
            continuation.invokeOnCancellation { pending.cancel() }
            pending.enqueue(object : Callback {
                /** 报告网络失败；call 为本次 HTTP 调用，e 为连接或超时原因。 */
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ModelFailure("UNAVAILABLE", "无法连接网关或请求超时", true))
                }
                /** 读取并释放响应；call 为 HTTP 调用，response 为必须关闭的网络资源。 */
                override fun onResponse(call: Call, response: Response) {
                    try { response.use {
                        if (!continuation.isActive) return
                        val text = response.body?.string().orEmpty()
                        if (!continuation.isActive) return
                        if (response.isSuccessful) continuation.resume(text)
                        else {
                            val retry = response.code == 429 || response.code >= 500
                            val message = when (response.code) {
                                401 -> "网关访问令牌无效"
                                403 -> "模型拒绝本次请求"
                                400, 422 -> "请求参数错误，请检查模型与协议"
                                else -> "网关或模型暂不可用（${response.code}）"
                            }
                            continuation.resumeWithException(ModelFailure("HTTP_${response.code}", message, retry))
                        }
                    } } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(ModelFailure("UNAVAILABLE", "网关响应读取中断", true))
                    }
                }
            })
        }
    }
}

/** 生成区域内的有限候选集合，不依赖设备语言猜测数据区域。 */
object ModelRouting {
    /** 获取候选顺序。
     * @param settings 用户明确设置的区域、模式和首选供应商。
     * @return 按优先级排列的模型标识，强制离线时只有 LOCAL。
     */
    fun candidates(settings: ModelSettings): List<ProviderId> {
        if (settings.mode == ModelMode.OFFLINE) return listOf(ProviderId.LOCAL)
        val regional = if (settings.region == Region.CN) listOf(ProviderId.QWEN, ProviderId.SEED) else listOf(ProviderId.GPT, ProviderId.GEMINI)
        val preferred = settings.preferred.takeIf { it in regional } ?: regional.first()
        return if (settings.mode == ModelMode.CLOUD) listOf(preferred) else listOf(preferred) + regional.filter { it != preferred } + ProviderId.LOCAL
    }
}
