package com.framer.sense.feature.camera.vlm

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framer.sense.feature.camera.vlm.scan.YuvBitmapDecoder
import org.opencv.android.OpenCVLoader
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YuvBitmapDecoderTest {
    @Test fun directConversionKeepsLeftRightOrderAndOpaquePixels() {
        assertTrue(OpenCVLoader.initLocal())
        val data = byteArrayOf(235.toByte(),235.toByte(),16,16,235.toByte(),235.toByte(),16,16,
            128.toByte(),128.toByte(),128.toByte(),128.toByte())
        val bitmap = YuvBitmapDecoder.decode(data,4,2)
        try {
            assertTrue(Color.red(bitmap.getPixel(0,0)) > 240)
            assertTrue(Color.red(bitmap.getPixel(3,0)) < 10)
            assertEquals(255, Color.alpha(bitmap.getPixel(0,0)))
        } finally { bitmap.recycle() }
    }

    @Test fun nv21ChromaIsRedInsteadOfSwappedBlue() {
        assertTrue(OpenCVLoader.initLocal())
        val data = ByteArray(12) { if (it < 8) 82.toByte() else if (it % 2 == 0) 240.toByte() else 90.toByte() }
        val bitmap = YuvBitmapDecoder.decode(data,4,2)
        try {
            val pixel = bitmap.getPixel(1,1)
            assertTrue(Color.red(pixel) > 240)
            assertTrue(Color.blue(pixel) < 20)
        } finally { bitmap.recycle() }
    }
}
