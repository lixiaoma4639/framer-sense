package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.ScanCollector
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PhotoOrientationTest {
    @Test fun imageRotationDependsOnDeviceAndSensorRatherThanLockedWindow() {
        listOf(0, 90, 180, 270).forEach { sensor ->
            (0..3).forEach { window ->
                (0..3).forEach { device ->
                    val windowImage = (sensor - window * 90 + 360) % 360
                    val orientation = PhotoOrientation(device, window)
                    assertEquals((sensor - device * 90 + 360) % 360, orientation.uprightDegrees(windowImage))
                    // 转正后再映回固定预览，应与 CameraX 原窗口方向完全一致。
                    assertEquals(windowImage, (orientation.uprightDegrees(windowImage) + orientation.previewDegrees) % 360)
                }
            }
        }
    }

    @Test fun delayedAnalysisUsesExposureOrientationAndSavedValuesDoNotChange() {
        val history = PhotoOrientationHistory()
        history.record(100, PhotoOrientation(0, 0))
        history.record(200, PhotoOrientation(1, 0))
        val landscape = history.at(250)
        history.record(300, PhotoOrientation(0, 0))
        history.record(400, PhotoOrientation(3, 0))
        assertEquals(PhotoOrientation(0, 0), history.at(199))
        assertEquals(PhotoOrientation(1, 0), history.at(200))
        assertEquals(landscape, history.at(299))
        assertEquals(PhotoOrientation(0, 0), history.at(399))
        assertEquals(PhotoOrientation(3, 0), history.at(450))
        assertEquals(90, landscape.previewDegrees)
    }

    @Test fun anchorsRotateWithTheSamePhotoIncludingReversePortrait() {
        val foot = Point2(.2f, .8f)
        val expected = listOf(Point2(.2f, .8f), Point2(.2f, .2f), Point2(.8f, .2f), Point2(.8f, .8f))
        (0..3).forEach { quarter ->
            val actual = PhotoOrientation(quarter, 0).toPreview(foot)
            assertEquals(expected[quarter].x, actual.x, .00001f)
            assertEquals(expected[quarter].y, actual.y, .00001f)
        }
    }

    @Test fun mixedDirectionsRemainInOneScanAndCannotReplaceEachOtherAsDuplicates() {
        val collector = ScanCollector(ScanSession("scan", 100)).apply { configure(7) }
        listOf(0, 1, 0, 3, 2).forEachIndexed { i, rotation ->
            val orientation = PhotoOrientation(rotation, 0)
            val config = ScanCameraConfig(7, orientation.uprightDegrees(90), 2f, CropRect(), true, orientation = orientation)
            assertTrue(collector.accepts("scan", 200L + i, 200L + i, config))
            val scene = SceneSnapshot("$i", if (rotation % 2 == 0) 480 else 640,
                if (rotation % 2 == 0) 640 else 480, 2f, "/$i", "/input$i", orientation = orientation)
            val old = collector.frames().lastOrNull()
            collector.add(ScanFrame("$i", "scan", 200L + i, 200L + i, config, null, 1f, scene, 10), old?.id)
        }
        collector.stop(400)
        val frozen = collector.snapshot()
        assertEquals(5, frozen.frames.size)
        assertEquals(listOf(0, 1, 0, 3, 2), frozen.frames.map { it.scene.orientation.deviceRotation })
        assertTrue(frozen.frames.all { it.config.version == 7 && it.config.orientation == it.scene.orientation })
    }

    @Test fun orientationDoesNotChangeSceneGatewayJson() {
        val scene = SceneSnapshot("photo", 640, 480, 1f, "/image", "/model")
        assertEquals(VlmJson.encodeToString(scene), VlmJson.encodeToString(scene.copy(orientation = PhotoOrientation(1, 0))))
        assertEquals(VlmJson.encodeToString(scene), VlmJson.encodeToString(scene.copy(
            captureBinding = PhotoViewBinding("single:photo", "photo", 7))))
    }
}
