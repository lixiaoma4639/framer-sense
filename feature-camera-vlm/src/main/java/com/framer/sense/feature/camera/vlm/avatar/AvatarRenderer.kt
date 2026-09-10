package com.framer.sense.feature.camera.vlm.avatar

import android.graphics.Bitmap
import com.framer.sense.feature.camera.vlm.model.*

/** 渲染结果包含可复用的透明人偶图与实际投影边界。 */
data class AvatarPreview(val bitmap: Bitmap, val bounds: CropRect)

interface AvatarRenderer {
    /** 在独立透明背景中渲染人偶。
     * @param plan 包含人偶姿态及布局的方案。
     * @param width 输出图片宽度，像素。
     * @param height 输出图片高度，像素。
     * @param warmth 从冻结图片估计的暖色偏移（-1..1）。
     * @param brightness 从冻结图片估计的亮度（0..1）。
     * @return 透明人偶图片；调用方管理 Bitmap 生命周期。
     */
    suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview
}
