package com.framer.sense.feature.camera.pytorch.v2.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.Map.entry
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt

// 单帧 ONNX 分析器：把 CameraX 的 ImageProxy 输入到多个 ONNX 模型，再合并成人体、物体、姿态、分割和场景结果。
class CameraV2OnnxAnalyzer(
    // ONNX 会话池，内部持有可用的检测、分割、姿态等模型会话。
    private val sessions: OnnxSessionPool,
    // 帧预处理器，负责亮度统计、图像缩放、裁剪、归一化和坐标变换信息生成。
    private val preprocessor: FramePreprocessor = FramePreprocessor()
) {

    // 分析一帧相机图像，并返回 UI 构图引擎需要的完整分析结果。
    fun analyze(imageProxy: ImageProxy): CameraV2Analysis {
        // 先统计当前画面平均亮度，用于后续提示环境过暗或曝光问题。
        val luminance = preprocessor.averageLuminance(imageProxy)
        // 把相机帧转换成 YOLO 模型需要的 RGB FloatBuffer，同时保存输入坐标到原图归一化坐标的变换。
        val objectInput = preprocessor.toYoloRgbFloatBuffer(imageProxy, YOLO_SIZE)
        // 如果物体检测模型可用，就运行检测；不可用时保持 null。
        val detectedObjects: Pair<List<ScenePerson>, List<SceneObject>>? = sessions.objectSession?.let { runObjectDetector(it, objectInput) }
        // 如果人体分割模型可用，就运行分割；不可用时返回空列表。
        val personSegments: List<PersonSegmentation> = sessions.segmentationSession?.let { runSegmentationDetector(it, objectInput) }.orEmpty()
        // 分割结果通常比普通检测的人体框更可靠；有分割时优先把分割框作为人。
        val people = if (personSegments.isNotEmpty()) {
            // 将每个人体分割结果转换成 ScenePerson，保留边界框和置信度。
            personSegments.map { ScenePerson(bounds = it.bounds, confidence = it.confidence) }
        } else {
            // 没有分割结果时，退回使用普通物体检测中的 person 类。
            detectedObjects?.first.orEmpty()
        }
        // 物体列表来自普通目标检测；如果检测模型不可用则为空。
        val objects = detectedObjects?.second.orEmpty()
        // 选择一个最适合做人全身姿态估计的目标框：面积和置信度乘积越大越优先。
        val wholeBodyTarget = personSegments.maxByOrNull { it.bounds.area * it.confidence }?.bounds
            // 如果没有分割框，就从检测到的人体框里选最可靠的目标。
            ?: people.maxByOrNull { it.bounds.area * it.confidence }?.bounds
        // 只有找到人体目标框时，才运行 whole-body 姿态模型。
        val wholeBodyPose: WholeBodyPoseEstimate = if (wholeBodyTarget != null) {
            // 如果 whole-body 姿态模型会话可用，则尝试运行它。
            sessions.wholeBodyPoseSession?.let { session ->
                // whole-body 姿态是增强能力，失败时不让整帧分析失败。
                runCatching {
                    // 对目标人体框做裁剪预处理，并运行 whole-body 姿态模型。
                    runWholeBodyPoseDetector(
                        // 当前 whole-body 姿态模型会话。
                        session = session,
                        // 原始相机帧，裁剪需要从原图读取。
                        imageProxy = imageProxy,
                        // 需要做人全身姿态估计的人体边界框。
                        targetBounds = wholeBodyTarget
                    )
                // 如果 whole-body 推理失败，就返回空姿态并由上层显示汉服引导。
                }.getOrDefault(WholeBodyPoseEstimate.Empty)
            // 模型会话不可用时返回空姿态。
            } ?: WholeBodyPoseEstimate.Empty
        } else {
            // 没有检测到人体目标时，whole-body 姿态为空。
            WholeBodyPoseEstimate.Empty
        }
        // 汇总这一帧所有模型和推断结果，返回给上层构图引擎。
        return CameraV2Analysis(
            // 当前帧中识别到的人。
            people = people,
            // 当前帧中识别到的非人体物体。
            objects = objects,
            // 当前帧中识别到的人体分割轮廓。
            personSegments = personSegments,
            // YOLO Pose 已退出当前业务链路；保留兼容字段但不再写入姿态数据。
            pose = PoseEstimate.Empty,
            // 更完整的全身姿态点集合，包含身体、脚、脸、手等更多关键点。
            wholeBodyPose = wholeBodyPose,
            // 根据检测到的物体粗略推断室内、城市、户外等场景。
            semanticScene = YoloSceneInferencer.infer(objects),
            // 当前帧亮度。
            luminance = luminance,
            // 当前 ONNX 模型可用性，用于 UI 或状态机判断能力缺失。
            modelAvailability = sessions.availability,
            // 当前帧按显示方向修正后的宽高比。
            frameAspectRatio = preprocessor.orientedFrameAspectRatio(imageProxy)
        )
    }

    // 运行 YOLO 目标检测模型，输出人体列表和其他物体列表。
    private fun runObjectDetector(
        // ONNX Runtime 模型会话。
        session: OrtSession,
        // YOLO 输入数据和坐标变换信息。
        yoloInput: YoloInput
    ): Pair<List<ScenePerson>, List<SceneObject>> {
        // 读取模型第一个输入名，ONNX Runtime 运行时必须用输入名传 tensor。
        val inputName = session.inputNames.first()
        // FloatBuffer 可能刚被写完或用过，rewind 后从开头读给 ONNX。
        yoloInput.buffer.rewind()
        // 创建 ONNX Tensor，形状为 [batch, channels, height, width]。
        OnnxTensor.createTensor(
            // 使用会话池共享的 ONNX Runtime 环境。
            sessions.environment,
            // 输入图像的 RGB float 数据。
            yoloInput.buffer,
            // YOLO 输入固定为 1x3x640x640。
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        // use 确保 tensor 使用完后释放 native 资源。
        ).use { tensor ->
            // 运行 ONNX 模型，并把输入 tensor 按输入名传入。
            session.run(mapOf(inputName to tensor)).use { result ->
                //输出张量: [1, 84, 8400]
                //1     = batch，一次处理 1 张图
                //84    = 每个候选框的特征数量; 其中 4  = bbox 框信息：cx, cy, w, h; 80 = COCO 80 个类别的分数
                //8400  = 候选框数量

                // 目标检测通常只关心第一个输出。
                val output = result.firstOutput()
                // 把 ONNX 输出解析成 ScenePerson 和 SceneObject。
                return YoloOutputParser.parseObjects(
                    // 将可能的多维数组输出摊平成 Float 列表。
                    values = flattenNumbers(output?.value),
                    // 读取输出张量形状；没有形状时传空数组让解析器兜底。
                    shape = output?.tensorShape() ?: LongArray(0),
                    // 坐标转换器用于把 YOLO 输入坐标还原到原图归一化坐标。
                    transform = yoloInput.transform
                )
            }
        }
    }

    // 运行 YOLO pose 模型，输出普通人体关键点姿态。
    private fun runPoseDetector(
        // ONNX Runtime 姿态模型会话。
        session: OrtSession,
        // 与目标检测共用的 YOLO 输入。
        yoloInput: YoloInput
    ): PoseEstimate {
        // 读取模型输入名。
        val inputName = session.inputNames.first()
        // 重置 buffer 读位置。
        yoloInput.buffer.rewind()
        // 创建 1x3x640x640 输入 tensor。
        OnnxTensor.createTensor(
            // ONNX Runtime 环境。
            sessions.environment,
            // 输入图像数据。
            yoloInput.buffer,
            // YOLO pose 输入形状。
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        // 自动释放 tensor。
        ).use { tensor ->
            // 运行姿态模型。
            session.run(mapOf(inputName to tensor)).use { result ->
                // 读取第一个输出。输出 [1, 56, 8400],
                // 56 = 4 + 1 + 17 * 3
                //4      = 人体框 bbox：cx, cy, w, h
                //1      = 当前候选人体置信度
                //17 * 3 = 17 个人体关键点，每个关键点包含 x, y, score
                val output = result.firstOutput()
                Log.i("OnnxSegmentation----",
//                    "entry.value.value = ${(output?.value as Array<*>).contentDeepToString()}, " +
                            "yolo-pose-shape = ${output?.tensorShape().contentToString()}"
                )
                // 解析 YOLO pose 输出为 PoseEstimate。
                return YoloOutputParser.parsePose(
                    // 摊平输出数值。
                    values = flattenNumbers(output?.value),
                    // 传入输出形状辅助判断布局。
                    shape = output?.tensorShape() ?: LongArray(0),
                    // 用于坐标还原。
                    transform = yoloInput.transform
                )
            }
        }
    }

    // 运行 YOLO segmentation 模型，输出人体分割轮廓。
    private fun runSegmentationDetector(
        // ONNX Runtime 分割模型会话。
        session: OrtSession,
        // YOLO 输入数据。
        yoloInput: YoloInput
    ): List<PersonSegmentation> {
        // 获取模型输入名。
        val inputName = session.inputNames.first()
        // 重置输入 buffer 读位置。
        yoloInput.buffer.rewind()
        // 创建分割模型输入 tensor。
        OnnxTensor.createTensor(
            // ONNX Runtime 环境。
            sessions.environment,
            // 输入图像数据。
            yoloInput.buffer,
            // 分割模型同样使用 1x3x640x640 输入。
            longArrayOf(1, RGB_CHANNELS.toLong(), YOLO_SIZE.toLong(), YOLO_SIZE.toLong())
        // 释放 tensor native 资源。
        ).use { tensor ->
            // 运行分割模型。
            session.run(mapOf(inputName to tensor)).use { result ->
                // 分割模型通常有多个输出：候选框/系数输出和 mask proto 输出。
                val outputs = result.iterator().asSequence().map { entry ->
                    // 将每个 ONNX 输出包装成统一结构，保存数值和形状。
//                    Log.i("OnnxSegmentation----",
//                        "entry.value.value数组维度 = ${entry.value.value.printArrayDimension()}, shape = ${entry.value.tensorShape().printArrayDimension()}"
//                    )
//                    Log.i("OnnxSegmentation----",
//                        "entry.value.value = ${(entry.value.value as Array<*>).contentDeepToString()}, " +
//                                "shape = ${entry.value.tensorShape().contentToString()}"
//                    )
                    YoloOnnxOutput(
                        // 摊平当前输出的数值。
                        values = flattenNumbers(entry.value.value),
                        // 保存当前输出的张量形状。
                        shape = entry.value.tensorShape()
                    )
                // 立即转成列表，供后续解析器多次查找不同输出。
                }.toList()
                // 解析人体分割候选、mask proto 和轮廓点。
                return YoloSegmentationParser.parsePersonSegments(
                    // 所有 ONNX 输出。
                    outputs = outputs,
                    // YOLO 输入坐标到原图归一化坐标的转换器。
                    transform = yoloInput.transform
                )
            }
        }
    }

    // 扩展函数：打印并返回数组维度
    fun Any.printArrayDimension(): Int {
        val dim = if (this.javaClass.isArray) {
            this.javaClass.name.takeWhile { it == '[' }.length
        } else {
            0
        }
        return dim
    }

    // 运行 whole-body 姿态模型；它基于一个人体框裁剪输入，输出更密集的 133 个关键点。
    private fun runWholeBodyPoseDetector(
        // ONNX Runtime whole-body 姿态模型会话。
        session: OrtSession,
        // 原始相机帧。
        imageProxy: ImageProxy,
        // 需要裁剪的人体目标框。
        targetBounds: V2Rect
    ): WholeBodyPoseEstimate {
        // 从原始帧里按人体框裁剪并缩放到 whole-body 模型输入尺寸。
        val input = preprocessor.toWholeBodyRgbFloatBuffer(
            // 原始图像。
            imageProxy = imageProxy,
            // 裁剪区域。
            bounds = targetBounds,
            // 模型输入宽度。
            width = WHOLE_BODY_WIDTH,
            // 模型输入高度。
            height = WHOLE_BODY_HEIGHT
        )
        // 读取模型输入名。
        val inputName = session.inputNames.first()
        // 重置 whole-body 输入 buffer。 把 FloatBuffer 的读写位置重置到开头，让 ONNX Runtime 从第 0 个 float 开始读取完整输入张量数据。
        input.buffer.rewind()
        // 创建 whole-body 模型输入 tensor。
        OnnxTensor.createTensor(
            // ONNX Runtime 环境。
            sessions.environment,
            // 裁剪后的人体 RGB float 数据。
            input.buffer,
            // whole-body 输入形状为 1x3x256x192。
            longArrayOf(1, RGB_CHANNELS.toLong(), WHOLE_BODY_HEIGHT.toLong(), WHOLE_BODY_WIDTH.toLong())
        // 使用后释放 tensor。
        ).use { tensor ->
            // 运行 whole-body 姿态模型。
            session.run(mapOf(inputName to tensor)).use { result ->
                // whole-body 模型可能有 x/y 两个 SimCC 输出，因此收集全部输出。
                // Simcc = Simple Coordinate Classification, 不直接回归一个连续坐标 x、y，而是把 x 轴和 y 轴分别当成分类问题来预测。
                // x 的输出的Simcc形状 shape = [1, 133, 384]
                // y 的输出的Simcc形状 shape = [1, 133, 512]
                val outputs = result.iterator().asSequence().map { entry ->
                    Log.i("OnnxSegmentation----",
                        //"entry.value.value = ${(entry.value.value as Array<*>).contentDeepToString()}, " +
                                "shape = ${entry.value.tensorShape().contentToString()}"
                    )

                    // 统一包装输出数值和形状。
                    YoloOnnxOutput(
                        // 摊平输出数据。
                        values = flattenNumbers(entry.value.value),
                        // 保存输出形状。
                        shape = entry.value.tensorShape()
                    )
                // 转成列表供解析器识别 x/y 输出。
                }.toList()
                // 解析 whole-body 姿态点，并用裁剪输入的 transform 还原到整帧归一化坐标。
                return WholeBodyPoseParser.parse(
                    // 模型所有输出。
                    outputs = outputs,
                    // whole-body 裁剪坐标到整帧归一化坐标的转换器。
                    transform = input.transform
                )
            }
        }
    }

    // 从 ONNX 运行结果中取第一个输出值。
    private fun OrtSession.Result.firstOutput(): OnnxValue? =
        // ONNX Result 可迭代，取第一项的 value；没有输出时返回 null。
        iterator().asSequence().firstOrNull()?.value

    // 读取 ONNX 输出值的张量形状。
    private fun OnnxValue.tensorShape(): LongArray =
        // 只有 TensorInfo 才有 shape；不是 tensor 时返回空形状。
        (info as? TensorInfo)?.shape ?: LongArray(0)

    // 当前分析器内部使用的模型输入尺寸常量。
    private companion object {
        // YOLO 系列模型输入尺寸，宽高都是 640。
        const val YOLO_SIZE = 640
        // whole-body 模型输入宽度。
        const val WHOLE_BODY_WIDTH = 192
        // whole-body 模型输入高度。
        const val WHOLE_BODY_HEIGHT = 256
        // RGB 三通道。
        const val RGB_CHANNELS = 3
    }
}

