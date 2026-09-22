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
                uncertainties = plan.uncertainties
            )
        }
    )

    /** 离线模型只能描述画面时，依据描述线索生成明确标记的场景分析备选。 */
    fun sceneFallback(description: String, input: DirectorInput): CompositionIntentDecision {
        val text = description.lowercase()
        val zone = when {
            listOf("左", "left").any(text::contains) -> CompositionZone.RIGHT
            listOf("右", "right").any(text::contains) -> CompositionZone.LEFT
            else -> CompositionZone.CENTER
        }
        val narrow = listOf("走廊", "通道", "狭", "门", "楼梯").any(text::contains)
        val warm = listOf("阳光", "暖", "花", "笑", "庆").any(text::contains)
        val shots = if (narrow) listOf(ShotType.ENVIRONMENT, ShotType.FULL, ShotType.HALF)
        else listOf(ShotType.FULL, ShotType.ENVIRONMENT, ShotType.HALF)
        val poses = if (narrow) listOf(PoseId.SIDE, PoseId.RELAXED, PoseId.LOOK_BACK)
        else listOf(PoseId.RELAXED, PoseId.HAND_HIP, PoseId.HANDS_FRONT)
        val zones = listOf(zone, CompositionZone.CENTER, opposite(zone))
        return CompositionIntentDecision(
            action = ActionKind.FINAL,
            imageId = input.scene.id,
            intents = shots.indices.map { index ->
                CompositionIntent(
                    id = "p${index + 1}",
                    title = description.take(28),
                    shot = shots[index],
                    zone = zones[index],
                    pose = poses[index],
                    facing = if (index == 1) FacingDirection.FRONT else FacingDirection.THREE_QUARTER_LEFT,
                    expression = if (warm) ExpressionId.SMILE else ExpressionId.CONFIDENT,
                    guidance = description.take(120),
                    reason = description.take(160),
                    uncertainties = listOf(SCENE_FALLBACK_MARKER)
                )
            }
        )
    }

    private fun distinct(source: List<CompositionIntent>, messages: MutableList<String>): List<CompositionIntent> {
        val used = mutableSetOf<String>()
        return source.mapIndexed { index, original ->
            var intent = original
            fun key(value: CompositionIntent) = "${value.shot}|${value.zone}|${value.pose}|${value.facing}|${value.expression}"
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
        val (height, footY, span) = when (intent.shot) {
            ShotType.ENVIRONMENT -> Triple(.24f, .92f, 1f)
            ShotType.FULL -> Triple(.64f, .93f, .76f)
            ShotType.HALF -> Triple(1.08f, 1.43f, .58f)
            ShotType.CLOSE_UP -> Triple(1.50f, 1.98f, .42f)
        }
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
                avatarId = input.avatarId, pose = intent.pose, expression = intent.expression,
                expressionIntensity = intent.expressionIntensity.coerceIn(0f, 1f)
            ),
            guidance = intent.guidance,
            reason = intent.reason,
            needsRetake = needsRetake,
            uncertainties = intent.uncertainties
        )
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
