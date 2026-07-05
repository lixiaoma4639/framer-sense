package com.framer.sense.feature.camera.pytorch.v2.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import androidx.camera.core.ImageProxy
import java.io.Closeable

class CameraV2OnnxAnalyzer(
    private val sessions: OnnxSessionPool,
    private val preprocessor: FramePreprocessor = FramePreprocessor()
) : Closeable {

    private var frameCount = 0
    private var latestScene: SemanticScene = SemanticScene.Unknown

    fun analyze(imageProxy: ImageProxy): CameraV2Analysis {
        val luminance = preprocessor.averageLuminance(imageProxy)
        val detectedObjects = sessions.objectSession?.let { runObjectDetector(it, imageProxy) }
        val people = detectedObjects?.first.orEmpty()
        val objects = detectedObjects?.second.orEmpty()
        val pose = sessions.poseSession?.let { runPoseDetector(it, imageProxy) } ?: PoseEstimate.Empty
        if (frameCount % SCENE_INTERVAL_FRAMES == 0) {
            latestScene = sessions.sceneSession?.let { runSceneClassifier(it, imageProxy) } ?: SemanticScene.Unknown
        }
        frameCount++
        return CameraV2Analysis(
            people = people,
            objects = objects,
            pose = pose,
            semanticScene = latestScene,
            luminance = luminance,
            modelAvailability = sessions.availability
        )
    }

    private fun runObjectDetector(
        session: OrtSession,
        imageProxy: ImageProxy
    ): Pair<List<ScenePerson>, List<SceneObject>> {
        val inputName = session.inputNames.first()
        val input = preprocessor.toRgbFloatBuffer(
            imageProxy = imageProxy,
            width = YOLO_SIZE,
            height = YOLO_SIZE,
            channelFirst = true
        )
        OnnxTensor.createTensor(
            sessions.environment,
            input,
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return parseObjectOutput(flattenNumbers(result.firstOutputValue()))
            }
        }
    }

    private fun runPoseDetector(
        session: OrtSession,
        imageProxy: ImageProxy
    ): PoseEstimate {
        val inputName = session.inputNames.first()
        val input = preprocessor.toRgbFloatBuffer(
            imageProxy = imageProxy,
            width = YOLO_SIZE,
            height = YOLO_SIZE,
            channelFirst = true
        )
        OnnxTensor.createTensor(
            sessions.environment,
            input,
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return parsePoseOutput(flattenNumbers(result.firstOutputValue()))
            }
        }
    }

    private fun runSceneClassifier(
        session: OrtSession,
        imageProxy: ImageProxy
    ): SemanticScene {
        val inputName = session.inputNames.first()
        val input = preprocessor.toRgbFloatBuffer(
            imageProxy = imageProxy,
            width = SCENE_SIZE,
            height = SCENE_SIZE,
            channelFirst = true,
            mean = floatArrayOf(123.675f, 116.28f, 103.53f),
            std = floatArrayOf(58.395f, 57.12f, 57.375f)
        )
        OnnxTensor.createTensor(
            sessions.environment,
            input,
            longArrayOf(1, RGB_CHANNELS.toLong(), SCENE_SIZE.toLong(), SCENE_SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return parseSceneOutput(flattenNumbers(result.firstOutputValue()))
            }
        }
    }

    private fun parseObjectOutput(values: List<Float>): Pair<List<ScenePerson>, List<SceneObject>> {
        if (values.isEmpty()) return emptyList<ScenePerson>() to emptyList()
        val stride = chooseYoloStride(values.size, hasPose = false)
        val detections = values.chunked(stride).mapNotNull { row ->
            val score = row.drop(4).take(COCO_LABELS.size).maxOrNull() ?: return@mapNotNull null
            val classId = row.drop(4).take(COCO_LABELS.size).indexOf(score)
            if (score < OBJECT_SCORE_THRESHOLD) return@mapNotNull null
            val cx = row.getOrNull(0)?.normalizedCoordinate() ?: return@mapNotNull null
            val cy = row.getOrNull(1)?.normalizedCoordinate() ?: return@mapNotNull null
            val width = (row.getOrNull(2)?.normalizedSize() ?: 0f).coerceIn(0f, 1f)
            val height = (row.getOrNull(3)?.normalizedSize() ?: 0f).coerceIn(0f, 1f)
            SceneObject(
                bounds = V2Rect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f).clamped(),
                label = COCO_LABELS.getOrElse(classId) { "class_$classId" },
                confidence = score,
                classId = classId
            )
        }
        val people = detections
            .filter { it.classId == PERSON_CLASS_ID && it.confidence >= PERSON_SCORE_THRESHOLD }
            .map { ScenePerson(bounds = it.bounds, confidence = it.confidence) }
        val objects = detections.filterNot { it.classId == PERSON_CLASS_ID }
        return people to objects
    }

    private fun parsePoseOutput(values: List<Float>): PoseEstimate {
        if (values.isEmpty()) return PoseEstimate.Empty
        val stride = chooseYoloStride(values.size, hasPose = true)
        val best = values.chunked(stride).maxByOrNull { row ->
            row.getOrNull(4) ?: 0f
        } ?: return PoseEstimate.Empty
        val confidence = best.getOrNull(4) ?: 0f
        if (confidence < POSE_SCORE_THRESHOLD) return PoseEstimate.Empty
        val start = 5
        val keypoints = PoseKeypointName.entries.mapIndexedNotNull { index, name ->
            val offset = start + index * 3
            val x = best.getOrNull(offset)?.normalizedCoordinate() ?: return@mapIndexedNotNull null
            val y = best.getOrNull(offset + 1)?.normalizedCoordinate() ?: return@mapIndexedNotNull null
            val score = best.getOrNull(offset + 2) ?: confidence
            PoseKeypoint(
                name = name,
                point = V2Point(x, y),
                confidence = score
            )
        }
        return PoseEstimate(keypoints = keypoints, confidence = confidence)
    }

    private fun parseSceneOutput(values: List<Float>): SemanticScene {
        val bestIndex = values.indices.maxByOrNull { values[it] } ?: return SemanticScene.Unknown
        val confidence = values[bestIndex]
        val label = PLACES_365_GROUPS[bestIndex % PLACES_365_GROUPS.size]
        return SemanticScene(
            label = label.first,
            group = label.second,
            confidence = confidence
        )
    }

    private fun chooseYoloStride(size: Int, hasPose: Boolean): Int {
        val preferred = if (hasPose) YOLO_POSE_STRIDE else YOLO_DETECT_STRIDE
        return when {
            size % preferred == 0 -> preferred
            !hasPose && size % YOLO_LEGACY_DETECT_STRIDE == 0 -> YOLO_LEGACY_DETECT_STRIDE
            else -> preferred
        }
    }

    private fun Float.normalizedCoordinate(): Float =
        if (this > 1f) (this / YOLO_SIZE).coerceIn(0f, 1f) else coerceIn(0f, 1f)

    private fun Float.normalizedSize(): Float =
        if (this > 1f) (this / YOLO_SIZE).coerceIn(0f, 1f) else coerceIn(0f, 1f)

    private fun OrtSession.Result.firstOutputValue(): Any? =
        iterator().asSequence().firstOrNull()?.value?.value

    private fun flattenNumbers(value: Any?): List<Float> =
        when (value) {
            null -> emptyList()
            is Float -> listOf(value)
            is Double -> listOf(value.toFloat())
            is Int -> listOf(value.toFloat())
            is Long -> listOf(value.toFloat())
            is FloatArray -> value.toList()
            is DoubleArray -> value.map { it.toFloat() }
            is IntArray -> value.map { it.toFloat() }
            is LongArray -> value.map { it.toFloat() }
            is Array<*> -> value.flatMap { flattenNumbers(it) }
            else -> emptyList()
        }

    override fun close() {
        sessions.close()
    }

    private companion object {
        const val YOLO_SIZE = 640
        const val SCENE_SIZE = 224
        const val RGB_CHANNELS = 3
        const val PERSON_CLASS_ID = 0
        const val PERSON_SCORE_THRESHOLD = 0.36f
        const val OBJECT_SCORE_THRESHOLD = 0.42f
        const val POSE_SCORE_THRESHOLD = 0.28f
        const val YOLO_DETECT_STRIDE = 84
        const val YOLO_LEGACY_DETECT_STRIDE = 85
        const val YOLO_POSE_STRIDE = 56
        const val SCENE_INTERVAL_FRAMES = 3

        val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
        )

        val PLACES_365_GROUPS = listOf(
            "living_room" to SceneGroup.INDOOR,
            "bedroom" to SceneGroup.INDOOR,
            "corridor" to SceneGroup.INDOOR,
            "street" to SceneGroup.URBAN,
            "plaza" to SceneGroup.URBAN,
            "bridge" to SceneGroup.URBAN,
            "park" to SceneGroup.NATURE,
            "forest_path" to SceneGroup.NATURE,
            "beach" to SceneGroup.OUTDOOR,
            "courtyard" to SceneGroup.OUTDOOR
        )
    }
}
