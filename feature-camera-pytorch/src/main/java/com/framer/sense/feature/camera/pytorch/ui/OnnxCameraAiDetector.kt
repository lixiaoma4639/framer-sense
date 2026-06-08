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

/**
 * ONNX 模型推理, 探测者
 */
class OnnxCameraAiDetector(
    context: Context
) : Closeable {

    private val appContext = context.applicationContext
    // 获取 ONNX Runtime 环境。
    private val environment = OrtEnvironment.getEnvironment()

    // 创建 session 会话。 ONNX Runtime 的模型推理会话
    private val session = environment.createSession(
        copyModelToCache().absolutePath,
        OrtSession.SessionOptions()
    )

    //获取模型第一个输入节点名称，后面执行推理时要用它传入 tensor。
    private val inputName = session.inputNames.first()

    fun detect(imageProxy: ImageProxy): CameraAnalysisResult {
        //这里会把 CameraX 的 YUV 图像转换成 RGB，并缩放采样到 300 x 300
        val inputRgbBuffer = imageProxy.toModelInputBuffer()
        //计算画面亮度, 通过YUV 数据的Y亮度进行计算
        val luminance = imageProxy.averageLuminance()

        //创建 ONNX 输入 tensor张量
        val inputTensor = OnnxTensor.createTensor(
            environment,
            inputRgbBuffer,
            INPUT_SHAPE, //也就是 NHWC 格式：batch=1，高=300，宽=300，RGB=3。
            OnnxJavaType.UINT8
        )

        //Kotlin 的 use 是对 Closeable 和 AutoCloseable 的扩展函数，用于自动关闭资源，
        //类似 Java 的 try-with-resources。无论正常结束还是发生异常，都会在 finally 中调用 close()。
        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                //把输入 tensor 传给模型，得到输出结果。
                //detection_boxes：检测框坐标, 格式[ymin, xmin, ymax, xmax] 其实就是top, left, bottom, right
                val boxes = flattenNumbers(result.valueFor("detection_boxes"))
                //detection_scores：置信度, 评分
                val scores = flattenNumbers(result.valueFor("detection_scores"))
                //detection_classes：类别 ID
                val classes = flattenNumbers(result.valueFor("detection_classes"))
                //num_detections：有效检测结果数量
                val detections = flattenNumbers(result.valueFor("num_detections"))
                    .firstOrNull()
                    ?.toInt()
                    ?: minOf(scores.size, classes.size, boxes.size / BOX_SIZE)

                //这里创建两个可变列表，用来分类保存模型检测结果：
                //people  = 检测到的人
                //objects = 检测到的其他物体
                val people = mutableListOf<DetectedObjectFrame>()
                val objects = mutableListOf<DetectedObjectFrame>()

                repeat(minOf(detections, scores.size, classes.size, boxes.size / BOX_SIZE)) { index ->
                    val score = scores[index]
                    //置信度的可信程度。所以低于 0.42 的结果不参与后续构图判断。跳出该循环
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

    /**
     * ImageProxy 是 CameraX ImageAnalysis 回调里拿到的一帧相机画面，可以理解成“相机当前帧的包装对象
     * 这个方法负责把相机帧转换成模型输入。
     * NHWC：像素优先 → 同一个像素的 R/G/B 挨在一起存 [1张, 高300, 宽300, 3通道]
     * NCHW（PyTorch 默认格式）：通道优先 → 先存完所有 R，再存 G，再存 B [1张, 3通道, 高300, 宽300]
     */
    private fun ImageProxy.toModelInputBuffer(): ByteBuffer {
        //创建直接内存 buffer，大小是 300 * 300 * 3。allocateDirect创建C层内存ByteBuffer数组
        val buffer = ByteBuffer.allocateDirect(MODEL_WIDTH * MODEL_HEIGHT * RGB_CHANNELS)
        //图像旋转角度,如果图像旋转了 90 或 270 度，宽高会互换
        val rotationDegrees = imageInfo.rotationDegrees
        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

        //然后遍历模型输入尺寸里的每个像素：
        for (modelY in 0 until MODEL_HEIGHT) {
            for (modelX in 0 until MODEL_WIDTH) {
                //这相当于做了一次简单的缩放采样，把原始图像缩小到模型需要的 300x300。
                val rotatedX = modelX * rotatedWidth / MODEL_WIDTH
                val rotatedY = modelY * rotatedHeight / MODEL_HEIGHT

                //把“旋转后图像坐标”转换回 ImageProxy 原始图像中的坐标。
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
        //想重新从头读：position=0可以再次读取。
        buffer.rewind()
        return buffer
    }

    /**
     * 把“旋转后图像坐标”转换回 ImageProxy 原始图像中的坐标。
     */
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

    /**
     * 通过ImageProxy中的planes中的YUV数据，把YUV转换成RGB
     */
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
        //duplicate() 会复制一个新的 ByteBuffer 视图
        val buffer = planes.firstOrNull()?.buffer?.duplicate() ?: return 128.0
        var sum = 0L
        var count = 0
        //buffer.remaining() 剩余可读字节
        val step = maxOf(1, buffer.remaining() / LUMINANCE_SAMPLE_COUNT)
        while (buffer.hasRemaining()) {
            sum += buffer.get().toInt() and 0xFF
            count++
            //coerceAtMost如果当前值大于指定值，则返回指定值；否则返回当前值。保证不会跳到 buffer 末尾之外。
            val nextPosition = (buffer.position() + step).coerceAtMost(buffer.limit())
            buffer.position(nextPosition)
        }
        return if (count == 0) 128.0 else sum.toDouble() / count
    }

    private fun OrtSession.Result.valueFor(outputName: String): Any? =
        get(outputName).orElse(null)?.value

    /**
     * ONNX 输出的多维数组统一压平成 List<Float>
     */
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

        /**
         * shape=torch.Size([1, 5, 5, 3]), row=1, columns=5, 3
         * NHWC：像素优先 → 同一个像素的 R/G/B 挨在一起存 [1张, 高300, 宽300, 3通道]
         * NCHW（PyTorch 默认格式）：通道优先 → 先存完所有 R，再存 G，再存 B [1张, 3通道, 高300, 宽300]
         * 这里用的是 NHWC：像素优先格式
         */
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
