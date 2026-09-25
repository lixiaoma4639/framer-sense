package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.abs

/** 将模型可理解的摄影意图收敛为可校验、可渲染的方案参数。 */
object CompositionIntentMapper {
    const val SCENE_FALLBACK_MARKER = "OFFLINE_SCENE_FALLBACK"

    data class Result(val plans: List<CompositionPlan>, val messages: List<String>)

    fun map(decision: CompositionIntentDecision, input: DirectorInput): Result {
        require(decision.action == ActionKind.FINAL) { "Only FINAL intents can become plans" }
        require(decision.intents.size == 3) { "Expected three composition intents" }
        val messages = mutableListOf<String>()
        val intents = distinct(decision.intents, messages)
        val plans = intents.mapIndexed { index, intent -> toPlan(intent.copy(id = "p${index + 1}"), input) }
        val merged = input.selectedId?.let { selectedId ->
            val replacement = plans.firstOrNull { it.id == selectedId } ?: plans.first()
            input.existing.map { old -> if (old.id == selectedId) replacement.copy(id = old.id) else old }
        } ?: plans
        return Result(merged, messages)
    }

    /** 兼容网关仍返回旧完整方案的情形，后续同样经过安全映射。 */
    fun fromLegacy(decision: DirectorDecision): CompositionIntentDecision = CompositionIntentDecision(
        action = decision.action,
        version = decision.version,
        imageId = decision.imageId,
        intents = decision.plans.map { plan ->
            CompositionIntent(
                id = plan.id,
                title = plan.title,
                shot = plan.shot,
                zone = zoneFor(plan.avatar.foot.x),
                pose = plan.avatar.pose,
                facing = facingFor(plan.avatar.yaw),
                expression = plan.avatar.expression,
                guidance = plan.guidance,
                reason = plan.reason,
                expressionIntensity = plan.avatar.expressionIntensity,
                uncertainties = plan.uncertainties,
                poseDirective = plan.avatar.poseDirective,
                subjectDistance = plan.avatar.subjectDistance
            )
        }
    )

    /** 离线模型只能描述画面时，依据描述线索生成明确标记的场景分析备选。 */
    fun sceneFallback(description: String, input: DirectorInput): CompositionIntentDecision {
        val text = description.lowercase()
        val narrow = listOf("走廊", "通道", "狭", "门", "楼梯").any(text::contains)
        val warm = listOf("阳光", "暖", "花", "笑", "庆").any(text::contains)
        val defaultShots = if (narrow) listOf(ShotType.ENVIRONMENT, ShotType.FULL, ShotType.HALF)
        else listOf(ShotType.FULL, ShotType.ENVIRONMENT, ShotType.HALF)
        val defaultPoses = if (narrow) listOf(PoseId.SIDE, PoseId.RELAXED, PoseId.LOOK_BACK)
        else listOf(PoseId.RELAXED, PoseId.HAND_HIP, PoseId.HANDS_FRONT)
        // 第一个方案直接优先采用 VLM 自然语言中出现的景别、站位和姿势；其余方案只在
        // 同一场景语义内做安全的差异化扩展，不需要模型输出坐标或复杂协议。
        val primaryShot = detectedShot(text)
        val primaryZone = detectedZone(text)
        val primaryPose = detectedPose(text)
        val primaryDirective = detectedDirective(text, primaryPose ?: defaultPoses.first())
        val distance = detectedDistance(text)
        val shots = preferredSequence(primaryShot, defaultShots)
        val zones = preferredSequence(primaryZone, listOf(CompositionZone.CENTER, CompositionZone.LEFT, CompositionZone.RIGHT))
        val directives = fallbackDirectives(primaryDirective)
        return CompositionIntentDecision(
            action = ActionKind.FINAL,
            imageId = input.scene.id,
            intents = shots.indices.map { index ->
                CompositionIntent(
                    id = "p${index + 1}",
                    title = "p${index + 1}",
                    shot = shots[index],
                    zone = zones[index],
                    pose = AvatarPoseDirective.legacyPose(directives[index]),
                    facing = listOf(FacingDirection.FRONT, FacingDirection.THREE_QUARTER_LEFT, FacingDirection.THREE_QUARTER_RIGHT)[index],
                    expression = detectedExpression(text) ?: if (warm) ExpressionId.SMILE else ExpressionId.CONFIDENT,
                    guidance = description,
                    reason = description,
                    uncertainties = listOf(SCENE_FALLBACK_MARKER),
                    poseDirective = directives[index],
                    subjectDistance = distance
                )
            }
        )
    }

