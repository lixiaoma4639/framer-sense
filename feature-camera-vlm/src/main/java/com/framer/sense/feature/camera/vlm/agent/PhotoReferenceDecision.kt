package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.avatar.PhotoActionCatalog
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionResolver
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class PhotoReferenceIntent(
    val id: String, val title: String, val shot: ShotType, val zone: CompositionZone,
    val actionId: String, val expression: ExpressionId, val expressionIntensity: Float,
    val yaw: Float, val reason: String, val backgroundKeepRegions: List<CropRect>,
    val uncertainties: List<String>
)
@Serializable data class PhotoReferenceDecision(val version: Int = 2, val imageId: String,
    val action: ActionKind = ActionKind.FINAL, val intents: List<PhotoReferenceIntent>)

/** 可执行摄影参考初稿目录，绝不把尚未验收的资源登记为专业动作。 */
object PhotoReferenceDecisionPolicy {
    data class Combination(val actionId: String, val shot: ShotType, val zone: CompositionZone, val expression: ExpressionId)
    val combinations = listOf(
        Combination(PhotoActionCatalog.SAMPLE_ID, ShotType.ENVIRONMENT, CompositionZone.LEFT, ExpressionId.SMILE),
        Combination(PhotoActionCatalog.SAMPLE_ID, ShotType.FULL, CompositionZone.RIGHT, ExpressionId.NEUTRAL),
        Combination(PhotoActionCatalog.SIDE_ID, ShotType.FULL, CompositionZone.LEFT, ExpressionId.SMILE),
        Combination(PhotoActionCatalog.SIDE_ID, ShotType.HALF, CompositionZone.RIGHT, ExpressionId.HAPPY),
        Combination(PhotoActionCatalog.LOOK_BACK_ID, ShotType.ENVIRONMENT, CompositionZone.RIGHT, ExpressionId.SMILE),
        Combination(PhotoActionCatalog.LOOK_BACK_ID, ShotType.HALF, CompositionZone.LEFT, ExpressionId.NEUTRAL)
    )
    /** JNI 新版本返回全部合法编号排序；旧单编号保留明确标识的 App 备选。 */
    fun ranking(raw: String): List<Int>? {
        val text = DirectorOutput.unwrap(raw).trim()
        if (!text.startsWith("RANK=")) return text.toIntOrNull()?.takeIf { it in 1..combinations.size }?.let(::listOf)
        val parts = text.removePrefix("RANK=").split(',')
        val order = parts.map { it.trim().toIntOrNull() ?: return null }
        return order.takeIf { it.size == combinations.size && it.toSet() == (1..combinations.size).toSet() }
    }
    private fun legacyOrder(primary: Int): List<Int> {
        val selected = combinations[primary-1]
        return listOf(primary)+combinations.indices.filter { it != primary-1 }.sortedByDescending { i ->
            val c=combinations[i]
            (if (c.shot != selected.shot) 4 else 0)+(if (c.zone != selected.zone) 2 else 0)+
                (if (c.actionId != selected.actionId) 1 else 0)
        }.map { it+1 }
    }
    fun enabled(input: DirectorInput) = input.photoReferences && input.avatarId == AvatarId.ADULT_FEMALE

    fun prompt(input: DirectorInput, offline: Boolean): String {
        val catalog = PhotoActionCatalog()
        val allowed = PhotoActionCatalog.previewModelActionIds.map { id ->
            val action = catalog.find(id)
            "${action.id}: ${VlmJson.encodeToString(action.guidance)}; resourceVersion=${action.version}; quality=DRAFT"
        }.joinToString("\n")
        val common = """
            图片是待拍摄的真实背景，人物可能尚未入镜。你是摄影构图导演，不要求背景中已有真人。
            按人物轮廓分离、留白、层次、引导线、地平线、背景主体保护和可见光线评估。
            不推断地面可站立性、真实人脸光照或遮挡；不可确认条件必须保持未知。
            用户要求=${VlmJson.encodeToString(input.instruction.take(1000))}
            背景保留区域=${VlmJson.encodeToString(input.backgroundRegions)}
            下列是可执行参考初稿，不是已验收专业素材；不能发明动作、表情通道或修改角色身份。
            $allowed
        """.trimIndent()
        if (offline) return common + "\n" + combinations.mapIndexed { index, c ->
            "${index + 1}: 景别=${c.shot},位置=${c.zone},动作=${c.actionId},表情=${c.expression}"
        }.joinToString("\n") + "\n只选择最适合当前背景和要求的一个组合，回答一个1到6的数字，不要说明。"
        return common + """

            只输出完整 JSON，顶层 version=2、imageId=${VlmJson.encodeToString(input.scene.id)}、action=FINAL、intents。
            intents 必须 ${if (input.selectedId == null) 3 else 1} 项，首项为最推荐构图，其他项是不同备选。
            每项字段 id,title,shot,zone,actionId,expression,expressionIntensity,yaw,reason,backgroundKeepRegions,uncertainties。
            shot 仅 ENVIRONMENT/FULL/HALF/CLOSE_UP，zone 仅 LEFT/CENTER/RIGHT。
            actionId 只能从以上目录选择。expression 仅 NEUTRAL/SMILE/HAPPY，强度0到1。
            yaw 是在动作基础上额外旋转，范围-35到35度，不是关节角度。
            reason/title 使用简体中文。reason 根据当前照片给出环境与摄影依据，自行决定内容长度、段落和表达，不限制条数、字数或每条换行，不套固定栏目或通用话术。
            reason 以人像摄影分析为重点：依据可见光线明暗解释曝光或背景干扰，依据色彩冷暖、饱和度及色彩对比解释人物分离和协调，依据空间层次、留白及引导线解释构图选择。物体描述只作为分析依据，不写成物体清单或风景介绍。
            文案使用正常中文标点和完整句子，分句使用逗号、冒号或分号，句末必须有句号；禁止用空格代替标点或串联关键词，输出前自行检查标点。
            不推测具体地点、天气、精确色温、曝光参数或未入镜人物的脸部光照。字符串内换行必须遵循 JSON 转义，不插入空白行或重复总结。
            backgroundKeepRegions 为归一化矩形 left/top/right/bottom。
            相机倍率能力=${VlmJson.encodeToString(input.camera)}；空间模式=${input.arMode}。空间模式固定后置原始倍率，仅允许画面裁切，不切镜头，不指定精确距离或世界坐标；禁止宣称已确认真实地面或真人姿势。
            修改仅输出 id=${input.selectedId ?: "p1/p2/p3"}，不修改其他已有方案。
        """.trimIndent()
    }

