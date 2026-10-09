package com.framer.sense.feature.camera.vlm.ui

import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.model.BackgroundStatus
import com.framer.sense.feature.camera.vlm.model.CompositionPlan

internal enum class ReferencePresentation { HIDDEN, SCREEN, SPATIAL }

/** 背景确认与空间落点是两件事；确认后不因地面暂时缺失而隐藏摄影示范。 */
internal fun referencePresentation(plan: CompositionPlan?, background: BackgroundStatus, ar: ArUiState): ReferencePresentation {
    if (plan == null || plan.viewBinding != null && background == BackgroundStatus.SEARCHING)
        return ReferencePresentation.HIDDEN
    return if (ar.source == ArSource.SHARED_CAMERA && ar.tracking == ArTrackingState.PLACED &&
        ar.placedReferenceKey == plan.arReferenceKey()) ReferencePresentation.SPATIAL
    else ReferencePresentation.SCREEN
}