    private fun detectedShot(text: String): ShotType? = when {
        listOf("特写", "近景", "胸像").any(text::contains) -> ShotType.CLOSE_UP
        listOf("半身", "上半身").any(text::contains) -> ShotType.HALF
        listOf("全身", "全景").any(text::contains) -> ShotType.FULL
        listOf("环境", "带景", "广角").any(text::contains) -> ShotType.ENVIRONMENT
        else -> null
    }

    private fun detectedZone(text: String): CompositionZone? = when {
        listOf("左侧", "左边", "左方", "左下").any(text::contains) -> CompositionZone.LEFT
        listOf("右侧", "右边", "右方", "右下").any(text::contains) -> CompositionZone.RIGHT
        listOf("中央", "中间", "居中").any(text::contains) -> CompositionZone.CENTER
        else -> null
    }

    private fun detectedPose(text: String): PoseId? = when {
        listOf("回眸", "回头").any(text::contains) -> PoseId.LOOK_BACK
        listOf("侧身", "侧站").any(text::contains) -> PoseId.SIDE
        text.contains("叉腰") -> PoseId.HAND_HIP
        listOf("双手", "交叠", "身前").any(text::contains) -> PoseId.HANDS_FRONT
        listOf("挥手", "抬手").any(text::contains) -> PoseId.WAVE
        listOf("自然站", "站立").any(text::contains) -> PoseId.RELAXED
        else -> null
    }

    /** 从离线短句中提取可执行的细分身体动作；未知字段保留旧姿势的安全默认值。 */
    private fun detectedDirective(text: String, fallback: PoseId): AvatarPoseDirective {
        val legacy = AvatarPoseDirective.fromLegacy(fallback)
        val body = when {
            listOf("左侧身", "向左侧身", "身体向左", "左转身").any(text::contains) -> BodyPose.TURN_LEFT
            listOf("右侧身", "向右侧身", "身体向右", "右转身", "侧身").any(text::contains) -> BodyPose.TURN_RIGHT
            listOf("前倾", "微微前倾", "俯身").any(text::contains) -> BodyPose.LEAN_FORWARD
            listOf("正面", "面向镜头", "身体朝前").any(text::contains) -> BodyPose.FRONT
            else -> legacy.body
        }
        val arms = when {
            listOf("双手叉腰", "双手扶腰").any(text::contains) -> ArmPose.HANDS_HIPS
            listOf("叉腰", "手扶腰").any(text::contains) -> ArmPose.HAND_HIP
            listOf("双手", "手放身前", "身前交叠", "交叠").any(text::contains) -> ArmPose.HANDS_FRONT
            listOf("挥手", "招手").any(text::contains) -> ArmPose.WAVE
            listOf("指向", "指着", "前伸手").any(text::contains) -> ArmPose.POINT
            listOf("张开双臂", "双手轻开", "展开双臂").any(text::contains) -> ArmPose.ARMS_OPEN
            listOf("手臂自然", "双手自然", "自然下垂").any(text::contains) -> ArmPose.RELAXED
            else -> legacy.arms
        }
        val head = when {
            listOf("回眸", "回头看", "回头").any(text::contains) -> HeadPose.LOOK_BACK
            listOf("头向左", "向左看", "左转头").any(text::contains) -> HeadPose.TURN_LEFT
            listOf("头向右", "向右看", "右转头").any(text::contains) -> HeadPose.TURN_RIGHT
            listOf("抬头", "仰头").any(text::contains) -> HeadPose.LOOK_UP
            listOf("低头", "低头看").any(text::contains) -> HeadPose.LOOK_DOWN
            listOf("正视", "看镜头", "头朝镜头").any(text::contains) -> HeadPose.FRONT
            else -> legacy.head
        }
        val stance = when {
            listOf("重心在左", "重心左", "左腿承重").any(text::contains) -> StancePose.WEIGHT_LEFT
            listOf("重心在右", "重心右", "右腿承重").any(text::contains) -> StancePose.WEIGHT_RIGHT
            listOf("前后脚", "前迈", "前脚").any(text::contains) -> StancePose.STEP_FORWARD
            listOf("自然站", "站立").any(text::contains) -> StancePose.NEUTRAL
            else -> legacy.stance
        }
        return AvatarPoseDirective(body, arms, head, stance)
    }

