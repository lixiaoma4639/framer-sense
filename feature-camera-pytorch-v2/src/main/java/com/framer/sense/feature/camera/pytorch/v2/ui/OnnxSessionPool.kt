package com.framer.sense.feature.camera.pytorch.v2.ui

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

// ONNX session 池：统一创建并持有相机 v2 所需的多个模型会话。
class OnnxSessionPool(
    // 用于访问 assets、cacheDir 和 applicationContext。
    context: Context
) {

    // 保存 applicationContext，避免长生命周期 session 池持有 Activity context。
    private val appContext = context.applicationContext
    // 获取 ONNX Runtime 全局环境；创建 tensor 和 session 时都会用到它。
    val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    // 尝试创建目标检测模型 session；模型不存在时为 null。
    val objectSession: OrtSession? = createSessionIfPresent(OBJECT_MODEL)
    // 尝试创建普通人体姿态模型 session；模型不存在时为 null。
    val poseSession: OrtSession? = createSessionIfPresent(POSE_MODEL)
    // 尝试创建人体分割模型 session；模型不存在时为 null。
    val segmentationSession: OrtSession? = createSessionIfPresent(SEGMENTATION_MODEL)
    // 尝试创建 whole-body 姿态模型 session；模型不存在时为 null。
    val wholeBodyPoseSession: OrtSession? = createSessionIfPresent(WHOLE_BODY_POSE_MODEL)

    // 汇总各模型是否成功创建 session，供 UI 或分析流程判断能力是否可用。
    val availability: ModelAvailability =
        // 构造模型可用性状态对象。
        ModelAvailability(
            // 目标检测 session 非空表示物体/人体检测可用。
            objectDetectorReady = objectSession != null,
            // 普通姿态 session 非空表示 YOLO pose 可用。
            poseDetectorReady = poseSession != null,
            // 分割 session 非空表示人体分割可用。
            segmentationReady = segmentationSession != null,
            // whole-body session 非空表示 133 点全身姿态可用。
            wholeBodyPoseReady = wholeBodyPoseSession != null
        )

    // 如果 assets 中存在指定模型文件，就复制到 cache 并创建 ONNX session；否则返回 null。
    private fun createSessionIfPresent(assetPath: String): OrtSession? {
        // 模型 asset 不存在时直接跳过，允许功能降级而不是启动失败。
        if (!assetExists(assetPath)) return null
        // ONNX Runtime 通常需要文件路径，因此先把 asset 复制到应用 cache 目录。
        val modelFile = copyAssetToCache(assetPath)
        // 使用模型文件绝对路径创建 OrtSession；SessionOptions 当前使用默认配置。
        return environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    // 判断 assets 中是否存在指定路径的模型文件。
    private fun assetExists(assetPath: String): Boolean =
        // 用 runCatching 捕获 open 失败，避免缺少可选模型时抛异常。
        runCatching {
            // 能成功打开 asset 就表示文件存在；use 会自动关闭输入流。
            appContext.assets.open(assetPath).use { true }
        // 打开失败时返回 false。
        }.getOrDefault(false)

    // 将 assets 中的模型文件复制到 cache 目录，并返回 cache 文件。
    private fun copyAssetToCache(assetPath: String): File {
        // cache 文件名取 asset 路径最后一段，例如 models/yolov8n.onnx -> yolov8n.onnx。
        val target = File(appContext.cacheDir, assetPath.substringAfterLast('/'))
        // 先打开 asset，用于检查 cache 中是否已有同大小文件。
        appContext.assets.open(assetPath).use { input ->
            // 如果 cache 文件已存在且长度等于 asset 可读字节数，就复用已有文件。
            if (target.exists() && target.length() == input.available().toLong()) {
                // 直接返回已有文件，避免每次启动都重复复制大模型。
                return target
            }
        }
        // cache 不存在或大小不一致时，重新打开 asset 准备复制。
        appContext.assets.open(assetPath).use { input ->
            // 打开目标文件输出流，并把 asset 内容复制进去。
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // 返回复制完成后的模型文件。
        return target
    }

    // 模型 asset 路径常量。
    companion object {
        // YOLOv8n 目标检测模型路径。
        const val OBJECT_MODEL = "models/yolov8n.onnx"
        // YOLOv8n pose 普通人体姿态模型路径。
        const val POSE_MODEL = "models/yolov8n-pose.onnx"
        // YOLOv8n segmentation 人体分割模型路径。
        const val SEGMENTATION_MODEL = "models/yolov8n-seg.onnx"
        // RTMPose whole-body 全身 133 点姿态模型路径。
        const val WHOLE_BODY_POSE_MODEL = "models/rtmpose_wholebody_256x192.onnx"
    }
}
