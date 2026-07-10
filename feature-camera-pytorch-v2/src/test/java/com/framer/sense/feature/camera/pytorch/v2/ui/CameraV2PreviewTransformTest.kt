package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraV2PreviewTransformTest {

    @Test
    fun map_keepsCoordinatesUnchanged_whenLandscapeFrameMatchesLandscapeViewport() {
        val transform = CameraV2PreviewTransform(
            frameAspectRatio = 16f / 9f,
            viewportWidth = 1920f,
            viewportHeight = 1080f
        )

        val mapped = transform.map(V2Point(0.2f, 0.8f))

        assertEquals(0.2f, mapped.x, EPSILON)
        assertEquals(0.8f, mapped.y, EPSILON)
    }

    @Test
    fun map_appliesSameFillCenterCrop_whenLandscapeFrameIsShownInPortraitViewport() {
        val transform = CameraV2PreviewTransform(
            frameAspectRatio = 16f / 9f,
            viewportWidth = 1080f,
            viewportHeight = 1920f
        )

        val center = transform.map(V2Point(0.5f, 0.5f))
        val leftEdge = transform.map(V2Point(0f, 0.5f))

        assertEquals(0.5f, center.x, EPSILON)
        assertEquals(0.5f, center.y, EPSILON)
        assertEquals(-1.0802469f, leftEdge.x, EPSILON)
    }

    @Test
    fun map_appliesSameFillCenterCrop_whenPortraitFrameIsShownInLandscapeViewport() {
        val transform = CameraV2PreviewTransform(
            frameAspectRatio = 9f / 16f,
            viewportWidth = 1920f,
            viewportHeight = 1080f
        )

        val center = transform.map(V2Point(0.5f, 0.5f))
        val topEdge = transform.map(V2Point(0.5f, 0f))

        assertEquals(0.5f, center.x, EPSILON)
        assertEquals(0.5f, center.y, EPSILON)
        assertEquals(-1.0802469f, topEdge.y, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
