package com.framer.sense.feature.camera.vlm.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.framer.sense.feature.camera.vlm.agent.PreviewInspector
import com.framer.sense.feature.camera.vlm.agent.PreviewInspection
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.math.roundToInt

/** 一张方案的背景合成、人偶透明层和真实投影范围。 */
data class PlanPreview(val composite: Bitmap, val avatar: Bitmap, val bounds: CropRect, val appliedPlan: CompositionPlan)

/** 缓存最近的离屏预览，Bitmap 交由持有它的界面与 GC 管理。 */
class PreviewStore(private val renderer: AvatarRenderer) : PreviewInspector {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, PlanPreview>()
    private val faces = LinkedHashMap<String, AvatarPreview>()

    suspend fun availableActions(role: AvatarId, shot: ShotType) = renderer.availableActions(role, shot)
    suspend fun availableExpressions(plan: CompositionPlan) = renderer.availableExpressions(plan)
    suspend fun selectAction(plan: CompositionPlan, id: String) = renderer.resolve(plan, id)

    /** 无冻结图片、无模型调用的制作样片入口，仍使用正式 Filament 执行链。 */
    suspend fun sample(plan: CompositionPlan): AvatarPreview = mutex.withLock {
        renderer.render(renderer.resolve(plan), 512, 768, 0f, .6f)
    }

    /** 脸部单独渲染，不放大构图缩略图；最多缓存两张示范。 */
    suspend fun face(plan: CompositionPlan): AvatarPreview? = mutex.withLock {
        val resolved = renderer.resolve(plan)
        val key = key("face", resolved, AvatarRenderPurpose.FACE)
        faces[key]?.let { return@withLock it }
        renderer.renderFace(resolved, 384)?.also {
            check(it.appliedSpec == null || it.appliedSpec.matches(resolved)) { "FACE_SPEC_MISMATCH" }
            faces[key] = it
            while (faces.size > 2) faces.remove(faces.keys.first())
        }
    }

