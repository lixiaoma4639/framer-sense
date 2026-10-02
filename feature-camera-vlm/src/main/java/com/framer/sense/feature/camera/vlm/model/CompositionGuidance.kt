package com.framer.sense.feature.camera.vlm.model

import kotlin.math.roundToInt

/** 只描述最终方案事实，文字由 UI 使用本地化资源生成，不复用模型原文指挥用户。 */
data class CompositionGuidance(
    val shot: ShotType,
    val zone: CompositionZone,
    val facing: FacingDirection,
    val directive: AvatarPoseDirective,
    val expression: ExpressionId,
    val distance: SubjectDistance,
    val horizontalPercent: Int,
    val footPercent: Int,
    val heightPercent: Int,
    val zoom: Float,
    val needsRetake: Boolean,
    /** 旧 HAT 没有对应细分枚举，保留实际旧渲染动作的语义。 */
    val legacyHandNearFace: Boolean
) {
    companion object {
        fun from(plan: CompositionPlan): CompositionGuidance {
            plan.resolvedSpec?.takeIf { it.matches(plan) }?.let { return it.guidance }
            val avatar = plan.avatar
            return CompositionGuidance(
                shot = plan.shot,
                zone = when {
                    avatar.foot.x < .4f -> CompositionZone.LEFT
                    avatar.foot.x > .6f -> CompositionZone.RIGHT
                    else -> CompositionZone.CENTER
                },
                facing = when {
                    avatar.yaw < -8f -> FacingDirection.THREE_QUARTER_LEFT
                    avatar.yaw > 8f -> FacingDirection.THREE_QUARTER_RIGHT
                    else -> FacingDirection.FRONT
                },
                directive = avatar.poseDirective ?: AvatarPoseDirective.fromLegacy(avatar.pose).copy(
                    // 旧渲染仅转动上半身，不包含细分动作新增的腿部重心修正。
                    stance = StancePose.NEUTRAL,
                    head = if (avatar.pose == PoseId.HAT) HeadPose.FRONT else AvatarPoseDirective.fromLegacy(avatar.pose).head
                ),
                expression = if (avatar.expressionIntensity <= 0f) ExpressionId.NEUTRAL else avatar.expression,
                distance = avatar.subjectDistance,
                horizontalPercent = (avatar.foot.x * 100f).roundToInt(),
                footPercent = (avatar.foot.y * 100f).roundToInt(),
                heightPercent = ((minOf(avatar.foot.y, 1f) - maxOf(avatar.foot.y - avatar.height, 0f)).coerceIn(0f, 1f) * 100f).roundToInt(),
                zoom = plan.zoom,
                needsRetake = plan.needsRetake,
                legacyHandNearFace = avatar.poseDirective == null && avatar.pose == PoseId.HAT
            )
        }
    }
}
