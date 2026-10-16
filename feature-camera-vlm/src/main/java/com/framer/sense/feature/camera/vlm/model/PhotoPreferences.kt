package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable

/** App 内取景偏好，不加入模型网关协议。 */
@Serializable
enum class CaptureAspectRatio {
    THREE_FOUR, NINE_SIXTEEN;
    fun ratio(landscape: Boolean): Float {
        val portrait = if (this == THREE_FOUR) 3f / 4f else 9f / 16f
        return if (landscape) 1f / portrait else portrait
    }
    fun centerCrop(width: Int, height: Int, landscape: Boolean): CropRect {
        return centerCropToAspect(width, height, ratio(landscape))
    }
}
enum class CameraMode { NORMAL, AR }

@Serializable
data class PhotoPreferences(
    val aspectRatio: CaptureAspectRatio = CaptureAspectRatio.THREE_FOUR,
    val avatar: AvatarId = AvatarId.ADULT_FEMALE,
    val ethnicity: AvatarEthnicity = AvatarEthnicity.WHITE,
    val showFace: Boolean = false,
    val instruction: String = "",
    val instructionFromRecommendation: Boolean = false
)

object CameraZoomPolicy {
    fun clamp(value: Float, min: Float, max: Float): Float =
        if (!value.isFinite()) min else value.coerceIn(min, max.coerceAtLeast(min))
    /** 常用快捷档位加设备实际最大倍率；档位不代表独立光学镜头。 */
    fun presets(min: Float, max: Float): List<Float> {
        require(min.isFinite() && max.isFinite() && min > 0f && max >= min)
        return listOf(1f, 2f, 3f, max).filter { it in min..max }.distinct().sorted()
    }

    fun lensRatio(focalLength: Float, sensorWidth: Float, sensorHeight: Float,
        referenceFocal: Float, referenceWidth: Float, referenceHeight: Float): Float? {
        val values = listOf(focalLength, sensorWidth, sensorHeight, referenceFocal, referenceWidth, referenceHeight)
        if (values.any { !it.isFinite() || it <= 0f }) return null
        val ratio = (focalLength / kotlin.math.hypot(sensorWidth, sensorHeight)) /
            (referenceFocal / kotlin.math.hypot(referenceWidth, referenceHeight))
        return ratio.takeIf { it.isFinite() && it > 0f }
    }
}

/** 多指触摸结束后不能变成对焦、拖动或落点点击。 */
class CameraGestureGate {
    private var multiple = false
    fun begin() { multiple = false }
    fun pointers(count: Int) { if (count > 1) multiple = true }
    fun canClick(): Boolean = !multiple
}

/** 预览、JPEG 保存与测试共用的归一化中心裁切。 */
fun centerCropToAspect(width: Int, height: Int, target: Float): CropRect {
    require(width > 0 && height > 0 && target.isFinite() && target > 0f)
    val source = width.toFloat() / height
    return if (source > target) {
        val span = target / source
        CropRect((1f - span) / 2f, 0f, (1f + span) / 2f, 1f)
    } else {
        val span = source / target
        CropRect(0f, (1f - span) / 2f, 1f, (1f + span) / 2f)
    }
}

data class CaptureViewportSize(val width: Float, val height: Float)
fun fitCaptureViewport(width: Float, height: Float, ratio: Float): CaptureViewportSize {
    require(width >= 0f && height >= 0f && ratio.isFinite() && ratio > 0f)
    return if (height * ratio <= width) CaptureViewportSize(height * ratio, height)
        else CaptureViewportSize(width, width / ratio)
}

/** 取景按宽度铺满，高度由比例决定，不因底部操作区缩窄。 */
fun widthCaptureViewport(width: Float, ratio: Float): CaptureViewportSize {
    require(width.isFinite() && width >= 0f && ratio.isFinite() && ratio > 0f)
    return CaptureViewportSize(width, width / ratio)
}
