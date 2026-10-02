package com.framer.sense.feature.camera.vlm.avatar

import com.framer.sense.feature.camera.vlm.model.*

/** 校准只改变布局；半身和特写保持原有身体裁切比例。 */
internal fun CompositionPlan.shrinkProjection(factor: Float): CompositionPlan {
    require(factor.isFinite() && factor in 0f..1f && factor > 0f)
    val height = avatar.height * factor
    val foot = if (shot in listOf(ShotType.HALF, ShotType.CLOSE_UP)) {
        val cutRatio = (avatar.foot.y - 1f) / avatar.height
        avatar.foot.copy(y = 1f + height * cutRatio)
    } else avatar.foot
    return withActualAvatar(avatar.copy(height = height, foot = foot))
}
