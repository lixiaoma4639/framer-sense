package com.framer.sense.feature.camera.pytorch.v2.ui

/**
 * 将已按目标屏幕方向旋转的相机帧坐标映射到 [PreviewView.ScaleType.FILL_CENTER] 的视口坐标。
 *
 * ONNX 输出使用相机帧的归一化坐标；PreviewView 在横竖屏比例不一致时会居中裁切，因此
 * 覆盖层必须使用同一套裁切规则，才能与预览中的人物和物体保持对齐。
 */
internal data class CameraV2PreviewTransform(
    val frameAspectRatio: Float,
    val viewportWidth: Float,
    val viewportHeight: Float
) {
    private val safeFrameAspectRatio = frameAspectRatio.takeIf { it > 0f } ?: DEFAULT_FRAME_ASPECT_RATIO
    private val safeViewportWidth = viewportWidth.coerceAtLeast(1f)
    private val safeViewportHeight = viewportHeight.coerceAtLeast(1f)
    private val scale = maxOf(
        safeViewportWidth,
        safeViewportHeight * safeFrameAspectRatio
    )
    private val renderedWidth = scale
    private val renderedHeight = scale / safeFrameAspectRatio
    private val offsetX = (safeViewportWidth - renderedWidth) / 2f
    private val offsetY = (safeViewportHeight - renderedHeight) / 2f

    fun map(point: V2Point): V2Point =
        V2Point(
            x = (offsetX + point.x * renderedWidth) / safeViewportWidth,
            y = (offsetY + point.y * renderedHeight) / safeViewportHeight
        )

    fun mapFrameWidth(radius: Float): Float = radius * renderedWidth

    companion object {
        const val DEFAULT_FRAME_ASPECT_RATIO = 9f / 16f
    }
}
