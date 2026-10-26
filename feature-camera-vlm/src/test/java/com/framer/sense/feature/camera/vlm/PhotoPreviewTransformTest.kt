package com.framer.sense.feature.camera.vlm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.framer.sense.feature.camera.vlm.ui.PhotoPreviewTransform
import org.junit.Assert.*
import org.junit.Test

class PhotoPreviewTransformTest {
    @Test fun zoomKeepsFocalPointAndPanStaysWithinImageBounds() {
        val size = IntSize(200, 100)
        val zoomed = PhotoPreviewTransform().updated(Offset.Zero, 2f, Offset(50f, 50f), size, 2f)
        assertEquals(2f, zoomed.scale, .001f)
        assertEquals(50f, zoomed.offset.x, .001f)
        val panned = zoomed.updated(Offset(1000f, -1000f), 1f, Offset(100f, 50f), size, 2f)
        assertEquals(100f, panned.offset.x, .001f)
        assertEquals(-50f, panned.offset.y, .001f)
        assertEquals(PhotoPreviewTransform(), panned.updated(Offset.Zero, .1f, Offset.Zero, size, 2f))
    }

    @Test fun letterboxingDoesNotAllowEmptySpaceToBeDraggedAcrossTheViewport() {
        val transformed = PhotoPreviewTransform().updated(Offset(1000f, 1000f), 1.5f,
            Offset(100f, 50f), IntSize(200, 100), 1f)
        assertEquals(0f, transformed.offset.x, .001f)
        assertEquals(25f, transformed.offset.y, .001f)
        val maximum = transformed.updated(Offset.Zero, 100f, Offset(100f, 50f), IntSize(200, 100), 1f)
        assertEquals(5f, maximum.scale, .001f)
    }
}