// 保留的历史兼容适配器；当前拍照业务不调用它，也不会把 133 点降级为 17 点。
private fun WholeBodyPoseEstimate.toPoseEstimate(): PoseEstimate? {
    // whole-body 总置信度太低时，不生成兼容姿态。
    if (confidence < 0.18f) return null
    // 读取 whole-body 原始关键点。
    val sourceKeypoints = keypoints
    // 按普通 PoseKeypointName 的顺序，从 whole-body 关键点里抽取对应点。
    val keypoints = PoseKeypointName.entries.mapIndexedNotNull { index, name ->
        // 只接受索引匹配且置信度足够的关键点。
        val keypoint = sourceKeypoints.firstOrNull { it.index == index && it.confidence >= 0.2f }
            // 当前点缺失或不可靠时跳过。
            ?: return@mapIndexedNotNull null
        // 将 whole-body 点转换成普通姿态点。
        PoseKeypoint(
            // 普通姿态关键点名称。
            name = name,
            // 已经归一化到整帧坐标的点。
            point = keypoint.point,
            // 保留原始置信度。
            confidence = keypoint.confidence
        )
    }
    // 普通姿态点太少时不生成兼容姿态。
    if (keypoints.size < 4) return null
    // 返回普通 PoseEstimate。
    return PoseEstimate(
        // 转换后的关键点列表。
        keypoints = keypoints,
        // 使用 whole-body 的整体置信度。
        confidence = confidence
    )
}

