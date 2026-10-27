package com.framer.sense.feature.camera.vlm

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.core.data.LocalCameraHistoryRepository
import com.framer.sense.core.database.CameraHistoryDatabase
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 真实 Room + 本地图片，不接触网络、相机、GPU 或生成模型。 */
class CameraHistoryStorageTest {
    @Test fun cacheCanBeRemovedAndRestoredFlowKeepsGeometryPromptStyleHistoryAndAppendIdentity() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "history-test-${newVlmId()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(base, CameraHistoryDatabase::class.java).build()
        val history = LocalCameraCompositionHistory(context, LocalCameraHistoryRepository(db.historyDao()))
        fun image(file: File, color: Int): File {
            file.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            return file
        }
        try {
            val source = image(File(sandbox, "cache/source.png"), Color.BLUE)
            val binding = PhotoViewBinding("scan", "frame", 7)
            val scene = SceneSnapshot("scene", 64, 80, 1f, source.path, source.path, binding, PhotoOrientation(1, 0))
            val oneId = newVlmId()
            val full = image(File(context.filesDir, "generated-references/$oneId/composition.png"), Color.BLUE)
            val request = GeneratedRequestSnapshot("qwen-id", "v1", "original exact prompt", mapOf("n" to "1"))
            val one = GeneratedCandidate(oneId, "batch", scene, QwenImageModel.V21_TURBO, "first title", 0, binding,
                status = GeneratedCandidateStatus.READY, completedAt = 10,
                assets = GeneratedReferenceAssets(full.path, full.path, 64, 80, "qwen-id", 1, requestSnapshot = request),
                styleChoice = GeneratedStyleChoice("wardrobe-a", "art-a"))
            val generated = GeneratedReferenceState(candidates = listOf(one), selectedId = one.id)
            val frame = ScanFrame("frame", "scan", 10, 11, ScanCameraConfig(7, 0, 1f, CropRect(), false), null, 1f, scene, source.length())
            val scan = ScanSnapshot(ScanSession("scan", 1, 20), listOf(frame), 15f)
            history.save(HistoryCapture(generated, scene, scan, emptyList(), CaptureAspectRatio.THREE_FOUR, false), one, "first title", "Qwen full name")
            val thumbnail = history.entries.first().single().thumbnail
            history.styles(generated.historyId, setOf("failed-attempt-style"))
            File(sandbox, "cache").deleteRecursively()
            val restored = history.load(generated.historyId)
            assertTrue(File(restored.generated.selected!!.scene.imagePath).isFile)
            assertEquals(binding, restored.generated.selected!!.scene.captureBinding)
            assertEquals(PhotoOrientation(1, 0), restored.generated.selected!!.scene.orientation)
            assertEquals("scan", restored.scan!!.session.id)
            assertEquals(request, restored.generated.selected!!.assets!!.requestSnapshot)
            assertTrue("failed-attempt-style" in restored.generated.usedStyleKeys)
            val secondId = newVlmId()
            val secondImage = image(File(context.filesDir, "generated-references/$secondId/composition.png"), Color.RED)
            val two = one.copy(id = secondId, scene = restored.generated.selected!!.scene, model = SeedreamModel.PRO,
                instruction = "second title", completedAt = 20, styleChoice = GeneratedStyleChoice("wardrobe-b", "art-b"),
                assets = one.assets!!.copy(compositePath = secondImage.path, portraitPath = secondImage.path))
            val appended = restored.generated.copy(candidates = restored.generated.candidates + two)
            history.save(HistoryCapture(appended, two.scene, restored.scan, restored.views, restored.aspectRatio, false),
                two, "second title", "Seed full name")
            val entry = history.entries.first().single()
            assertEquals(generated.historyId, entry.id)
            assertEquals("second title", entry.title)
            assertEquals("Seed full name", entry.modelName)
            assertEquals(thumbnail, entry.thumbnail)
            assertEquals(2, history.load(entry.id).generated.candidates.size)
            assertEquals("second title", history.load(entry.id).instruction)
            assertTrue("failed-attempt-style" in history.load(entry.id).generated.usedStyleKeys)
            secondImage.delete()
            assertTrue(runCatching { history.load(entry.id) }.isFailure)
            history.delete(entry.id)
            assertTrue(history.entries.first().isEmpty())
            assertFalse(full.exists())
            assertFalse(File(context.filesDir, "camera-history/${entry.id}").exists())
        } finally { history.flush(); db.close(); sandbox.deleteRecursively() }
    }
}
