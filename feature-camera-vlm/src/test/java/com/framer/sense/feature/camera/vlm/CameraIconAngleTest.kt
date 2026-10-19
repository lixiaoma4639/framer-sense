package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ui.CameraIconAngle
import com.framer.sense.feature.camera.vlm.ui.shortestIconRotation
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraIconAngleTest {
    @Test fun intermediateAnglesStayInFourDirectionsInsteadOfRotatingContinuously() {
        val tracker = CameraIconAngle(pageRotation = 0)
        listOf(0 to 0f, 37 to 0f, 56 to 270f, 90 to 270f, 125 to 270f,
            146 to 180f, 180 to 180f, 235 to 180f, 236 to 90f, 270 to 90f,
            325 to 90f, 326 to 0f, 359 to 0f, 0 to 0f)
            .forEach { (sensor, icon) -> assertEquals(icon, tracker.update(sensor), 0f) }
    }

    @Test fun pageEntryDirectionIsTheReferenceForAllFourDisplayRotations() {
        (0..3).forEach { page ->
            val tracker = CameraIconAngle(page)
            val initialSensor = (360 - page * 90) % 360
            assertEquals(0f, tracker.update(initialSensor), 0f)
            assertEquals(0f, tracker.update((initialSensor + 30) % 360), 0f)
            assertEquals(270f, tracker.update((initialSensor + 90) % 360), 0f)
            assertEquals(90f, tracker.update((initialSensor + 270) % 360), 0f)
        }
    }

    @Test fun unknownFlatReadingsKeepTheLastDirection() {
        val tracker = CameraIconAngle(0)
        assertEquals(0f, tracker.update(10), 0f)
        assertEquals(270f, tracker.update(90), 0f)
        listOf(-1, 360, Int.MAX_VALUE).forEach { assertEquals(270f, tracker.update(it), 0f) }
    }

    @Test fun hysteresisPreventsBackAndForthAtBothBoundariesOfEachDirection() {
        (0..3).forEach { page ->
            val center = ((4 - page) % 4) * 90
            val clockwise = CameraIconAngle(page)
            listOf(44, 46, 54, 45, 55).forEach { offset ->
                assertEquals(0f, clockwise.update((center + offset) % 360), 0f)
            }
            assertEquals(270f, clockwise.update((center + 56) % 360), 0f)
            assertEquals(270f, clockwise.update((center + 44) % 360), 0f)
            assertEquals(0f, clockwise.update((center + 34) % 360), 0f)
            val counterclockwise = CameraIconAngle(page)
            assertEquals(0f, counterclockwise.update((center - 55 + 360) % 360), 0f)
            assertEquals(90f, counterclockwise.update((center - 56 + 360) % 360), 0f)
        }
    }

    @Test fun crossingZeroAndRepeatedTurnsUseTheShortestPath() {
        assertEquals(2f, shortestIconRotation(359f, 1f), 0f)
        assertEquals(-2f, shortestIconRotation(1f, 359f), 0f)
        assertEquals(90f, shortestIconRotation(270f, 0f), 0f)
        assertEquals(-90f, shortestIconRotation(0f, 270f), 0f)
        assertEquals(2f, shortestIconRotation(719f, 1f), 0f)
    }
}
