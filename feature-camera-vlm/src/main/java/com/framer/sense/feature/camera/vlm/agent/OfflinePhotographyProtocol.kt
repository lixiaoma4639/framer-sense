package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.avatar.PhotoActionCatalog
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 离线只传输模型需要决定的数据；扩展后仍经过完整 v3 来源、资源和几何校验。 */
object OfflinePhotographyProtocol {
    private val shots = listOf(ShotType.ENVIRONMENT, ShotType.FULL, ShotType.HALF, ShotType.CLOSE_UP)
    private val expressions = listOf(ExpressionId.NEUTRAL, ExpressionId.SMILE, ExpressionId.HAPPY)

    @Serializable private data class Decision(val s: String, val p: List<Plan>)
    @Serializable private data class Plan(
        val t: String, val q: Int, val c: List<Float>, val b: List<Float>,
        val a: Int, val e: List<Float>, val r: String, val g: String,
        val y: Float = 0f, val k: List<List<Float>> = emptyList(),
        val z: List<PhotographyReference> = emptyList(), val u: List<String> = emptyList()
    )

    fun prompt(input: DirectorInput, feedback: List<ToolObservation>): String {
        val actions = PhotographyProtocol.actionIds(input).mapIndexed { i, id ->
            val action = PhotoActionCatalog().find(id)
            val allowed = input.photographyActions?.firstOrNull { it.id == id }?.expressions ?: expressions
            "${i + 1}=${action.legacyLabel};表情=${allowed.map { expressions.indexOf(it) }.filter { it >= 0 }}"
        }.joinToString("，")
        val ids = PhotographyProtocol.actionIds(input)
        require(ids.isNotEmpty()) { "PHOTO_ACTION_UNAVAILABLE" }
        val masks = ids.map { id ->
            val allowed = input.photographyActions?.firstOrNull { it.id == id }?.expressions ?: expressions
            allowed.fold(0) { mask, e -> mask or (1 shl expressions.indexOf(e).also { require(it >= 0) }) }
        }
        val count = if (input.selectedId == null) 3 else 1
        val keep = input.backgroundRegions.joinToString(" ") { "${it.left} ${it.top} ${it.right} ${it.bottom}" }
        // 首行供原生约束器读取，随后移除；不是来自模型的内容，也不占视觉提示词 token。
        val constraint = "PHOTO_ROWS_V1 $count ${masks.size} ${masks.joinToString(" ")} ${input.backgroundRegions.size} $keep"
        return constraint + "\n" + """
            根据照片设计${count}套独立人像构图。环境美感第一，人物与背景协调第二。组织主体、层次、留白、明暗；人物未入镜正常，避开杂物、头部碰线。身高${input.subjectHeightMeters}米，实际倍率${input.scene.currentZoom}。要求=${VlmJson.encodeToString(input.instruction)}。
            动作：$actions。表情0平静/1微笑/2开朗。只选支持的编号。
            只输出JSON：{"s":"本图摄影分析。","p":[["标题",景别,裁切左,裁切上,裁切边长,人物中心x,头顶y,脚底y,人物宽,动作编号,表情编号,表情强度,朝向,"构图与裁切理由。","摄影操作。"],...]}。p必须${count}行，每行15项；不写字段名或额外对象。
            坐标、边长、强度用整数百分数(0..100)，不要小数。景别0环境/1全身/2半身/3特写；半身、特写的完整脚底允许超过100。裁切保持照片比例；越小裁切越大，必须解释裁去什么。原图背景保留区域=$keep。
            s简洁描述本图的光线、色彩和层次；标题简短；理由和操作各一句具体摄影建议，保留标点。三套方案的站位、大小和取景分别判断，禁止复制，不要编造地点、距离或人物光照。只输出完整JSON，不输出思考或代码块。
        """.trimIndent()
    }

    private fun expandRows(root: JsonObject): JsonObject {
        val rows = root["p"] as? JsonArray ?: return root
        if (rows.none { it is JsonArray }) return root // 旧对象协议仍兼容，但不补造缺失方案。
        require(root.keys == setOf("s", "p")) { "PHOTO_FIELDS_INVALID" }
        return buildJsonObject {
            put("s", root["s"] ?: throw IllegalArgumentException("PHOTO_SCENE_DESCRIPTION_MISSING"))
            putJsonArray("p") {
                rows.forEachIndexed { i, value ->
                    val row = value as? JsonArray ?: throw IllegalArgumentException("PHOTO_ROW_INVALID:$i")
                    require(row.size == 15) { "PHOTO_ROW_SIZE_INVALID:$i expected=15 actual=${row.size}" }
                    fun integer(index: Int): Int {
                        val v = row[index] as? JsonPrimitive
                        require(v != null && !v.isString && v.intOrNull != null) { "PHOTO_ROW_INTEGER_REQUIRED:$i:$index" }
                        return v.int
                    }
                    fun text(index: Int): JsonPrimitive {
                        val v = row[index] as? JsonPrimitive
                        require(v != null && v.isString) { "PHOTO_ROW_TEXT_REQUIRED:$i:$index" }
                        return v
                    }
                    val l = integer(2) / 100f; val t = integer(3) / 100f; val size = integer(4) / 100f
                    add(buildJsonObject {
                        put("t", text(0)); put("q", integer(1))
                        put("c", JsonArray(listOf(l,t,l+size,t+size).map { JsonPrimitive(it) }))
                        put("b", JsonArray((5..8).map { JsonPrimitive(integer(it)/100f) }))
                        put("a", integer(9)); put("e", JsonArray(listOf(JsonPrimitive(integer(10)), JsonPrimitive(integer(11)/100f))))
                        put("y", integer(12)); put("r", text(13)); put("g", text(14))
                    })
                }
            }
        }
    }