// 统一承载一个 ONNX 输出：数值已摊平，形状保留下来用于判断布局。
internal data class YoloOnnxOutput(
    // 输出 tensor 的所有数值。
    val values: List<Float>,
    // 输出 tensor 的 shape。 [1, 116, 8400] 或者 [1, 32, 160, 160]
    val shape: LongArray
)

// YOLO 检测和姿态输出解析器。
internal object YoloOutputParser {

    // 解析 YOLO 目标检测输出，返回人体和非人体物体。
    fun parseObjects(
        // ONNX 输出数值。
        values: List<Float>,
        // ONNX 输出形状。
        shape: LongArray,
        // 输入坐标到原图归一化坐标的转换器。
        transform: YoloInputTransform
    ): Pair<List<ScenePerson>, List<SceneObject>> {
        // 没有输出时直接返回空结果。
        if (values.isEmpty()) return emptyList<ScenePerson>() to emptyList()
        // 根据输出布局判断是模型已做 NMS 的格式，还是 YOLO 原始候选格式。
        val detections = when {
            // 已 NMS 输出通常是 [x1,y1,x2,y2,score,class] 六个特征。
            YoloTensorLayout.isNmsDetectionOutput(shape, values.size) -> parseNmsObjects(values, shape, transform)
            // 其他情况按原始 YOLO 候选输出解析。
            else -> parseRawObjects(values, shape, transform)
        // 解析后再做一次 NMS，去掉同类高度重叠框。
        }.nms(IOU_THRESHOLD)

        // 从检测结果中筛出 person 类。
        val people = detections
            // COCO 中 person 的 classId 是 0。
            .filter { it.classId == PERSON_CLASS_ID && it.confidence >= PERSON_SCORE_THRESHOLD }
            // 转成业务层 ScenePerson。
            .map { ScenePerson(bounds = it.bounds, confidence = it.confidence) }
        // 从检测结果中筛出非 person 的场景物体。
        val objects = detections
            // 去掉人体类别。
            .filterNot { it.classId == PERSON_CLASS_ID }
            // 物体使用单独阈值。
            .filter { it.confidence >= OBJECT_SCORE_THRESHOLD }
            // 转成业务层 SceneObject。
            .map { detection ->
                // 保存边界框、标签、置信度和 classId。
                SceneObject(
                    // 物体边界框。
                    bounds = detection.bounds,
                    // 根据 COCO classId 找标签；越界时使用 class_x 兜底。
                    label = COCO_LABELS.getOrElse(detection.classId) { "class_${detection.classId}" },
                    // 检测置信度。
                    confidence = detection.confidence,
                    // 原始类别 ID。
                    classId = detection.classId
                )
            }
        // 返回人体列表和物体列表。
        return people to objects
    }

    // 解析 YOLO pose 输出，返回普通人体姿态关键点。
    fun parsePose(
        // ONNX 输出数值。
        values: List<Float>,
        // ONNX 输出形状。
        shape: LongArray,
        // 坐标转换器。
        transform: YoloInputTransform
    ): PoseEstimate {
        // 没有输出时返回空姿态。
        if (values.isEmpty()) return PoseEstimate.Empty
        // 根据输出 shape 推断候选数量、特征数量和通道排列。
        val layout = YoloTensorLayout.fromShape(
            // 原始 shape。
            shape = shape,
            // 输出总元素数。
            totalValues = values.size,
            // YOLO pose 每个候选默认 56 个特征。
            preferredFeatures = YOLO_POSE_FEATURES
        // 无法识别布局时返回空姿态。
        ) ?: return PoseEstimate.Empty
        // 找到目标置信度最高的候选人体。
        val bestIndex = (0 until layout.candidates).maxByOrNull { candidate ->
            // feature 4 通常是候选整体置信度。
            layout.value(values, candidate, 4).coerceScore()
        // 没有候选时返回空姿态。
        } ?: return PoseEstimate.Empty
        // 读取最佳候选的整体置信度。
        val confidence = layout.value(values, bestIndex, 4).coerceScore()
        // 整体置信度低于阈值时，不使用该姿态。
        if (confidence < POSE_SCORE_THRESHOLD) return PoseEstimate.Empty

        // 按关键点定义顺序解析每个关键点。
        val keypoints: List<PoseKeypoint> = PoseKeypointName.entries.mapIndexedNotNull { index, name ->
            // 每个关键点由 x、y、score 三个值组成。
            val offset = POSE_KEYPOINT_START + index * POSE_KEYPOINT_STRIDE
            // 读取关键点置信度；如果模型没有该值，就退回使用整体置信度。
            val rawScore = layout.valueOrNull(values, bestIndex, offset + 2) ?: confidence
            // 把分数夹到 0..1，避免异常输出影响判断。
            val score = rawScore.coerceScore()
            // 单个关键点置信度太低时跳过。
            if (score < KEYPOINT_SCORE_THRESHOLD) return@mapIndexedNotNull null
            // 构造普通姿态关键点。
            PoseKeypoint(
                // 当前关键点名称。
                name = name,
                // 将 YOLO 输入坐标转换为原图归一化坐标。
                point = transform.pointToNormalized(
                    // 原始关键点坐标位于 YOLO 输入空间。
                    V2Point(
                        // 关键点 x。
                        x = layout.value(values, bestIndex, offset),
                        // 关键点 y。
                        y = layout.value(values, bestIndex, offset + 1)
                    )
                ),
                // 关键点置信度。
                confidence = score
            )
        }
        // 返回普通姿态估计。
        return PoseEstimate(keypoints = keypoints, confidence = confidence)
    }

    // 解析原始 YOLO 检测输出：每个候选包含框坐标、置信度/类别分数等特征。
    private fun parseRawObjects(
        // 摊平后的输出数值。
        values: List<Float>,
        // 输出 shape。
        shape: LongArray,
        // 坐标转换器。
        transform: YoloInputTransform
    ): List<YoloDetection> {
        // 优先按新版 YOLO 检测格式推断布局。
        val layout = YoloTensorLayout.fromShape(
            // 原始 shape。
            shape = shape,
            // 输出总元素数。
            totalValues = values.size,
            // 新版检测通常每个候选 84 个特征：4 个框坐标 + 80 个类别分数。
            preferredFeatures = YOLO_DETECT_FEATURES
        // 如果新版格式不匹配，再尝试旧版 85 特征格式：4 框 + objectness + 80 类别。
        ) ?: YoloTensorLayout.fromShape(
            // 原始 shape。
            shape = shape,
            // 输出总元素数。
            totalValues = values.size,
            // 旧版检测特征数。
            preferredFeatures = YOLO_LEGACY_DETECT_FEATURES
        // 两种格式都识别不了，就返回空。
        ) ?: return emptyList()
        // 85 特征格式包含 objectness；84 特征格式一般没有单独 objectness。
        val hasObjectness = layout.features >= YOLO_LEGACY_DETECT_FEATURES
        // 有 objectness 时类别分数从第 5 位开始，否则从第 4 位开始。
        val classStart = if (hasObjectness) 5 else 4
        // 类别数量不能超过 COCO 标签数，也不能超过输出实际特征数。
        val classCount = minOf(COCO_LABELS.size, layout.features - classStart)
        // 没有类别分数就无法解析检测结果。
        if (classCount <= 0) return emptyList()

        // 遍历每个候选框，筛选出置信度足够的检测。
        return (0 until layout.candidates).mapNotNull { candidate ->
            // 当前候选的最佳类别，-1 表示尚未找到。
            var bestClass = -1
            // 当前候选的最佳类别分数。
            var bestClassScore = 0f
            // 遍历所有 COCO 类别分数。
            for (classIndex in 0 until classCount) {
                // 读取该类别分数并限制到 0..1。
                val score = layout.value(values, candidate, classStart + classIndex).coerceScore()
                // 记录分数最高的类别。
                if (score > bestClassScore) {
                    // 更新最高类别分数。
                    bestClassScore = score
                    // 更新最佳类别 ID。
                    bestClass = classIndex
                }
            }
            // 旧版格式使用 objectness；新版格式默认 objectness 为 1。
            val objectness = if (hasObjectness) layout.value(values, candidate, 4).coerceScore() else 1f
            // 最终置信度 = 最佳类别分数 * objectness。
            val confidence = bestClassScore * objectness
            // 人体和普通物体使用不同阈值，人体阈值略低以提高召回。
            val threshold = if (bestClass == PERSON_CLASS_ID) PERSON_SCORE_THRESHOLD else OBJECT_SCORE_THRESHOLD
            // 没有有效类别或置信度不足时丢弃候选。
            if (bestClass < 0 || confidence < threshold) return@mapNotNull null
            // 构造内部检测结果。
            YoloDetection(
                // 原始 YOLO 框为中心点 + 宽高，转换成原图归一化矩形。
                bounds = transform.xywhToNormalizedRect(
                    // 框中心 x。
                    cx = layout.value(values, candidate, 0),
                    // 框中心 y。
                    cy = layout.value(values, candidate, 1),
                    // 框宽。
                    width = layout.value(values, candidate, 2),
                    // 框高。
                    height = layout.value(values, candidate, 3)
                ),
                // 检测置信度。
                confidence = confidence,
                // COCO 类别 ID。
                classId = bestClass
            )
        }
    }

