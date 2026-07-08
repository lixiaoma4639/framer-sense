package com.framer.sense.feature.camera.pytorch.v2.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import androidx.camera.core.ImageProxy
import java.io.Closeable

class CameraV2OnnxAnalyzer(
    private val sessions: OnnxSessionPool,
    private val preprocessor: FramePreprocessor = FramePreprocessor()
) : Closeable {

    fun analyze(imageProxy: ImageProxy): CameraV2Analysis {
        val luminance = preprocessor.averageLuminance(imageProxy)
        val objectInput = preprocessor.toYoloRgbFloatBuffer(imageProxy, YOLO_SIZE)
        val detectedObjects = sessions.objectSession?.let { runObjectDetector(it, objectInput) }
        val people = detectedObjects?.first.orEmpty()
        val objects = detectedObjects?.second.orEmpty()
        val pose = sessions.poseSession?.let { runPoseDetector(it, objectInput) } ?: PoseEstimate.Empty
        return CameraV2Analysis(
            people = people,
            objects = objects,
            pose = pose,
            semanticScene = YoloSceneInferencer.infer(objects),
            luminance = luminance,
            modelAvailability = sessions.availability
        )
    }

    private fun runObjectDetector(
        session: OrtSession,
        yoloInput: YoloInput
    ): Pair<List<ScenePerson>, List<SceneObject>> {
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(
            sessions.environment,
            yoloInput.buffer,
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.firstOutput()
                return YoloOutputParser.parseObjects(
                    values = flattenNumbers(output?.value),
                    shape = output?.tensorShape() ?: LongArray(0),
                    transform = yoloInput.transform
                )
            }
        }
    }

    private fun runPoseDetector(
        session: OrtSession,
        yoloInput: YoloInput
    ): PoseEstimate {
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(
            sessions.environment,
            yoloInput.buffer,
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.firstOutput()
                return YoloOutputParser.parsePose(
                    values = flattenNumbers(output?.value),
                    shape = output?.tensorShape() ?: LongArray(0),
                    transform = yoloInput.transform
                )
            }
        }
    }

    private fun OrtSession.Result.firstOutput(): OnnxValue? =
        iterator().asSequence().firstOrNull()?.value

    private fun OnnxValue.tensorShape(): LongArray =
        (info as? TensorInfo)?.shape ?: LongArray(0)

    override fun close() {
        sessions.close()
    }

    private companion object {
        const val YOLO_SIZE = 640
        const val RGB_CHANNELS = 3
    }
}

internal object YoloOutputParser {

    fun parseObjects(
        values: List<Float>,
        shape: LongArray,
        transform: YoloInputTransform
    ): Pair<List<ScenePerson>, List<SceneObject>> {
        if (values.isEmpty()) return emptyList<ScenePerson>() to emptyList()
        val detections = when {
            YoloTensorLayout.isNmsDetectionOutput(shape, values.size) -> parseNmsObjects(values, shape, transform)
            else -> parseRawObjects(values, shape, transform)
        }.nms(IOU_THRESHOLD)

        val people = detections
            .filter { it.classId == PERSON_CLASS_ID && it.confidence >= PERSON_SCORE_THRESHOLD }
            .map { ScenePerson(bounds = it.bounds, confidence = it.confidence) }
        val objects = detections
            .filterNot { it.classId == PERSON_CLASS_ID }
            .filter { it.confidence >= OBJECT_SCORE_THRESHOLD }
            .map { detection ->
                SceneObject(
                    bounds = detection.bounds,
                    label = COCO_LABELS.getOrElse(detection.classId) { "class_${detection.classId}" },
                    confidence = detection.confidence,
                    classId = detection.classId
                )
            }
        return people to objects
    }

    fun parsePose(
        values: List<Float>,
        shape: LongArray,
        transform: YoloInputTransform
    ): PoseEstimate {
        if (values.isEmpty()) return PoseEstimate.Empty
        val layout = YoloTensorLayout.fromShape(
            shape = shape,
            totalValues = values.size,
            preferredFeatures = YOLO_POSE_FEATURES
        ) ?: return PoseEstimate.Empty
        val bestIndex = (0 until layout.candidates).maxByOrNull { candidate ->
            layout.value(values, candidate, 4).coerceScore()
        } ?: return PoseEstimate.Empty
        val confidence = layout.value(values, bestIndex, 4).coerceScore()
        if (confidence < POSE_SCORE_THRESHOLD) return PoseEstimate.Empty

        val keypoints = PoseKeypointName.entries.mapIndexedNotNull { index, name ->
            val offset = POSE_KEYPOINT_START + index * POSE_KEYPOINT_STRIDE
            val rawScore = layout.valueOrNull(values, bestIndex, offset + 2) ?: confidence
            val score = rawScore.coerceScore()
            if (score < KEYPOINT_SCORE_THRESHOLD) return@mapIndexedNotNull null
            PoseKeypoint(
                name = name,
                point = transform.pointToNormalized(
                    V2Point(
                        x = layout.value(values, bestIndex, offset),
                        y = layout.value(values, bestIndex, offset + 1)
                    )
                ),
                confidence = score
            )
        }
        return PoseEstimate(keypoints = keypoints, confidence = confidence)
    }

