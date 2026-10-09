package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.avatar.AvatarDisplayColor
import org.junit.Assert.*
import org.junit.Test

class AvatarDisplayColorTest {
    private fun gray(channel: Int) = (255 shl 24) or (channel shl 16) or (channel shl 8) or channel

    @Test fun linearMidtonesAreEncodedForDisplayRatherThanShownAsDarkSrgb() {
        // 标准参考值：50% 线性灰在 sRGB 约为 188；18% 线性灰约为 118。
        assertEquals(gray(188), AvatarDisplayColor.restorePixel(gray(128), gray(128), false))
        assertEquals(gray(118), AvatarDisplayColor.restorePixel(gray(46), gray(46), false))
    }

    @Test fun blackWhiteAndTransparentBackgroundRemainCorrect() {
        assertEquals(gray(0), AvatarDisplayColor.restorePixel(gray(0), gray(0), false))
        assertEquals(gray(255), AvatarDisplayColor.restorePixel(gray(255), gray(255), false))
        assertEquals(0, AvatarDisplayColor.restorePixel(gray(0), gray(255), false))
        assertEquals(0, AvatarDisplayColor.restorePixel(gray(0), gray(255), true))
    }

    @Test fun alphaIsRecoveredBeforeNonlinearEncodingToAvoidDarkEdges() {
        // 约 50% 覆盖的 25% 线性前景：黑底 32，白底 159，恢复后 sRGB 前景约为 137。
        val recovered = AvatarDisplayColor.restorePixel(gray(32), gray(159), false)
        assertEquals(128, recovered ushr 24)
        assertEquals(137, recovered and 255)
        assertEquals(recovered and 255, recovered ushr 16 and 255)
    }

    @Test fun warmSkinChannelsKeepTheirOrderAndFullOpacity() {
        val skin = (255 shl 24) or (120 shl 16) or (70 shl 8) or 40
        val result = AvatarDisplayColor.restorePixel(skin, skin, false)
        assertEquals(255, result ushr 24)
        assertTrue((result ushr 16 and 255) > (result ushr 8 and 255))
        assertTrue((result ushr 8 and 255) > (result and 255))
        assertTrue((result ushr 16 and 255) > 120)
    }

    @Test fun legacySofteningDoesNotApplyASecondGammaConversion() {
        val result = AvatarDisplayColor.restorePixel(gray(128), gray(128), true)
        assertEquals(gray(195), result)
    }
}
