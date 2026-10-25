package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.framer.sense.feature.camera.vlm.agent.GeneratedReferencePrompt
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class GeneratedReferenceFailure(val code: String, cause: Throwable? = null) : IOException(code, cause)

/** BitmapFactory.Options 的默认采样值为 0，计算倍率时必须从 1 开始。 */
internal fun referenceImageSampleSize(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) throw GeneratedReferenceFailure("IMAGE")
    var sampleSize = 1
    val longestSide = maxOf(width, height).toLong()
    while ((longestSide + sampleSize - 1) / sampleSize > 2048) sampleSize *= 2
    return sampleSize
}

data class SeedreamLayer(val url: String, val z: Int, val name: String, val bounds: List<Int>?, val description: String = "",
                         val absoluteBounds: List<Int>? = null) {
    val requestedPerson: Boolean get() = name == "reference_person" || name.startsWith("reference_person_")
    // reference_person 是 APP 约定的标签，不能把标签自身当作图像内容的证据。
    private val contentMetadata: String get() = "${if (requestedPerson) "" else name} $description"
    val personHint: Boolean get() = personPattern.containsMatchIn(contentMetadata.replace(noPersonPattern, ""))
    val objectHint: Boolean get() = objectPattern.containsMatchIn(contentMetadata)

    private companion object {
        val noPersonPattern = Regex("(?:没有|无|不含|不包含)(?:任何)?(?:人像|人物|女子|男子|女孩|男孩|女性|男性|女士|男士)|\\b(?:no|without(?: a| any)?|not a)\\s+(?:persons?|people|humans?|women|woman|men|man|girls?|boys?)\\b", RegexOption.IGNORE_CASE)
        val personPattern = Regex("人像|人物|女子|男子|女孩|男孩|女性|男性|女士|男士|\\b(person|people|human|woman|women|man|men|girl|girls|boy|boys)\\b", RegexOption.IGNORE_CASE)
        val objectPattern = Regex("凳|椅|桌|家具|建筑|\\b(stool|bench|chair|table|furniture|building)\\b", RegexOption.IGNORE_CASE)
    }
}
data class SeedreamLayers(val model: String, val layers: List<SeedreamLayer>, val outputs: Int) {
    val foregroundLayers: List<SeedreamLayer> get() = layers.filter { it.z > 0 }.sortedBy { it.z }
    val personLayers: List<SeedreamLayer> get() {
        // 人物标签只是命名约定；即使标签匹配，也必须有独立的人物内容线索。
        val foregrounds = layers.filter { it.z > 0 && it.personHint }
        val named = foregrounds.filter { it.requestedPerson }
        if (named.isNotEmpty()) return named
        // 只接受元数据明确指向人物的唯一候选，绝不因为只有一个前景就认作人像。
        return foregrounds.filter { it.personHint }.singleOrNull()?.let { listOf(it) }.orEmpty()
    }
}

/** 只使用官方文档的图层模式，不伪造联合生图参数、不串行追加第二次收费调用。 */
object SeedreamImageProtocol {
    fun payload(model: String, image: String, prompt: String): JsonObject = buildJsonObject {
        put("model", model)
        put("image", image)
        put("prompt", prompt)
        put("layer_decomposition", true)
        put("size", "1.5K")
        put("output_format", "png")
        put("response_format", "url")
        put("watermark", true)
    }

