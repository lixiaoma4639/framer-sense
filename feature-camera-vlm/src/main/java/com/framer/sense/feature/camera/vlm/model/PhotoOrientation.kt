package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable

/** Surface 四档方向值；页面方向与拍摄方向独立，照片像素在写入模型前已转正。 */
@Serializable
data class PhotoOrientation(val deviceRotation: Int = 0, val previewRotation: Int = 0) {
    init { require(deviceRotation in 0..3 && previewRotation in 0..3) }

    /** 转正照片回到固定取景坐标所需的顺时针角度。 */
    val previewDegrees: Int get() = ((deviceRotation - previewRotation + 4) % 4) * 90

    /** 相机缓冲原来针对窗口的旋转量，改为针对这一帧的握持方向；只适用于后置相机。 */
    fun uprightDegrees(windowImageDegrees: Int): Int = (windowImageDegrees - previewDegrees + 360) % 360

    fun toPreview(point: Point2): Point2 = when (previewDegrees) {
        90 -> Point2(1f - point.y, point.x)
        180 -> Point2(1f - point.x, 1f - point.y)
        270 -> Point2(point.y, 1f - point.x)
        else -> point
    }
}

/** 有界方向历史：分析回调可能晚于方向改变，可靠的相机时间戳应使用曝光时的方向。 */
class PhotoOrientationHistory {
    private val readings = ArrayDeque<Pair<Long, PhotoOrientation>>()

    @Synchronized fun record(atNs: Long, orientation: PhotoOrientation) {
        if (readings.lastOrNull()?.second == orientation) return
        readings.addLast(atNs to orientation)
        while (readings.size > 32) readings.removeFirst()
    }

    @Synchronized fun at(atNs: Long): PhotoOrientation =
        readings.lastOrNull { it.first <= atNs }?.second ?: readings.firstOrNull()?.second ?: PhotoOrientation()
}
