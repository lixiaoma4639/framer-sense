package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.VlmJson

/** 仅修正 JSON 结构位置的中文标点，不改写正常字符串，不补字段或方案。 */
internal object OfflineJsonSyntax {
    fun normalize(raw: String): String {
        // 合法 JSON 原样保留，文案中的中文引号、标点不参与修复。
        if (runCatching { VlmJson.parseToJsonElement(raw) }.isSuccess) return raw
        return buildString {
            var quoted = false
            var smart = false
            var escaped = false
            var innerQuotes = 0
            raw.forEachIndexed { i, c ->
                if (quoted) {
                    when {
                        escaped -> { append(c); escaped = false }
                        c == '\\' -> { append(c); escaped = true }
                        smart && c == '”' -> { append('"'); quoted = false; smart = false }
                        !smart && c == '“' -> { append(c); innerQuotes++ }
                        !smart && c == '”' && innerQuotes > 0 -> { append(c); innerQuotes-- }
                        !smart && c == '”' && raw.substring(i + 1).firstOrNull { !it.isWhitespace() } in listOf(',', '，', '}', ']') -> {
                            append('"'); quoted = false
                        }
                        !smart && c == '"' -> { append(c); quoted = false }
                        smart && c == '"' -> append("\\\"")
                        else -> append(c)
                    }
                } else when (c) {
                    '"', '“' -> { append('"'); quoted = true; smart = c == '“'; innerQuotes = 0 }
                    '：' -> append(':')
                    '，' -> append(',')
                    // 句号出现在字符串外且紧接容器闭合时，仅是多余的展示标点。
                    '。' -> if (raw.substring(i + 1).firstOrNull { !it.isWhitespace() } !in listOf('}', ']')) append(c)
                    else -> append(c)
                }
            }
        }
    }
}