    fun response(status: Int, body: String, requestedModel: String): SeedreamLayers {
        if (status == 400) {
            val error = runCatching { VlmJson.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
            if ((error?.get("code") as? JsonPrimitive)?.content == "InvalidParameter" &&
                (error["param"] as? JsonPrimitive)?.content == "image")
                throw GeneratedReferenceFailure("IMAGE_REJECTED")
        }
        if (status !in 200..299) throw GeneratedReferenceFailure(when (status) {
            401, 403 -> "AUTH"
            429 -> "LIMIT"
            else -> "SERVICE"
        })
        return try {
            val root = VlmJson.parseToJsonElement(body).jsonObject
            val items = root["data"]!!.jsonArray
            val layers = items.map { item ->
                val row = item.jsonObject
                val z = row["z_index"]?.jsonPrimitive?.intOrNull ?: throw GeneratedReferenceFailure("LAYERS_MISSING")
                if (z < 0) throw GeneratedReferenceFailure("LAYERS_MISSING")
                val box = row["bounding_box"] as? JsonObject
                val bounds = box?.get("normalized")?.jsonArray?.map { it.jsonPrimitive.int }
                val absolute = box?.get("absolute")?.jsonArray?.map { it.jsonPrimitive.int }
                if (z > 0 && !validSeedreamBounds(absolute) && !validSeedreamBounds(bounds, 1000))
                    throw GeneratedReferenceFailure("BOUNDS")
                SeedreamLayer(row["url"]!!.jsonPrimitive.content, z,
                    row["name"]?.jsonPrimitive?.content.orEmpty(), bounds,
                    row["description"]?.jsonPrimitive?.content.orEmpty(), absolute)
            }
            if (layers.size !in 1..17 || layers.count { it.z == 0 } != 1 || layers.map { it.z }.distinct().size != layers.size)
                throw GeneratedReferenceFailure("LAYERS_MISSING")
            val result = SeedreamLayers(root["model"]?.jsonPrimitive?.content ?: requestedModel, layers.sortedBy { it.z },
                root["usage"]?.jsonObject?.get("generated_images")?.jsonPrimitive?.intOrNull ?: layers.size)
            result
        } catch (failure: GeneratedReferenceFailure) { throw failure }
        catch (failure: Exception) { throw GeneratedReferenceFailure("OUTPUT", failure) }
    }

    fun trustedAsset(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            listOf("volces.com", "volcengine.com", "volccdn.com", "byteimg.com", "byteplus.com").any {
                parsed.host == it || parsed.host.endsWith(".$it")
            }
    }
}

fun interface GeneratedReferenceGenerator {
    suspend fun generate(candidate: GeneratedCandidate, settings: ModelSettings): GeneratedReferenceAssets
}

class GeneratedReferenceRepository(context: Context) : GeneratedReferenceGenerator {
    private val root = File(context.filesDir, "generated-references").apply { mkdirs() }
    private val renderMutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    // 资源下载与生图独立连接池，避免沿用长时间空闲的资源连接；仍保留默认 TLS 校验。
    private val assetClient = client.newBuilder().connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    override suspend fun generate(candidate: GeneratedCandidate, settings: ModelSettings): GeneratedReferenceAssets = withContext(Dispatchers.IO) {
        val trace = GeneratedReferenceTrace(candidate)
        trace.event("BEGIN", "provider=${settings.preferred} access=${settings.seedAccess} keyConfigured=${settings.seedApiKey.isNotBlank()} gatewayTokenConfigured=${settings.gatewayToken.isNotBlank()}")
        try {
            if (!settings.supportsImageGeneration) throw GeneratedReferenceFailure("UNSUPPORTED")
            val folder = File(root, candidate.id).apply { mkdirs() }
            val manifest = File(folder, "assets.json")
            if (manifest.isFile) {
                val cached = VlmJson.decodeFromString<GeneratedReferenceAssets>(manifest.readText())
                if (File(cached.compositePath).isFile && (!cached.hasPortrait || File(cached.portraitPath).isFile)) {
                    trace.event("CACHE_HIT", "width=${cached.width} height=${cached.height}")
                    return@withContext cached
                }
            }
            if (settings.mode == ModelMode.OFFLINE) throw GeneratedReferenceFailure("OFFLINE")
            val input = File(candidate.scene.imagePath)
            trace.event("INPUT", "exists=${input.isFile} bytes=${input.length()} width=${candidate.scene.width} height=${candidate.scene.height}")
            if (!input.isFile || input.length() !in 1..(30L * 1024 * 1024) ||
                candidate.scene.width.toLong() * candidate.scene.height < 512L * 512) throw GeneratedReferenceFailure("INPUT")
            val inputBytes = input.readBytes()
            val inputInfo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, inputInfo)
            trace.event("INPUT_DATA", "bytes=${inputBytes.size} fingerprint=${GeneratedReferenceDiagnostics.imageFingerprint(inputBytes)} actualWidth=${inputInfo.outWidth} actualHeight=${inputInfo.outHeight} mime=${GeneratedReferenceDiagnostics.identifier(inputInfo.outMimeType?.replace('/', '_'))}")
            val encoded = Base64.encodeToString(inputBytes, Base64.NO_WRAP)
            val prompt = GeneratedReferencePrompt.build(candidate.instruction, candidate.variation)
            trace.event("PARAMETERS", "layer_decomposition=true size=1.5K output_format=png response_format=url promptChars=${prompt.length}")
            val response = if (settings.seedAccess == SeedAccess.OFFICIAL) {
                if (settings.seedApiKey.isBlank()) throw GeneratedReferenceFailure("KEY")
                val url = CloudModelConfig.imageBaseUrl.trimEnd('/') + CloudModelConfig.imageGenerationPath
                val request = Request.Builder().url(url).header("Authorization", "Bearer ${settings.seedApiKey.trim()}")
                    .post(SeedreamImageProtocol.payload(candidate.model.apiId, "data:image/jpeg;base64,$encoded", prompt)
                        .toString().toRequestBody("application/json".toMediaType())).build()
                execute(request, trace, "GENERATE", privateValues = listOf(settings.seedApiKey.trim(), prompt, candidate.instruction))
                    .let { (status, bytes) -> SeedreamImageProtocol.response(status, bytes.toString(Charsets.UTF_8), candidate.model.apiId) }
            } else gateway(candidate, settings, encoded, prompt, trace)
            trace.event("LAYERS_PARSED", "actualModel=${GeneratedReferenceDiagnostics.identifier(response.model)} layers=${response.layers.size} personLayers=${response.personLayers.size} outputs=${response.outputs}")
            // 保留串行位图合成，避免后续调用方式调整时多份大画布同时占用内存。
            val result = renderMutex.withLock { render(response, candidate, settings, folder, trace) }
            trace.event("PERSIST")
            val pending = File(folder, "assets.pending")
            pending.writeText(VlmJson.encodeToString(result))
            check(pending.renameTo(manifest))
            trace.event("READY", "width=${result.width} height=${result.height} layers=${result.sourceLayerCount} hasPortrait=${result.hasPortrait} compositeBytes=${File(result.compositePath).length()} portraitBytes=${if (result.hasPortrait) File(result.portraitPath).length() else 0}")
            result
        } catch (cancel: CancellationException) { trace.event("CANCELLED"); throw cancel }
        catch (failure: Exception) { trace.failed("FAILED", failure); throw failure }
    }

