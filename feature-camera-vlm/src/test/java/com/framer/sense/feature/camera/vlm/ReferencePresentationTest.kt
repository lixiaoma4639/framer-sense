package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionResolver
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Test

class ReferencePresentationTest {
    private fun plan() = CompositionPlan("p", "参考", ShotType.FULL, CropRect(), 1f,
        AvatarPlacement(), "站立", "保留背景", viewBinding = PhotoViewBinding("scan", "frame", 1))

    @Test fun unresolvedReferenceRemainsHiddenButEstimatedReferenceNeedsNoGround() {
        val p = plan()
        for (confirmed in listOf(BackgroundStatus.ALIGNED_AUTO, BackgroundStatus.ALIGNED_MANUAL)) {
            for (tracking in listOf(ArTrackingState.TRACKING, ArTrackingState.NEEDS_GROUND)) {
                assertEquals(ReferencePresentation.HIDDEN,
                    referencePresentation(p, confirmed, ArUiState(ArSource.SHARED_CAMERA, tracking)))
            }
        }
        val estimated = PhotoScaleEstimator.estimate(p)
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(estimated,BackgroundStatus.ALIGNED_AUTO,
            ArUiState(ArSource.SHARED_CAMERA,ArTrackingState.NEEDS_GROUND)))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(estimated,BackgroundStatus.ALIGNED_MANUAL,
            ArUiState(ArSource.SHARED_CAMERA,ArTrackingState.LOST)))
        assertEquals(ReferencePresentation.HIDDEN,
            referencePresentation(p, BackgroundStatus.SEARCHING, ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.NEEDS_GROUND)))
    }

    @Test fun onlyCurrentSpatialReferenceReplacesScreenReference() {
        val snapshot = ArScaleSnapshot("world",1,1,SpatialMatrices.identity(),SpatialMatrices.identity(),SpatialPoint(0f,1f,0f),480,640)
        val scale = AvatarScaleMetadata("vlm_avatars/adult_female.glb",1,1,SpatialPoint(0f,0f,0f),SpatialPoint(0f,1.6f,0f))
        val placement = ResolvedSpatialPlacement(snapshot,"floor",SpatialPoint(0f,0f,-3f),0f,1.65f,scale,Point2(.5f,.9f),.6f,3f)
        val p = PhotoActionResolver().resolve(plan().copy(arCamera=snapshot,viewBinding=PhotoViewBinding("scan","frame",1,"world"))).withSpatial(placement)
        val placed = ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.PLACED, "world", placedReferenceKey = p.arReferenceKey())
        assertEquals(ReferencePresentation.SPATIAL, referencePresentation(p, BackgroundStatus.ALIGNED_AUTO, placed))
        assertEquals(ReferencePresentation.HIDDEN, referencePresentation(p.copy(revision = 1), BackgroundStatus.ALIGNED_AUTO, placed))
        assertEquals(ReferencePresentation.HIDDEN, referencePresentation(p.copy(viewBinding = PhotoViewBinding("scan2", "frame2", 2)), BackgroundStatus.ALIGNED_AUTO, placed))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(p, BackgroundStatus.ALIGNED_AUTO, placed.copy(placedReferenceKey = null)))
        assertEquals(ReferencePresentation.HIDDEN, referencePresentation(p, BackgroundStatus.SEARCHING, placed))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(p, BackgroundStatus.ALIGNED_AUTO, placed.copy(sessionId="new")))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(p, BackgroundStatus.ALIGNED_AUTO, placed.copy(tracking=ArTrackingState.LOST)))
    }

    @Test fun singleImageAndNonArModeUseExplicitlyMarkedEstimate() {
        val screen = ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED)
        assertEquals(ReferencePresentation.HIDDEN, referencePresentation(plan(), BackgroundStatus.ALIGNED_MANUAL, screen))
        assertEquals(ReferencePresentation.HIDDEN, referencePresentation(plan().copy(viewBinding = null), BackgroundStatus.SEARCHING, screen))
        assertEquals(ReferencePresentation.SCREEN, referencePresentation(PhotoScaleEstimator.estimate(plan()), BackgroundStatus.ALIGNED_MANUAL, screen))
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
