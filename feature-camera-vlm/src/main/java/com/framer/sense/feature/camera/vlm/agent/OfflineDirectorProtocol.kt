package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 离线模型以三行中文提供画面分析；应用负责固定且已校验的人偶几何。 */
object OfflineDirectorProtocol {
    fun prompt(input: DirectorInput): String = compositionPrompt(input)

    private fun compositionPrompt(input: DirectorInput): String = buildString {
        // 导入的 Qwen3-VL 在 CPU 上对长格式约束会逐字复述提示词。先只取真实视觉分析，
        // 再由应用生成已经过几何校验的环境、全身、半身三个镜头，避免把格式遵循能力当作视觉能力。
        append("请看这张照片，用一句中文描述画面中可见的人物、环境，以及适合拍摄的人像构图。只回答描述，不要复述问题，不要列清单。")
    }.trim()

    fun decode(raw: String, input: DirectorInput): DirectorDecision {
        val text = restoreMissingOuterArray(DirectorOutput.unwrap(raw))
        // 已完整遵守旧协议的响应仍可使用，禁止对旧协议补造缺失字段或方案。
        if (text.startsWith("{")) return DirectorOutput.decode(text)
        if (!text.trimStart().startsWith("[")) return decodeNatural(text, input)
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
            fun number(value: JsonElement): Float {
                val p = primitive(value)
                require(!p.isString && p.float.isFinite()) { "Expected finite number" }
                return p.float
            }
            fun enumIndex(value: JsonElement, size: Int): Int {
                require(!primitive(value).isString) { "Expected numeric enum" }
                return primitive(value).int.also { require(it in 0 until size) { "Unknown enum" } }
            }
            fun text(value: JsonElement): String {
                require(primitive(value).isString) { "Expected text" }
                return primitive(value).content
            }
            require(!primitive(row[7]).isString) { "Expected boolean" }
            CompositionPlan(
                id = selected?.id ?: "p${index + 1}", title = text(row[4]),
                shot = ShotType.entries[enumIndex(row[0], ShotType.entries.size)],
                crop = CropRect(number(crop[0]), number(crop[1]), number(crop[2]), number(crop[3])), zoom = number(row[2]),
                avatar = AvatarPlacement(Point2(number(avatar[0]), number(avatar[1])), number(avatar[2]), number(avatar[3]),
                    PoseId.entries[enumIndex(avatar[4], PoseId.entries.size)], ExpressionId.entries[enumIndex(avatar[5], ExpressionId.entries.size)], number(avatar[6])),
                guidance = text(row[5]), reason = text(row[6]), needsRetake = requireNotNull(primitive(row[7]).booleanOrNull),
                uncertainties = (row[8] as? JsonArray ?: throw IllegalArgumentException("Expected uncertainties")).map { text(it) }, revision = selected?.revision ?: 0
            )
        }
        val safePlans = plans.map(::fitWholePersonInsideFrame)
        val merged = if (selected == null) safePlans else input.existing.map { if (it.id == selected.id) safePlans.single() else it }
        return DirectorDecision(ActionKind.FINAL, merged, imageId = input.scene.id)
    }

    private fun decodeNatural(text: String, input: DirectorInput): DirectorDecision {
        val expected = listOf("环境" to ShotType.ENVIRONMENT, "全身" to ShotType.FULL, "半身" to ShotType.HALF)
        val rows = text.lineSequence().map(String::trim)
            .map { it.replaceFirst(Regex("^[•*-]?\\s*\\d*[.、)]?\\s*"), "") }
            .filter { it.startsWith("环境：") || it.startsWith("环境:") || it.startsWith("全身：") || it.startsWith("全身:") || it.startsWith("半身：") || it.startsWith("半身:") }
            .toList()
        if (rows.isEmpty()) return decisionFromVisualDescription(text, input, expected)
        require(rows.size == 3) { "Expected three offline suggestions" }
        val plans = rows.zip(expected).mapIndexed { index, (row, expectedShot) ->
            val (label, shot) = expectedShot
            require(row.startsWith("$label：") || row.startsWith("$label:")) { "Unexpected offline suggestion order" }
            val content = row.substringAfter('：', row.substringAfter(':')).trim()
            val fields = content.split('｜', '|').map(String::trim).filter(String::isNotBlank)
            require(content.isNotBlank()) { "Expected offline suggestion content" }
            val title = if (fields.size >= 3) fields[0].take(18) else content.take(18)
            val guidance = if (fields.size >= 3) fields[1] else content
            val reason = if (fields.size >= 3) fields[2] else content
            val avatar = when (shot) {
                ShotType.ENVIRONMENT -> AvatarPlacement(Point2(.5f, .9f), .3f)
                ShotType.FULL -> AvatarPlacement(Point2(.5f, .92f), .65f, pose = PoseId.SIDE)
                ShotType.HALF -> AvatarPlacement(Point2(.5f, 1.4f), 1.25f, pose = PoseId.LOOK_BACK)
                else -> error("Unsupported offline shot")
            }
            CompositionPlan("p${index + 1}", title, shot, CropRect(), input.scene.currentZoom, avatar, guidance, reason)
        }
        return DirectorDecision(ActionKind.FINAL, plans, imageId = input.scene.id)
    }

    private fun decisionFromVisualDescription(
        raw: String,
        input: DirectorInput,
        expected: List<Pair<String, ShotType>>
    ): DirectorDecision {
        val description = raw.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.startsWith("景别：") || it.startsWith("景别:") }
            .joinToString(" ")
            .trim()
        // 复述协议不是画面分析，绝不拿它伪造建议。
        require(description.length >= 8 &&
            !description.contains("用户要求") &&
            !description.contains("只输出") &&
            !description.contains("每行格式") &&
            !description.contains("不要复述问题")) { "Expected visual description" }
        val plans = expected.mapIndexed { index, (_, shot) ->
            val avatar = when (shot) {
                ShotType.ENVIRONMENT -> AvatarPlacement(Point2(.5f, .9f), .3f)
                ShotType.FULL -> AvatarPlacement(Point2(.5f, .92f), .65f, pose = PoseId.SIDE)
                ShotType.HALF -> AvatarPlacement(Point2(.5f, 1.4f), 1.25f, pose = PoseId.LOOK_BACK)
                else -> error("Unsupported offline shot")
            }
            CompositionPlan(
                id = "p${index + 1}",
                title = description.take(18),
                shot = shot,
                crop = CropRect(),
                zoom = input.scene.currentZoom,
                avatar = avatar,
                guidance = description,
                reason = description
            )
        }
        return DirectorDecision(ActionKind.FINAL, plans, imageId = input.scene.id)
    }

    /**
     * 离线短协议常把半身示例的脚点复制给全身项。全身要求头脚可见，故只在该
     * 已知矛盾出现时收紧人偶盒至画面内；不更改模型给出的景别、文本、姿态或裁剪。
     */
    private fun fitWholePersonInsideFrame(plan: CompositionPlan): CompositionPlan {
        if (plan.shot !in setOf(ShotType.ENVIRONMENT, ShotType.FULL)) return plan
        val avatar = plan.avatar
        if (avatar.foot.y <= 1f && avatar.foot.y - avatar.height >= 0f) return plan
        val footY = avatar.foot.y.coerceIn(0.08f, 1f)
        return plan.copy(avatar = avatar.copy(foot = avatar.foot.copy(y = footY), height = avatar.height.coerceIn(0.08f, footY)))
    }

    /**
     * MNN 的视觉预填充路径会在少数设备上遗漏最外层数组的第一个 `[`，但其余 token
     * 已完整输出，例如 `[0,...],[1,...],[2,...]]`。只补回这个可证明缺失的结构字符，
     * 不补字段、不修改任何模型数值；随后仍由严格协议和几何校验决定是否可用。
     */
    private fun restoreMissingOuterArray(text: String): String {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("[") && trimmed.drop(1).trimStart().firstOrNull()?.isDigit() == true && trimmed.endsWith("]]")) "[$trimmed" else text
    }

    private fun encodePlan(plan: CompositionPlan): JsonArray = buildJsonArray {
        add(plan.shot.ordinal)
        add(buildJsonArray { with(plan.crop) { add(left); add(top); add(right); add(bottom) } })
        add(plan.zoom)
        add(buildJsonArray { with(plan.avatar) { add(foot.x); add(foot.y); add(height); add(yaw); add(pose.ordinal); add(expression.ordinal); add(expressionIntensity) } })
        add(plan.title); add(plan.guidance); add(plan.reason); add(plan.needsRetake)
        add(buildJsonArray { plan.uncertainties.forEach { add(it) } })
    }
}
