package com.framer.sense.feature.camera.vlm.data

import android.os.SystemClock
import android.util.Log
import com.framer.sense.feature.camera.vlm.model.GeneratedCandidate
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 常规日志仅保留固定字段；Debug 错误说明须移除请求私密值、图片和资源链接。 */
internal object GeneratedReferenceDiagnostics {
    const val TAG = "VlmImageGen"
    fun identifier(value: String?): String = value?.takeIf {
        it.isNotBlank() && it.length <= 160 && !it.startsWith("sk-", true) &&
            it.all { char -> char.isLetterOrDigit() || char in "._:-" }
    } ?: "unknown"

    fun imageFingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun errorMessage(body: String, privateValues: List<String>): String {
        val message = runCatching {
            val error = Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.content
        }.getOrNull() ?: return "unavailable"
        var safe = message
        privateValues.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }.forEach {
            safe = safe.replace(it, "[redacted]", ignoreCase = true)
        }
        safe = safe.replace(Regex("data:image/[^\\s,;]+;base64,[A-Za-z0-9+/=_-]+", RegexOption.IGNORE_CASE), "[image]")
            .replace(Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE), "[url]")
            .replace(Regex("\\bBearer\\s+[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE), "[credential]")
            .replace(Regex("\\bsk-[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE), "[credential]")
            .replace(Regex("[A-Za-z0-9+/_=-]{32,}"), "[data]")
            .replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")
        return safe.take(600)
    }

    /** 仅输出固定关键词分类，分类只是排查线索，不代表已经确认根因。 */
    private fun reasonHints(message: String): String = listOf(
        "DECODE" to "\\b(decode|decoding|corrupt|malformed)\\b|解码|损坏",
        "FORMAT" to "\\b(format|mime|jpeg|png|webp)\\b|格式",
        "BASE64" to "\\bbase64\\b",
        "SIZE" to "\\b(size|bytes|mb|large|small)\\b|大小|过大|过小",
        "DIMENSIONS" to "\\b(width|height|resolution|pixels?)\\b|宽|高|像素|分辨率",
        "ASPECT" to "\\b(aspect|ratio)\\b|宽高比",
        "LAYERS" to "\\b(layers?|foreground|extract|extraction|split|decomposition)\\b|图层|前景|拆分|提取",
        "CONTENT" to "\\b(content|safety|policy|moderation)\\b|内容|审核",
        "FETCH" to "\\b(fetch|download|unreachable|timeout|timed)\\b|下载|超时",
        "EMPTY" to "\\b(empty|missing)\\b|为空|缺失",
        "PROCESSING" to "\\b(process|processing)\\b|处理",
        "UNSUPPORTED" to "\\b(unsupported|not supported)\\b|不支持"
    ).filter { (_, pattern) -> Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(message) }
        .joinToString(",") { it.first }.ifEmpty { "unknown" }

    fun response(body: String): String = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val error = root["error"] as? JsonObject
        val detail = root["detail"] as? JsonObject
        val failure = (root["result"] as? JsonObject)?.get("failure") as? JsonObject
        val data = root["data"] as? JsonArray
        val parameters = listOf("model", "image", "prompt", "size", "layer_decomposition", "output_format", "response_format", "watermark")
        val message = (error?.get("message") as? JsonPrimitive)?.content.orEmpty()
        val mentioned = parameters.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(message) }
        fun JsonObject?.field(key: String) = identifier((this?.get(key) as? JsonPrimitive)?.content)
        "json=true dataCount=${data?.size ?: -1} hasError=${error != null} " +
            "upstream=${(error ?: detail ?: failure).field("code")} type=${(error ?: failure).field("type")} " +
            "param=${error.field("param")} mentioned=${mentioned.joinToString(",").ifEmpty { "none" }} " +
            "reasonHints=${reasonHints(message)} " +
            "requestId=${identifier((root["request_id"] as? JsonPrimitive)?.content)} " +
            "gatewayStage=${failure.field("stage")} gatewayHttp=${failure.field("http_status")} " +
            "gatewayRequestId=${failure.field("request_id")} " +
            "layerFields=${data?.take(17)?.joinToString(";") { item ->
                val row = item as? JsonObject
                val evidence = SeedreamLayer("", 1, (row?.get("name") as? JsonPrimitive)?.content.orEmpty(), null,
                    (row?.get("description") as? JsonPrimitive)?.content.orEmpty())
                "z:${(row?.get("z_index") as? JsonPrimitive)?.intOrNull ?: -1}," +
                    "url:${row?.containsKey("url") == true},bounds:${row?.containsKey("bounding_box") == true}," +
                    "target:${evidence.requestedPerson},personHint:${evidence.personHint},objectHint:${evidence.objectHint}"
            }.orEmpty()}"
    }.getOrDefault("json=false")

    fun failure(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(5).map { identifier(it.javaClass.simpleName) }.joinToString("/")
        val frames = error.stackTrace.filter { it.className.startsWith("com.framer.sense.") }.take(5)
            .joinToString(";") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return "code=${identifier((error as? GeneratedReferenceFailure)?.code)} causes=$causes frames=$frames"
    }
}

internal class GeneratedReferenceTrace(candidate: GeneratedCandidate) {
    private val started = SystemClock.elapsedRealtime()
    private val identity = "candidate=${candidate.id} batch=${candidate.batchId} scene=${candidate.scene.id} model=${candidate.model.apiId}"
    fun event(stage: String, details: String = "") {
        Log.i(GeneratedReferenceDiagnostics.TAG, "$identity stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - started} $details")
    }
    fun failed(stage: String, error: Throwable) {
        Log.e(GeneratedReferenceDiagnostics.TAG, "$identity stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - started} ${GeneratedReferenceDiagnostics.failure(error)}")
    }
}
