package com.framer.sense.feature.camera.vlm.avatar

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Filament 无后处理、无 SRGB swapchain 的读回是线性 RGB。
 * 先在线性空间从黑白底恢复 alpha 和非预乘前景，再编码为 Android Bitmap 的 sRGB。
 * 不能先转换黑白底：非线性传递函数会破坏 alpha 求解，并产生暗边。
 */
internal object AvatarDisplayColor {
    const val VERSION = "linear-matte-to-srgb-v1"

    private val srgb = IntArray(256) { channel ->
        val linear = channel / 255.0
        val encoded = if (linear <= .0031308) 12.92 * linear
            else 1.055 * linear.pow(1.0 / 2.4) - .055
        (encoded * 255.0).roundToInt().coerceIn(0, 255)
    }

    fun restorePixel(black: Int, white: Int, softenColors: Boolean): Int {
        val red = black ushr 16 and 255
        val green = black ushr 8 and 255
        val blue = black and 255
        val difference = (((white ushr 16 and 255) - red).coerceIn(0, 255) +
            ((white ushr 8 and 255) - green).coerceIn(0, 255) +
            ((white and 255) - blue).coerceIn(0, 255)) / 3
        val alpha = 255 - difference
        if (alpha == 0) return 0
        val displayRed = srgb[(red * 255.0 / alpha).roundToInt().coerceIn(0, 255)]
        val displayGreen = srgb[(green * 255.0 / alpha).roundToInt().coerceIn(0, 255)]
        val displayBlue = srgb[(blue * 255.0 / alpha).roundToInt().coerceIn(0, 255)]
        if (!softenColors) return alpha shl 24 or (displayRed shl 16) or (displayGreen shl 8) or displayBlue
        // 旧写实角色仍轻度柔化；在 sRGB 上处理，不再用 sqrt 重复提亮／近似 gamma。
        val luminance = displayRed * .2126f + displayGreen * .7152f + displayBlue * .0722f
        fun soften(channel: Int) = ((luminance + (channel - luminance) * .82f) * .90f + 25.5f)
            .roundToInt().coerceIn(0, 255)
        return alpha shl 24 or (soften(displayRed) shl 16) or (soften(displayGreen) shl 8) or soften(displayBlue)
    }
}
