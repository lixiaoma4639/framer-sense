package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 实际像素、文件及合成流程；替代渲染器只画竖直标记，不访问相机、网络或 GPU。 */
class PhotoOrientationPipelineTest {
    private fun sample(rotation: Int, width: Int = 160, height: Int = 120, target: String = "scan"): AnalysisSample {
        val orientation = PhotoOrientation(rotation, 0)
        val buffer = ByteArray(width * height * 3 / 2) { 128.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            buffer[y * width + x] = (64 + (if (x >= width / 2) 40 else 0) + (if (y >= height / 2) 80 else 0)).toByte()
        }
        return AnalysisSample(target, 200L + rotation, 200L + rotation,
            ScanCameraConfig(7, orientation.uprightDegrees(90), 1f, CropRect(), false, orientation = orientation),
            null, width, height, buffer)
    }

    @Test fun eachFrameRotatesPixelsBeforeSavingBothAspectRatiosIncludingReverseDirections() {
        listOf(160 to 120, 192 to 108).forEach { (width, height) ->
            // 各方向转正后的左上角分别来自原缓冲的左下、左上、右上、右下。
            val expected = listOf(149, 56, 103, 196)
            (0..3).forEach { rotation ->
                val bitmap = sample(rotation, width, height).bitmap()
                try {
                    assertEquals(if (rotation % 2 == 0) height else width, bitmap.width)
                    assertEquals(if (rotation % 2 == 0) width else height, bitmap.height)
                    assertEquals(expected[rotation].toDouble(), Color.red(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4)).toDouble(), 4.0)
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun mixedScanKeepsPerFrameOrientationAndRendererUsesTheSelectedPhotoDimensions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = PhotoSessionController(context)
        val renderedSizes = mutableListOf<Pair<Int, Int>>()
        val previews = PreviewStore(object : AvatarRenderer {
            override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview {
                renderedSizes += width to height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // 所有方向的模型输入中，人像标记都沿照片的纵轴站立。
                Canvas(bitmap).drawRect(width * .45f, height * .2f, width * .55f, height * .8f,
                    Paint().apply { color = Color.RED })
                return AvatarPreview(bitmap, CropRect(.45f, .2f, .55f, .8f), plan.avatar)
            }
        })
        try {
            val scan = sessions.start(100)
            sessions.configure(7)
            listOf(0, 1, 2, 3).forEach { rotation -> sessions.collect(sample(rotation, target = scan.id)) }
            sessions.stop(400)
            val frozen = sessions.finish()
            assertEquals(4, frozen.frames.size)
            frozen.frames.forEachIndexed { rotation, frame ->
                val scene = frame.scene
                assertEquals(PhotoOrientation(rotation, 0), scene.orientation)
                val display = requireNotNull(BitmapFactory.decodeFile(scene.imagePath))
                val model = requireNotNull(BitmapFactory.decodeFile(scene.modelImagePath))
                try {
                    assertEquals(scene.width, display.width)
                    assertEquals(scene.height, display.height)
                    assertEquals(display.width, model.width)
                    assertEquals(display.height, model.height)
                } finally { display.recycle(); model.recycle() }
                val plan = CompositionPlan("same-plan", "测试构图", ShotType.FULL, CropRect(), 1f,
                    AvatarPlacement(), "自然站立", "保留背景")
                val preview = previews.preview(scene, plan)
                assertEquals(scene.width to scene.height, renderedSizes.last())
                assertEquals(scene.width, preview.composite.width)
                assertEquals(scene.height, preview.composite.height)
                assertEquals(scene.width, preview.avatar.width)
                assertEquals(scene.height, preview.avatar.height)
                assertEquals(Color.RED, preview.composite.getPixel(scene.width / 2, scene.height / 4))
                assertEquals(Color.TRANSPARENT, preview.avatar.getPixel(scene.width / 4, scene.height / 2))
                preview.composite.recycle(); preview.avatar.recycle()
            }
        } finally { sessions.dispose() }
    }

    @Test fun exifImportKeepsItsOwnOrientationAndDoesNotUseCurrentPhoneDirection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SnapshotStore(context)
        val file = java.io.File(context.cacheDir, "orientation-${java.util.UUID.randomUUID()}.jpg")
        val raw = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        try {
            file.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            androidx.exifinterface.media.ExifInterface(file).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val imported = store.importImage(android.net.Uri.fromFile(file))
            try {
                assertEquals(80, imported.width); assertEquals(120, imported.height)
                assertEquals(PhotoOrientation(), imported.orientation)
            } finally { store.delete(imported) }
        } finally { raw.recycle(); file.delete() }
    }
}
