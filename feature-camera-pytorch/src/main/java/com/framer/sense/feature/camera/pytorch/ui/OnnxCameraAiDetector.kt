package com.framer.sense.feature.camera.pytorch.ui

import android.content.Context
import androidx.camera.core.ImageProxy
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

class OnnxCameraAiDetector(
    context: Context
) : Closeable {

    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(
        copyModelToCache().absolutePath,
        OrtSession.SessionOptions()
    )
    private val inputName = session.inputNames.first()

    fun detect(imageProxy: ImageProxy): CameraAnalysisResult {
        val inputBuffer = imageProxy.toModelInputBuffer()
        val luminance = imageProxy.averageLuminance()
        val inputTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            INPUT_SHAPE,
            OnnxJavaType.UINT8
        )

        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val boxes = flattenNumbers(result.valueFor("detection_boxes"))
                val scores = flattenNumbers(result.valueFor("detection_scores"))
                val classes = flattenNumbers(result.valueFor("detection_classes"))
                val detections = flattenNumbers(result.valueFor("num_detections"))
                    .firstOrNull()
                    ?.toInt()
                    ?: minOf(scores.size, classes.size, boxes.size / BOX_SIZE)
                val people = mutableListOf<DetectedObjectFrame>()
                val objects = mutableListOf<DetectedObjectFrame>()

                repeat(minOf(detections, scores.size, classes.size, boxes.size / BOX_SIZE)) { index ->
                    val score = scores[index]
                    if (score < DETECTION_SCORE_THRESHOLD) return@repeat

                    val boxOffset = index * BOX_SIZE
                    val classId = classes[index].toInt()
                    val frame = DetectedObjectFrame(
                        bounds = NormalizedRect(
                            left = boxes[boxOffset + 1],
                            top = boxes[boxOffset],
                            right = boxes[boxOffset + 3],
                            bottom = boxes[boxOffset + 2]
                        ).clamped(),
                        confidence = score,
                        classId = classId,
                        label = labelForClass(classId)
                    )

                    if (frame.bounds.area <= MIN_BOX_AREA) return@repeat
                    if (classId == PERSON_CLASS_ID) {
                        people += frame
                    } else if (score >= OBJECT_SCORE_THRESHOLD) {
                        objects += frame
                    }
                }

                return CameraAnalysisResult(
                    people = people,
                    objects = objects,
                    luminance = luminance
                )
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun copyModelToCache(): File {
        val modelFile = File(appContext.cacheDir, MODEL_ASSET_PATH.substringAfterLast('/'))
        appContext.assets.open(MODEL_ASSET_PATH).use { input ->
            if (modelFile.exists() && modelFile.length() == input.available().toLong()) {
                return modelFile
            }
        }
        appContext.assets.open(MODEL_ASSET_PATH).use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return modelFile
    }

    private fun ImageProxy.toModelInputBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(MODEL_WIDTH * MODEL_HEIGHT * RGB_CHANNELS)
        val rotationDegrees = imageInfo.rotationDegrees
        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

        for (modelY in 0 until MODEL_HEIGHT) {
            for (modelX in 0 until MODEL_WIDTH) {
                val rotatedX = modelX * rotatedWidth / MODEL_WIDTH
                val rotatedY = modelY * rotatedHeight / MODEL_HEIGHT
                val sourcePoint = mapRotatedPointToSource(
                    x = rotatedX,
                    y = rotatedY,
                    rotatedWidth = rotatedWidth,
                    rotatedHeight = rotatedHeight,
                    sourceWidth = width,
                    sourceHeight = height,
                    rotationDegrees = rotationDegrees
                )
                val rgb = readRgb(sourcePoint.first, sourcePoint.second)
                buffer.put(rgb.first.toByte())
                buffer.put(rgb.second.toByte())
                buffer.put(rgb.third.toByte())
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun ImageProxy.mapRotatedPointToSource(
        x: Int,
        y: Int,
        rotatedWidth: Int,
        rotatedHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> =
        when (rotationDegrees) {
            90 -> y.coerceIn(0, sourceWidth - 1) to (sourceHeight - 1 - x).coerceIn(0, sourceHeight - 1)
            180 -> (sourceWidth - 1 - x).coerceIn(0, sourceWidth - 1) to
                (sourceHeight - 1 - y).coerceIn(0, sourceHeight - 1)
            270 -> (sourceWidth - 1 - y).coerceIn(0, sourceWidth - 1) to x.coerceIn(0, sourceHeight - 1)
            else -> (x * sourceWidth / rotatedWidth).coerceIn(0, sourceWidth - 1) to
                (y * sourceHeight / rotatedHeight).coerceIn(0, sourceHeight - 1)
        }

    private fun ImageProxy.readRgb(x: Int, y: Int): Triple<Int, Int, Int> {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yValue = yPlane.valueAt(x, y)
        val uvX = x / 2
        val uvY = y / 2
        val uValue = uPlane.valueAt(uvX, uvY)
        val vValue = vPlane.valueAt(uvX, uvY)
        val adjustedY = (yValue - 16).coerceAtLeast(0)
        val adjustedU = uValue - 128
        val adjustedV = vValue - 128

        val red = (1.164f * adjustedY + 1.596f * adjustedV).toInt().coerceIn(0, 255)
        val green = (1.164f * adjustedY - 0.392f * adjustedU - 0.813f * adjustedV).toInt().coerceIn(0, 255)
        val blue = (1.164f * adjustedY + 2.017f * adjustedU).toInt().coerceIn(0, 255)
        return Triple(red, green, blue)
    }

    private fun ImageProxy.PlaneProxy.valueAt(x: Int, y: Int): Int {
        val index = y * rowStride + x * pixelStride
        val duplicate = buffer.duplicate()
        if (index !in 0 until duplicate.limit()) return 128
        return duplicate.get(index).toInt() and 0xFF
    }

    private fun ImageProxy.averageLuminance(): Double {
        val buffer = planes.firstOrNull()?.buffer?.duplicate() ?: return 128.0
        var sum = 0L
        var count = 0
        val step = maxOf(1, buffer.remaining() / LUMINANCE_SAMPLE_COUNT)
        while (buffer.hasRemaining()) {
            sum += buffer.get().toInt() and 0xFF
            count++
            val nextPosition = (buffer.position() + step).coerceAtMost(buffer.limit())
            buffer.position(nextPosition)
        }
        return if (count == 0) 128.0 else sum.toDouble() / count
    }

    private fun OrtSession.Result.valueFor(outputName: String): Any? =
        get(outputName).orElse(null)?.value

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

    private fun labelForClass(classId: Int): String =
        if (classId in COCO_LABELS.indices) COCO_LABELS[classId] else "class_$classId"

    private companion object {
        const val MODEL_ASSET_PATH = "models/ssd_mobilenet_v1_12-int8.onnx"
        const val MODEL_WIDTH = 300
        const val MODEL_HEIGHT = 300
        const val RGB_CHANNELS = 3
        const val BOX_SIZE = 4
        const val PERSON_CLASS_ID = 1
        const val DETECTION_SCORE_THRESHOLD = 0.42f
        const val OBJECT_SCORE_THRESHOLD = 0.50f
        const val MIN_BOX_AREA = 0.006f
        const val LUMINANCE_SAMPLE_COUNT = 800
        val INPUT_SHAPE = longArrayOf(1, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong(), RGB_CHANNELS.toLong())

        val COCO_LABELS = listOf(
            "background",
            "person",
            "bicycle",
            "car",
            "motorcycle",
            "airplane",
            "bus",
            "train",
            "truck",
            "boat",
            "traffic light",
            "fire hydrant",
            "street sign",
            "stop sign",
            "parking meter",
            "bench",
            "bird",
            "cat",
            "dog",
            "horse",
            "sheep",
            "cow",
            "elephant",
            "bear",
            "zebra",
            "giraffe",
            "hat",
            "backpack",
            "umbrella",
            "shoe",
            "eye glasses",
            "handbag",
            "tie",
            "suitcase",
            "frisbee",
            "skis",
            "snowboard",
            "sports ball",
            "kite",
            "baseball bat",
            "baseball glove",
            "skateboard",
            "surfboard",
            "tennis racket",
            "bottle",
            "plate",
            "wine glass",
            "cup",
            "fork",
            "knife",
            "spoon",
            "bowl",
            "banana",
            "apple",
            "sandwich",
            "orange",
            "broccoli",
            "carrot",
            "hot dog",
            "pizza",
            "donut",
            "cake",
            "chair",
            "couch",
            "potted plant",
            "bed",
            "mirror",
            "dining table",
            "window",
            "desk",
            "toilet",
            "door",
            "tv",
            "laptop",
            "mouse",
            "remote",
            "keyboard",
            "cell phone",
            "microwave",
            "oven",
            "toaster",
            "sink",
            "refrigerator",
            "blender",
            "book",
            "clock",
            "vase",
            "scissors",
            "teddy bear",
            "hair drier",
            "toothbrush"
        )
    }
}