    // 解析已经做过 NMS 的检测输出，常见格式是 x1,y1,x2,y2,score,class。
    private fun parseNmsObjects(
        // 摊平后的输出数值。
        values: List<Float>,
        // 输出 shape。
        shape: LongArray,
        // 坐标转换器。
        transform: YoloInputTransform
    ): List<YoloDetection> {
        // 按每个候选 6 个特征识别布局。
        val layout = YoloTensorLayout.fromShape(
            // 原始 shape。
            shape = shape,
            // 输出总元素数。
            totalValues = values.size,
            // NMS 输出特征数。
            preferredFeatures = YOLO_NMS_FEATURES
        // 无法识别 NMS 布局时返回空。
        ) ?: return emptyList()
        // 遍历候选检测框。
        return (0 until layout.candidates).mapNotNull { candidate ->
            // 第 4 个特征是置信度。
            val confidence = layout.value(values, candidate, 4).coerceScore()
            // 第 5 个特征是类别 ID。
            val classId = layout.value(values, candidate, 5).toInt()
            // 人体和物体使用各自阈值。
            val threshold = if (classId == PERSON_CLASS_ID) PERSON_SCORE_THRESHOLD else OBJECT_SCORE_THRESHOLD
            // 类别越界或置信度不足时丢弃。
            if (classId !in COCO_LABELS.indices || confidence < threshold) return@mapNotNull null
            // 构造检测结果。
            YoloDetection(
                // NMS 格式框为左上右下坐标，转换成原图归一化矩形。
                bounds = transform.xyxyToNormalizedRect(
                    // 左边界。
                    left = layout.value(values, candidate, 0),
                    // 上边界。
                    top = layout.value(values, candidate, 1),
                    // 右边界。
                    right = layout.value(values, candidate, 2),
                    // 下边界。
                    bottom = layout.value(values, candidate, 3)
                ),
                // 检测置信度。
                confidence = confidence,
                // COCO 类别 ID。
                classId = classId
            )
        }
    }

    // 对检测框执行非极大值抑制，去掉同类重叠框。
    private fun List<YoloDetection>.nms(iouThreshold: Float): List<YoloDetection> {
        // 保存最终保留的检测框。
        val kept = mutableListOf<YoloDetection>()
        // 按置信度从高到低排序，优先保留最可信框。
        val remaining = sortedByDescending { it.confidence }.toMutableList()
        // 只要还有候选且未超过最大保留数量，就持续筛选。
        while (remaining.isNotEmpty() && kept.size < MAX_DETECTIONS) {
            // 取当前置信度最高的框。
            val best = remaining.removeAt(0)
            // 保留该框。
            kept += best
            // 删除和 best 同类别且重叠度超过阈值的其他框。
            remaining.removeAll { candidate ->
                // 只有同类别框互相抑制，避免人和物体互相删除。
                candidate.classId == best.classId && best.bounds.iou(candidate.bounds) > iouThreshold
            }
        }
        // 返回保留下来的检测结果。
        return kept
    }

    // 计算两个矩形框的 IoU，用于判断重叠程度。
    private fun V2Rect.iou(other: V2Rect): Float {
        // 计算交集面积。
        val intersection = intersectionArea(other)
        // 并集面积 = 两个面积之和 - 交集面积。
        val union = area + other.area - intersection
        // 并集无效时返回 0，否则返回交并比。
        return if (union <= 0f) 0f else intersection / union
    }

    // 把模型输出分数限制在 0..1。
    private fun Float.coerceScore(): Float =
        // 避免模型异常输出负数或大于 1 的分数影响阈值判断。
        coerceIn(0f, 1f)

    // 内部检测结果结构，还未区分成人体/物体业务模型。
    private data class YoloDetection(
        // 检测框，使用原图归一化坐标。
        val bounds: V2Rect,
        // 检测置信度。
        val confidence: Float,
        // COCO 类别 ID。
        val classId: Int
    )

    // COCO 数据集中 person 类别 ID。
    private const val PERSON_CLASS_ID = 0
    // 人体检测阈值。
    private const val PERSON_SCORE_THRESHOLD = 0.25f
    // 普通物体检测阈值。
    private const val OBJECT_SCORE_THRESHOLD = 0.30f
    // YOLO pose 整体姿态阈值。
    private const val POSE_SCORE_THRESHOLD = 0.25f
    // 单个关键点阈值。
    private const val KEYPOINT_SCORE_THRESHOLD = 0.12f
    // NMS IoU 阈值。
    private const val IOU_THRESHOLD = 0.45f
    // 最多保留的检测框数量。
    private const val MAX_DETECTIONS = 40
    // 新版 YOLO 检测每个候选的特征数量。
    private const val YOLO_DETECT_FEATURES = 84
    // 旧版 YOLO 检测每个候选的特征数量。
    private const val YOLO_LEGACY_DETECT_FEATURES = 85
    // YOLO pose 每个候选的特征数量。
    private const val YOLO_POSE_FEATURES = 56
    // 已 NMS 检测输出每个候选的特征数量。
    private const val YOLO_NMS_FEATURES = 6
    // YOLO pose 中关键点数据起始特征下标。
    private const val POSE_KEYPOINT_START = 5
    // YOLO pose 每个关键点的步长：x、y、score。
    private const val POSE_KEYPOINT_STRIDE = 3

    // COCO 类别标签表，classId 会映射到这里的人类可读标签。
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

// YOLO segmentation 输出解析器，负责从候选框和 mask proto 中恢复人体轮廓。
internal object YoloSegmentationParser {

