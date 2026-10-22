package com.framer.sense.feature.camera.vlm

import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import com.framer.sense.feature.camera.vlm.camera.withPhotoTargetRotation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 检查真实 CameraX 用例的方向配置，不打开硬件、不写入相册。 */
class CameraCaptureRotationTest {
    private val rotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90,
        Surface.ROTATION_180, Surface.ROTATION_270)

    @Test fun eachPhotoUsesItsOwnDirectionAndRestoresTheFixedPreviewCoordinateSystem() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            rotations.forEach { window ->
                val preview = Preview.Builder().setTargetRotation(window).build()
                val analysis = ImageAnalysis.Builder().setTargetRotation(window).build()
                val capture = ImageCapture.Builder().setTargetRotation(window).build()
                rotations.forEach { device ->
                    val result = capture.withPhotoTargetRotation(device) {
                        assertEquals(device, capture.targetRotation)
                        assertEquals(window, preview.targetRotation)
                        assertEquals(window, analysis.targetRotation)
                        "saved-photo"
                    }
                    assertEquals("saved-photo", result)
                    // 下一次扫描冻结仍使用窗口方向，不能再叠加上一次拍照的方向。
                    assertEquals(window, capture.targetRotation)
                    assertEquals(window, preview.targetRotation)
                    assertEquals(window, analysis.targetRotation)
                }
            }
        }
    }

    @Test fun rotatingWhileSavingDoesNotChangeTheDirectionAlreadyChosenForThisPhoto() = runBlocking {
        val capture = ImageCapture.Builder().setTargetRotation(Surface.ROTATION_0).build()
        val entered = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        var device = Surface.ROTATION_90
        val request = async(Dispatchers.Main.immediate) {
            capture.withPhotoTargetRotation(device) {
                entered.complete(Unit)
                saved.await()
                assertEquals(Surface.ROTATION_90, capture.targetRotation)
            }
        }
        entered.await()
        device = Surface.ROTATION_270
        assertEquals(Surface.ROTATION_270, device)
        saved.complete(Unit)
        request.await()
        assertEquals(Surface.ROTATION_0, capture.targetRotation)
    }

    @Test fun failureAndCancellationAlsoRestoreTheCaptureDirection() = runBlocking {
        val capture = ImageCapture.Builder().setTargetRotation(Surface.ROTATION_0).build()
        val failure = ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "测试拍照失败", null)
        val observed = runCatching {
            capture.withPhotoTargetRotation(Surface.ROTATION_270) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertEquals(Surface.ROTATION_0, capture.targetRotation)

        val entered = CompletableDeferred<Unit>()
        val request = launch(Dispatchers.Main.immediate) {
            capture.withPhotoTargetRotation(Surface.ROTATION_180) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertEquals(Surface.ROTATION_180, capture.targetRotation)
        request.cancelAndJoin()
        assertEquals(Surface.ROTATION_0, capture.targetRotation)
    }
}
