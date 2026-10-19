package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.BackgroundGuidance
import com.framer.sense.feature.camera.vlm.model.BackgroundStatus
import com.framer.sense.feature.camera.vlm.ui.mergeBackgroundAlignment
import org.junit.Assert.*
import org.junit.Test

class BackgroundAlignmentPolicyTest {
    @Test fun autoAndManualConfirmationBothSurviveBriefMatchingLoss() {
        for (status in listOf(BackgroundStatus.ALIGNED_AUTO, BackgroundStatus.ALIGNED_MANUAL)) {
            val confirmed = BackgroundGuidance(status)
            for (sample in listOf(BackgroundGuidance(), BackgroundGuidance(headingError = 11.9f, pitchError = -11.9f))) {
                val next = mergeBackgroundAlignment(confirmed, sample)
                assertEquals(status, next.status)
                assertEquals(sample.headingError, next.headingError)
                assertEquals(sample.pitchError, next.pitchError)
                assertEquals(sample.matched, next.matched)
            }
        }
    }

    @Test fun turningAwayReleasesEitherConfirmationAtTheSameThreshold() {
        for (status in listOf(BackgroundStatus.ALIGNED_AUTO, BackgroundStatus.ALIGNED_MANUAL)) {
            for (sample in listOf(BackgroundGuidance(headingError = 12f), BackgroundGuidance(headingError = -12f),
                BackgroundGuidance(pitchError = 12f), BackgroundGuidance(pitchError = -12f))) {
                assertEquals(BackgroundStatus.SEARCHING, mergeBackgroundAlignment(BackgroundGuidance(status), sample).status)
            }
        }
    }

    @Test fun searchingOrExplicitlyResetStateStillRequiresStableMatchingOrManualConfirmation() {
        val reset = BackgroundGuidance()
        assertEquals(BackgroundStatus.SEARCHING,
            mergeBackgroundAlignment(reset, BackgroundGuidance(matched = true)).status)
        val stable = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO, matched = true)
        assertEquals(stable, mergeBackgroundAlignment(reset, stable))
    }
}