    private fun detectedDistance(text: String): SubjectDistance = when {
        listOf("远处", "远景", "背景", "远离镜头").any(text::contains) -> SubjectDistance.FAR
        listOf("近处", "近景", "前景", "靠近镜头").any(text::contains) -> SubjectDistance.NEAR
        else -> SubjectDistance.MID
    }

    private fun detectedExpression(text: String): ExpressionId? = when {
        listOf("开朗", "大笑", "开心").any(text::contains) -> ExpressionId.HAPPY
        listOf("微笑", "笑容").any(text::contains) -> ExpressionId.SMILE
        listOf("自信", "从容").any(text::contains) -> ExpressionId.CONFIDENT
        listOf("沉思", "若有所思").any(text::contains) -> ExpressionId.THOUGHTFUL
        listOf("惊喜", "惊讶").any(text::contains) -> ExpressionId.SURPRISED
        listOf("平静", "自然表情", "中性").any(text::contains) -> ExpressionId.NEUTRAL
        else -> null
    }

    /** 第一条忠实采用模型线索，后两条只改为已验证的不同动作组合。 */
    private fun fallbackDirectives(primary: AvatarPoseDirective): List<AvatarPoseDirective> = listOf(
        primary,
        AvatarPoseDirective(
            body = if (primary.body == BodyPose.FRONT) BodyPose.TURN_RIGHT else BodyPose.FRONT,
            arms = if (primary.arms == ArmPose.HAND_HIP) ArmPose.HANDS_FRONT else ArmPose.HAND_HIP,
            head = if (primary.head == HeadPose.FRONT) HeadPose.TURN_LEFT else HeadPose.FRONT,
            stance = StancePose.WEIGHT_RIGHT
        ),
        AvatarPoseDirective(
            body = if (primary.body == BodyPose.TURN_LEFT) BodyPose.TURN_RIGHT else BodyPose.TURN_LEFT,
            arms = if (primary.arms == ArmPose.ARMS_OPEN) ArmPose.POINT else ArmPose.ARMS_OPEN,
            head = if (primary.head == HeadPose.LOOK_BACK) HeadPose.LOOK_UP else HeadPose.LOOK_BACK,
            stance = StancePose.WEIGHT_LEFT
        )
    )

    private fun <T> preferredSequence(primary: T?, defaults: List<T>): List<T> = buildList {
        primary?.let(::add)
        defaults.forEach { if (it !in this) add(it) }
    }.take(3)

    private fun distinct(source: List<CompositionIntent>, messages: MutableList<String>): List<CompositionIntent> {
        val used = mutableSetOf<String>()
        return source.mapIndexed { index, original ->
            var intent = original
            fun key(value: CompositionIntent) = "${value.shot}|${value.zone}|${value.pose}|${value.facing}|${value.expression}|${value.poseDirective}"
            if (key(intent) in used) {
                val zone = CompositionZone.entries.firstOrNull { candidate -> key(intent.copy(zone = candidate)) !in used }
                if (zone != null) intent = intent.copy(zone = zone)
                else {
                    val facing = FacingDirection.entries.first { candidate -> key(intent.copy(facing = candidate)) !in used }
                    intent = intent.copy(facing = facing)
                }
                messages += "p${index + 1}: 模型建议重复，已保留景别与姿势并调整站位或朝向"
            }
            used += key(intent)
            intent
        }
    }

