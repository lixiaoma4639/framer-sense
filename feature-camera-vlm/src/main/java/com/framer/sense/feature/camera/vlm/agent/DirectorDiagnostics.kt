package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.serialization.encodeToString

/** 仅 Debug 出口读取完整内容；每段重复任务标识，避免 Logcat 截断后失去上下文。 */
internal fun DirectorLogger.payload(context: String, label: String, content: String) {
    diagnostic {
        val encoded = VlmJson.encodeToString(content)
        // 分段由具体出口完成；前缀用于每一段定位同一个任务和处理阶段。
        "$context payload=$label chars=${content.length} raw=$encoded"
    }
}