    /** 按冻结图片和完整方案构建预览。
     * @param scene 已转正图片。
     * @param plan 裁剪和人物配置。
     * @return 可直接展示的合成及透明人偶。
     */
    suspend fun preview(scene: SceneSnapshot, plan: CompositionPlan): PlanPreview = mutex.withLock {
        val resolved = renderer.resolve(plan)
        // 图片方向和尺寸属于冻结来源，不取当前设备方向，也不能与旧方向的缓存混用。
        val sceneKey = "${scene.id}:${scene.width}x${scene.height}:${scene.orientation}"
        val key = key(sceneKey, resolved)
        cache[key]?.let {
            // 描述不影响渲染缓存，但必须跟随本次方案，不能被旧缓存方案覆盖。
            return@withLock it.copy(appliedPlan = it.appliedPlan.copy(sceneDescription = resolved.sceneDescription))
        }
        val background = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(scene.imagePath, info)
            while (maxOf(info.outWidth, info.outHeight) / options.inSampleSize > 1536) options.inSampleSize *= 2
            BitmapFactory.decodeFile(scene.imagePath, options) ?: error("冻结图片已丢失，请重新构图")
        }
        try {
            val ratio = minOf(1f, 768f / maxOf(scene.width, scene.height))
            val w = (scene.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (scene.height * ratio).roundToInt().coerceAtLeast(1)
            val colors = environment(background)
            val human = renderer.render(resolved, w, h, colors.first, colors.second)
            val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val crop = plan.crop
            val src = Rect((crop.left * background.width).roundToInt().coerceIn(0, background.width - 1), (crop.top * background.height).roundToInt().coerceIn(0, background.height - 1), (crop.right * background.width).roundToInt().coerceIn(1, background.width), (crop.bottom * background.height).roundToInt().coerceIn(1, background.height))
            check(src.width() > 0 && src.height() > 0) { "无效裁剪范围" }
            Canvas(composite).apply {
                drawBitmap(background, src, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                drawContactShadow(human.bounds, w, h, colors.second)
                drawBitmap(human.bitmap, 0f, 0f, null)
            }
            val adopted = resolved.withActualAvatar(human.appliedAvatar ?: resolved.avatar)
            val applied = adopted.copy(resolvedSpec = human.appliedSpec ?: adopted.resolvedSpec)
            check(applied.resolvedSpec?.matches(applied) == true) { "RESOLVED_SPEC_MISMATCH" }
            val result = PlanPreview(composite, human.bitmap, human.bounds, applied)
            cache[key] = result
            // 请求和实际采用值共享同一张图，避免提交校准结果后再渲染、再次缩小。
            cache[key(sceneKey, applied)] = result
            while (cache.size > 6) cache.remove(cache.keys.first())
            result
        } finally { background.recycle() }
    }

    /** 执行真实渲染并将几何边界作为工具观察。
     * @param plan 已通过协议校验的方案。
     * @param scene 冻结图片。
     * @return 投影范围及全身出画错误；不进行审美自评分。
     */
    override suspend fun inspect(plan: CompositionPlan, scene: SceneSnapshot): PreviewInspection {
        val result = preview(scene, plan)
        val b = result.bounds
        val applied = result.appliedPlan
        val originalFoot = PlanCoordinates.toOriginal(applied.avatar.foot, applied.crop)
        val errors = if (plan.shot in listOf(ShotType.ENVIRONMENT, ShotType.FULL) && (b.top < -.02f || b.bottom > 1.02f || b.left < -.02f || b.right > 1.02f)) listOf("全身/环境方案的实际人偶被画面裁掉，请缩小或移动人物") else emptyList()
        return PreviewInspection(b, listOf("原图脚底锚点=(${originalFoot.x},${originalFoot.y})"), errors, applied.avatar, applied.resolvedSpec)
    }

    private fun key(sceneId: String, plan: CompositionPlan, purpose: AvatarRenderPurpose = AvatarRenderPurpose.COMPOSITION): String =
        sceneId + VlmJson.encodeToString(plan) + ":" + plan.avatar.projectionCalibrated + ":" + purpose + ":" +
            plan.resolvedSpec?.action?.cacheIdentity() + ":" + plan.viewBinding + ":" + AvatarDisplayColor.VERSION

    /** 从稀疏像素估计色温倾向与亮度。
     * @param bitmap 原始相机背景，不包含虚拟人偶。
     * @return 暖色倾向(-1..1)和亮度(0..1)。
     */
    private fun environment(bitmap: Bitmap): Pair<Float, Float> {
        var red = 0f; var green = 0f; var blue = 0f
        for (y in 0 until 12) for (x in 0 until 12) {
            val pixel = bitmap.getPixel(x * bitmap.width / 12, y * bitmap.height / 12)
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
        }
        return ((red - blue) / (144f * 255f)).coerceIn(-1f, 1f) to ((red + green + blue) / (432f * 255f)).coerceIn(0f, 1f)
    }

    /** 在人偶脚底添加克制的软阴影，给冻结画面提供最低限度的接地线索。 */
    private fun Canvas.drawContactShadow(bounds: CropRect, width: Int, height: Int, brightness: Float) {
        if (bounds.bottom !in .02f..1.02f) return
        val centerX = (bounds.left + bounds.right) * width / 2f
        val footY = bounds.bottom * height
        val bodyWidth = (bounds.right - bounds.left).coerceAtLeast(.04f) * width
        val alpha = (66f - brightness.coerceIn(0f, 1f) * 22f).toInt()
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(alpha, 0, 0, 0)
            setShadowLayer(bodyWidth * .035f, 0f, bodyWidth * .018f, android.graphics.Color.argb(alpha / 2, 0, 0, 0))
        }
        drawOval(RectF(centerX - bodyWidth * .30f, footY - bodyWidth * .035f, centerX + bodyWidth * .30f, footY + bodyWidth * .055f), shadow)
    }
}