    private fun toPlan(intent: CompositionIntent, input: DirectorInput): CompositionPlan {
        val directive = intent.poseDirective ?: AvatarPoseDirective.fromLegacy(intent.pose)
        val (height, footY, span) = placement(intent.shot, intent.subjectDistance)
        val preferredCenter = when (intent.zone) {
            CompositionZone.LEFT -> .32f
            CompositionZone.CENTER -> .5f
            CompositionZone.RIGHT -> .68f
        }
        val center = preferredCenter.coerceIn(span / 2f, 1f - span / 2f)
        val crop = CropRect(center - span / 2f, (1f - span) / 2f, center + span / 2f, (1f + span) / 2f)
        val requestedZoom = input.scene.currentZoom / span
        val needsRetake = abs(center - .5f) > .02f || requestedZoom !in input.camera.minZoom..input.camera.maxZoom
        val footX = when (intent.zone) {
            CompositionZone.LEFT -> .30f
            CompositionZone.CENTER -> .50f
            CompositionZone.RIGHT -> .70f
        }
        val yaw = when (intent.facing) {
            FacingDirection.FRONT -> 0f
            FacingDirection.THREE_QUARTER_LEFT -> -24f
            FacingDirection.THREE_QUARTER_RIGHT -> 24f
        }
        return CompositionPlan(
            id = intent.id,
            title = intent.title,
            shot = intent.shot,
            crop = crop,
            zoom = requestedZoom.coerceIn(input.camera.minZoom, input.camera.maxZoom),
            avatar = AvatarPlacement(
                foot = Point2(footX, footY), height = height, yaw = yaw,
                avatarId = input.avatarId, pose = AvatarPoseDirective.legacyPose(directive), expression = intent.expression,
                expressionIntensity = intent.expressionIntensity.coerceIn(0f, 1f), poseDirective = directive,
                subjectDistance = intent.subjectDistance
            ),
            guidance = intent.guidance,
            reason = intent.reason,
            needsRetake = needsRetake,
            uncertainties = intent.uncertainties
        )
    }

    /**
     * 高度是裁剪后预览画面中的归一化人体高度。先按景别，再按模型的远中近摄影语义
     * 收敛到保守范围；环境构图绝不使用会遮住场景的大人像。
     */
    private fun placement(shot: ShotType, distance: SubjectDistance): Triple<Float, Float, Float> {
        val height = when (shot) {
            ShotType.ENVIRONMENT -> when (distance) { SubjectDistance.FAR -> .18f; SubjectDistance.MID -> .23f; SubjectDistance.NEAR -> .28f }
            ShotType.FULL -> when (distance) { SubjectDistance.FAR -> .36f; SubjectDistance.MID -> .46f; SubjectDistance.NEAR -> .56f }
            ShotType.HALF -> when (distance) { SubjectDistance.FAR -> .50f; SubjectDistance.MID -> .60f; SubjectDistance.NEAR -> .70f }
            ShotType.CLOSE_UP -> when (distance) { SubjectDistance.FAR -> .64f; SubjectDistance.MID -> .74f; SubjectDistance.NEAR -> .82f }
        }
        val footY = when (shot) {
            ShotType.ENVIRONMENT -> .92f
            ShotType.FULL -> .93f
            ShotType.HALF -> 1.10f
            ShotType.CLOSE_UP -> 1.32f
        }
        val span = when (shot) {
            ShotType.ENVIRONMENT -> 1f
            ShotType.FULL -> .76f
            ShotType.HALF -> .58f
            ShotType.CLOSE_UP -> .42f
        }
        return Triple(height, footY, span)
    }

    private fun zoneFor(x: Float) = when {
        x < .4f -> CompositionZone.LEFT
        x > .6f -> CompositionZone.RIGHT
        else -> CompositionZone.CENTER
    }

    private fun facingFor(yaw: Float) = when {
        yaw < -8f -> FacingDirection.THREE_QUARTER_LEFT
        yaw > 8f -> FacingDirection.THREE_QUARTER_RIGHT
        else -> FacingDirection.FRONT
    }

    private fun opposite(zone: CompositionZone) = when (zone) {
        CompositionZone.LEFT -> CompositionZone.RIGHT
        CompositionZone.RIGHT -> CompositionZone.LEFT
        CompositionZone.CENTER -> CompositionZone.RIGHT
    }
}
