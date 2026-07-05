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
    }
}
