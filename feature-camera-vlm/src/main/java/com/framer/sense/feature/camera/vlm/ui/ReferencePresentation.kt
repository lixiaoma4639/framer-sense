package com.framer.sense.feature.camera.vlm.ui

import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.model.BackgroundStatus
import com.framer.sense.feature.camera.vlm.model.CompositionPlan
import com.framer.sense.feature.camera.vlm.model.hasRenderableScale

internal enum class ReferencePresentation { HIDDEN, SCREEN, SPATIAL }

/** 实测/估算都展示；AR 锚点尚未生效或跟踪失效时保留同参数屏幕参考。 */
internal fun referencePresentation(plan: CompositionPlan?, background: BackgroundStatus, ar: ArUiState): ReferencePresentation {
    if (plan == null || plan.viewBinding != null && background == BackgroundStatus.SEARCHING)
        return ReferencePresentation.HIDDEN
    if (!plan.hasRenderableScale() || plan.resolvedSpec?.matches(plan) == false) return ReferencePresentation.HIDDEN
    return if (ar.source == ArSource.SHARED_CAMERA && plan.spatialPlacement != null && plan.spatialPlacement.camera.sessionId == ar.sessionId &&
        ar.tracking == ArTrackingState.PLACED && ar.placedReferenceKey == plan.arReferenceKey()) ReferencePresentation.SPATIAL
    else ReferencePresentation.SCREEN
}
