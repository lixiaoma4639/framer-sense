package com.framer.sense.feature.camera.vlm.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
data class PlanPreview(val composite: Bitmap, val avatar: Bitmap, val bounds: CropRect)

/** 缓存最近的离屏预览，Bitmap 交由持有它的界面与 GC 管理。 */
class PreviewStore(private val renderer: AvatarRenderer) : PreviewInspector {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, PlanPreview>()

    /** 按冻结图片和完整方案构建预览。
     * @param scene 已转正图片。
     * @param plan 裁剪和人物配置。
     * @return 可直接展示的合成及透明人偶。
     */
    suspend fun preview(scene: SceneSnapshot, plan: CompositionPlan): PlanPreview = mutex.withLock {
        val key = scene.id + VlmJson.encodeToString(plan)
        cache[key]?.let { return@withLock it }
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
            val human = renderer.render(plan, w, h, colors.first, colors.second)
            val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val crop = plan.crop
            val src = Rect((crop.left * background.width).roundToInt().coerceIn(0, background.width - 1), (crop.top * background.height).roundToInt().coerceIn(0, background.height - 1), (crop.right * background.width).roundToInt().coerceIn(1, background.width), (crop.bottom * background.height).roundToInt().coerceIn(1, background.height))
            check(src.width() > 0 && src.height() > 0) { "无效裁剪范围" }
            Canvas(composite).apply {
                drawBitmap(background, src, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                drawBitmap(human.bitmap, 0f, 0f, null)
            }
            val result = PlanPreview(composite, human.bitmap, human.bounds)
            cache[key] = result
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
        val originalFoot = PlanCoordinates.toOriginal(plan.avatar.foot, plan.crop)
        val errors = if (plan.shot in listOf(ShotType.ENVIRONMENT, ShotType.FULL) && (b.top < -.02f || b.bottom > 1.02f || b.left < -.02f || b.right > 1.02f)) listOf("全身/环境方案的实际人偶被画面裁掉，请缩小或移动人物") else emptyList()
        return PreviewInspection(b, listOf("原图脚底锚点=(${originalFoot.x},${originalFoot.y})"), errors)
    }

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
}
