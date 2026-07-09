package com.framer.sense.feature.camera.pytorch.v2.ui

import androidx.camera.core.ImageProxy
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class FramePreprocessor {

    fun averageLuminance(imageProxy: ImageProxy): Double {
        val buffer = imageProxy.planes.firstOrNull()?.buffer?.duplicate() ?: return DEFAULT_LUMINANCE
        if (!buffer.hasRemaining()) return DEFAULT_LUMINANCE
        var sum = 0L
        var count = 0
        val step = maxOf(1, buffer.remaining() / LUMINANCE_SAMPLES)
        while (buffer.hasRemaining()) {
            sum += buffer.get().toInt() and 0xFF
            count++
            buffer.position((buffer.position() + step).coerceAtMost(buffer.limit()))
        }
        return if (count == 0) DEFAULT_LUMINANCE else sum.toDouble() / count
    }

    fun toRgbFloatBuffer(
        imageProxy: ImageProxy,
        width: Int,
        height: Int,
        channelFirst: Boolean,
        mean: FloatArray = floatArrayOf(0f, 0f, 0f),
        std: FloatArray = floatArrayOf(255f, 255f, 255f)
    ): FloatBuffer {
        val buffer = FloatBuffer.allocate(width * height * RGB_CHANNELS)
        val redValues = if (channelFirst) FloatArray(width * height) else null
        val greenValues = if (channelFirst) FloatArray(width * height) else null
        val blueValues = if (channelFirst) FloatArray(width * height) else null
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val rotatedX = x * rotatedWidth / width
                val rotatedY = y * rotatedHeight / height
                val source = mapRotatedPointToSource(
                    x = rotatedX,
                    y = rotatedY,
                    rotatedWidth = rotatedWidth,
                    rotatedHeight = rotatedHeight,
                    sourceWidth = imageProxy.width,
                    sourceHeight = imageProxy.height,
                    rotationDegrees = rotation
                )
                val rgb = imageProxy.readRgb(source.first, source.second)
                val index = y * width + x
                val red = (rgb.red - mean[0]) / std[0]
                val green = (rgb.green - mean[1]) / std[1]
                val blue = (rgb.blue - mean[2]) / std[2]
                if (channelFirst) {
                    redValues?.set(index, red)
                    greenValues?.set(index, green)
                    blueValues?.set(index, blue)
                } else {
                    buffer.put(red)
                    buffer.put(green)
                    buffer.put(blue)
                }
            }
        }

        if (channelFirst && redValues != null && greenValues != null && blueValues != null) {
            redValues.forEach(buffer::put)
            greenValues.forEach(buffer::put)
            blueValues.forEach(buffer::put)
        }
        buffer.rewind()
        return buffer
    }

    fun toYoloRgbFloatBuffer(
        imageProxy: ImageProxy,
        size: Int
    ): YoloInput {
        val buffer = FloatBuffer.allocate(size * size * RGB_CHANNELS)
        val redValues = FloatArray(size * size)
        val greenValues = FloatArray(size * size)
        val blueValues = FloatArray(size * size)
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val scale = minOf(size.toFloat() / rotatedWidth, size.toFloat() / rotatedHeight)
        val contentWidth = (rotatedWidth * scale).roundToInt().coerceIn(1, size)
        val contentHeight = (rotatedHeight * scale).roundToInt().coerceIn(1, size)
        val padLeft = (size - contentWidth) / 2f
        val padTop = (size - contentHeight) / 2f
        val transform = YoloInputTransform(
            inputSize = size,
            rotatedWidth = rotatedWidth,
            rotatedHeight = rotatedHeight,
            scale = scale,
            padLeft = padLeft,
            padTop = padTop
        )

        for (y in 0 until size) {
            for (x in 0 until size) {
                val index = y * size + x
                val insideContent =
                    x >= padLeft &&
                        x < padLeft + contentWidth &&
                        y >= padTop &&
                        y < padTop + contentHeight
                val rgb = if (insideContent) {
                    val rotatedX = ((x - padLeft) / scale).roundToInt().coerceIn(0, rotatedWidth - 1)
                    val rotatedY = ((y - padTop) / scale).roundToInt().coerceIn(0, rotatedHeight - 1)
                    val source = mapRotatedPointToSource(
                        x = rotatedX,
                        y = rotatedY,
                        rotatedWidth = rotatedWidth,
                        rotatedHeight = rotatedHeight,
                        sourceWidth = imageProxy.width,
                        sourceHeight = imageProxy.height,
                        rotationDegrees = rotation
                    )
                    imageProxy.readRgb(source.first, source.second)
                } else {
                    Rgb(red = YOLO_PADDING_RGB, green = YOLO_PADDING_RGB, blue = YOLO_PADDING_RGB)
                }
                redValues[index] = rgb.red / 255f
                greenValues[index] = rgb.green / 255f
                blueValues[index] = rgb.blue / 255f
            }
        }

        redValues.forEach(buffer::put)
        greenValues.forEach(buffer::put)
        blueValues.forEach(buffer::put)
        buffer.rewind()
        return YoloInput(buffer = buffer, transform = transform)
    }

    fun toWholeBodyRgbFloatBuffer(
        imageProxy: ImageProxy,
        bounds: V2Rect,
        width: Int,
        height: Int
    ): WholeBodyInput {
        val buffer = FloatBuffer.allocate(width * height * RGB_CHANNELS)
        val redValues = FloatArray(width * height)
        val greenValues = FloatArray(width * height)
        val blueValues = FloatArray(width * height)
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val crop = bounds
            .expand(WHOLE_BODY_CROP_PADDING)
            .fitAspect(width.toFloat() / height.toFloat())
        val transform = WholeBodyInputTransform(
            inputWidth = width,
            inputHeight = height,
            cropBounds = crop
        )

        for (y in 0 until height) {
            for (x in 0 until width) {
                val normalizedX = crop.left + ((x + 0.5f) / width) * crop.width
                val normalizedY = crop.top + ((y + 0.5f) / height) * crop.height
                val rotatedX = (normalizedX * rotatedWidth).roundToInt().coerceIn(0, rotatedWidth - 1)
                val rotatedY = (normalizedY * rotatedHeight).roundToInt().coerceIn(0, rotatedHeight - 1)
                val source = mapRotatedPointToSource(
                    x = rotatedX,
                    y = rotatedY,
                    rotatedWidth = rotatedWidth,
                    rotatedHeight = rotatedHeight,
                    sourceWidth = imageProxy.width,
                    sourceHeight = imageProxy.height,
                    rotationDegrees = rotation
                )
                val rgb = imageProxy.readRgb(source.first, source.second)
                val index = y * width + x
                redValues[index] = (rgb.red - WHOLE_BODY_MEAN[0]) / WHOLE_BODY_STD[0]
                greenValues[index] = (rgb.green - WHOLE_BODY_MEAN[1]) / WHOLE_BODY_STD[1]
                blueValues[index] = (rgb.blue - WHOLE_BODY_MEAN[2]) / WHOLE_BODY_STD[2]
            }
        }

        redValues.forEach(buffer::put)
        greenValues.forEach(buffer::put)
        blueValues.forEach(buffer::put)
        buffer.rewind()
        return WholeBodyInput(buffer = buffer, transform = transform)
    }

    private fun mapRotatedPointToSource(
        x: Int,
        y: Int,
        rotatedWidth: Int,
        rotatedHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> =
        when (rotationDegrees) {
            90 -> y.coerceIn(0, sourceWidth - 1) to (sourceHeight - 1 - x).coerceIn(0, sourceHeight - 1)
            180 -> (sourceWidth - 1 - x).coerceIn(0, sourceWidth - 1) to
                (sourceHeight - 1 - y).coerceIn(0, sourceHeight - 1)
            270 -> (sourceWidth - 1 - y).coerceIn(0, sourceWidth - 1) to x.coerceIn(0, sourceHeight - 1)
            else -> (x * sourceWidth / rotatedWidth).coerceIn(0, sourceWidth - 1) to
                (y * sourceHeight / rotatedHeight).coerceIn(0, sourceHeight - 1)
        }

    private fun ImageProxy.readRgb(x: Int, y: Int): Rgb {
        val yValue = planes[0].valueAt(x, y)
        val uValue = planes[1].valueAt(x / 2, y / 2)
        val vValue = planes[2].valueAt(x / 2, y / 2)
        val adjustedY = (yValue - 16).coerceAtLeast(0)
        val adjustedU = uValue - 128
        val adjustedV = vValue - 128
        return Rgb(
            red = (1.164f * adjustedY + 1.596f * adjustedV).roundToInt().coerceIn(0, 255),
            green = (1.164f * adjustedY - 0.392f * adjustedU - 0.813f * adjustedV).roundToInt().coerceIn(0, 255),
            blue = (1.164f * adjustedY + 2.017f * adjustedU).roundToInt().coerceIn(0, 255)
        )
    }

    private fun ImageProxy.PlaneProxy.valueAt(x: Int, y: Int): Int {
        val duplicate = buffer.duplicate()
        val index = y * rowStride + x * pixelStride
        if (index !in 0 until duplicate.limit()) return 128
        return duplicate.get(index).toInt() and 0xFF
    }

    private data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int
    )

    private companion object {
        const val RGB_CHANNELS = 3
        const val LUMINANCE_SAMPLES = 900
        const val DEFAULT_LUMINANCE = 128.0
        const val YOLO_PADDING_RGB = 114
        const val WHOLE_BODY_CROP_PADDING = 0.08f
        val WHOLE_BODY_MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
        val WHOLE_BODY_STD = floatArrayOf(58.395f, 57.12f, 57.375f)
    }
}

