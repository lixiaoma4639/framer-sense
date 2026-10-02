package com.framer.sense.feature.camera.vlm.avatar

import android.graphics.Bitmap
import com.framer.sense.feature.camera.vlm.model.*

/** 渲染结果包含可复用的透明人偶图与实际投影边界。 */
data class AvatarPreview(
    val bitmap: Bitmap,
    val bounds: CropRect,
    /** 真实渲染采用值；旧测试渲染器不回传时沿用请求。 */
    val appliedAvatar: AvatarPlacement? = null,
    val appliedSpec: ResolvedPhotoSpec? = null
)

interface AvatarRenderer {
    suspend fun availableActions(role: AvatarId, shot: ShotType): List<PhotoActionDefinition> =
        PhotoActionCatalog().candidates(role, shot).filter { it.selectable }

    suspend fun resolve(plan: CompositionPlan, selectedActionId: String? = null): CompositionPlan =
        PhotoActionResolver().resolve(plan, selectedActionId)

    suspend fun availableExpressions(plan: CompositionPlan): List<ExpressionId> = ExpressionId.entries

    /** 旧测试渲染器不支持脸部示范时返回空；生产 GLB 实现单独对准头部。 */
    suspend fun renderFace(plan: CompositionPlan, size: Int): AvatarPreview? = null
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
