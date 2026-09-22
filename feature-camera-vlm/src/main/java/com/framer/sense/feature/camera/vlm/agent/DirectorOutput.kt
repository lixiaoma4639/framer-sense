package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.DirectorDecision
import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 只剥离完整的展示包装；字段、枚举、截断输出仍由严格协议校验拒绝。 */
object DirectorOutput {
    fun decode(raw: String): DirectorDecision {
        return VlmJson.decodeFromString<DirectorDecision>(unwrap(raw))
    }

    /** 向下一轮提供结构化修正点，不自动填字段、补方案或将单方案误当作最终结果。 */
    fun repairErrors(raw: String, imageId: String): List<String> {
        val errors = mutableListOf("输出不是完整的构图意图 JSON，请修正字段并以 FINAL 返回三个 intents")
        val root = runCatching { VlmJson.parseToJsonElement(unwrap(raw)) }.getOrNull() as? JsonObject
        if (root == null) {
            errors += "必须返回一个完整 JSON 对象，检查代码块、引号、花括号和数组是否闭合"
            return errors
        }
        val singleIntent = "id" in root && "shot" in root && "zone" in root
        if (singleIntent) errors += "当前仅返回单个构图意图；它必须放入顶层 intents 数组，并生成另外两个不同意图"
        val missing = listOf("version", "imageId", "action").filterNot { it in root }
        if (missing.isNotEmpty()) errors += "顶层缺少字段：${missing.joinToString()}；version=1，imageId=$imageId，action=FINAL"
        val intents = root["intents"] as? JsonArray
        if ("intents" !in root) errors += "顶层缺少 intents 数组；完整旧协议可以使用 plans，但新协议请使用 intents"
        if ("intents" in root && intents == null) errors += "intents 必须是数组"
        if (intents != null && intents.size != 3) errors += "intents 当前有 ${intents.size} 项，必须恰好三项"
        val candidates = if (singleIntent) listOf(root) else intents?.toList().orEmpty()
        candidates.forEachIndexed { index, element ->
            val plan = element as? JsonObject
            if (plan == null) {
                errors += "intents[$index] 必须是完整构图意图对象"
                return@forEachIndexed
            }
            val path = if (singleIntent) "当前构图意图" else "intents[$index]"
            if ("uncertainty" in plan) errors += "$path：将 uncertainty 改为 uncertainties，保留数组内容"
            if ("uncertainties" !in plan && "uncertainty" !in plan) errors += "$path 缺少 uncertainties 数组，无不确定项时写 []"
            listOf("id", "title", "shot", "zone", "pose", "facing", "expression", "expressionIntensity", "guidance", "reason").filterNot { it in plan }
                .takeIf { it.isNotEmpty() }?.let { errors += "$path 缺少字段：${it.joinToString()}" }
        }
        return errors
    }

    internal fun unwrap(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.startsWith("<think>")) {
            val end = text.indexOf("</think>")
            require(end >= 0) { "Unclosed think block" }
            text = text.substring(end + "</think>".length).trim()
        }
        if (text.startsWith("```")) {
            val match = Regex("^```(?:json)?\\s*([\\s\\S]*?)`{3,}$", RegexOption.IGNORE_CASE).matchEntire(text)
            require(match != null) { "Unclosed or unsupported code fence" }
            text = match.groupValues[1].trim()
        }
        return text
    }
}
