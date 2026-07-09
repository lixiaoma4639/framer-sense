package com.framer.sense.feature.camera.pytorch.v2.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import androidx.camera.core.ImageProxy
import java.io.Closeable
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt

class CameraV2OnnxAnalyzer(
    private val sessions: OnnxSessionPool,
    private val preprocessor: FramePreprocessor = FramePreprocessor()
) : Closeable {

    fun analyze(imageProxy: ImageProxy): CameraV2Analysis {
        val luminance = preprocessor.averageLuminance(imageProxy)
        val objectInput = preprocessor.toYoloRgbFloatBuffer(imageProxy, YOLO_SIZE)
        val detectedObjects = sessions.objectSession?.let { runObjectDetector(it, objectInput) }
        val personSegments = sessions.segmentationSession?.let { runSegmentationDetector(it, objectInput) }.orEmpty()
        val people = if (personSegments.isNotEmpty()) {
            personSegments.map { ScenePerson(bounds = it.bounds, confidence = it.confidence) }
        } else {
            detectedObjects?.first.orEmpty()
        }
        val objects = detectedObjects?.second.orEmpty()
        val wholeBodyTarget = personSegments.maxByOrNull { it.bounds.area * it.confidence }?.bounds
            ?: people.maxByOrNull { it.bounds.area * it.confidence }?.bounds
        val wholeBodyPose = if (wholeBodyTarget != null) {
            sessions.wholeBodyPoseSession?.let { session ->
                runCatching {
                    runWholeBodyPoseDetector(
                        session = session,
                        imageProxy = imageProxy,
                        targetBounds = wholeBodyTarget
                    )
                }.getOrDefault(WholeBodyPoseEstimate.Empty)
            } ?: WholeBodyPoseEstimate.Empty
        } else {
            WholeBodyPoseEstimate.Empty
        }
        val yoloPose = sessions.poseSession?.let { runPoseDetector(it, objectInput) } ?: PoseEstimate.Empty
        val pose = wholeBodyPose.toPoseEstimate() ?: yoloPose
        return CameraV2Analysis(
            people = people,
            objects = objects,
            personSegments = personSegments,
            pose = pose,
            wholeBodyPose = wholeBodyPose,
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
        yoloInput.buffer.rewind()
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
        yoloInput.buffer.rewind()
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

    private fun runSegmentationDetector(
        session: OrtSession,
        yoloInput: YoloInput
    ): List<PersonSegmentation> {
        val inputName = session.inputNames.first()
        yoloInput.buffer.rewind()
        OnnxTensor.createTensor(
            sessions.environment,
            yoloInput.buffer,
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outputs = result.iterator().asSequence().map { entry ->
                    YoloOnnxOutput(
                        values = flattenNumbers(entry.value.value),
                        shape = entry.value.tensorShape()
                    )
                }.toList()
                return YoloSegmentationParser.parsePersonSegments(
                    outputs = outputs,
                    transform = yoloInput.transform
                )
            }
        }
    }

    private fun runWholeBodyPoseDetector(
        session: OrtSession,
        imageProxy: ImageProxy,
        targetBounds: V2Rect
    ): WholeBodyPoseEstimate {
        val input = preprocessor.toWholeBodyRgbFloatBuffer(
            imageProxy = imageProxy,
            bounds = targetBounds,
            width = WHOLE_BODY_WIDTH,
            height = WHOLE_BODY_HEIGHT
        )
        val inputName = session.inputNames.first()
        input.buffer.rewind()
        OnnxTensor.createTensor(
            sessions.environment,
            input.buffer,
            longArrayOf(1, RGB_CHANNELS.toLong(), WHOLE_BODY_HEIGHT.toLong(), WHOLE_BODY_WIDTH.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outputs = result.iterator().asSequence().map { entry ->
                    YoloOnnxOutput(
                        values = flattenNumbers(entry.value.value),
                        shape = entry.value.tensorShape()
                    )
                }.toList()
                return WholeBodyPoseParser.parse(
                    outputs = outputs,
                    transform = input.transform
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
        const val WHOLE_BODY_WIDTH = 192
        const val WHOLE_BODY_HEIGHT = 256
        const val RGB_CHANNELS = 3
    }
}

private fun WholeBodyPoseEstimate.toPoseEstimate(): PoseEstimate? {
    if (confidence < 0.18f) return null
    val sourceKeypoints = keypoints
    val keypoints = PoseKeypointName.entries.mapIndexedNotNull { index, name ->
        val keypoint = sourceKeypoints.firstOrNull { it.index == index && it.confidence >= 0.2f }
            ?: return@mapIndexedNotNull null
        PoseKeypoint(
            name = name,
            point = keypoint.point,
            confidence = keypoint.confidence
        )
    }
    if (keypoints.size < 4) return null
    return PoseEstimate(
        keypoints = keypoints,
        confidence = confidence
    )
}

internal data class YoloOnnxOutput(
    val values: List<Float>,
    val shape: LongArray
)

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

internal object YoloSegmentationParser {

    fun parsePersonSegments(
        outputs: List<YoloOnnxOutput>,
        transform: YoloInputTransform
    ): List<PersonSegmentation> {
        val predictionOutput = outputs.firstNotNullOfOrNull { output ->
            val layout = YoloTensorLayout.fromShape(
                shape = output.shape,
                totalValues = output.values.size,
                preferredFeatures = SEGMENT_FEATURES
            )
            layout?.let { output to it }
        } ?: return emptyList()
        val protoOutput = outputs.firstNotNullOfOrNull { output ->
            ProtoLayout.fromShape(output.shape)?.let { output to it }
        } ?: return emptyList()

        val prediction = predictionOutput.first
        val predictionLayout = predictionOutput.second
        val proto = protoOutput.first
        val protoLayout = protoOutput.second
        val maskDimension = predictionLayout.features - MASK_COEFFICIENT_START
        if (maskDimension <= 0 || maskDimension != protoLayout.channels) return emptyList()

        val detections = (0 until predictionLayout.candidates).mapNotNull { candidate ->
            val confidence = predictionLayout.value(prediction.values, candidate, PERSON_SCORE_FEATURE).coerceScore()
            if (confidence < SEGMENT_SCORE_THRESHOLD) return@mapNotNull null
            val coefficients = FloatArray(maskDimension) { index ->
                predictionLayout.value(prediction.values, candidate, MASK_COEFFICIENT_START + index)
            }
            SegDetection(
                bounds = transform.xywhToNormalizedRect(
                    cx = predictionLayout.value(prediction.values, candidate, 0),
                    cy = predictionLayout.value(prediction.values, candidate, 1),
                    width = predictionLayout.value(prediction.values, candidate, 2),
                    height = predictionLayout.value(prediction.values, candidate, 3)
                ),
                confidence = confidence,
                coefficients = coefficients
            )
        }.nms(SEGMENT_IOU_THRESHOLD).take(MAX_SEGMENTS)

        return detections.mapNotNull { detection ->
            val contour = buildContour(
                detection = detection,
                protoValues = proto.values,
                protoLayout = protoLayout,
                transform = transform
            )
            if (contour.size < MIN_CONTOUR_POINTS) return@mapNotNull null
            PersonSegmentation(
                bounds = detection.bounds,
                contour = contour,
                confidence = detection.confidence
            )
        }
    }

    private fun buildContour(
        detection: SegDetection,
        protoValues: List<Float>,
        protoLayout: ProtoLayout,
        transform: YoloInputTransform
    ): List<V2Point> {
        val mask = BooleanArray(protoLayout.width * protoLayout.height)
        for (y in 0 until protoLayout.height) {
            for (x in 0 until protoLayout.width) {
                val point = transform.pointToNormalized(
                    V2Point(
                        x = (x + 0.5f) * transform.inputSize / protoLayout.width,
                        y = (y + 0.5f) * transform.inputSize / protoLayout.height
                    )
                )
                if (!point.inside(detection.bounds.expand(CONTOUR_BOUNDS_PADDING))) continue
                var sum = 0f
                for (channel in 0 until protoLayout.channels) {
                    sum += detection.coefficients[channel] * protoLayout.value(protoValues, channel, y, x)
                }
                mask[y * protoLayout.width + x] = sigmoid(sum) >= MASK_THRESHOLD
            }
        }

        val boundary = mutableListOf<V2Point>()
        for (y in 1 until protoLayout.height - 1) {
            for (x in 1 until protoLayout.width - 1) {
                if (!mask[y * protoLayout.width + x]) continue
                val isBoundary =
                    !mask[y * protoLayout.width + x - 1] ||
                        !mask[y * protoLayout.width + x + 1] ||
                        !mask[(y - 1) * protoLayout.width + x] ||
                        !mask[(y + 1) * protoLayout.width + x]
                if (isBoundary) {
                    boundary += transform.pointToNormalized(
                        V2Point(
                            x = (x + 0.5f) * transform.inputSize / protoLayout.width,
                            y = (y + 0.5f) * transform.inputSize / protoLayout.height
                        )
                    )
                }
            }
        }
        if (boundary.size < MIN_CONTOUR_POINTS) return detection.bounds.toContour()
        return boundary
            .orderClockwise()
            .sampleEvenly(MAX_CONTOUR_POINTS)
    }

    private fun List<SegDetection>.nms(iouThreshold: Float): List<SegDetection> {
        val kept = mutableListOf<SegDetection>()
        val remaining = sortedByDescending { it.confidence }.toMutableList()
        while (remaining.isNotEmpty() && kept.size < MAX_SEGMENTS) {
            val best = remaining.removeAt(0)
            kept += best
            remaining.removeAll { best.bounds.iou(it.bounds) > iouThreshold }
        }
        return kept
    }

    private fun V2Rect.iou(other: V2Rect): Float {
        val intersection = intersectionArea(other)
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun V2Rect.expand(padding: Float): V2Rect =
        V2Rect(left - padding, top - padding, right + padding, bottom + padding).clamped()

    private fun V2Rect.toContour(): List<V2Point> =
        listOf(
            V2Point(left, top),
            V2Point(right, top),
            V2Point(right, bottom),
            V2Point(left, bottom)
        )

    private fun V2Point.inside(rect: V2Rect): Boolean =
        x in rect.left..rect.right && y in rect.top..rect.bottom

    private fun List<V2Point>.orderClockwise(): List<V2Point> {
        val centerX = sumOf { it.x.toDouble() }.toFloat() / size
        val centerY = sumOf { it.y.toDouble() }.toFloat() / size
        return sortedBy { atan2((it.y - centerY).toDouble(), (it.x - centerX).toDouble()) }
    }

    private fun List<V2Point>.sampleEvenly(maxPoints: Int): List<V2Point> {
        if (size <= maxPoints) return this
        val step = size.toFloat() / maxPoints
        return List(maxPoints) { index -> this[(index * step).roundToInt().coerceIn(0, lastIndex)] }
    }

    private fun sigmoid(value: Float): Float =
        (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

    private fun Float.coerceScore(): Float =
        coerceIn(0f, 1f)

    private data class SegDetection(
        val bounds: V2Rect,
        val confidence: Float,
        val coefficients: FloatArray
    )

    private data class ProtoLayout(
        val channels: Int,
        val height: Int,
        val width: Int,
        val channelsLast: Boolean
    ) {
        fun value(values: List<Float>, channel: Int, y: Int, x: Int): Float {
            val index = if (channelsLast) {
                (y * width + x) * channels + channel
            } else {
                channel * height * width + y * width + x
            }
            return values.getOrNull(index) ?: 0f
        }

        companion object {
            fun fromShape(shape: LongArray): ProtoLayout? {
                val dims = shape.toList().mapNotNull { it.takeIf { dim -> dim > 1 }?.toInt() }
                if (dims.size != 3) return null
                return when {
                    dims.first() == MASK_CHANNELS -> ProtoLayout(
                        channels = dims[0],
                        height = dims[1],
                        width = dims[2],
                        channelsLast = false
                    )
                    dims.last() == MASK_CHANNELS -> ProtoLayout(
                        channels = dims[2],
                        height = dims[0],
                        width = dims[1],
                        channelsLast = true
                    )
                    else -> null
                }
            }
        }
    }

    private const val SEGMENT_FEATURES = 116
    private const val MASK_CHANNELS = 32
    private const val MASK_COEFFICIENT_START = 84
    private const val PERSON_SCORE_FEATURE = 4
    private const val SEGMENT_SCORE_THRESHOLD = 0.25f
    private const val SEGMENT_IOU_THRESHOLD = 0.45f
    private const val MASK_THRESHOLD = 0.50f
    private const val CONTOUR_BOUNDS_PADDING = 0.04f
    private const val MIN_CONTOUR_POINTS = 4
    private const val MAX_CONTOUR_POINTS = 96
    private const val MAX_SEGMENTS = 3
}

internal object WholeBodyPoseParser {

    fun parse(
        outputs: List<YoloOnnxOutput>,
        transform: WholeBodyInputTransform
    ): WholeBodyPoseEstimate {
        val layouts = outputs.mapNotNull { output ->
            SimccLayout.fromShape(output.shape, output.values.size)?.let { output to it }
        }
        if (layouts.size < 2) return WholeBodyPoseEstimate.Empty

        val xOutput = layouts.minByOrNull { (_, layout) ->
            kotlin.math.abs(layout.bins - transform.inputWidth * SIMCC_SPLIT_RATIO)
        } ?: return WholeBodyPoseEstimate.Empty
        val yOutput = layouts
            .filterNot { it === xOutput }
            .minByOrNull { (_, layout) ->
                kotlin.math.abs(layout.bins - transform.inputHeight * SIMCC_SPLIT_RATIO)
            } ?: return WholeBodyPoseEstimate.Empty

        val xLayout = xOutput.second
        val yLayout = yOutput.second
        val keypointCount = minOf(WHOLE_BODY_KEYPOINT_COUNT, xLayout.keypoints, yLayout.keypoints)
        val keypoints = (0 until keypointCount).mapNotNull { keypointIndex ->
            val xPeak = xLayout.argmax(xOutput.first.values, keypointIndex)
            val yPeak = yLayout.argmax(yOutput.first.values, keypointIndex)
            val confidence = ((xPeak.score.toConfidence() + yPeak.score.toConfidence()) / 2f).coerceIn(0f, 1f)
            if (confidence < KEYPOINT_SCORE_THRESHOLD) return@mapNotNull null
            WholeBodyKeypoint(
                index = keypointIndex,
                point = transform.pointToNormalized(
                    V2Point(
                        x = (xPeak.index + 0.5f) * transform.inputWidth / xLayout.bins,
                        y = (yPeak.index + 0.5f) * transform.inputHeight / yLayout.bins
                    )
                ),
                confidence = confidence
            )
        }
        if (keypoints.isEmpty()) return WholeBodyPoseEstimate.Empty
        return WholeBodyPoseEstimate(
            keypoints = keypoints,
            confidence = keypoints.map { it.confidence }.average().toFloat()
        )
    }

    fun groupForIndex(index: Int): WholeBodyPart =
        when (index) {
            in WholeBodyPoseEstimate.BODY_RANGE -> WholeBodyPart.BODY
            in WholeBodyPoseEstimate.FOOT_RANGE -> WholeBodyPart.FOOT
            in WholeBodyPoseEstimate.FACE_RANGE -> WholeBodyPart.FACE
            in WholeBodyPoseEstimate.LEFT_HAND_RANGE -> WholeBodyPart.LEFT_HAND
            in WholeBodyPoseEstimate.RIGHT_HAND_RANGE -> WholeBodyPart.RIGHT_HAND
            else -> WholeBodyPart.UNKNOWN
        }

    private fun Float.toConfidence(): Float =
        when {
            isNaN() -> 0f
            this in 0f..1f -> this
            else -> (1.0 / (1.0 + exp(-toDouble()))).toFloat()
        }

    private data class Peak(
        val index: Int,
        val score: Float
    )

    private data class SimccLayout(
        val keypoints: Int,
        val bins: Int,
        val keypointsFirst: Boolean
    ) {
        fun argmax(values: List<Float>, keypoint: Int): Peak {
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (bin in 0 until bins) {
                val score = value(values, keypoint, bin)
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = bin
                }
            }
            return Peak(bestIndex, bestScore.takeIf { it.isFinite() } ?: 0f)
        }

        private fun value(values: List<Float>, keypoint: Int, bin: Int): Float {
            val index = if (keypointsFirst) {
                keypoint * bins + bin
            } else {
                bin * keypoints + keypoint
            }
            return values.getOrNull(index) ?: Float.NEGATIVE_INFINITY
        }

        companion object {
            fun fromShape(shape: LongArray, totalValues: Int): SimccLayout? {
                val dims = shape.toList()
                    .filter { it > 0 }
                    .dropLeadingBatchDimension()
                    .map { it.toInt() }
                    .ifEmpty { inferDims(totalValues) }
                if (dims.size != 2) return null
                val first = dims[0]
                val second = dims[1]
                return when {
                    first == WHOLE_BODY_KEYPOINT_COUNT -> SimccLayout(
                        keypoints = first,
                        bins = second,
                        keypointsFirst = true
                    )
                    second == WHOLE_BODY_KEYPOINT_COUNT -> SimccLayout(
                        keypoints = second,
                        bins = first,
                        keypointsFirst = false
                    )
                    else -> null
                }
            }

            private fun inferDims(totalValues: Int): List<Int> =
                if (totalValues % WHOLE_BODY_KEYPOINT_COUNT == 0) {
                    listOf(WHOLE_BODY_KEYPOINT_COUNT, totalValues / WHOLE_BODY_KEYPOINT_COUNT)
                } else {
                    emptyList()
                }
        }
    }

    private fun List<Long>.dropLeadingBatchDimension(): List<Long> =
        if (size > 2 && first() == 1L) drop(1) else this

    private const val WHOLE_BODY_KEYPOINT_COUNT = 133
    private const val SIMCC_SPLIT_RATIO = 2
    private const val KEYPOINT_SCORE_THRESHOLD = 0.18f
}

enum class WholeBodyPart {
    BODY,
    FOOT,
    FACE,
    LEFT_HAND,
    RIGHT_HAND,
    UNKNOWN
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
            .filter { it > 0 }
            .dropLeadingBatchDimension()
            .map { it.toInt() }
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

    private fun List<Long>.dropLeadingBatchDimension(): List<Long> =
        if (size > 2 && first() == 1L) drop(1) else this

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
