package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.ArGroundDelivery
import com.framer.sense.feature.camera.vlm.ar.ArGroundTracking
import org.junit.Assert.*
import org.junit.Test

class ArGroundTrackingTest {
    private data class PlaneWrapper(val logicalId: String)

    @Test fun newWrappersForTheSamePlaneKeepStableObservationsAndAnchorIdentity() {
        var number = 0
        val tracker = ArGroundTracking<PlaneWrapper> { "plane-${++number}" }
        val first = PlaneWrapper("floor")
        val id = tracker.observe(first, 0f)
        val next = PlaneWrapper("floor")
        assertNotSame(first, next)
        assertEquals(id, tracker.observe(next, .01f))
        assertEquals(id, tracker.observe(PlaneWrapper("floor"), .02f))
        assertEquals(3, tracker.count(id))
        assertTrue(tracker.stable)
        assertEquals(id, tracker.findId(PlaneWrapper("floor")))
        assertNotEquals(id, tracker.observe(PlaneWrapper("table"), .8f))
    }

    @Test fun heightJumpOrTrackingLossRequiresFreshEvidenceAndNewSessionDiscardsIds() {
        var number = 0
        val tracker = ArGroundTracking<String> { "plane-${++number}" }
        val id = tracker.observe("floor", 0f)
        repeat(4) { tracker.observe("floor", 0f) }
        assertEquals(3, tracker.count(id))
        tracker.observe("floor", .1f)
        assertEquals(1, tracker.count(id))
        tracker.lost()
        assertFalse(tracker.stable)
        assertEquals(id, tracker.observe("floor", .1f))
        assertEquals(1, tracker.count(id))
        tracker.retain(emptySet())
        assertEquals(0, tracker.count(id))
        tracker.reset()
        assertNotEquals(id, tracker.observe("floor", .1f))
    }

    @Test fun identicalGroundIsReplayedAtBoundedRateAndChangedInputIsImmediate() {
        val delivery = ArGroundDelivery()
        assertTrue(delivery.shouldPublish("session:config:floor", 0))
        assertFalse(delivery.shouldPublish("session:config:floor", 1_000_000_000))
        assertTrue(delivery.shouldPublish("session:config:floor", 2_000_000_000))
        assertTrue(delivery.shouldPublish("session:newConfig:floor", 2_000_000_001))
        delivery.reset()
        assertTrue(delivery.shouldPublish("session:newConfig:floor", 2_000_000_002))
    }
}
