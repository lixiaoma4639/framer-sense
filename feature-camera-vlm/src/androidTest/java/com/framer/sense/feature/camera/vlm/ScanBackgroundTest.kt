package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.random.Random
import java.io.File
import java.util.UUID

/** 固定种子的纹理验证 ORB 几何；不访问相机、上游模型或 GPU 人偶。 */
class ScanBackgroundTest {
    @get:Rule val compose = createComposeRule()
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
    @Test fun unselectedScanImageIsShownWithProvisionalLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "scan-preview-${UUID.randomUUID()}.jpg")
        val bitmap = fixture(12)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            val scene = SceneSnapshot("frame", 640, 480, 1f, file.path, file.path)
            compose.setContent { MaterialTheme { FrozenSceneImage(scene, true) } }
            compose.onNodeWithTag("vlm_frozen_scene").assertExists()
            compose.onNodeWithTag("vlm_scan_preview_unselected").assertTextEquals(context.getString(R.string.vlm_scan_preview_unselected))
        } finally { bitmap.recycle(); file.delete() }
    }
    @Test fun identicalBackgroundMatchesButWrongSceneAndDisplacementDoNotAlign() {
        val tools = OpenCvSceneTools(); val reference = fixture(12); val wrong = fixture(79)
        val shifted = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(shifted).drawBitmap(reference, Matrix().apply { postTranslate(80f, 0f) }, null)
        try {
            val features = requireNotNull(tools.extract(reference))
            val exact = requireNotNull(tools.match(features, tools.extract(reference)))
            assertTrue(BackgroundMatchPolicy.aligned(exact))
            assertFalse(tools.match(features, tools.extract(wrong))?.let(BackgroundMatchPolicy::confident) == true)
            val displaced = requireNotNull(tools.match(features, tools.extract(shifted)))
            assertTrue(BackgroundMatchPolicy.confident(displaced))
            assertFalse(BackgroundMatchPolicy.aligned(displaced))
        } finally { reference.recycle(); wrong.recycle(); shifted.recycle() }
    }
    @Test fun plainTextureIsNotDeclaredBlurredAndCannotAutoConfirm() {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        try {
            val tools = OpenCvSceneTools(); val features = requireNotNull(tools.extract(bitmap))
            assertFalse(features.unusable); assertNull(tools.match(features, features))
        } finally { bitmap.recycle() }
    }
    @Test fun scanButtonsEmitExplicitEventsAndHideOtherActions() {
        val events = mutableListOf<VlmIntent>()
        compose.setContent { MaterialTheme {
            Controls(VlmUiState(stage = VlmStage.SCANNING, scanSession = ScanSession("scan", 1)), events::add, {}, Modifier)
        } }
        compose.onNodeWithTag("vlm_scan_start").assertDoesNotExist()
        compose.onNodeWithTag("vlm_start").assertDoesNotExist()
        compose.onNodeWithTag("vlm_scan_prefer").performClick()
        compose.onNodeWithTag("vlm_scan_stop").performClick()
        compose.onNodeWithTag("vlm_scan_cancel").performClick()
        assertEquals(listOf(VlmIntent.PreferScanView, VlmIntent.StopScan, VlmIntent.CancelScan), events)
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
            Controls(VlmUiState(referencePlan = plan), events::add, {}, Modifier)
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