    private suspend fun gateway(candidate: GeneratedCandidate, settings: ModelSettings, image: String, prompt: String, trace: GeneratedReferenceTrace): SeedreamLayers {
        val base = settings.gateway.trimEnd('/').toHttpUrlOrNull() ?: throw GeneratedReferenceFailure("GATEWAY")
        if (base.scheme != "https" && !(com.framer.sense.feature.camera.vlm.BuildConfig.DEBUG && base.scheme == "http"))
            throw GeneratedReferenceFailure("GATEWAY")
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null)
            throw GeneratedReferenceFailure("GATEWAY")
        if (settings.gatewayToken.isBlank()) throw GeneratedReferenceFailure("GATEWAY")
        val payload = buildJsonObject {
            put("requestId", candidate.id); put("model", candidate.model.apiId)
            put("imageBase64", image); put("prompt", prompt)
        }
        val submitUrl = base.toString().trimEnd('/') + CloudModelConfig.portraitGatewayPath
        val headers = "Bearer ${settings.gatewayToken}"
        val submitted = execute(Request.Builder().url(submitUrl).header("Authorization", headers)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build(), trace, "GATEWAY_SUBMIT")
        if (submitted.first !in 200..299) throw GeneratedReferenceFailure("GATEWAY")
        // 提交只进行一次；轮询不会再次向供应商提交收费请求。
        repeat(180) {
            val (status, bytes) = execute(Request.Builder().url("$submitUrl/${candidate.id}").header("Authorization", headers).build(), trace, "GATEWAY_POLL")
            if (status !in 200..299) throw GeneratedReferenceFailure("GATEWAY")
            val result = VlmJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            trace.event("GATEWAY_STATUS", "status=${GeneratedReferenceDiagnostics.identifier(result["status"]?.jsonPrimitive?.content)} poll=${it + 1}")
            when (result["status"]?.jsonPrimitive?.content) {
                "READY" -> return SeedreamImageProtocol.response(200, result["result"].toString(), candidate.model.apiId)
                "FAILED", "INTERRUPTED" -> throw GeneratedReferenceFailure("SERVICE")
            }
            delay(1500)
        }
        throw GeneratedReferenceFailure("TIMEOUT")
    }

    private suspend fun render(response: SeedreamLayers, candidate: GeneratedCandidate, settings: ModelSettings, folder: File, trace: GeneratedReferenceTrace): GeneratedReferenceAssets {
        trace.event("RENDER")
        val baseLayer = response.layers.single { it.z == 0 }
        val baseImage = download(baseLayer.url, settings, trace, "BASE")
        val base = baseImage.bitmap
        try {
            val aspectMatches = abs(base.width.toDouble() / base.height / (candidate.scene.width.toDouble() / candidate.scene.height) - 1) <= .015
            val personLayers = response.personLayers.toSet()
            SeedreamLayerCompositor(base, baseImage.sourceWidth, baseImage.sourceHeight, personLayers).use { compositor ->
                // 全部前景按官方层级叠放；是否识别到人物不能影响完整合成图。
                for (layer in response.foregroundLayers) {
                    val image = download(layer.url, settings, trace, "LAYER_${layer.z}").bitmap
                    try {
                        val position = compositor.draw(layer, image)
                        trace.event("LAYER_COMPOSITED", "z=${layer.z} coordinates=${if (validSeedreamBounds(layer.absoluteBounds)) "absolute" else "normalized"} left=${position.left} top=${position.top} right=${position.right} bottom=${position.bottom} personSelected=${layer in personLayers}")
                    } finally { image.recycle() }
                }
                val hasPortrait = aspectMatches && compositor.hasPortrait()
                trace.event("COMPOSITE_ALL_LAYERS", "layers=${response.layers.size} foregrounds=${response.foregroundLayers.size} selectedPersonLayers=${personLayers.size} hasPortrait=$hasPortrait aspectMatches=$aspectMatches")
                val compositeFile = File(folder, "composition.png")
                compositeFile.outputStream().use { check(compositor.composite.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val portraitPath = if (hasPortrait) {
                    File(folder, "portrait.png").also { file ->
                        file.outputStream().use { check(compositor.portrait.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    }.path
                } else ""
                return GeneratedReferenceAssets(compositeFile.path, portraitPath, base.width, base.height,
                    response.model, response.outputs, sourceLayerCount = response.layers.size)
            }
        } finally { base.recycle() }
    }

    private data class DownloadedImage(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

    private suspend fun download(url: String, settings: ModelSettings, trace: GeneratedReferenceTrace, layer: String): DownloadedImage {
        val request = if (url.startsWith(CloudModelConfig.portraitGatewayPath + "/") &&
            Regex(Regex.escape(CloudModelConfig.portraitGatewayPath) + "/[a-zA-Z0-9-]+/assets/[0-9]+").matches(url) &&
            settings.seedAccess == SeedAccess.GATEWAY) {
            val base = settings.gateway.trimEnd('/').toHttpUrlOrNull() ?: throw GeneratedReferenceFailure("ASSET_URL")
            val target = (base.toString().trimEnd('/') + url).toHttpUrlOrNull() ?: throw GeneratedReferenceFailure("ASSET_URL")
            Request.Builder().url(target).header("Authorization", "Bearer ${settings.gatewayToken}").build()
        } else {
            if (!SeedreamImageProtocol.trustedAsset(url)) throw GeneratedReferenceFailure("ASSET_URL")
            Request.Builder().url(url).build()
        }
        val (status, bytes) = retryGeneratedAssetDownload(request.method, onRetry = { failure ->
            trace.failed("DOWNLOAD_${layer}_RETRY_REASON", failure)
            trace.event("DOWNLOAD_${layer}_RETRY", "attempt=2 maxAttempts=2 freshConnection=true delayMs=300")
        }) { freshConnection ->
            val downloader = if (freshConnection) assetClient.newBuilder().connectionPool(ConnectionPool()).build() else assetClient
            try {
                execute(request, trace, "DOWNLOAD_$layer", 32 * 1024 * 1024, httpClient = downloader)
            } finally {
                if (freshConnection) downloader.connectionPool.evictAll()
            }
        }
        if (status !in 200..299) throw GeneratedReferenceFailure("DOWNLOAD")
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
        trace.event("IMAGE_INFO", "layer=$layer bytes=${bytes.size} width=${info.outWidth} height=${info.outHeight} mime=${GeneratedReferenceDiagnostics.identifier(info.outMimeType?.replace('/', '_'))}")
        if (info.outWidth <= 0 || info.outHeight <= 0 || info.outWidth.toLong() * info.outHeight > 18_000_000L)
            throw GeneratedReferenceFailure("IMAGE")
        val options = BitmapFactory.Options().apply {
            inSampleSize = referenceImageSampleSize(info.outWidth, info.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        trace.event("IMAGE_DECODE", "layer=$layer sampleSize=${options.inSampleSize}")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw GeneratedReferenceFailure("IMAGE")
        trace.event("IMAGE_DECODED", "layer=$layer width=${bitmap.width} height=${bitmap.height} alpha=${bitmap.hasAlpha()}")
        return DownloadedImage(bitmap, info.outWidth, info.outHeight)
    }

    private suspend fun execute(request: Request, trace: GeneratedReferenceTrace, stage: String, limit: Int = 2 * 1024 * 1024,
                                privateValues: List<String> = emptyList(), httpClient: OkHttpClient = client): Pair<Int, ByteArray> =
        suspendCancellableCoroutine { continuation ->
            trace.event("${stage}_REQUEST", "method=${request.method} host=${request.url.host}")
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) { trace.event("${stage}_CANCELLED"); return }
                    trace.failed("${stage}_NETWORK", e)
                    if (continuation.isActive) continuation.resumeWithException(GeneratedReferenceFailure("NETWORK", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        trace.event("${stage}_HTTP", "status=${response.code} requestId=${GeneratedReferenceDiagnostics.identifier(response.header("x-request-id") ?: response.header("x-tt-logid"))} contentLength=${response.body?.contentLength()} contentType=${GeneratedReferenceDiagnostics.identifier(response.header("Content-Type")?.substringBefore(';')?.replace('/', '_'))}")
                        try {
                            val body = response.body ?: throw GeneratedReferenceFailure("OUTPUT")
                            if (body.contentLength() > limit) throw GeneratedReferenceFailure("OUTPUT")
                            val bytes = body.byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > limit) throw GeneratedReferenceFailure("OUTPUT")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            if (!stage.startsWith("DOWNLOAD_") || !response.isSuccessful)
                                trace.event("${stage}_RESPONSE", "bytes=${bytes.size} ${GeneratedReferenceDiagnostics.response(bytes.toString(Charsets.UTF_8))}")
                            if (com.framer.sense.feature.camera.vlm.BuildConfig.DEBUG && stage == "GENERATE" && !response.isSuccessful)
                                trace.event("${stage}_ERROR_DETAIL", "message=${GeneratedReferenceDiagnostics.errorMessage(bytes.toString(Charsets.UTF_8), privateValues)}")
                            if (continuation.isActive) continuation.resume(response.code to bytes)
                        } catch (failure: Exception) {
                            trace.failed("${stage}_READ", failure)
                            if (continuation.isActive) continuation.resumeWithException(GeneratedReferenceFailure("OUTPUT", failure))
                        }
                    }
                }
            })
        }
}