    // 解析人体分割输出。
    fun parsePersonSegments(
        /**
         * 分割模型的所有输出。有两个值:
         * prediction output，候选目标预测 index0 = [1, 116, 8400],
         *      116个特征分别是4 个 box 参数+ 80 个 class score+ 32 个 mask coefficients
         *
         * mask proto output，分割原型图 index1 = [1, 32, 160, 160]
         */
        outputs: List<YoloOnnxOutput>,
        // 坐标转换器。
        transform: YoloInputTransform
    ): List<PersonSegmentation> {
        // 找到候选预测输出，通常包含框、类别分数和 mask 系数。
        val predictionOutput = outputs.firstNotNullOfOrNull { output ->
            // 尝试用 segmentation 特征数量识别预测输出布局。
            val layout = YoloTensorLayout.fromShape(
                // 输出 shape。
                shape = output.shape,
                // 输出总元素数。
                totalValues = output.values.size,
                // 分割预测每个候选的特征数。
                preferredFeatures = SEGMENT_FEATURES
            )
            // 如果识别成功，返回输出和布局。
            layout?.let { output to it }
        // 找不到预测输出时无法解析分割。
        } ?: return emptyList()
        // 找到 mask proto 输出，它保存 32 个原型 mask 通道。
        val protoOutput = outputs.firstNotNullOfOrNull { output ->
            // 根据 shape 判断是否是 proto 输出。
            ProtoLayout.fromShape(output.shape)?.let { output to it }
        // 找不到 proto 输出时无法恢复轮廓。
        } ?: return emptyList()

        // 取出预测输出数据。原始的 prediction output，张量形状 = [1, 116, 8400], 这里的prediction是张量值，
        val prediction = predictionOutput.first
        // 取出预测输出布局。张量形状的更具体 描述, 也可以理解为还是 张量形状, 这里的形状更具有 业务的意义
        val predictionLayout = predictionOutput.second
        // 取出 proto 输出数据。原始的  mask proto output，分割原型图 张量形状 = [1, 32, 160, 160], 这里的proto是张量值，
        // 是模型对当前图片算出来的输出特征图 [1, 32, 160, 160], 这里的32类似CNN中输出的特征图个数 32个 160* 160的特征图
        val proto = protoOutput.first
        // 取出 proto 输出布局。
        val protoLayout = protoOutput.second
        // mask 系数数量 = 候选特征数 - 系数起始位置。
        val maskDimension = predictionLayout.features - MASK_COEFFICIENT_START
        // 系数数量必须和 proto 通道数一致，否则无法线性组合出 mask。
        if (maskDimension <= 0 || maskDimension != protoLayout.channels) return emptyList()

        // 先从预测输出中解析候选分割框和 mask 系数。
        val detections: List<SegDetection> = (0 until predictionLayout.candidates).mapNotNull { candidate ->
            // 读取人体置信度。
            val confidence = predictionLayout.value(prediction.values, candidate, PERSON_SCORE_FEATURE).coerceScore()
            // 置信度不足时丢弃该候选。
            if (confidence < SEGMENT_SCORE_THRESHOLD) return@mapNotNull null
            // 读取该候选对应的所有 mask 系数。
            val coefficients = FloatArray(maskDimension) { index ->
                // mask 系数从 MASK_COEFFICIENT_START 开始连续存放。
                predictionLayout.value(prediction.values, candidate, MASK_COEFFICIENT_START + index)
            }
            // 构造内部候选。
            SegDetection(
                // 分割候选框同样是中心点 + 宽高格式。
                bounds = transform.xywhToNormalizedRect(
                    // 中心 x。
                    cx = predictionLayout.value(prediction.values, candidate, 0),
                    // 中心 y。
                    cy = predictionLayout.value(prediction.values, candidate, 1),
                    // 宽。
                    width = predictionLayout.value(prediction.values, candidate, 2),
                    // 高。
                    height = predictionLayout.value(prediction.values, candidate, 3)
                ),
                // 候选置信度。
                confidence = confidence,
                // mask 线性组合系数。
                coefficients = coefficients
            )
        // 分割候选也做 NMS，这里8400个候选值, 满足条件的也不再少数, 隐藏为避免同一个人生成多个重叠 mask, 需要做NMS
        }.nms(SEGMENT_IOU_THRESHOLD).take(MAX_SEGMENTS)

        // 将候选框和 mask proto 转换成业务层人体分割结果。
        // detections: List<SegDetection>
        return detections.mapNotNull { detection ->
            // 生成该候选的人体轮廓点。
            val contour = buildContour(
                // 当前分割候选。
                detection = detection,
                // proto 原始数值。
                protoValues = proto.values,
                // proto 布局。
                protoLayout = protoLayout,
                // 坐标转换器。
                transform = transform
            )
            // 轮廓点太少时认为结果无效。
            if (contour.size < MIN_CONTOUR_POINTS) return@mapNotNull null
            // 构造最终人体分割结果。
            PersonSegmentation(
                // 人体边界框。
                bounds = detection.bounds,
                // 人体轮廓点。
                contour = contour,
                // 分割置信度。
                confidence = detection.confidence
            )
        }
    }

    // 根据分割候选的 mask 系数和 proto 输出生成轮廓点。
    private fun buildContour(
        // 分割候选。
        detection: SegDetection,
        // proto 输出数值。
        protoValues: List<Float>,
        // proto 输出布局。
        protoLayout: ProtoLayout,
        // 坐标转换器。
        transform: YoloInputTransform
    ): List<V2Point> {
        // 布尔 mask，大小等于 proto 的宽高。
        val mask = BooleanArray(protoLayout.width * protoLayout.height)
        // 遍历 proto mask 的每一行。
        for (y in 0 until protoLayout.height) {
            // 遍历 proto mask 的每一列。
            for (x in 0 until protoLayout.width) {
                // 将 proto 网格点映射到原图归一化坐标。
                val point = transform.pointToNormalized(
                    // proto 网格中心点先换算到 YOLO 输入坐标。
                    V2Point(
                        // 网格中心 x。
                        x = (x + 0.5f) * transform.inputSize / protoLayout.width,
                        // 网格中心 y。
                        y = (y + 0.5f) * transform.inputSize / protoLayout.height
                    )
                )
                // 只在候选框附近计算 mask，减少噪声和计算量。
                if (!point.inside(detection.bounds.expand(CONTOUR_BOUNDS_PADDING))) continue
                // 当前网格点的 mask logit 累加值。
                var sum = 0f
                // 遍历所有 proto 通道，用候选系数做线性组合。
                for (channel in 0 until protoLayout.channels) {
                    // sum += 系数 * 对应 proto 通道值。
                    sum += detection.coefficients[channel] * protoLayout.value(protoValues, channel, y, x)
                }
                // sigmoid 后超过阈值，认为该网格点属于人体 mask。
                mask[y * protoLayout.width + x] = sigmoid(sum) >= MASK_THRESHOLD
            }
        }

        // 保存 mask 边界点。
        val boundary = mutableListOf<V2Point>()
        // 跳过最外圈，避免检查邻居时越界。
        for (y in 1 until protoLayout.height - 1) {
            // 遍历当前行的内部列。
            for (x in 1 until protoLayout.width - 1) {
                // 当前点不属于 mask 时跳过。
                if (!mask[y * protoLayout.width + x]) continue
                // 如果四邻域存在非 mask 点，当前点就是边界点。
                val isBoundary =
                    // 左邻居不在 mask 内。
                    !mask[y * protoLayout.width + x - 1] ||
                        // 右邻居不在 mask 内。
                        !mask[y * protoLayout.width + x + 1] ||
                        // 上邻居不在 mask 内。
                        !mask[(y - 1) * protoLayout.width + x] ||
                        // 下邻居不在 mask 内。
                        !mask[(y + 1) * protoLayout.width + x]
                // 只有边界点才加入轮廓。
                if (isBoundary) {
                    // 将边界网格点转换到原图归一化坐标。
                    boundary += transform.pointToNormalized(
                        // proto 网格中心点坐标。
                        V2Point(
                            // 网格中心 x。
                            x = (x + 0.5f) * transform.inputSize / protoLayout.width,
                            // 网格中心 y。
                            y = (y + 0.5f) * transform.inputSize / protoLayout.height
                        )
                    )
                }
            }
        }
        // 如果边界点太少，就用检测框四个角作为兜底轮廓。
        if (boundary.size < MIN_CONTOUR_POINTS) return detection.bounds.toContour()
        // 对边界点排序并均匀采样，避免轮廓点过多。
        return boundary
            // 按相对中心的角度排序，形成近似顺时针轮廓。
            .orderClockwise()
            // 均匀采样到最多 96 个点。
            .sampleEvenly(MAX_CONTOUR_POINTS)
    }

    // 分割候选的 NMS，只按框重叠度抑制，不区分类别，因为这里都是人体。
    private fun List<SegDetection>.nms(iouThreshold: Float): List<SegDetection> {
        // 保存最终保留的分割候选。
        val kept = mutableListOf<SegDetection>()
        // 按置信度降序排列候选。
        val remaining = sortedByDescending { it.confidence }.toMutableList()
        // 循环保留高置信度候选，同时限制最大人体分割数量。
        while (remaining.isNotEmpty() && kept.size < MAX_SEGMENTS) {
            // 取当前最可信候选。
            val best = remaining.removeAt(0)
            // 保留该候选。
            kept += best
            // 删除和当前候选重叠度太高的其他候选。
            remaining.removeAll { best.bounds.iou(it.bounds) > iouThreshold }
        }
        // 返回 NMS 后的候选。
        return kept
    }

    // 计算两个矩形的 IoU。
    private fun V2Rect.iou(other: V2Rect): Float {
        // 交集面积。
        val intersection = intersectionArea(other)
        // 并集面积。
        val union = area + other.area - intersection
        // 防止除以 0；正常情况下返回交并比。
        return if (union <= 0f) 0f else intersection / union
    }

    // 将矩形向外扩展指定 padding，并限制在合法 0..1 归一化范围内。
    private fun V2Rect.expand(padding: Float): V2Rect =
        // 扩展四条边后调用 clamped 防止越界。
        V2Rect(left - padding, top - padding, right + padding, bottom + padding).clamped()

    // 把矩形框转换成四点轮廓，用作 mask 轮廓不足时的兜底。
    private fun V2Rect.toContour(): List<V2Point> =
        // 按左上、右上、右下、左下返回。
        listOf(
            V2Point(left, top),
            V2Point(right, top),
            V2Point(right, bottom),
            V2Point(left, bottom)
        )