    private fun parseRawObjects(
        values: List<Float>,
        shape: LongArray,
        transform: YoloInputTransform
    ): List<YoloDetection> {
        val layout = YoloTensorLayout.fromShape(
            shape = shape,
            totalValues = values.size,
            preferredFeatures = YOLO_DETECT_FEATURES
        ) ?: YoloTensorLayout.fromShape(
            shape = shape,
            totalValues = values.size,
            preferredFeatures = YOLO_LEGACY_DETECT_FEATURES
        ) ?: return emptyList()
        val hasObjectness = layout.features >= YOLO_LEGACY_DETECT_FEATURES
        val classStart = if (hasObjectness) 5 else 4
        val classCount = minOf(COCO_LABELS.size, layout.features - classStart)
        if (classCount <= 0) return emptyList()

        return (0 until layout.candidates).mapNotNull { candidate ->
            var bestClass = -1
            var bestClassScore = 0f
            for (classIndex in 0 until classCount) {
                val score = layout.value(values, candidate, classStart + classIndex).coerceScore()
                if (score > bestClassScore) {
                    bestClassScore = score
                    bestClass = classIndex
                }
            }
            val objectness = if (hasObjectness) layout.value(values, candidate, 4).coerceScore() else 1f
            val confidence = bestClassScore * objectness
            val threshold = if (bestClass == PERSON_CLASS_ID) PERSON_SCORE_THRESHOLD else OBJECT_SCORE_THRESHOLD
            if (bestClass < 0 || confidence < threshold) return@mapNotNull null
            YoloDetection(
                bounds = transform.xywhToNormalizedRect(
                    cx = layout.value(values, candidate, 0),
                    cy = layout.value(values, candidate, 1),
                    width = layout.value(values, candidate, 2),
                    height = layout.value(values, candidate, 3)
                ),
                confidence = confidence,
                classId = bestClass
            )
        }
    }

    private fun parseNmsObjects(
        values: List<Float>,
        shape: LongArray,
        transform: YoloInputTransform
    ): List<YoloDetection> {
        val layout = YoloTensorLayout.fromShape(
            shape = shape,
            totalValues = values.size,
            preferredFeatures = YOLO_NMS_FEATURES
        ) ?: return emptyList()
        return (0 until layout.candidates).mapNotNull { candidate ->
            val confidence = layout.value(values, candidate, 4).coerceScore()
            val classId = layout.value(values, candidate, 5).toInt()
            val threshold = if (classId == PERSON_CLASS_ID) PERSON_SCORE_THRESHOLD else OBJECT_SCORE_THRESHOLD
            if (classId !in COCO_LABELS.indices || confidence < threshold) return@mapNotNull null
            YoloDetection(
                bounds = transform.xyxyToNormalizedRect(
                    left = layout.value(values, candidate, 0),
                    top = layout.value(values, candidate, 1),
                    right = layout.value(values, candidate, 2),
                    bottom = layout.value(values, candidate, 3)
                ),
                confidence = confidence,
                classId = classId
            )
        }
    }

    private fun List<YoloDetection>.nms(iouThreshold: Float): List<YoloDetection> {
        val kept = mutableListOf<YoloDetection>()
        val remaining = sortedByDescending { it.confidence }.toMutableList()
        while (remaining.isNotEmpty() && kept.size < MAX_DETECTIONS) {
            val best = remaining.removeAt(0)
            kept += best
            remaining.removeAll { candidate ->
                candidate.classId == best.classId && best.bounds.iou(candidate.bounds) > iouThreshold
            }
        }
        return kept
    }

    private fun V2Rect.iou(other: V2Rect): Float {
        val intersection = intersectionArea(other)
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun Float.coerceScore(): Float =
        coerceIn(0f, 1f)

    private data class YoloDetection(
        val bounds: V2Rect,
        val confidence: Float,
        val classId: Int
    )

    private const val PERSON_CLASS_ID = 0
    private const val PERSON_SCORE_THRESHOLD = 0.25f
    private const val OBJECT_SCORE_THRESHOLD = 0.30f
    private const val POSE_SCORE_THRESHOLD = 0.25f
    private const val KEYPOINT_SCORE_THRESHOLD = 0.12f
    private const val IOU_THRESHOLD = 0.45f
    private const val MAX_DETECTIONS = 40
    private const val YOLO_DETECT_FEATURES = 84
    private const val YOLO_LEGACY_DETECT_FEATURES = 85
    private const val YOLO_POSE_FEATURES = 56
    private const val YOLO_NMS_FEATURES = 6
    private const val POSE_KEYPOINT_START = 5
    private const val POSE_KEYPOINT_STRIDE = 3

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
}

internal object YoloSceneInferencer {