    /** 紧凑坐标只转换数据表示，不重估身高或改变模型裁切；旧完整对象保留兼容。 */
    fun expand(raw: String, input: DirectorInput, onDiagnostic: (String, String) -> Unit = { _, _ -> }): String {
        onDiagnostic("NORMALIZE_START", "chars=${raw.length}")
        val text = DirectorOutput.unwrapOfflineJson(raw)
        onDiagnostic("NORMALIZED_JSON", text)
        onDiagnostic("JSON_PARSE_START", "")
        val original = VlmJson.parseToJsonElement(text).jsonObject
        onDiagnostic("WIRE_FORMAT", "format=${if ((original["p"] as? JsonArray)?.firstOrNull() is JsonArray) "rows_v1" else "legacy_object"} submittedPlans=${(original["p"] as? JsonArray)?.size}")
        val root = expandRows(original)
        onDiagnostic("JSON_SHAPE", "keys=${root.keys} pType=${root["p"]?.javaClass?.simpleName} pCount=${(root["p"] as? JsonArray)?.size} pTypes=${(root["p"] as? JsonArray)?.map { it.javaClass.simpleName }}")
        if ("version" in root) return text
        onDiagnostic("FIELD_VALIDATION", "expectedPlans=${if(input.selectedId == null) 3 else 1} required=t,q,c,b,a,e,r,g")
        val plans = root["p"] as? JsonArray ?: throw IllegalArgumentException("PHOTO_PLAN_ARRAY_REQUIRED")
        require(plans.size == if (input.selectedId == null) 3 else 1) { "PHOTO_PLAN_IDS_INVALID:expected=${if (input.selectedId == null) 3 else 1} actual=${plans.size}" }
        plans.forEachIndexed { i, plan ->
            require(plan is JsonObject) { "PHOTO_PLAN_OBJECT_REQUIRED:p[$i] must contain layout/action/guidance, not an object name" }
            val missing = listOf("t", "q", "c", "b", "a", "e", "r", "g").filterNot { it in plan }
            require(missing.isEmpty()) { "PHOTO_PLAN_FIELDS_MISSING:p[$i]:${missing.joinToString()}" }
        }
        val d = VlmJson.decodeFromString<Decision>(root.toString())
        require(d.p.size == if (input.selectedId == null) 3 else 1) { "PHOTO_PLAN_IDS_INVALID" }
        require(!d.s.contains("填写本图") && d.p.none { p -> listOf(p.t, p.r, p.g).any { it.contains("填写本图") } }) {
            "PHOTO_EXAMPLE_NOT_REPLACED"
        }
        val actions = PhotographyProtocol.actionIds(input)
        val intents = d.p.mapIndexed { i, p ->
            onDiagnostic("PLAN_PARAMETERS", "index=${i+1} shot=${p.q} crop=${p.c} body=${p.b} actionIndex=${p.a} expression=${p.e} yaw=${p.y}")
            require(p.q in shots.indices && p.a in 1..actions.size) { "PHOTO_PARAMETERS_INVALID" }
            require(p.c.size == 4 && p.b.size == 4 && p.e.size == 2 &&
                (p.c + p.b + p.e).all { it.isFinite() }) { "PHOTO_COORDINATES_INVALID" }
            require(p.e[0] in 0f..2f && p.e[0] == p.e[0].toInt().toFloat()) { "PHOTO_EXPRESSION_UNAVAILABLE" }
            require(p.b[3] > 0f) { "PERSON_REGION_INVALID" }
            fun rect(v: List<Float>): CropRect {
                require(v.size == 4) { "PHOTO_REGION_INVALID" }
                return CropRect(v[0], v[1], v[2], v[3])
            }
            PhotographyIntent(input.selectedId ?: "p${i + 1}", p.t, shots[p.q], rect(p.c), p.r,
                Point2(p.b[0], p.b[1]), Point2(p.b[0], p.b[2]),
                CropRect(p.b[0] - p.b[3] / 2, p.b[1], p.b[0] + p.b[3] / 2, p.b[2]),
                actions[p.a - 1], expressions[p.e[0].toInt()], p.e[1], p.y, p.r, p.g,
                p.k.map(::rect), p.z, p.u)
        }
        return VlmJson.encodeToString(PhotographyDecision(3, input.scene.id, d.s, intents)).also { onDiagnostic("EXPANDED_V3", it) }
    }
}
