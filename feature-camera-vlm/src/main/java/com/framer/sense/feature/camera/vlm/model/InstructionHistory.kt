package com.framer.sense.feature.camera.vlm.model

/** 最近确认的用户文字在前，同文去重；直接采用推荐文字不形成历史。 */
internal object InstructionHistory {
    fun normalize(values: List<String>): List<String> = values.map { it.trim().take(2000) }.filter(String::isNotBlank).distinct()

    fun record(values: List<String>, text: String, fromRecommendation: Boolean): List<String> {
        val history = normalize(values)
        val value = text.trim().take(2000)
        return if (fromRecommendation || value.isBlank()) history else listOf(value) + history.filterNot { it == value }
    }
}