    fun infer(objects: List<SceneObject>): SemanticScene {
        if (objects.isEmpty()) {
            return SemanticScene(label = "户外", group = SceneGroup.OUTDOOR, confidence = 0.35f)
        }
        val indoorScore = objects.sumScoreFor(INDOOR_CLASS_IDS)
        val urbanScore = objects.sumScoreFor(URBAN_CLASS_IDS)
        val natureScore = objects.sumScoreFor(NATURE_CLASS_IDS)
        return when {
            indoorScore >= urbanScore * SCORE_BIAS && indoorScore >= natureScore * SCORE_BIAS && indoorScore > 0f -> {
                SemanticScene(label = "室内", group = SceneGroup.INDOOR, confidence = indoorScore.coerceAtMost(1f))
            }
            urbanScore >= indoorScore * SCORE_BIAS && urbanScore >= natureScore * SCORE_BIAS && urbanScore > 0f -> {
                SemanticScene(label = "城市", group = SceneGroup.URBAN, confidence = urbanScore.coerceAtMost(1f))
            }
            natureScore > 0f -> {
                SemanticScene(label = "户外", group = SceneGroup.NATURE, confidence = natureScore.coerceAtMost(1f))
            }
            else -> SemanticScene(label = "未知场景", group = SceneGroup.UNKNOWN, confidence = 0.25f)
        }
    }

    private fun List<SceneObject>.sumScoreFor(classIds: Set<Int>): Float =
        filter { it.classId in classIds }.sumOf { it.confidence.toDouble() }.toFloat()

    private const val SCORE_BIAS = 1.08f

    private val INDOOR_CLASS_IDS = setOf(
        56, 57, 58, 59, 60, 61, 62, 63, 64, 66, 67, 68, 69, 71, 72, 73, 74, 75, 76, 77, 78, 79
    )
    private val URBAN_CLASS_IDS = setOf(
        1, 2, 3, 5, 6, 7, 9, 10, 11, 12, 13
    )
    private val NATURE_CLASS_IDS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22, 23
    )
}

private object YoloTensorLayout {

    fun isNmsDetectionOutput(shape: LongArray, totalValues: Int): Boolean =
        shape.isNotEmpty() && fromShape(shape, totalValues, preferredFeatures = 6)?.features == 6

    fun fromShape(
        shape: LongArray,
        totalValues: Int,
        preferredFeatures: Int
    ): Layout? {
        val dims = shape.toList()
            .mapNotNull { it.takeIf { dim -> dim > 1 }?.toInt() }
            .ifEmpty { inferDims(totalValues, preferredFeatures) }
        if (dims.size < 2) return null
        val first = dims[dims.lastIndex - 1]
        val second = dims[dims.lastIndex]
        return when {
            second == preferredFeatures -> Layout(
                features = second,
                candidates = first,
                channelsFirst = false
            )
            first == preferredFeatures -> Layout(
                features = first,
                candidates = second,
                channelsFirst = true
            )
            preferredFeatures == 84 && first == 85 -> Layout(
                features = first,
                candidates = second,
                channelsFirst = true
            )
            preferredFeatures == 84 && second == 85 -> Layout(
                features = second,
                candidates = first,
                channelsFirst = false
            )
            else -> null
        }
    }

    private fun inferDims(totalValues: Int, preferredFeatures: Int): List<Int> =
        when {
            totalValues % preferredFeatures == 0 -> listOf(preferredFeatures, totalValues / preferredFeatures)
            preferredFeatures == 84 && totalValues % 85 == 0 -> listOf(85, totalValues / 85)
            else -> emptyList()
        }

    data class Layout(
        val features: Int,
        val candidates: Int,
        val channelsFirst: Boolean
    ) {
        fun value(values: List<Float>, candidate: Int, feature: Int): Float =
            valueOrNull(values, candidate, feature) ?: 0f

        fun valueOrNull(values: List<Float>, candidate: Int, feature: Int): Float? {
            if (candidate !in 0 until candidates || feature !in 0 until features) return null
            val index = if (channelsFirst) {
                feature * candidates + candidate
            } else {
                candidate * features + feature
            }
            return values.getOrNull(index)
        }
    }
}

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