    // 判断点是否落在矩形内。
    private fun V2Point.inside(rect: V2Rect): Boolean =
        // x 和 y 都在矩形边界范围内才算内部。
        x in rect.left..rect.right && y in rect.top..rect.bottom

    // 将一组轮廓点按中心点周围的角度排序。
    private fun List<V2Point>.orderClockwise(): List<V2Point> {
        // 计算所有点的平均 x 作为轮廓中心 x。
        val centerX = sumOf { it.x.toDouble() }.toFloat() / size
        // 计算所有点的平均 y 作为轮廓中心 y。
        val centerY = sumOf { it.y.toDouble() }.toFloat() / size
        // 按 atan2 角度排序，让散乱边界点形成连续轮廓顺序。
        return sortedBy { atan2((it.y - centerY).toDouble(), (it.x - centerX).toDouble()) }
    }

    // 将轮廓点均匀采样到指定最大数量。
    private fun List<V2Point>.sampleEvenly(maxPoints: Int): List<V2Point> {
        // 点数本来就不多时直接返回。
        if (size <= maxPoints) return this
        // 计算采样步长。
        val step = size.toFloat() / maxPoints
        // 按步长抽取点，并限制索引不越界。
        return List(maxPoints) { index -> this[(index * step).roundToInt().coerceIn(0, lastIndex)] }
    }

    // sigmoid 函数，把 mask logit 转换成 0..1 概率。
    private fun sigmoid(value: Float): Float =
        // 标准 sigmoid 公式。
        (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

    // 将分数限制在 0..1。
    private fun Float.coerceScore(): Float =
        // 避免异常分数破坏阈值判断。
        coerceIn(0f, 1f)

    // 分割内部候选结构。
    private data class SegDetection(
        // 候选人体框。
        val bounds: V2Rect,
        // 候选置信度。 大部分是person分类的可信度
        val confidence: Float,
        // 该候选用于组合 proto mask 的系数。
        val coefficients: FloatArray
    )

    // mask proto 输出布局。
    private data class ProtoLayout(
        // proto 通道数，通常是 32。
        val channels: Int,
        // proto 高度。
        val height: Int,
        // proto 宽度。
        val width: Int,
        // 是否是 channels-last 布局；否则就是 channels-first。
        val channelsLast: Boolean
    ) {
        // 按通道、行、列读取 proto 数值。
        fun value(values: List<Float>, channel: Int, y: Int, x: Int): Float {
            // 根据 channels-last 或 channels-first 计算摊平数组下标。
            val index = if (channelsLast) {
                // channels-last: [height, width, channels]。
                (y * width + x) * channels + channel
            } else {
                // channels-first: [channels, height, width]。
                channel * height * width + y * width + x
            }
            // 下标越界时返回 0，避免异常输出直接崩溃。
            return values.getOrNull(index) ?: 0f
        }

        // ProtoLayout 构造器。
        companion object {
            // 根据 ONNX 输出 shape 判断它是不是 mask proto 输出。
            fun fromShape(shape: LongArray): ProtoLayout? {
                // 去掉 batch 或无效维度，只保留大于 1 的维度。
                val dims = shape.toList().mapNotNull { it.takeIf { dim -> dim > 1 }?.toInt() }
                // proto 应该剩下三个维度。
                if (dims.size != 3) return null
                // 根据 32 通道出现在第一维还是最后一维判断内存布局。
                return when {
                    // [channels, height, width]。
                    dims.first() == MASK_CHANNELS -> ProtoLayout(
                        // 通道数。
                        channels = dims[0],
                        // 高度。
                        height = dims[1],
                        // 宽度。
                        width = dims[2],
                        // 不是 channels-last。
                        channelsLast = false
                    )
                    // [height, width, channels]。
                    dims.last() == MASK_CHANNELS -> ProtoLayout(
                        // 通道数。
                        channels = dims[2],
                        // 高度。
                        height = dims[0],
                        // 宽度。
                        width = dims[1],
                        // 是 channels-last。
                        channelsLast = true
                    )
                    // 不符合 proto 形状。
                    else -> null
                }
            }
        }
    }

    //特征图个数:
    //4 个 box 参数
    //+ 80 个 class score
    //+ 32 个 mask coefficients
    //= 116

    // 分割候选每个预测的特征数量。
    private const val SEGMENT_FEATURES = 116
    // mask proto 通道数量。
    private const val MASK_CHANNELS = 32
    // mask 系数在预测特征中的起始下标。
    private const val MASK_COEFFICIENT_START = 84
    // 人体置信度所在特征下标。
    private const val PERSON_SCORE_FEATURE = 4
    // 分割候选置信度阈值。
    private const val SEGMENT_SCORE_THRESHOLD = 0.25f
    // 分割候选 NMS 阈值。
    private const val SEGMENT_IOU_THRESHOLD = 0.45f
    // mask 二值化阈值。
    private const val MASK_THRESHOLD = 0.50f
    // 计算轮廓时在候选框外额外扩展的范围。
    private const val CONTOUR_BOUNDS_PADDING = 0.04f
    // 最少轮廓点数量。
    private const val MIN_CONTOUR_POINTS = 4
    // 最多保留轮廓点数量。
    private const val MAX_CONTOUR_POINTS = 96
    // 最多保留的人体分割数量。
    private const val MAX_SEGMENTS = 3
}

// whole-body 姿态输出解析器，解析 SimCC 格式的 x/y 坐标分布。
internal object WholeBodyPoseParser {

    // 将 whole-body 模型输出解析为 133 点姿态估计。
    fun parse(
        // 模型所有输出。
        outputs: List<YoloOnnxOutput>,
        // 裁剪输入坐标到整帧归一化坐标的转换器。
        transform: WholeBodyInputTransform
    ): WholeBodyPoseEstimate {
        // 从所有输出里识别 SimCC 布局输出。
        val layouts: List<Pair<YoloOnnxOutput, SimccLayout>>  = outputs.mapNotNull { output ->
            // 识别成功后保留输出和对应布局。
            SimccLayout.fromShape(output.shape, output.values.size)?.let { output to it }
        }
        // SimCC 至少需要 x 和 y 两个输出。
        if (layouts.size < 2) return WholeBodyPoseEstimate.Empty

        // 选择 bins 更接近 inputWidth * splitRatio 的输出作为 x 分布。
        val xOutput: Pair<YoloOnnxOutput, SimccLayout> = layouts.minByOrNull { (_, layout) ->
            // x 轴 bins 通常与输入宽度成比例。
            kotlin.math.abs(layout.bins - transform.inputWidth * SIMCC_SPLIT_RATIO)
        // 找不到 x 输出则返回空。
        } ?: return WholeBodyPoseEstimate.Empty
        // 在剩余输出中选择 bins 更接近 inputHeight * splitRatio 的输出作为 y 分布。
        val yOutput: Pair<YoloOnnxOutput, SimccLayout> = layouts
            // 排除已经选为 x 的输出。
            .filterNot { it === xOutput }
            // 找最像 y 轴的输出。
            .minByOrNull { (_, layout) ->
                // y 轴 bins 通常与输入高度成比例。
                kotlin.math.abs(layout.bins - transform.inputHeight * SIMCC_SPLIT_RATIO)
            // 找不到 y 输出则返回空。
            } ?: return WholeBodyPoseEstimate.Empty

        // x 输出布局。
        val xLayout = xOutput.second
        // y 输出布局。
        val yLayout = yOutput.second
        // 实际关键点数量取模型上限、x 输出和 y 输出三者最小值。
        val keypointCount = minOf(WHOLE_BODY_KEYPOINT_COUNT, xLayout.keypoints, yLayout.keypoints)
        // 逐个关键点从 x/y 分布中取峰值。
        val keypoints: List<WholeBodyKeypoint> = (0 until keypointCount).mapNotNull { keypointIndex ->
            // 找到该关键点 x 轴概率最高的 bin。
            val xPeak: Peak = xLayout.argmax(xOutput.first.values, keypointIndex)
            // 找到该关键点 y 轴概率最高的 bin。
            val yPeak: Peak = yLayout.argmax(yOutput.first.values, keypointIndex)
            // x/y 两个峰值分数转换为置信度后取平均。
            val confidence = ((xPeak.score.toConfidence() + yPeak.score.toConfidence()) / 2f).coerceIn(0f, 1f)
            // 置信度不足时丢弃该关键点。
            if (confidence < KEYPOINT_SCORE_THRESHOLD) return@mapNotNull null
            // 构造 whole-body 关键点。
            WholeBodyKeypoint(
                // whole-body 原始关键节点索引。
                index = keypointIndex,
                // 将 SimCC bin 坐标转换为模型输入坐标，再还原到整帧归一化坐标。 这里得到的是已经归一化过的数据
                point = transform.pointToNormalized(
                    // SimCC 坐标。
                    V2Point(
                        // x bin 中心映射回输入宽度。
                        x = (xPeak.index + 0.5f) * transform.inputWidth / xLayout.bins,
                        // y bin 中心映射回输入高度。
                        y = (yPeak.index + 0.5f) * transform.inputHeight / yLayout.bins
                    )
                ),
                // 关键点置信度。
                confidence = confidence
            )
        }
        // 没有有效关键点时返回空姿态。
        if (keypoints.isEmpty()) return WholeBodyPoseEstimate.Empty
        // 返回 whole-body 姿态估计。
        return WholeBodyPoseEstimate(
            // 有效关键点列表。
            keypoints = keypoints,
            // 整体置信度取所有有效关键点置信度平均值。
            confidence = keypoints.map { it.confidence }.average().toFloat()
        )
    }

