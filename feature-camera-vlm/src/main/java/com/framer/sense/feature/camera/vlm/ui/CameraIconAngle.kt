package com.framer.sense.feature.camera.vlm.ui

import kotlin.math.abs

/** 将设备方向归为四档；控件和采集共用相同档位，避免各自跨档的时机不同。 */
internal class CameraIconAngle(private val pageRotation: Int) {
    private var deviceRotation = pageRotation
    var degrees: Float = 0f
        private set

    fun update(deviceOrientation: Int): Float {
        // 平放时 Android 返回 -1；保持原方向，避免突然回正。
        if (deviceOrientation !in 0..359) return degrees
        // 每个方向占 90°，在 45° 边界外增加 10° 回差，避免临界角度来回跳向。
        val center = ((4 - deviceRotation) % 4) * 90f
        if (abs(shortestIconRotation(center, deviceOrientation.toFloat())) <= 55f) return degrees
        deviceRotation = (4 - ((deviceOrientation + 45) / 90) % 4) % 4
        degrees = ((deviceRotation - pageRotation + 4) % 4) * 90f
        return degrees
    }
}

/** 跨越 0°/360° 时也只走最短路径，不让图标反向绕一整圈。 */
internal fun shortestIconRotation(from: Float, to: Float): Float =
    ((to - from) % 360f + 540f) % 360f - 180f
