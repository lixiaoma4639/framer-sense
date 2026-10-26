package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import android.graphics.Color
import com.framer.sense.feature.camera.vlm.data.QwenReferenceCompositor
import org.junit.Assert.*
import org.junit.Test

class QwenReferenceCompositorTest {
    @Test fun transparentForegroundKeepsPlacementAndPreviewMatchesCameraAsset() {
        val background = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val foreground = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { setPixel(6, 2, Color.RED) }
        try {
            assertTrue(QwenReferenceCompositor.hasTransparentForeground(foreground))
            assertTrue(QwenReferenceCompositor.matchingAspect(background, foreground))
            val portrait = QwenReferenceCompositor.portrait(background, foreground)
            val composite = QwenReferenceCompositor.composite(background, portrait)
            try {
                assertEquals(Color.RED, portrait.getPixel(6, 2))
                assertEquals(Color.RED, composite.getPixel(6, 2))
                assertEquals(Color.TRANSPARENT, portrait.getPixel(0, 0))
                assertEquals(Color.BLUE, composite.getPixel(0, 0))
                assertEquals(Color.BLUE, background.getPixel(6, 2))
            } finally { portrait.recycle(); composite.recycle() }
        } finally { background.recycle(); foreground.recycle() }
    }

    @Test fun emptyAlphaOpaqueWhiteAndWrongAspectAreNotUsablePortraits() {
        val empty = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val white = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val wide = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        try {
            assertFalse(QwenReferenceCompositor.hasTransparentForeground(empty))
            assertFalse(QwenReferenceCompositor.hasTransparentForeground(white))
            val whiteStats = QwenReferenceCompositor.alphaStats(white)
            assertEquals(64L, whiteStats.total)
            assertEquals(0L, whiteStats.clear)
            assertEquals(64L, whiteStats.visible)
            assertFalse(whiteStats.usable)
            assertFalse(QwenReferenceCompositor.matchingAspect(white, wide))
        } finally { empty.recycle(); white.recycle(); wide.recycle() }
    }
}
