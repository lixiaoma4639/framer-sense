package com.framer.sense.feature.camera.vlm.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 持有独立的冻结图片和模型输入文件，不保存相机 ImageProxy。 */
class SnapshotStore(private val context: Context) {
    private val root = File(context.cacheDir, "vlm-scenes").apply { mkdirs() }

    /** 保存已转正、已裁剪的图片。
     * @param bitmap 调用方持有的冻结图片，本方法不回收它。
     * @param zoom 拍摄该图片时的真实界面倍率。
     * @return 图片及模型缩略图的私有路径。
     */
    suspend fun save(bitmap: Bitmap, zoom: Float): SceneSnapshot = withContext(Dispatchers.IO) {
        val id = newVlmId()
        val dir = File(root, id).apply { mkdirs() }
        val display = File(dir, "scene.jpg")
        val model = File(dir, "input.jpg")
        try {
            display.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            val scale = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
            val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
            try { model.outputStream().use { check(small.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
            finally { if (small !== bitmap) small.recycle() }
            SceneSnapshot(id, bitmap.width, bitmap.height, zoom, display.path, model.path)
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        }
    }

    /** 从系统选择器导入固定测试图，并按 EXIF 转正。
     * @param uri 用户选择的图片 URI。
     * @return 用于真实 VLM 调用的快照，测试图片的初始倍率为 1。
     */
    suspend fun importImage(uri: Uri): SceneSnapshot = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取测试图片" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("图片解码失败")
        val orientation = context.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        try { save(upright, 1f) } finally { if (upright !== bitmap) upright.recycle(); bitmap.recycle() }
    }

    /** 清理指定过期快照。
     * @param scene 已不再被推理或界面使用的快照。
     */
    suspend fun delete(scene: SceneSnapshot) = withContext(Dispatchers.IO) {
        File(root, scene.id).deleteRecursively()
        Unit
    }
}