data class YoloInput(
    val buffer: FloatBuffer,
    val transform: YoloInputTransform
)

data class YoloInputTransform(
    val inputSize: Int,
    val rotatedWidth: Int,
    val rotatedHeight: Int,
    val scale: Float,
    val padLeft: Float,
    val padTop: Float
) {
    fun xywhToNormalizedRect(cx: Float, cy: Float, width: Float, height: Float): V2Rect {
        val normalizedWidth = width.toModelPixels() / scale / rotatedWidth
        val normalizedHeight = height.toModelPixels() / scale / rotatedHeight
        val centerX = (cx.toModelPixels() - padLeft) / scale / rotatedWidth
        val centerY = (cy.toModelPixels() - padTop) / scale / rotatedHeight
        return V2Rect(
            left = centerX - normalizedWidth / 2f,
            top = centerY - normalizedHeight / 2f,
            right = centerX + normalizedWidth / 2f,
            bottom = centerY + normalizedHeight / 2f
        ).clamped()
    }

    fun xyxyToNormalizedRect(left: Float, top: Float, right: Float, bottom: Float): V2Rect {
        val normalizedLeft = (left.toModelPixels() - padLeft) / scale / rotatedWidth
        val normalizedTop = (top.toModelPixels() - padTop) / scale / rotatedHeight
        val normalizedRight = (right.toModelPixels() - padLeft) / scale / rotatedWidth
        val normalizedBottom = (bottom.toModelPixels() - padTop) / scale / rotatedHeight
        return V2Rect(
            left = normalizedLeft,
            top = normalizedTop,
            right = normalizedRight,
            bottom = normalizedBottom
        ).clamped()
    }

    fun pointToNormalized(point: V2Point): V2Point =
        V2Point(
            x = ((point.x.toModelPixels() - padLeft) / scale / rotatedWidth).coerceIn(0f, 1f),
            y = ((point.y.toModelPixels() - padTop) / scale / rotatedHeight).coerceIn(0f, 1f)
        )

    private fun Float.toModelPixels(): Float =
        if (this <= 1f) this * inputSize else this
}

