package com.framer.sense.feature.camera.vlm.data

import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

/** 千问兼容接口：图生图也使用 images/generations，不携带 Seedream 图层参数。 */
internal object QwenImageProtocol {
    data class OutputSize(val width: Int, val height: Int)

    /** 输入原片与生成画布是不同限制；长边最多 2048，等比缩小，不裁切原环境。 */
    fun outputSize(width: Int, height: Int): OutputSize {
        if (width <= 0 || height <= 0 || width.toDouble() / height !in .125..8.0)
            throw GeneratedReferenceFailure("INPUT")
        val scale = minOf(1.0, 2048.0 / maxOf(width, height))
        val size = OutputSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        if (size.width.toLong() * size.height !in 512L * 512..2048L * 2048 ||
            size.width.toDouble() / size.height !in .125..8.0) throw GeneratedReferenceFailure("INPUT")
        return size
    }

    fun endpoint(settings: ModelSettings): HttpUrl {
        if (settings.preferred != ProviderId.QWEN) throw GeneratedReferenceFailure("UNSUPPORTED")
        try {
            QwenOfficialProtocol.validate(settings)
        } catch (failure: ModelFailure) {
            throw GeneratedReferenceFailure(if (failure.code == "QWEN_KEY") "KEY" else "QWEN_CONFIG", failure)
        }
        return (settings.qwenBaseUrl.trim().trimEnd('/') + CloudModelConfig.qwenImageGenerationPath)
            .toHttpUrlOrNull() ?: throw GeneratedReferenceFailure("QWEN_CONFIG")
    }

    fun payload(model: QwenImageModel, image: String, prompt: String, width: Int, height: Int): JsonObject {
        val size = outputSize(width, height)
        return buildJsonObject {
            put("model", model.apiId)
            put("prompt", prompt)
            put("image", image)
            put("size", "${size.width}x${size.height}")
            put("n", 1)
            // 已有完整输出约束，避免服务端默认改写；不代表能强制生成 Alpha。
            put("prompt_extend", false)
        }
    }

    fun response(status: Int, body: String): String {
        val root = runCatching { VlmJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (root?.get("code") as? JsonPrimitive)?.contentOrNull
        if (status !in 200..299 || error != null || !code.isNullOrBlank()) throw GeneratedReferenceFailure(when {
            status == 401 || code in setOf("InvalidApiKey", "invalid_api_key") -> "AUTH"
            status == 429 -> "LIMIT"
            code?.startsWith("DataInspectionFailed") == true -> "IMAGE_REJECTED"
            status == 403 || code in setOf("Arrearage", "AllocationQuota.FreeTierOnly") -> "QWEN_ACCOUNT"
            status == 400 || status == 404 -> "QWEN_CONFIG"
            else -> "SERVICE"
        })
        val items = root?.get("data") as? JsonArray ?: throw GeneratedReferenceFailure("OUTPUT")
        if (items.size != 1) throw GeneratedReferenceFailure("OUTPUT")
        val url = ((items.single() as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: throw GeneratedReferenceFailure("OUTPUT")
        if (!trustedAsset(url)) throw GeneratedReferenceFailure("DOWNLOAD")
        return url
    }

    fun trustedAsset(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            Regex(".+\\.oss-[a-z0-9-]+\\.aliyuncs\\.com").matches(url.host)
    }
}