    fun decode(raw: String, input: DirectorInput, offline: Boolean): List<CompositionPlan> {
        val decision = if (offline) {
            val ranked = ranking(raw)
            require(ranked != null) { "PHOTO_COMBINATION_INVALID" }
            val order = if (ranked.size == 1) legacyOrder(ranked.first()) else ranked
            PhotoReferenceDecision(imageId = input.scene.id, intents = order.take(if (input.selectedId == null) 3 else 1)
                .mapIndexed { index, candidate -> combinations[candidate-1].let { c ->
                    PhotoReferenceIntent(input.selectedId ?: "p${index + 1}", "", c.shot, c.zone, c.actionId,
                        c.expression, 1f, 0f, "", input.backgroundRegions, emptyList())
                } })
        } else VlmJson.decodeFromString<PhotoReferenceDecision>(DirectorOutput.unwrap(raw))
        require(decision.version == 2 && decision.action == ActionKind.FINAL && decision.imageId == input.scene.id)
        require(decision.intents.size == if (input.selectedId == null) 3 else 1)
        require(decision.intents.map { it.id }.toSet() == (input.selectedId?.let { setOf(it) } ?: setOf("p1", "p2", "p3"))) { "PHOTO_PLAN_IDS_INVALID" }
        require(decision.intents.map { it.id }.distinct().size == decision.intents.size)
        val catalog = PhotoActionCatalog(); val resolver = PhotoActionResolver(catalog)
        val replacements = decision.intents.mapIndexed { index, reference ->
            require(PhotoActionCatalog.isPreviewModelAction(reference.actionId)) { "PHOTO_ACTION_UNAVAILABLE" }
            require(reference.expression in listOf(ExpressionId.NEUTRAL, ExpressionId.SMILE, ExpressionId.HAPPY))
            require(reference.expressionIntensity.isFinite() && reference.expressionIntensity in 0f..1f)
            require(reference.yaw.isFinite() && reference.yaw in -35f..35f)
            reference.backgroundKeepRegions.forEach { r -> require(listOf(r.left, r.top, r.right, r.bottom).all { it.isFinite() && it in 0f..1f } && r.right > r.left && r.bottom > r.top) }
            val action = catalog.find(reference.actionId)
            val intent = CompositionIntent(input.selectedId ?: "p${index + 1}", reference.title, reference.shot,
                reference.zone, action.legacyLabel, FacingDirection.FRONT, reference.expression, "", reference.reason,
                reference.expressionIntensity, reference.uncertainties, action.guidance)
            // 单项安全映射，完整姿势与表情再由执行目录解析，模型文字不能改写它们。
            val mapped = CompositionIntentMapper.toPlan(intent, input)
            val base = mapped.copy(id = input.selectedId ?: "p${index + 1}",
                revision = input.existing.firstOrNull { it.id == input.selectedId }?.revision?.plus(1) ?: 0, avatar = mapped.avatar.copy(
                ethnicity = input.ethnicity, yaw = reference.yaw), viewBinding = input.viewBinding,
                backgroundKeepRegions = (input.backgroundRegions + reference.backgroundKeepRegions).distinct(),
                localCombination = if (offline) combinations.indexOfFirst { it.actionId == reference.actionId && it.shot == reference.shot && it.zone == reference.zone && it.expression == reference.expression } + 1 else null,
                localAlternative = offline && index > 0,
                localRanked = offline && ranking(raw)?.size == combinations.size)
            // 保护区域被候选裁剪严重截断时保留完整背景，以景别的人物比例执行。
            val safe = if (base.backgroundKeepRegions.any { r ->
                val area = (r.right-r.left)*(r.bottom-r.top)
                val visible = (minOf(r.right,base.crop.right)-maxOf(r.left,base.crop.left)).coerceAtLeast(0f) * (minOf(r.bottom,base.crop.bottom)-maxOf(r.top,base.crop.top)).coerceAtLeast(0f)
                visible < area * .5f
            }) base.copy(crop = CropRect(0f,0f,1f,1f), zoom = input.scene.currentZoom, needsRetake = false) else base
            resolver.resolve(safe, reference.actionId, ActionOrigin.PREVIEW_MODEL)
        }
        return input.selectedId?.let { selected ->
            require(input.existing.any { it.id == selected })
            input.existing.map { if (it.id == selected) replacements.single() else it }
        } ?: replacements
    }
}
