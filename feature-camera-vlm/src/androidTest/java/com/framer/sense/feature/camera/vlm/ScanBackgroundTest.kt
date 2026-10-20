package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import java.io.File
import java.util.UUID

/** 固定种子的纹理验证 ORB 几何；不访问相机、上游模型或 GPU 人偶。 */
class ScanBackgroundTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oversizedAvatarDoesNotEraseReferenceFeaturesOrPreventAutomaticAlignment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texture = fixture(12)
        val bitmap = Bitmap.createBitmap(texture.width, texture.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(texture, 0f, 0f, Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        })
        texture.recycle()
        val file = File(context.cacheDir, "background-mask-${UUID.randomUUID()}.jpg")
        val sessions = PhotoSessionController(context)
        try {
            val tools = OpenCvSceneTools()
            // 旧公式在这个近景布局下覆盖整张图，实际 ORB 参考点会变为零。
            assertEquals(0, requireNotNull(tools.extract(bitmap, CropRect())).points.size)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            val plan = CompositionPlan("p3", "近景", ShotType.CLOSE_UP, CropRect(), 1f,
                AvatarPlacement(height = 2.8f, foot = Point2(.5f, 1.4f)), "近景站姿", "保留背景",
                viewBinding = PhotoViewBinding("scan", "frame", 7))
            assertTrue(requireNotNull(tools.extract(bitmap)).points.size >= BackgroundMatchPolicy.MIN_MATCHES)
            val scene = SceneSnapshot("frame", bitmap.width, bitmap.height, 1f, file.path, file.path)
            val config = ScanCameraConfig(7, 0, 1f, CropRect(), false)
            sessions.configure(7)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val nv21 = ByteArray(pixels.size * 3 / 2) { 128.toByte() }
            pixels.forEachIndexed { index, pixel -> nv21[index] = (16 + (pixel and 255) * 219 / 255).toByte() }
            repeat(4) { index ->
                val time = 100L + index * 200_000_000L
                val sample = AnalysisSample("guide:frame:p3:0", time, time, config, null, bitmap.width, bitmap.height, nv21)
                val guidance = sessions.align(sample, plan, scene, null)
                assertTrue(guidance.matched)
                assertEquals(if (index == 3) BackgroundStatus.ALIGNED_AUTO else BackgroundStatus.SEARCHING, guidance.status)
            }
            // 同一背景／裁剪只改人像大小和站位，不应更换匹配特征或重置稳定结果。
            for ((index, avatar) in listOf(
                AvatarPlacement(height = .23f, foot = Point2(.3f, .92f)),
                AvatarPlacement(height = 1f, foot = Point2(.6598766f, 1.4026855f)),
                AvatarPlacement(height = 3f, foot = Point2(.7f, 1.8f))
            ).withIndex()) {
                val time = 800_000_100L + index * 200_000_000L
                val sample = AnalysisSample("guide:frame:p3:0", time, time, config, null, bitmap.width, bitmap.height, nv21)
                assertEquals(BackgroundStatus.ALIGNED_AUTO, sessions.align(sample, plan.copy(avatar = avatar), scene, null).status)
            }
            sessions.resetReference()
            val newReference = AnalysisSample("guide:frame:p3:0", 1_400_000_100L, 1_400_000_100L,
                config, null, bitmap.width, bitmap.height, nv21)
            assertEquals(BackgroundStatus.SEARCHING, sessions.align(newReference, plan, scene, null).status)
        } finally {
            sessions.dispose()
            bitmap.recycle()
            file.delete()
        }
    }

    @Test fun backgroundDetailsInsideTheVirtualAvatarAreaRemainAvailableForMatching() {
        val bitmap = fixture(12)
        try {
            // 背景纹理恰好集中在用户日志中的旧人像排除区域内。
            val canvas = Canvas(bitmap)
            val gray = Paint().apply { color = Color.LTGRAY }
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height * .44f, gray)
            canvas.drawRect(0f, bitmap.height * .95f, bitmap.width.toFloat(), bitmap.height.toFloat(), gray)
            canvas.drawRect(0f, 0f, bitmap.width * .44f, bitmap.height.toFloat(), gray)
            canvas.drawRect(bitmap.width * .88f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), gray)
            val tools = OpenCvSceneTools()
            val masked = requireNotNull(tools.extract(bitmap, CropRect(.40987659f, .40268552f, .9098766f, 1f)))
            val raw = requireNotNull(tools.extract(bitmap))
            assertTrue(raw.points.size > masked.points.size)
            assertFalse(tools.match(masked, raw)?.let(BackgroundMatchPolicy::alignedForGuidance) == true)
            val recovered = requireNotNull(tools.match(raw, raw))
            assertTrue(BackgroundMatchPolicy.alignedForGuidance(recovered))
            assertTrue(recovered.inliers >= BackgroundMatchPolicy.MIN_INLIERS)
        } finally { bitmap.recycle() }
    }

    @Test fun applyingShowsAlignmentAndCompositionToggleSwitchesBetweenTheTwoModules() {
        val plan = CompositionPlan("p", "背景", ShotType.FULL, CropRect(), 1f, AvatarPlacement(), "自然站立", "保留建筑",
            viewBinding = PhotoViewBinding("scan", "frame", 1))
        val state = mutableStateOf(VlmUiState(stage = VlmStage.LIVE, referencePlan = plan,
            selectedId = plan.id, result = CompositionResult(listOf(plan), emptyList(), emptyList(), emptyList())))
        val onEvent: (VlmIntent) -> Unit = { event ->
            state.value = when (event) {
                is VlmIntent.CompositionDetailsVisible -> state.value.copy(compositionDetailsVisible = event.visible, compositionDetailsExpanded = false)
                is VlmIntent.CompositionDetailsExpanded -> state.value.copy(compositionDetailsExpanded = event.expanded)
                VlmIntent.Apply -> state.value.copy(compositionDetailsVisible = false, compositionDetailsExpanded = false)
                else -> state.value
            }
        }
        compose.setContent { MaterialTheme {
            Box(Modifier.height(620.dp)) {
                Controls(state.value, onEvent, {}, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                BackgroundAlignmentOverlay(state.value, onEvent)
            }
        } }
        compose.onNodeWithTag("vlm_background_status").assertExists()
        compose.onNodeWithTag("vlm_background_confirm").assertExists()
        compose.onNodeWithTag("vlm_details").performClick()
        compose.onNodeWithTag("vlm_composition_choices").assertExists()
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_composition_info_toggle").performClick()
        compose.onNodeWithTag("vlm_composition_details_panel").assertExists()
        compose.onNodeWithTag("vlm_background_status").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_confirm").assertDoesNotExist()
        compose.onNodeWithTag("vlm_apply").performClick()
        compose.onNodeWithTag("vlm_composition_choices").assertDoesNotExist()
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_status").assertExists()
        compose.onNodeWithTag("vlm_background_confirm").assertExists()
        compose.onNodeWithTag("vlm_details").performClick()
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_composition_info_toggle").assertExists()
        compose.onNodeWithTag("vlm_background_status").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_confirm").assertDoesNotExist()
        compose.onNodeWithTag("vlm_details").performClick()
        compose.onNodeWithTag("vlm_composition_choices").assertDoesNotExist()
        compose.onNodeWithTag("vlm_composition_details_panel").assertDoesNotExist()
        compose.onNodeWithTag("vlm_background_status").assertExists()
        compose.onNodeWithTag("vlm_background_confirm").assertExists()
    }
    private fun fixture(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.LTGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG); val random = Random(seed)
        repeat(350) {
            paint.color = Color.rgb(random.nextInt(20, 235), random.nextInt(20, 235), random.nextInt(20, 235))
            canvas.drawCircle(random.nextFloat() * 640, random.nextFloat() * 480, random.nextFloat() * 10 + 3, paint)
        }
        return bitmap
    }
    @Test fun unselectedScanImageIsShownWithoutProvisionalLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "scan-preview-${UUID.randomUUID()}.jpg")
        val bitmap = fixture(12)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            val scene = SceneSnapshot("frame", 640, 480, 1f, file.path, file.path)
            compose.setContent { MaterialTheme { FrozenSceneImage(scene) } }
            compose.onNodeWithTag("vlm_frozen_scene").assertExists()
            compose.onNodeWithTag("vlm_scan_preview_unselected").assertDoesNotExist()
        } finally { bitmap.recycle(); file.delete() }
    }
    @Test fun identicalBackgroundMatchesButWrongSceneAndDisplacementDoNotAlign() {
        val tools = OpenCvSceneTools(); val reference = fixture(12); val wrong = fixture(79)
        val shifted = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val nearby = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(shifted).drawBitmap(reference, Matrix().apply { postTranslate(80f, 0f) }, null)
        Canvas(nearby).drawBitmap(reference, Matrix().apply { postTranslate(32f, 16f) }, null)
        try {
            val features = requireNotNull(tools.extract(reference))
            val exact = requireNotNull(tools.match(features, tools.extract(reference)))
            assertTrue(BackgroundMatchPolicy.aligned(exact))
            assertTrue(BackgroundMatchPolicy.alignedForGuidance(exact))
            val handheldOffset = requireNotNull(tools.match(features, tools.extract(nearby)))
            assertTrue(BackgroundMatchPolicy.alignedForGuidance(handheldOffset))
            assertFalse(BackgroundMatchPolicy.aligned(handheldOffset))
            val wrongScene = tools.match(features, tools.extract(wrong))
            assertFalse(wrongScene?.let(BackgroundMatchPolicy::confident) == true)
            assertFalse(wrongScene?.let(BackgroundMatchPolicy::alignedForGuidance) == true)
            val displaced = requireNotNull(tools.match(features, tools.extract(shifted)))
            assertTrue(BackgroundMatchPolicy.confident(displaced))
            assertFalse(BackgroundMatchPolicy.aligned(displaced))
            assertFalse(BackgroundMatchPolicy.alignedForGuidance(displaced))
        } finally { reference.recycle(); wrong.recycle(); shifted.recycle(); nearby.recycle() }
    }
    @Test fun plainTextureIsNotDeclaredBlurredAndCannotAutoConfirm() {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        try {
            val tools = OpenCvSceneTools(); val features = requireNotNull(tools.extract(bitmap))
            assertFalse(features.unusable); assertNull(tools.match(features, features))
            assertEquals("INSUFFICIENT_FEATURES", tools.lastMatchRejection)
        } finally { bitmap.recycle() }
    }
    @Test fun scanButtonsEmitExplicitEventsAndHideOtherActions() {
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            Controls(VlmUiState(stage = VlmStage.SCANNING, scanSession = ScanSession("scan", 1)), events::add, {}, Modifier)
        } }
        compose.onNodeWithTag("vlm_scan_start").assertDoesNotExist()
        compose.onNodeWithTag("vlm_start").assertDoesNotExist()
        val tags = listOf("vlm_scan_restart_active", "vlm_scan_prefer", "vlm_scan_stop", "vlm_scan_cancel")
        val bounds = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEach { (left, right) ->
            assertEquals(left.top, right.top)
            assertTrue(left.right <= right.left)
        }
        compose.onNodeWithTag("vlm_scan_stop").assertTextContains("完成扫描")
        tags.forEach { compose.onNodeWithTag(it).performScrollTo().performClick() }
        assertEquals(listOf(VlmIntent.StartScan, VlmIntent.PreferScanView, VlmIntent.StopScan, VlmIntent.CancelScan), events)
    }
    @Test fun resourcePauseDisablesPreferenceButStillAllowsExplicitEnd() {
        compose.setContent { MaterialTheme {
            Controls(VlmUiState(stage = VlmStage.SCANNING, scanSession = ScanSession("scan", 1),
                scanProgress = ScanProgress(paused = true)), {}, {}, Modifier)
        } }
        compose.onNodeWithTag("vlm_scan_prefer").assertIsNotEnabled()
        compose.onNodeWithTag("vlm_scan_stop").assertIsEnabled()
    }
    @Test fun manualBackgroundConfirmationHasItsOwnEvent() {
        val events = mutableListOf<VlmIntent>()
        val plan = CompositionPlan("p", "山体", ShotType.ENVIRONMENT, CropRect(), 1f,
            AvatarPlacement(), "保留背景", "站立", viewBinding = PhotoViewBinding("scan", "f", 1))
        compose.setContent { MaterialTheme {
            Box(Modifier.size(320.dp, 480.dp)) {
                Controls(VlmUiState(referencePlan = plan), events::add, {}, Modifier.align(Alignment.BottomCenter))
                BackgroundAlignmentOverlay(VlmUiState(referencePlan = plan), events::add)
            }
        } }
        compose.onNodeWithTag("vlm_background_confirm").performClick()
        assertEquals(listOf(VlmIntent.ConfirmBackground), events)
        compose.onNodeWithTag("vlm_reference_guidance").assertDoesNotExist()
    }
    @Test fun alternativeBackgroundEmitsItsBoundFrameId() {
        val events = mutableListOf<VlmIntent>()
        val config = ScanCameraConfig(1, 0, 1f, CropRect(), false)
        fun frame(id: String) = ScanFrame(id, "s", 0, 0, config, null, 1f,
            SceneSnapshot(id, 640, 480, 1f, "/missing", "/missing"), 1)
        val snapshot = ScanSnapshot(ScanSession("s", 0, 1), listOf(frame("main"), frame("alternative")), 0f)
        compose.setContent { MaterialTheme {
            Controls(VlmUiState(stage = VlmStage.READY, scanSnapshot = snapshot, selectedFrameId = "main", manualScanSelectionVisible = true,
                viewCandidates = listOf(ViewCandidate("main", "主背景", "山体", emptyList(), emptyList(), emptyList()),
                    ViewCandidate("alternative", "备选背景", "树林", emptyList(), emptyList(), emptyList()))), events::add, {}, Modifier)
        } }
        compose.onNodeWithTag("vlm_scan_view_alternative").performClick()
        assertEquals(listOf(VlmIntent.ScanViewSelected("alternative")), events)
    }

    @Test fun agentResultShowsChosenBackgroundWithoutFramePicker() {
        val config = ScanCameraConfig(1, 0, 1f, CropRect(), false)
        fun frame(id: String) = ScanFrame(id, "s", 0, 0, config, null, 1f,
            SceneSnapshot(id, 640, 480, 1f, "/missing", "/missing"), 1)
        val snapshot = ScanSnapshot(ScanSession("s", 0, 1), listOf(frame("main"), frame("alternative")), 0f)
        val candidate = ViewCandidate("main", "山体", "保留山体轮廓", emptyList(), emptyList(), emptyList())
        compose.setContent { MaterialTheme {
            Controls(VlmUiState(stage = VlmStage.READY, scanSnapshot = snapshot, selectedFrameId = "main",
                viewCandidates = listOf(candidate)), {}, {}, Modifier)
        } }
        compose.onNodeWithTag("vlm_scan_agent_background").assertExists()
        compose.onNodeWithTag("vlm_scan_view_main").assertDoesNotExist()
        compose.onNodeWithTag("vlm_scan_change_background").assertExists()
    }
    @Test fun manualFallbackDoesNotForceFrameChoiceAndCanBeOpenedExplicitly() {
        val events = mutableListOf<VlmIntent>()
        val config = ScanCameraConfig(1, 0, 1f, CropRect(), false)
        val frame = ScanFrame("frame", "s", 0, 0, config, null, 1f,
            SceneSnapshot("frame", 640, 480, 1f, "/missing", "/missing"), 1)
        compose.setContent { MaterialTheme {
            Controls(VlmUiState(stage = VlmStage.SCAN_REVIEW,
                scanSnapshot = ScanSnapshot(ScanSession("s", 0, 1), listOf(frame), 0f)), events::add, {}, Modifier)
        } }
        compose.onNodeWithTag("vlm_scan_view_frame").assertDoesNotExist()
        compose.onNodeWithTag("vlm_scan_manual_open").performClick()
        assertEquals(listOf(VlmIntent.ManualScanSelectionVisible(true)), events)
    }

}