    // 根据 whole-body 关键点索引判断它属于身体、脚、脸或手。
    fun groupForIndex(index: Int): WholeBodyPart =
        // 按 WholeBodyPoseEstimate 中定义的索引范围分组。
        when (index) {
            // 身体关键点。
            in WholeBodyPoseEstimate.BODY_RANGE -> WholeBodyPart.BODY
            // 脚部关键点。
            in WholeBodyPoseEstimate.FOOT_RANGE -> WholeBodyPart.FOOT
            // 脸部关键点。
            in WholeBodyPoseEstimate.FACE_RANGE -> WholeBodyPart.FACE
            // 左手关键点。
            in WholeBodyPoseEstimate.LEFT_HAND_RANGE -> WholeBodyPart.LEFT_HAND
            // 右手关键点。
            in WholeBodyPoseEstimate.RIGHT_HAND_RANGE -> WholeBodyPart.RIGHT_HAND
            // 未知范围。
            else -> WholeBodyPart.UNKNOWN
        }

    // 将模型输出分数转换成 0..1 置信度。
    private fun Float.toConfidence(): Float =
        // 兼容模型输出本身已是概率，或是 logit 的两种情况。
        when {
            // NaN 视为无效。
            isNaN() -> 0f
            // 已经在 0..1 范围内时直接使用。
            this in 0f..1f -> this
            // 其他值按 logit 走 sigmoid。
            else -> (1.0 / (1.0 + exp(-toDouble()))).toFloat()
        }

    // SimCC 轴向分布里的峰值。
    private data class Peak(
        // 峰值所在 bin 下标。
        val index: Int,
        // 峰值分数。
        val score: Float
    )

    // SimCC 输出布局：一个关键点在 x 或 y 轴上对应一条离散概率分布。
    private data class SimccLayout(
        // 关键点数量。
        val keypoints: Int,
        // 每个关键点的轴向 bin 数量。
        val bins: Int,
        // 是否是 [keypoints, bins] 布局；否则为 [bins, keypoints]。
        val keypointsFirst: Boolean
    ) {
        // 找到指定关键点在该轴上的最高分 bin。
        fun argmax(values: List<Float>, keypoint: Int): Peak {
            // 当前最佳 bin 下标。
            var bestIndex = 0
            // 当前最佳分数。
            var bestScore = Float.NEGATIVE_INFINITY
            // 遍历所有 bin。
            for (bin in 0 until bins) {
                // 读取该 bin 分数。
                val score = value(values, keypoint, bin)
                // 如果更高则更新峰值。
                if (score > bestScore) {
                    // 更新最佳分数。
                    bestScore = score
                    // 更新最佳下标。
                    bestIndex = bin
                }
            }
            // 返回峰值；非有限分数兜底为 0。
            return Peak(bestIndex, bestScore.takeIf { it.isFinite() } ?: 0f)
        }

        // 按布局读取指定关键点、指定 bin 的分数。
        private fun value(values: List<Float>, keypoint: Int, bin: Int): Float {
            // 根据 keypoints-first 或 bins-first 计算摊平下标。
            val index = if (keypointsFirst) {
                // [keypoints, bins]。
                keypoint * bins + bin
            } else {
                // [bins, keypoints]。
                bin * keypoints + keypoint
            }
            // 越界时返回负无穷，确保不会被 argmax 选中。
            return values.getOrNull(index) ?: Float.NEGATIVE_INFINITY
        }

        // SimCC 布局推断工具。
        companion object {
            // 根据输出 shape 和总元素数推断 SimCC 输出布局。
            fun fromShape(shape: LongArray, totalValues: Int): SimccLayout? {
                // 去掉 batch 维度，必要时根据总元素数反推二维布局。
                val dims = shape.toList()
                    // 只保留有效维度。
                    .filter { it > 0 }
                    // 如果第一维是 batch=1，则去掉。
                    .dropLeadingBatchDimension()
                    // Long 转 Int 便于后续计算。
                    .map { it.toInt() }
                    // 如果 shape 不完整，则根据 totalValues 兜底推断。
                    .ifEmpty { inferDims(totalValues) }
                // SimCC 单个输出应该是二维：关键点数 x bins 或 bins x 关键点数。
                if (dims.size != 2) return null
                // 第一维。
                val first = dims[0]
                // 第二维。
                val second = dims[1]
                // 根据 133 个关键点出现在哪一维判断布局方向。
                return when {
                    // [keypoints, bins]。
                    first == WHOLE_BODY_KEYPOINT_COUNT -> SimccLayout(
                        // 关键点数。
                        keypoints = first,
                        // bin 数。
                        bins = second,
                        // 关键点维度在前。
                        keypointsFirst = true
                    )
                    // [bins, keypoints]。
                    second == WHOLE_BODY_KEYPOINT_COUNT -> SimccLayout(
                        // 关键点数。
                        keypoints = second,
                        // bin 数。
                        bins = first,
                        // 关键点维度不在前。
                        keypointsFirst = false
                    )
                    // 不是预期的 SimCC 输出。
                    else -> null
                }
            }

            // 当 shape 不可用时，通过总元素数反推出 [133, bins]。
            private fun inferDims(totalValues: Int): List<Int> =
                // 总元素数能被 133 整除时才能推断。
                if (totalValues % WHOLE_BODY_KEYPOINT_COUNT == 0) {
                    // 认为布局是 [keypoints, bins]。
                    listOf(WHOLE_BODY_KEYPOINT_COUNT, totalValues / WHOLE_BODY_KEYPOINT_COUNT)
                } else {
                    // 无法推断。
                    emptyList()
                }
        }
    }

    // 去掉 ONNX 输出中常见的 batch=1 前导维度。
    private fun List<Long>.dropLeadingBatchDimension(): List<Long> =
        // 只有维度大于 2 且第一维为 1 时才去掉。
        if (size > 2 && first() == 1L) drop(1) else this

    // whole-body 模型关键点数量。
    private const val WHOLE_BODY_KEYPOINT_COUNT = 133
    // SimCC 通常把坐标轴分辨率扩大 2 倍。
    private const val SIMCC_SPLIT_RATIO = 2
    // whole-body 单点置信度阈值。
    private const val KEYPOINT_SCORE_THRESHOLD = 0.18f
}

// whole-body 关键点分组。
enum class WholeBodyPart {
    // 身体。
    BODY,
    // 脚部。
    FOOT,
    // 脸部。
    FACE,
    // 左手。
    LEFT_HAND,
    // 右手。
    RIGHT_HAND,
    // 未知分组。
    UNKNOWN
}

// 根据检测到的 COCO 物体粗略推断拍摄场景。
internal object YoloSceneInferencer {

