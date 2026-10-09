package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Test

class ReferencePresentationTest {
    private fun plan() = CompositionPlan("p", "参考", ShotType.FULL, CropRect(), 1f,
        AvatarPlacement(), "站立", "保留背景", viewBinding = PhotoViewBinding("scan", "frame", 1))

    @Test fun confirmedBackgroundShowsReferenceWhileGroundIsMissing() {
        val p = plan()
        for (confirmed in listOf(BackgroundStatus.ALIGNED_AUTO, BackgroundStatus.ALIGNED_MANUAL)) {
            for (tracking in listOf(ArTrackingState.TRACKING, ArTrackingState.NEEDS_GROUND)) {
                assertEquals(ReferencePresentation.SCREEN,
                    referencePresentation(p, confirmed, ArUiState(ArSource.SHARED_CAMERA, tracking)))
            }
        }
        assertEquals(ReferencePresentation.HIDDEN,
            referencePresentation(p, BackgroundStatus.SEARCHING, ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.NEEDS_GROUND)))
    }

    @Test fun onlyCurrentSpatialReferenceReplacesScreenReference() {
        val p = plan()
        val placed = ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.PLACED, "world", placedReferenceKey = p.arReferenceKey())
        assertEquals(ReferencePresentation.SPATIAL, referencePresentation(p, BackgroundStatus.ALIGNED_AUTO, placed))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(p.copy(revision = 1), BackgroundStatus.ALIGNED_AUTO, placed))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(p.copy(viewBinding = PhotoViewBinding("scan2", "frame2", 2)), BackgroundStatus.ALIGNED_AUTO, placed))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(p, BackgroundStatus.ALIGNED_AUTO, placed.copy(placedReferenceKey = null)))
        assertEquals(ReferencePresentation.HIDDEN, referencePresentation(p, BackgroundStatus.SEARCHING, placed))
    }

    @Test fun singleImageAndNonArModeStillShowScreenReference() {
        val screen = ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED)
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(plan(), BackgroundStatus.ALIGNED_MANUAL, screen))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(plan().copy(viewBinding = null), BackgroundStatus.SEARCHING, screen))
        assertEquals(ReferencePresentation.HIDDEN, referencePresentation(null, BackgroundStatus.ALIGNED_AUTO, screen))
    }

    @Test fun groundRetryIsThrottledAndCanRetryAfterEarlyFailure() {
        val retry = ArPlacementRetry(1_000)
        assertTrue(retry.shouldAttempt(0))
        assertFalse(retry.shouldAttempt(999))
        assertTrue(retry.shouldAttempt(1_000))
        assertFalse(retry.shouldAttempt(1_999))
        assertEquals(2, retry.attempts)
        retry.reset()
        assertTrue(retry.shouldAttempt(2_000))
        assertEquals(1, retry.attempts)
    }
}
