package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import android.graphics.Color
import com.framer.sense.feature.camera.vlm.data.SeedreamLayer
import com.framer.sense.feature.camera.vlm.data.SeedreamLayerCompositor
import com.framer.sense.feature.camera.vlm.data.SeedreamLayers
import org.junit.Assert.*
import org.junit.Test

/** 仅使用合成色块验证真实 Canvas，不调用网络、相机或模型。 */
class SeedreamLayerCompositorTest {
    @Test fun allSixteenForegroundsAreCompositedEvenWithoutPersonMetadata() {
        val base = Bitmap.createBitmap(17, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val layers = (0 until 16).map { index ->
            SeedreamLayer("unused", index + 1, "object", null, absoluteBounds = listOf(index, 0, index + 1, 1))
        }
        val response = SeedreamLayers("fixture", layers.reversed(), 17)
        try {
            SeedreamLayerCompositor(base, 17, 2, response.personLayers.toSet()).use { compositor ->
                response.foregroundLayers.forEach { layer ->
                    val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    try {
                        image.eraseColor(Color.rgb(layer.z * 15, 255 - layer.z * 15, 128))
                        compositor.draw(layer, image)
                    } finally { image.recycle() }
                }
                layers.forEachIndexed { index, layer ->
                    assertEquals(Color.rgb(layer.z * 15, 255 - layer.z * 15, 128), compositor.composite.getPixel(index, 0))
                }
                assertEquals(Color.BLACK, compositor.composite.getPixel(16, 0))
                assertEquals(Color.BLACK, compositor.composite.getPixel(0, 1))
                assertFalse(compositor.hasPortrait())
            }
        } finally { base.recycle() }
    }

    @Test fun overlappingLayersRespectZOrderAndTransparentPixelsPreserveLowerLayers() {
        val base = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val furniture = SeedreamLayer("unused", 1, "bench", null, absoluteBounds = listOf(0, 0, 4, 4))
        val person = SeedreamLayer("unused", 2, "reference_person", null, "woman", listOf(2, 2, 4, 4))
        val response = SeedreamLayers("fixture", listOf(person, furniture), 3)
        val lower = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val upper = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { setPixel(1, 1, Color.GREEN) }
        try {
            SeedreamLayerCompositor(base, 8, 8, response.personLayers.toSet()).use { compositor ->
                response.foregroundLayers.forEach { compositor.draw(it, if (it.z == 1) lower else upper) }
                assertEquals(Color.RED, compositor.composite.getPixel(2, 2))
                assertEquals(Color.GREEN, compositor.composite.getPixel(3, 3))
                assertEquals(Color.BLUE, compositor.composite.getPixel(7, 7))
                assertTrue(compositor.hasPortrait())
                assertEquals(0, Color.alpha(compositor.portrait.getPixel(0, 0)))
                assertEquals(Color.GREEN, compositor.portrait.getPixel(3, 3))
            }
        } finally { base.recycle(); lower.recycle(); upper.recycle() }
    }
}