    // 输入非人体物体列表，输出语义场景。
    fun infer(objects: List<SceneObject>): SemanticScene {
        // 没有物体线索时，默认给一个低置信度户外判断。
        if (objects.isEmpty()) {
            return SemanticScene(label = "户外", group = SceneGroup.OUTDOOR, confidence = 0.35f)
        }
        // 统计室内物体类别的置信度总分。
        val indoorScore = objects.sumScoreFor(INDOOR_CLASS_IDS)
        // 统计城市场景物体类别的置信度总分。
        val urbanScore = objects.sumScoreFor(URBAN_CLASS_IDS)
        // 统计自然/动物类物体类别的置信度总分。
        val natureScore = objects.sumScoreFor(NATURE_CLASS_IDS)
        // 选择得分占优的场景类型。
        return when {
            // 室内得分相对其他类型有足够优势时，判断为室内。
            indoorScore >= urbanScore * SCORE_BIAS && indoorScore >= natureScore * SCORE_BIAS && indoorScore > 0f -> {
                // 置信度最多限制为 1。
                SemanticScene(label = "室内", group = SceneGroup.INDOOR, confidence = indoorScore.coerceAtMost(1f))
            }
            // 城市得分占优时，判断为城市。
            urbanScore >= indoorScore * SCORE_BIAS && urbanScore >= natureScore * SCORE_BIAS && urbanScore > 0f -> {
                // 返回城市场景。
                SemanticScene(label = "城市", group = SceneGroup.URBAN, confidence = urbanScore.coerceAtMost(1f))
            }
            // 自然类别有得分时，判断为户外自然。
            natureScore > 0f -> {
                // 返回自然场景。
                SemanticScene(label = "户外", group = SceneGroup.NATURE, confidence = natureScore.coerceAtMost(1f))
            }
            // 没有明确倾向时返回未知。
            else -> SemanticScene(label = "未知场景", group = SceneGroup.UNKNOWN, confidence = 0.25f)
        }
    }

    // 统计指定 classId 集合对应物体的置信度总和。
    private fun List<SceneObject>.sumScoreFor(classIds: Set<Int>): Float =
        // 过滤目标类别并累加置信度。
        filter { it.classId in classIds }.sumOf { it.confidence.toDouble() }.toFloat()

    // 场景分类偏置，要求某类得分至少比其他类高 8% 才算占优。
    private const val SCORE_BIAS = 1.08f

    // 室内相关 COCO 类别 ID，例如椅子、沙发、床、电视、餐桌等。
    private val INDOOR_CLASS_IDS = setOf(
        56, 57, 58, 59, 60, 61, 62, 63, 64, 66, 67, 68, 69, 71, 72, 73, 74, 75, 76, 77, 78, 79
    )
    // 城市交通相关 COCO 类别 ID，例如车、自行车、公交、红绿灯等。
    private val URBAN_CLASS_IDS = setOf(
        1, 2, 3, 5, 6, 7, 9, 10, 11, 12, 13
    )
    // 自然/动物相关 COCO 类别 ID。
    private val NATURE_CLASS_IDS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22, 23
    )
}

// YOLO 输出张量布局推断器，兼容 channels-first 和 channels-last 两类输出排列。
private object YoloTensorLayout {

    // 判断输出是否像已 NMS 的检测格式。
    fun isNmsDetectionOutput(shape: LongArray, totalValues: Int): Boolean =
        // 只要能按 6 特征布局识别，就认为是 NMS 检测输出。
        shape.isNotEmpty() && fromShape(shape, totalValues, preferredFeatures = 6)?.features == 6

    // 根据 shape、总元素数和期望特征数推断 YOLO 输出布局。
    // shape分别是 prediction output，候选目标预测 index0 = [1, 116, 8400]
    // 和    mask proto output，分割原型图 index1 = [1, 32, 160, 160]
    fun fromShape(
        // ONNX 输出 shape。
        shape: LongArray,
        // 输出总元素数。
        totalValues: Int,
        // 每个候选期望的特征数量。
        preferredFeatures: Int
    ): Layout? {
        // 清理 shape，并在 shape 不可用时用总元素数反推。
        val dims = shape.toList()
            // 去掉无效维度。
            .filter { it > 0 }
            // 去掉 batch=1。
            .dropLeadingBatchDimension()
            // 转成 Int。
            .map { it.toInt() }
            // shape 为空时尝试用 totalValues 推断。
            .ifEmpty { inferDims(totalValues, preferredFeatures) }
        // 至少需要两个维度才能判断候选数和特征数。
        if (dims.size < 2) return null
        // 倒数第二维。
        val first = dims[dims.lastIndex - 1]
        // 最后一维。
        val second = dims[dims.lastIndex]
        // 判断特征维度是在前还是在后。
        return when {
            // [candidates, features]，也就是 channels-last。
            second == preferredFeatures -> Layout(
                // 特征数量。
                features = second,
                // 候选数量。
                candidates = first,
                // 特征维度不在前。
                channelsFirst = false
            )
            // [features, candidates]，也就是 channels-first。
            first == preferredFeatures -> Layout(
                // 特征数量。
                features = first,
                // 候选数量。
                candidates = second,
                // 特征维度在前。
                channelsFirst = true
            )
            // 兼容调用方期望 84 但模型输出 85 的旧 YOLO 格式。
            preferredFeatures == 84 && first == 85 -> Layout(
                // 实际特征数量。
                features = first,
                // 候选数量。
                candidates = second,
                // 特征维度在前。
                channelsFirst = true
            )
            // 兼容 [candidates, 85] 旧格式。
            preferredFeatures == 84 && second == 85 -> Layout(
                // 实际特征数量。
                features = second,
                // 候选数量。
                candidates = first,
                // 特征维度在后。
                channelsFirst = false
            )
            // 无法识别。
            else -> null
        }
    }

    // shape 不可用时，根据总元素数和期望特征数推断二维布局。
    private fun inferDims(totalValues: Int, preferredFeatures: Int): List<Int> =
        // 优先按期望特征数整除。
        when {
            // total = features * candidates。
            totalValues % preferredFeatures == 0 -> listOf(preferredFeatures, totalValues / preferredFeatures)
            // 检测模型兼容 85 特征旧格式。
            preferredFeatures == 84 && totalValues % 85 == 0 -> listOf(85, totalValues / 85)
            // 无法推断。
            else -> emptyList()
        }

    // 去掉 batch=1 前导维度。
    private fun List<Long>.dropLeadingBatchDimension(): List<Long> =
        // 只处理多维且第一维是 1 的情况。
        if (size > 2 && first() == 1L) drop(1) else this

    // YOLO 输出布局描述。
    data class Layout(
        // 每个候选的特征数量。
        val features: Int,
        // 候选数量。
        val candidates: Int,
        // 是否是 [features, candidates] 排列。
        val channelsFirst: Boolean
    ) {
        // 读取指定候选、指定特征的值；不存在时返回 0。
        fun value(values: List<Float>, candidate: Int, feature: Int): Float =
            // 使用可空读取并兜底 0。
            valueOrNull(values, candidate, feature) ?: 0f

        // 读取指定候选、指定特征的值；越界时返回 null。
        // 比如: feature = 4
        fun valueOrNull(values: List<Float>, candidate: Int, feature: Int): Float? {
            // 候选或特征越界时直接返回 null。
            if (candidate !in 0 until candidates || feature !in 0 until features) return null
            // 根据通道排列计算一维数组下标。
            val index = if (channelsFirst) {
                // [features, candidates]。
                feature * candidates + candidate
            } else {
                // [candidates, features]。
                candidate * features + feature
            }
            // 返回对应值；底层数组越界也返回 null。
            return values.getOrNull(index)
        }
    }
}

// 将 ONNX Runtime 返回的各种数值/数组结构递归摊平成 Float 列表。
private fun flattenNumbers(value: Any?): List<Float> =
    // 根据实际运行时类型做转换。
    when (value) {
        // 空输出返回空列表。
        null -> emptyList()
        // 单个 Float。
        is Float -> listOf(value)
        // 单个 Double 转 Float。
        is Double -> listOf(value.toFloat())
        // 单个 Int 转 Float。
        is Int -> listOf(value.toFloat())
        // 单个 Long 转 Float。
        is Long -> listOf(value.toFloat())
        // FloatArray 直接转列表。
        is FloatArray -> value.toList()
        // DoubleArray 逐项转 Float。
        is DoubleArray -> value.map { it.toFloat() }
        // IntArray 逐项转 Float。
        is IntArray -> value.map { it.toFloat() }
        // LongArray 逐项转 Float。
        is LongArray -> value.map { it.toFloat() }
        // 多维数组递归摊平。
        is Array<*> -> value.flatMap { flattenNumbers(it) }
        // 其他未知类型不解析。
        else -> emptyList()
    }
