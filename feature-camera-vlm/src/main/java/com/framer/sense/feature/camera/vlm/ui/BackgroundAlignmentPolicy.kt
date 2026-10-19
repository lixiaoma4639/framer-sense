package com.framer.sense.feature.camera.vlm.ui

import com.framer.sense.feature.camera.vlm.model.BackgroundGuidance
import com.framer.sense.feature.camera.vlm.model.BackgroundStatus
import kotlin.math.abs

/** 自动和手动确认共用原人工确认的保持范围，避免短暂匹配波动反复隐藏人像。 */
internal fun mergeBackgroundAlignment(
    previous: BackgroundGuidance,
    observed: BackgroundGuidance
): BackgroundGuidance {
    val withinConfirmedPose = (observed.headingError == null || abs(observed.headingError) < 12f) &&
        (observed.pitchError == null || abs(observed.pitchError) < 12f)
    return if (previous.status != BackgroundStatus.SEARCHING && withinConfirmedPose)
        observed.copy(status = previous.status)
    else observed
}