data class WholeBodyInput(
    val buffer: FloatBuffer,
    val transform: WholeBodyInputTransform
)

data class WholeBodyInputTransform(
    val inputWidth: Int,
    val inputHeight: Int,
    val cropBounds: V2Rect
) {
    fun pointToNormalized(point: V2Point): V2Point =
        V2Point(
            x = (cropBounds.left + (point.x / inputWidth) * cropBounds.width).coerceIn(0f, 1f),
            y = (cropBounds.top + (point.y / inputHeight) * cropBounds.height).coerceIn(0f, 1f)
        )
}

private fun V2Rect.expand(padding: Float): V2Rect =
    V2Rect(left - padding, top - padding, right + padding, bottom + padding).clamped()

private fun V2Rect.fitAspect(targetAspect: Float): V2Rect {
    val safeWidth = width.coerceAtLeast(0.001f)
    val safeHeight = height.coerceAtLeast(0.001f)
    val currentAspect = safeWidth / safeHeight
    val adjustedWidth: Float
    val adjustedHeight: Float
    if (currentAspect > targetAspect) {
        adjustedWidth = safeWidth
        adjustedHeight = safeWidth / targetAspect
    } else {
        adjustedHeight = safeHeight
        adjustedWidth = safeHeight * targetAspect
    }
    val finalWidth = adjustedWidth.coerceAtMost(1f)
    val finalHeight = adjustedHeight.coerceAtMost(1f)
    val adjustedLeft = (centerX - finalWidth / 2f).coerceIn(0f, 1f - finalWidth)
    val adjustedTop = (centerY - finalHeight / 2f).coerceIn(0f, 1f - finalHeight)
    return V2Rect(
        left = adjustedLeft,
        top = adjustedTop,
        right = adjustedLeft + finalWidth,
        bottom = adjustedTop + finalHeight
    )
}
