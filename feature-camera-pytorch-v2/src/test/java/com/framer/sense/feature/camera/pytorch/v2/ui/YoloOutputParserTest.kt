package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloOutputParserTest {

    private val squareTransform = YoloInputTransform(
        inputSize = 640,
        rotatedWidth = 640,
        rotatedHeight = 640,
        scale = 1f,
        padLeft = 0f,
        padTop = 0f
    )

    @Test
    fun parseObjects_supportsChannelsFirstYolov8OutputAndNms() {
        val candidateCount = 2
        val values = MutableList(84 * candidateCount) { 0f }
        values.setChannelsFirst(candidateCount, candidate = 0, feature = 0, value = 320f)
        values.setChannelsFirst(candidateCount, candidate = 0, feature = 1, value = 320f)
        values.setChannelsFirst(candidateCount, candidate = 0, feature = 2, value = 160f)
        values.setChannelsFirst(candidateCount, candidate = 0, feature = 3, value = 240f)
        values.setChannelsFirst(candidateCount, candidate = 0, feature = 4, value = 0.9f)
        values.setChannelsFirst(candidateCount, candidate = 1, feature = 0, value = 322f)
        values.setChannelsFirst(candidateCount, candidate = 1, feature = 1, value = 322f)
        values.setChannelsFirst(candidateCount, candidate = 1, feature = 2, value = 158f)
        values.setChannelsFirst(candidateCount, candidate = 1, feature = 3, value = 238f)
        values.setChannelsFirst(candidateCount, candidate = 1, feature = 4, value = 0.72f)

        val (people, objects) = YoloOutputParser.parseObjects(
            values = values,
            shape = longArrayOf(1, 84, candidateCount.toLong()),
            transform = squareTransform
        )

        assertEquals(1, people.size)
        assertEquals(0, objects.size)
        assertTrue(people.first().bounds.centerX in 0.49f..0.51f)
        assertTrue(people.first().bounds.height in 0.36f..0.39f)
    }

    @Test
    fun parseObjects_supportsRowsFirstYolov8Output() {
        val values = MutableList(84) { 0f }
        values[0] = 160f
        values[1] = 300f
        values[2] = 100f
        values[3] = 120f
        values[4 + 56] = 0.82f

        val (_, objects) = YoloOutputParser.parseObjects(
            values = values,
            shape = longArrayOf(1, 1, 84),
            transform = squareTransform
        )

        assertEquals(1, objects.size)
        assertEquals(56, objects.first().classId)
        assertEquals("chair", objects.first().label)
    }

    @Test
    fun parsePose_supportsChannelsFirstPoseOutput() {
        val values = MutableList(56) { 0f }
        values[0] = 320f
        values[1] = 320f
        values[2] = 220f
        values[3] = 420f
        values[4] = 0.86f
        PoseKeypointName.entries.forEachIndexed { index, _ ->
            val offset = 5 + index * 3
            values[offset] = 220f + index * 8f
            values[offset + 1] = 160f + index * 12f
            values[offset + 2] = 0.74f
        }

        val pose = YoloOutputParser.parsePose(
            values = values,
            shape = longArrayOf(1, 56, 1),
            transform = squareTransform
        )

        assertEquals(0.86f, pose.confidence, 0.001f)
        assertEquals(17, pose.keypoints.size)
        assertTrue(pose.point(PoseKeypointName.LEFT_SHOULDER) != null)
    }

    @Test
    fun yoloInputTransform_removesLetterboxPaddingWhenMappingCoordinates() {
        val transform = YoloInputTransform(
            inputSize = 640,
            rotatedWidth = 480,
            rotatedHeight = 640,
            scale = 1f,
            padLeft = 80f,
            padTop = 0f
        )

        val rect = transform.xywhToNormalizedRect(cx = 320f, cy = 320f, width = 160f, height = 320f)

        assertEquals(0.5f, rect.centerX, 0.001f)
        assertEquals(0.5f, rect.centerY, 0.001f)
        assertEquals(1f / 3f, rect.width, 0.001f)
        assertEquals(0.5f, rect.height, 0.001f)
    }

    @Test
    fun sceneInferencer_usesDetectedObjectsForSceneGroup() {
        val indoor = YoloSceneInferencer.infer(
            listOf(SceneObject(V2Rect(0.1f, 0.2f, 0.4f, 0.7f), "chair", 0.9f, 56))
        )
        val urban = YoloSceneInferencer.infer(
            listOf(SceneObject(V2Rect(0.2f, 0.2f, 0.5f, 0.5f), "car", 0.85f, 2))
        )

        assertEquals(SceneGroup.INDOOR, indoor.group)
        assertEquals(SceneGroup.URBAN, urban.group)
    }

    @Test
    fun parsePersonSegments_supportsRawYolov8SegOutput() {
        val prediction = MutableList(116) { 0f }
        prediction[0] = 320f
        prediction[1] = 320f
        prediction[2] = 640f
        prediction[3] = 640f
        prediction[4] = 0.92f
        prediction[84] = 10f

        val proto = MutableList(32 * 4 * 4) { -1f }
        for (y in 1..2) {
            for (x in 1..2) {
                proto[y * 4 + x] = 1f
            }
        }

        val segments = YoloSegmentationParser.parsePersonSegments(
            outputs = listOf(
                YoloOnnxOutput(values = prediction, shape = longArrayOf(1, 1, 116)),
                YoloOnnxOutput(values = proto, shape = longArrayOf(1, 32, 4, 4))
            ),
            transform = squareTransform
        )

        assertEquals(1, segments.size)
        assertTrue(segments.first().contour.size >= 4)
        assertTrue(segments.first().contour.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun modelAvailability_requiresOnlyObjectAndPoseModels() {
        assertTrue(ModelAvailability(objectDetectorReady = true, poseDetectorReady = true).allRequiredReady)
        assertTrue(ModelAvailability(objectDetectorReady = true, poseDetectorReady = true, segmentationReady = false).allRequiredReady)
        assertTrue(!ModelAvailability(objectDetectorReady = true, poseDetectorReady = false).allRequiredReady)
    }

    private fun MutableList<Float>.setChannelsFirst(
        candidateCount: Int,
        candidate: Int,
        feature: Int,
        value: Float
    ) {
        this[feature * candidateCount + candidate] = value
    }
}
