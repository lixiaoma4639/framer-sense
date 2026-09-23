package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int

/**
 * 本地模型优先给出极短场景观察；应用再沿用同一安全映射器生成方案。
 * 它仍能兼容旧的逐行意图和数组协议，但不要求低算力模型稳定生成复杂格式。
 */
object OfflineDirectorProtocol {
    fun prompt(input: DirectorInput): String = """
        只看图片。用一句简体中文、50字以内，依次说明可见环境、人物远中近、推荐景别和位置、身体姿态、手臂动作、头部方向、表情。
        禁止列表、标题、Markdown、英文、坐标、姿势编号和解释；回答一句后立刻结束。
        拍摄要求：${input.instruction.take(120)}
    """.trimIndent()

    /** 新主链路：读取模型摄影意图，随后由映射器生成安全方案。 */
    fun decodeIntent(raw: String, input: DirectorInput): CompositionIntentDecision {
        val text = restoreMissingOuterArray(DirectorOutput.unwrap(raw)).trim()
        require(text.isNotBlank()) { "Empty offline output" }
        if (text.startsWith("{")) {
            return runCatching { VlmJson.decodeFromString<CompositionIntentDecision>(text) }
                .getOrElse { CompositionIntentMapper.fromLegacy(DirectorOutput.decode(text)) }
        }
        if (text.startsWith("[")) return CompositionIntentMapper.fromLegacy(decodeLegacyArray(text, input))
        val intents = decodeLines(text)
        return if (intents.size == 3) {
            CompositionIntentDecision(ActionKind.FINAL, intents, imageId = input.scene.id)
        } else {
            CompositionIntentMapper.sceneFallback(visualDescription(text), input)
        }
    }

    /** 保留旧调用点的返回类型，避免外围旧集成直接依赖本地协议细节。 */
    fun decode(raw: String, input: DirectorInput): DirectorDecision {
        val intent = decodeIntent(raw, input)
        val mapped = CompositionIntentMapper.map(intent, input).plans
        return DirectorDecision(intent.action, mapped, intent.version, intent.imageId)
    }

    private fun decodeLines(text: String): List<CompositionIntent> = text.lineSequence()
        .map(String::trim)
        .map { it.replaceFirst(Regex("^[•*-]?\\s*\\d*[.、)]?\\s*"), "") }
        .mapNotNull { line ->
            val fields = line.split('|').map(String::trim)
            if (fields.size != 8) return@mapNotNull null
            val shot = enumValue<ShotType>(fields[0]) ?: return@mapNotNull null
            val zone = enumValue<CompositionZone>(fields[1]) ?: return@mapNotNull null
            val pose = enumValue<PoseId>(fields[2]) ?: return@mapNotNull null
            val facing = enumValue<FacingDirection>(fields[3]) ?: return@mapNotNull null
            val expression = enumValue<ExpressionId>(fields[4]) ?: return@mapNotNull null
            if (fields.drop(5).any(String::isBlank)) return@mapNotNull null
            CompositionIntent(
                id = "p${System.identityHashCode(line)}",
                title = fields[5].take(28), shot = shot, zone = zone, pose = pose,
                facing = facing, expression = expression,
                guidance = fields[6].take(160), reason = fields[7].take(160)
            )
        }.toList()

    private inline fun <reified T : Enum<T>> enumValue(value: String): T? = enumValues<T>()
        .firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }

    private fun visualDescription(raw: String): String {
        val description = raw.lineSequence().map(String::trim).filter(String::isNotBlank)
            // 部分损坏的三行协议不是场景描述，不能据它回退生成貌似有效的三张卡片。
            .filterNot { it.contains('|') || it.contains("景别|方位|姿势") || it.contains("只输出三行") }
            .joinToString(" ").trim()
        require(description.length >= 8) { "Offline model did not return visual analysis" }
        return description.take(480)
    }

    /** 兼容已经安装旧离线模型或缓存提示词时产生的数组完整方案。 */
    private fun decodeLegacyArray(text: String, input: DirectorInput): DirectorDecision {
        val rows = VlmJson.parseToJsonElement(text) as? JsonArray ?: throw IllegalArgumentException("Expected plan array")
        val selected = input.existing.find { it.id == input.selectedId }
        require(input.selectedId == null || selected != null) { "Unknown selected plan" }
        require(rows.size == if (selected == null) 3 else 1) { "Unexpected plan count" }
        val plans = rows.mapIndexed { index, item ->
            val row = item as? JsonArray ?: throw IllegalArgumentException("Expected plan row")
            require(row.size == 9) { "Expected nine fields" }
            val crop = row[1] as? JsonArray ?: throw IllegalArgumentException("Expected crop")
            val avatar = row[3] as? JsonArray ?: throw IllegalArgumentException("Expected avatar")
            require(crop.size == 4 && avatar.size == 7) { "Invalid geometry fields" }
            fun primitive(value: JsonElement): JsonPrimitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected primitive")
            fun number(value: JsonElement): Float = primitive(value).let { p ->
                require(!p.isString && p.float.isFinite()) { "Expected finite number" }; p.float
            }
            fun enumIndex(value: JsonElement, size: Int): Int = primitive(value).int.also { require(it in 0 until size) { "Unknown enum" } }
            fun string(value: JsonElement): String = primitive(value).let { require(it.isString) { "Expected text" }; it.content }
            CompositionPlan(
                id = selected?.id ?: "p${index + 1}", title = string(row[4]),
                shot = ShotType.entries[enumIndex(row[0], ShotType.entries.size)],
                crop = CropRect(number(crop[0]), number(crop[1]), number(crop[2]), number(crop[3])), zoom = number(row[2]),
                avatar = AvatarPlacement(
                    foot = Point2(number(avatar[0]), number(avatar[1])), height = number(avatar[2]), yaw = number(avatar[3]),
                    avatarId = input.avatarId, pose = PoseId.entries[enumIndex(avatar[4], PoseId.entries.size)],
                    expression = ExpressionId.entries[enumIndex(avatar[5], ExpressionId.entries.size)], expressionIntensity = number(avatar[6])
                ),
                guidance = string(row[5]), reason = string(row[6]),
                needsRetake = requireNotNull(primitive(row[7]).booleanOrNull),
                uncertainties = (row[8] as? JsonArray ?: throw IllegalArgumentException("Expected uncertainties")).map(::string),
                revision = selected?.revision ?: 0
            )
        }
        return DirectorDecision(ActionKind.FINAL, plans, imageId = input.scene.id)
    }

    /** 只补 MNN 已知会遗漏的最外层数组符号，绝不补造模型字段。 */
    private fun restoreMissingOuterArray(text: String): String {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("[") && trimmed.drop(1).trimStart().firstOrNull()?.isDigit() == true && trimmed.endsWith("]]")) "[$trimmed" else text
    }
}
