package com.framer.sense.feature.camera.vlm.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.Closeable

internal fun validSeedreamBounds(bounds: List<Int>?, limit: Int = Int.MAX_VALUE): Boolean =
    bounds != null && bounds.size == 4 && bounds.all { it in 0..limit } &&
        bounds[0] < bounds[2] && bounds[1] < bounds[3]

internal data class SeedreamLayerPosition(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** absolute 属于未降采样的输出底图坐标系；缩小画布时必须同步缩放。 */
internal fun SeedreamLayer.position(canvasWidth: Int, canvasHeight: Int, sourceWidth: Int, sourceHeight: Int): SeedreamLayerPosition {
    if (canvasWidth <= 0 || canvasHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0)
        throw GeneratedReferenceFailure("BOUNDS")
    val absolute = absoluteBounds?.takeIf { validSeedreamBounds(it) }
    if (absolute != null) {
        if (absolute[2] > sourceWidth || absolute[3] > sourceHeight) throw GeneratedReferenceFailure("BOUNDS")
        return SeedreamLayerPosition(absolute[0].toFloat() / sourceWidth * canvasWidth,
            absolute[1].toFloat() / sourceHeight * canvasHeight,
            absolute[2].toFloat() / sourceWidth * canvasWidth,
            absolute[3].toFloat() / sourceHeight * canvasHeight)
    }
    val normalized = bounds?.takeIf { validSeedreamBounds(it, 1000) } ?: throw GeneratedReferenceFailure("BOUNDS")
    return SeedreamLayerPosition(normalized[0] / 1000f * canvasWidth, normalized[1] / 1000f * canvasHeight,
        normalized[2] / 1000f * canvasWidth, normalized[3] / 1000f * canvasHeight)
}

/** 全部图层的合成不依赖人物筛选；人物画布仅用于可选的相机叠加。 */
internal class SeedreamLayerCompositor(base: Bitmap, private val sourceWidth: Int, private val sourceHeight: Int,
                                      private val personLayers: Set<SeedreamLayer>) : Closeable {
    val composite: Bitmap = base.copy(Bitmap.Config.ARGB_8888, true) ?: throw GeneratedReferenceFailure("IMAGE")
    val portrait: Bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
    private val fullCanvas = Canvas(composite)
    private val personCanvas = Canvas(portrait)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var portraitAlphaCompatible = true

    fun draw(layer: SeedreamLayer, image: Bitmap): SeedreamLayerPosition {
        val position = layer.position(composite.width, composite.height, sourceWidth, sourceHeight)
        val rect = RectF(position.left, position.top, position.right, position.bottom)
        fullCanvas.drawBitmap(image, null, rect, paint)
        if (layer in personLayers) {
            portraitAlphaCompatible = portraitAlphaCompatible && image.hasAlpha()
            personCanvas.drawBitmap(image, null, rect, paint)
        }
        return position
    }

    fun hasPortrait(): Boolean {
        if (personLayers.isEmpty() || !portraitAlphaCompatible) return false
        var visible = false
        var transparent = false
        for (y in 0 until portrait.height step maxOf(1, portrait.height / 100))
            for (x in 0 until portrait.width step maxOf(1, portrait.width / 100)) {
                val alpha = portrait.getPixel(x, y) ushr 24
                visible = visible || alpha > 0
                transparent = transparent || alpha == 0
            }
        return visible && transparent
    }

    override fun close() {
        composite.recycle()
        portrait.recycle()
    }
}
