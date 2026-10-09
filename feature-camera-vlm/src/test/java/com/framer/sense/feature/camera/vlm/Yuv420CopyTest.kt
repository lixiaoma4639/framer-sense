package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.camera.Yuv420Copy
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Yuv420CopyTest {
    private fun plane(bytes: ByteArray, row: Int, pixel: Int, start: Int = 0): Yuv420Copy.Plane {
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); position(start) }
        return Yuv420Copy.Plane(buffer, row, pixel)
    }

    @Test fun packedLumaAndPlanarChromaProduceVuOrderWithoutConsumingSource() {
        val y = plane(byteArrayOf(1,2,3,4,5,6,7,8), 4, 1)
        val u = plane(byteArrayOf(11,12), 2, 1)
        val v = plane(byteArrayOf(21,22), 2, 1)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,21,11,22,12), Yuv420Copy.toNv21(4, 2, listOf(y,u,v)))
        assertEquals(0, y.buffer.position())
        assertEquals(0, u.buffer.position())
    }

    @Test fun paddedRowsInterleavedChromaAndShortLastRowKeepAllPixels() {
        val y = plane(byteArrayOf(99,1,2,3,4,99,99,5,6,7,8,99,99,9,10,11,12,99,99,13,14,15,16), 6, 1, 1)
        val u = plane(byteArrayOf(99,31,99,32,99,99,99,33,99,34), 6, 2, 1)
        val v = plane(byteArrayOf(99,41,99,42,99,99,99,43,99,44), 6, 2, 1)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,41,31,42,32,43,33,44,34),
            Yuv420Copy.toNv21(4, 4, listOf(y,u,v)))
        assertEquals(1, v.buffer.position())
    }
}
