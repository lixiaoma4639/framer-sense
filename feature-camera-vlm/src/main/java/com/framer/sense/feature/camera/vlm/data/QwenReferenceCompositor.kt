package com.framer.sense.feature.camera.vlm.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs

/** 仅验证透明像素与画幅，不将 Alpha 校验误称为人物识别或摄影质量评审。 */
internal object QwenReferenceCompositor {
    data class AlphaStats(val total: Long, val clear: Long, val visible: Long, val hasAlphaChannel: Boolean) {
        val usable: Boolean get() = hasAlphaChannel && clear >= total * .01 && visible >= total * .01
    }

    fun matchingAspect(background: Bitmap, foreground: Bitmap): Boolean =
        abs(foreground.width.toDouble() / foreground.height / (background.width.toDouble() / background.height) - 1) <= .015

    fun hasTransparentForeground(bitmap: Bitmap): Boolean = alphaStats(bitmap).usable

    fun alphaStats(bitmap: Bitmap): AlphaStats {
        val count = bitmap.width.toLong() * bitmap.height
        if (!bitmap.hasAlpha()) return AlphaStats(count, 0, count, false)
        var clear = 0L
        var visible = 0L
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEach { pixel ->
                val alpha = pixel ushr 24
                if (alpha <= 8) clear++
                if (alpha >= 128) visible++
            }
        }
        return AlphaStats(count, clear, visible, true)
    }

    /** 背景不修补、不重绘；原图已有的人物或前景遮挡无法在此消除。 */
    fun portrait(background: Bitmap, foreground: Bitmap): Bitmap = Bitmap.createBitmap(
        background.width, background.height, Bitmap.Config.ARGB_8888
    ).also { canvas ->
        Canvas(canvas).drawBitmap(foreground, null, Rect(0, 0, canvas.width, canvas.height), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    fun composite(background: Bitmap, portrait: Bitmap): Bitmap = background.copy(Bitmap.Config.ARGB_8888, true).also {
        Canvas(it).drawBitmap(portrait, 0f, 0f, null)
    }
}
