package com.framer.sense.feature.camera.pytorch.v2.ui

import androidx.camera.core.ImageProxy
import java.nio.FloatBuffer
import kotlin.math.roundToInt

// 相机帧预处理器：把 CameraX 的 ImageProxy 转成 ONNX 模型需要的 FloatBuffer，并提供坐标还原能力。
class FramePreprocessor {

    // 计算按相机旋转角度修正后的画面宽高比。
    fun orientedFrameAspectRatio(imageProxy: ImageProxy): Float {
        // 读取 CameraX 给出的当前帧旋转角度。
        val rotation = imageProxy.imageInfo.rotationDegrees
        // 90/270 度旋转后，显示意义上的宽度等于原始高度。
        val width = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        // 90/270 度旋转后，显示意义上的高度等于原始宽度。
        val height = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        // 返回宽高比，并确保高度至少为 1，避免除以 0。
        return width.toFloat() / height.coerceAtLeast(1)
    }

    // 计算当前帧的平均亮度，用于判断画面是否过暗。
    fun averageLuminance(imageProxy: ImageProxy): Double {
        // 读取 YUV 图像的第一个平面 Y 平面；Y 平面代表亮度。
        val buffer = imageProxy.planes.firstOrNull()?.buffer?.duplicate() ?: return DEFAULT_LUMINANCE
        // 没有可读数据时返回默认中性亮度。
        if (!buffer.hasRemaining()) return DEFAULT_LUMINANCE
        // 亮度累加和。
        var sum = 0L
        // 实际采样点数量。
        var count = 0
        // 按最多 LUMINANCE_SAMPLES 个点采样，避免遍历整帧造成额外开销。
        val step = maxOf(1, buffer.remaining() / LUMINANCE_SAMPLES)
        // 遍历亮度 buffer。
        while (buffer.hasRemaining()) {
            // 读取一个 unsigned byte 亮度值并累加。
            sum += buffer.get().toInt() and 0xFF
            // 记录采样数量。
            count++
            // 跳过 step 个字节，控制采样密度。
            buffer.position((buffer.position() + step).coerceAtMost(buffer.limit()))
        }
        // 没采到数据时返回默认值，否则返回平均亮度。
        return if (count == 0) DEFAULT_LUMINANCE else sum.toDouble() / count
    }

    // 通用 RGB FloatBuffer 转换函数，可指定输出宽高、通道顺序、均值和标准差。
    fun toRgbFloatBuffer(
        // 原始相机帧。
        imageProxy: ImageProxy,
        // 输出宽度。
        width: Int,
        // 输出高度。
        height: Int,
        // true 表示输出为 CHW；false 表示输出为 HWC。
        channelFirst: Boolean,
        // RGB 均值，用于归一化。
        mean: FloatArray = floatArrayOf(0f, 0f, 0f),
        // RGB 标准差，用于归一化。
        std: FloatArray = floatArrayOf(255f, 255f, 255f)
    ): FloatBuffer {
        // 分配输出 buffer，大小为宽 * 高 * 3 通道。
        val buffer = FloatBuffer.allocate(width * height * RGB_CHANNELS)
        // CHW 格式需要先分别收集 R 通道。
        val redValues = if (channelFirst) FloatArray(width * height) else null
        // CHW 格式需要先分别收集 G 通道。
        val greenValues = if (channelFirst) FloatArray(width * height) else null
        // CHW 格式需要先分别收集 B 通道。
        val blueValues = if (channelFirst) FloatArray(width * height) else null
        // 当前帧旋转角度。
        val rotation = imageProxy.imageInfo.rotationDegrees
        // 旋转后的画面宽度。
        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        // 旋转后的画面高度。
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        // 遍历输出图像的每一行。
        for (y in 0 until height) {
            // 遍历输出图像的每一列。
            for (x in 0 until width) {
                // 将输出坐标按比例映射到旋转后画面坐标。
                val rotatedX = x * rotatedWidth / width
                // 将输出 y 坐标按比例映射到旋转后画面坐标。
                val rotatedY = y * rotatedHeight / height
                // 再把旋转后坐标映射回原始 ImageProxy 坐标。
                val source = mapRotatedPointToSource(
                    // 旋转后 x。
                    x = rotatedX,
                    // 旋转后 y。
                    y = rotatedY,
                    // 旋转后宽度。
                    rotatedWidth = rotatedWidth,
                    // 旋转后高度。
                    rotatedHeight = rotatedHeight,
                    // 原始帧宽度。
                    sourceWidth = imageProxy.width,
                    // 原始帧高度。
                    sourceHeight = imageProxy.height,
                    // 旋转角度。
                    rotationDegrees = rotation
                )
                // 从 YUV 原始帧读取该点 RGB。
                val rgb = imageProxy.readRgb(source.first, source.second)
                // 输出平面中的一维像素下标。
                val index = y * width + x
                // R 通道按 mean/std 归一化。
                val red = (rgb.red - mean[0]) / std[0]
                // G 通道按 mean/std 归一化。
                val green = (rgb.green - mean[1]) / std[1]
                // B 通道按 mean/std 归一化。
                val blue = (rgb.blue - mean[2]) / std[2]
                // 如果模型需要 CHW，就先写入三个临时通道数组。
                if (channelFirst) {
                    // 保存 R。
                    redValues?.set(index, red)
                    // 保存 G。
                    greenValues?.set(index, green)
                    // 保存 B。
                    blueValues?.set(index, blue)
                } else {
                    // HWC 格式直接按 R、G、B 写入 buffer。
                    buffer.put(red)
                    // 写入 G。
                    buffer.put(green)
                    // 写入 B。
                    buffer.put(blue)
                }
            }
        }

        // 如果是 CHW 格式，就按 R 平面、G 平面、B 平面的顺序写入 buffer。
        if (channelFirst && redValues != null && greenValues != null && blueValues != null) {
            // 写入完整 R 通道。
            redValues.forEach(buffer::put)
            // 写入完整 G 通道。
            greenValues.forEach(buffer::put)
            // 写入完整 B 通道。
            blueValues.forEach(buffer::put)
        }
        // 重置 buffer 读写位置，方便 ONNX Runtime 从开头读取。
        buffer.rewind()
        // 返回预处理后的 RGB float 数据。
        return buffer
    }

    // 将相机帧转换成 YOLO 模型输入，并返回对应坐标变换信息。
    // shape = [1, 3, 640, 640]
    //  格式 = NCHW
    //  类型 = Float
    //  颜色 = RGB
    //  数值范围 = 0.0 .. 1.0
    fun toYoloRgbFloatBuffer(
        // 原始相机帧。
        imageProxy: ImageProxy,
        // YOLO 输入尺寸，通常是 640。
        size: Int
    ): YoloInput {
        // 分配 3 * size * size 的 FloatBuffer。
        val buffer = FloatBuffer.allocate(size * size * RGB_CHANNELS)
        // YOLO 模型使用 CHW 格式，先存 R 通道。
        val redValues = FloatArray(size * size)
        // 存 G 通道。
        val greenValues = FloatArray(size * size)
        // 存 B 通道。
        val blueValues = FloatArray(size * size)
        // 当前帧旋转角度。
        val rotation = imageProxy.imageInfo.rotationDegrees
        // 旋转后的画面宽度。
        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        // 旋转后的画面高度。
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        // 等比缩放到 YOLO 正方形输入里，取较小缩放比例以保留完整画面。
        val scale = minOf(size.toFloat() / rotatedWidth, size.toFloat() / rotatedHeight)
        // 缩放后内容区域宽度。
        val contentWidth = (rotatedWidth * scale).roundToInt().coerceIn(1, size)
        // 缩放后内容区域高度。
        val contentHeight = (rotatedHeight * scale).roundToInt().coerceIn(1, size)
        // 左侧 padding，形成 letterbox。
        val padLeft = (size - contentWidth) / 2f
        // 顶部 padding，形成 letterbox。
        val padTop = (size - contentHeight) / 2f
        // 保存 YOLO 输入坐标还原到原图归一化坐标所需的参数。
        val transform = YoloInputTransform(
            // 输入尺寸。
            inputSize = size,
            // 旋转后原图宽度。
            rotatedWidth = rotatedWidth,
            // 旋转后原图高度。
            rotatedHeight = rotatedHeight,
            // 缩放比例。
            scale = scale,
            // 左侧 padding。
            padLeft = padLeft,
            // 顶部 padding。
            padTop = padTop
        )

        // 遍历 YOLO 输入正方形的每一行。
        for (y in 0 until size) {
            // 遍历每一列。
            for (x in 0 until size) {
                // 当前输出像素下标。
                val index = y * size + x
                // 判断当前点是否落在真实图像内容区域，而不是 letterbox padding。
                val insideContent =
                    // x 在内容左边界右侧。
                    x >= padLeft &&
                        // x 在内容右边界左侧。
                        x < padLeft + contentWidth &&
                        // y 在内容上边界下方。
                        y >= padTop &&
                        // y 在内容下边界上方。
                        y < padTop + contentHeight
                // 内容区域读取原图 RGB，padding 区域使用 YOLO 常见灰色填充值。
                val rgb = if (insideContent) {
                    // 去掉 padding 并除以 scale，映射回旋转后原图 x。
                    val rotatedX = ((x - padLeft) / scale).roundToInt().coerceIn(0, rotatedWidth - 1)
                    // 去掉 padding 并除以 scale，映射回旋转后原图 y。
                    val rotatedY = ((y - padTop) / scale).roundToInt().coerceIn(0, rotatedHeight - 1)
                    // 将旋转后坐标映射回 ImageProxy 原始坐标。
                    val source = mapRotatedPointToSource(
                        // 旋转后 x。
                        x = rotatedX,
                        // 旋转后 y。
                        y = rotatedY,
                        // 旋转后宽度。
                        rotatedWidth = rotatedWidth,
                        // 旋转后高度。
                        rotatedHeight = rotatedHeight,
                        // 原图宽度。
                        sourceWidth = imageProxy.width,
                        // 原图高度。
                        sourceHeight = imageProxy.height,
                        // 旋转角度。
                        rotationDegrees = rotation
                    )
                    // 从原始帧读取 RGB。
                    imageProxy.readRgb(source.first, source.second)
                } else {
                    // padding 区域填 114，是 YOLO letterbox 里常见的中性灰。
                    Rgb(red = YOLO_PADDING_RGB, green = YOLO_PADDING_RGB, blue = YOLO_PADDING_RGB)
                }
                // R 归一化到 0..1。
                redValues[index] = rgb.red / 255f
                // G 归一化到 0..1。
                greenValues[index] = rgb.green / 255f
                // B 归一化到 0..1。
                blueValues[index] = rgb.blue / 255f
            }
        }

        // 按 CHW 顺序写入 R 通道。
        redValues.forEach(buffer::put)
        // 写入 G 通道。
        greenValues.forEach(buffer::put)
        // 写入 B 通道。
        blueValues.forEach(buffer::put)
        // 重置 buffer 位置，供 ONNX Runtime 读取。
        buffer.rewind()
        // 返回输入 buffer 和坐标变换对象。
        return YoloInput(buffer = buffer, transform = transform)
    }

    // 将人体框区域裁剪并转换成 whole-body 姿态模型需要的输入。
    fun toWholeBodyRgbFloatBuffer(
        // 原始相机帧。
        imageProxy: ImageProxy,
        // 已检测到的人体框，使用原图归一化坐标。
        bounds: V2Rect,
        // whole-body 模型输入宽度。
        width: Int,
        // whole-body 模型输入高度。
        height: Int
    ): WholeBodyInput {
        // 分配输出 buffer。
        val buffer = FloatBuffer.allocate(width * height * RGB_CHANNELS)
        // whole-body 模型使用 CHW 格式，先存 R 通道。
        val redValues = FloatArray(width * height)
        // 存 G 通道。
        val greenValues = FloatArray(width * height)
        // 存 B 通道。
        val blueValues = FloatArray(width * height)
        // 当前帧旋转角度。
        val rotation = imageProxy.imageInfo.rotationDegrees
        // 旋转后的画面宽度。
        val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        // 旋转后的画面高度。
        val rotatedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        // 在人体框外扩一圈，再调整成 whole-body 输入需要的宽高比。
        val crop = bounds
            // 扩展裁剪框，避免手脚等关键点被贴边裁掉。
            .expand(WHOLE_BODY_CROP_PADDING)
            // 调整裁剪框宽高比为模型输入宽高比。
            .fitAspect(width.toFloat() / height.toFloat())
        // 保存 whole-body 输入坐标还原到整帧归一化坐标的变换信息。
        val transform = WholeBodyInputTransform(
            // 输入宽度。
            inputWidth = width,
            // 输入高度。
            inputHeight = height,
            // 裁剪区域。
            cropBounds = crop
        )

        // 遍历 whole-body 输入的每一行。width 和 height是实际px高度
        for (y in 0 until height) {
            // 遍历每一列。
            for (x in 0 until width) {
                // 将输入像素中心点映射到裁剪框内的归一化 x。
                val normalizedX = crop.left + ((x + 0.5f) / width) * crop.width
                // 将输入像素中心点映射到裁剪框内的归一化 y。
                val normalizedY = crop.top + ((y + 0.5f) / height) * crop.height
                // 归一化 x 转成旋转后原图像素 x。
                val rotatedX = (normalizedX * rotatedWidth).roundToInt().coerceIn(0, rotatedWidth - 1)
                // 归一化 y 转成旋转后原图像素 y。
                val rotatedY = (normalizedY * rotatedHeight).roundToInt().coerceIn(0, rotatedHeight - 1)
                // 将旋转后坐标映射回原始 ImageProxy 坐标。
                val source = mapRotatedPointToSource(
                    // 旋转后 x。
                    x = rotatedX,
                    // 旋转后 y。
                    y = rotatedY,
                    // 旋转后宽度。
                    rotatedWidth = rotatedWidth,
                    // 旋转后高度。
                    rotatedHeight = rotatedHeight,
                    // 原始宽度。
                    sourceWidth = imageProxy.width,
                    // 原始高度。
                    sourceHeight = imageProxy.height,
                    // 旋转角度。
                    rotationDegrees = rotation
                )
                // 从原始 YUV 帧读取 RGB。
                val rgb = imageProxy.readRgb(source.first, source.second)
                // 当前输入像素一维下标。
                val index = y * width + x
                // R 按 whole-body 模型训练时使用的均值/标准差归一化。
                redValues[index] = (rgb.red - WHOLE_BODY_MEAN[0]) / WHOLE_BODY_STD[0]
                // G 按 whole-body 模型训练时使用的均值/标准差归一化。
                greenValues[index] = (rgb.green - WHOLE_BODY_MEAN[1]) / WHOLE_BODY_STD[1]
                // B 按 whole-body 模型训练时使用的均值/标准差归一化。
                blueValues[index] = (rgb.blue - WHOLE_BODY_MEAN[2]) / WHOLE_BODY_STD[2]
            }
        }

        // 按 CHW 顺序写入 R 通道。
        redValues.forEach(buffer::put)
        // 写入 G 通道。
        greenValues.forEach(buffer::put)
        // 写入 B 通道。
        blueValues.forEach(buffer::put)
        // 重置 buffer 读写位置。
        buffer.rewind()
        // 返回 whole-body 输入和坐标还原变换。
        return WholeBodyInput(buffer = buffer, transform = transform)
    }

    // 把旋转后画面坐标映射回 ImageProxy 原始 buffer 坐标。
    private fun mapRotatedPointToSource(
        // 旋转后坐标 x。
        x: Int,
        // 旋转后坐标 y。
        y: Int,
        // 旋转后画面宽度。
        rotatedWidth: Int,
        // 旋转后画面高度。
        rotatedHeight: Int,
        // 原始帧宽度。
        sourceWidth: Int,
        // 原始帧高度。
        sourceHeight: Int,
        // CameraX 给出的旋转角度。
        rotationDegrees: Int
    ): Pair<Int, Int> =
        // 根据旋转角度执行反向坐标变换。
        when (rotationDegrees) {
            // 顺时针 90 度显示时，旋转坐标反算到原始坐标。
            90 -> y.coerceIn(0, sourceWidth - 1) to (sourceHeight - 1 - x).coerceIn(0, sourceHeight - 1)
            // 180 度时，x/y 都反向。
            180 -> (sourceWidth - 1 - x).coerceIn(0, sourceWidth - 1) to
                // y 也按高度反向。
                (sourceHeight - 1 - y).coerceIn(0, sourceHeight - 1)
            // 270 度时，使用另一组反向映射。
            270 -> (sourceWidth - 1 - y).coerceIn(0, sourceWidth - 1) to x.coerceIn(0, sourceHeight - 1)
            // 无旋转或其他角度时，按比例从旋转后尺寸映射到原始尺寸。
            else -> (x * sourceWidth / rotatedWidth).coerceIn(0, sourceWidth - 1) to
                // y 按比例映射到原始高度。
                (y * sourceHeight / rotatedHeight).coerceIn(0, sourceHeight - 1)
        }

    // 从 ImageProxy 的 YUV_420_888 平面读取指定像素并转换为 RGB。
    private fun ImageProxy.readRgb(x: Int, y: Int): Rgb {
        // Y 平面保存亮度，按完整分辨率采样。
        val yValue = planes[0].valueAt(x, y)
        // U 平面为色度分量，通常是 2x2 共享一个采样点。
        val uValue = planes[1].valueAt(x / 2, y / 2)
        // V 平面也是色度分量，通常是 2x2 共享一个采样点。
        val vValue = planes[2].valueAt(x / 2, y / 2)
        // YUV 转 RGB 公式中，Y 需要先减去 16 并保证非负。
        val adjustedY = (yValue - 16).coerceAtLeast(0)
        // U 以 128 为中心。
        val adjustedU = uValue - 128
        // V 以 128 为中心。
        val adjustedV = vValue - 128
        // 按 BT.601 近似公式转换为 RGB，并限制到 0..255。
        return Rgb(
            // 计算红色通道。
            red = (1.164f * adjustedY + 1.596f * adjustedV).roundToInt().coerceIn(0, 255),
            // 计算绿色通道。
            green = (1.164f * adjustedY - 0.392f * adjustedU - 0.813f * adjustedV).roundToInt().coerceIn(0, 255),
            // 计算蓝色通道。
            blue = (1.164f * adjustedY + 2.017f * adjustedU).roundToInt().coerceIn(0, 255)
        )
    }

    // 从 ImageProxy 的某个 PlaneProxy 中读取指定采样点。
    private fun ImageProxy.PlaneProxy.valueAt(x: Int, y: Int): Int {
        // duplicate 避免修改原始 buffer 的 position。
        val duplicate = buffer.duplicate()
        // 根据 rowStride 和 pixelStride 计算平面内实际字节下标。
        val index = y * rowStride + x * pixelStride
        // 越界时返回 128，作为色度中性值或亮度兜底值。
        if (index !in 0 until duplicate.limit()) return 128
        // 读取 unsigned byte 值。
        return duplicate.get(index).toInt() and 0xFF
    }

    // 简单 RGB 像素结构。
    private data class Rgb(
        // 红色通道，范围 0..255。
        val red: Int,
        // 绿色通道，范围 0..255。
        val green: Int,
        // 蓝色通道，范围 0..255。
        val blue: Int
    )

    // 预处理常量。
    private companion object {
        // RGB 三通道数量。
        const val RGB_CHANNELS = 3
        // 平均亮度最多采样点数量。
        const val LUMINANCE_SAMPLES = 900
        // 默认中性亮度。
        const val DEFAULT_LUMINANCE = 128.0
        // YOLO letterbox padding 颜色。
        const val YOLO_PADDING_RGB = 114
        // whole-body 裁剪框额外扩展比例。
        const val WHOLE_BODY_CROP_PADDING = 0.08f
        // whole-body 模型训练时使用的 RGB 均值。
        val WHOLE_BODY_MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
        // whole-body 模型训练时使用的 RGB 标准差。
        val WHOLE_BODY_STD = floatArrayOf(58.395f, 57.12f, 57.375f)
    }
}

// YOLO 模型输入：包含 RGB FloatBuffer 和坐标还原变换。
data class YoloInput(
    // 输入 tensor 数据。
    val buffer: FloatBuffer,
    // YOLO 输入坐标到原图归一化坐标的变换。
    val transform: YoloInputTransform
)

// YOLO letterbox 预处理产生的坐标变换参数。
data class YoloInputTransform(
    // 模型输入正方形尺寸。
    val inputSize: Int,
    // 旋转后原图宽度。
    val rotatedWidth: Int,
    // 旋转后原图高度。
    val rotatedHeight: Int,
    // 原图缩放到输入内容区域的比例。
    val scale: Float,
    // 左侧 padding。
    val padLeft: Float,
    // 顶部 padding。
    val padTop: Float
) {
    // 将 YOLO 的中心点+宽高格式框还原为原图归一化矩形。
    fun xywhToNormalizedRect(cx: Float, cy: Float, width: Float, height: Float): V2Rect {
        // 宽度先转模型像素，再去掉 scale，最后除以原图宽度归一化。
        val normalizedWidth = width.toModelPixels() / scale / rotatedWidth
        // 高度先转模型像素，再去掉 scale，最后除以原图高度归一化。
        val normalizedHeight = height.toModelPixels() / scale / rotatedHeight
        // 中心 x 去掉 padding 和 scale 后归一化。
        val centerX = (cx.toModelPixels() - padLeft) / scale / rotatedWidth
        // 中心 y 去掉 padding 和 scale 后归一化。
        val centerY = (cy.toModelPixels() - padTop) / scale / rotatedHeight
        // 根据中心点和宽高构造矩形。
        return V2Rect(
            // 左边界。
            left = centerX - normalizedWidth / 2f,
            // 上边界。
            top = centerY - normalizedHeight / 2f,
            // 右边界。
            right = centerX + normalizedWidth / 2f,
            // 下边界。
            bottom = centerY + normalizedHeight / 2f
        // 限制到 0..1 范围内。
        ).clamped()
    }

    // 将 YOLO 的左上右下格式框还原为原图归一化矩形。
    fun xyxyToNormalizedRect(left: Float, top: Float, right: Float, bottom: Float): V2Rect {
        // 左边界去掉 padding 和 scale 后归一化。
        val normalizedLeft = (left.toModelPixels() - padLeft) / scale / rotatedWidth
        // 上边界去掉 padding 和 scale 后归一化。
        val normalizedTop = (top.toModelPixels() - padTop) / scale / rotatedHeight
        // 右边界去掉 padding 和 scale 后归一化。
        val normalizedRight = (right.toModelPixels() - padLeft) / scale / rotatedWidth
        // 下边界去掉 padding 和 scale 后归一化。
        val normalizedBottom = (bottom.toModelPixels() - padTop) / scale / rotatedHeight
        // 构造归一化矩形。
        return V2Rect(
            // 左。
            left = normalizedLeft,
            // 上。
            top = normalizedTop,
            // 右。
            right = normalizedRight,
            // 下。
            bottom = normalizedBottom
        // 限制在 0..1 范围。
        ).clamped()
    }

    // 将 YOLO 输入空间中的点还原为原图归一化点。
    fun pointToNormalized(point: V2Point): V2Point =
        // 构造归一化点。
        V2Point(
            // x 去掉 padding 和 scale 后除以原图宽度。
            x = ((point.x.toModelPixels() - padLeft) / scale / rotatedWidth).coerceIn(0f, 1f),
            // y 去掉 padding 和 scale 后除以原图高度。
            y = ((point.y.toModelPixels() - padTop) / scale / rotatedHeight).coerceIn(0f, 1f)
        )

    // 兼容模型输出为 0..1 归一化坐标或像素坐标两种情况。
    private fun Float.toModelPixels(): Float =
        // 小于等于 1 时认为是归一化坐标，乘以 inputSize；否则认为已经是模型像素。
        if (this <= 1f) this * inputSize else this
}

// whole-body 模型输入：包含裁剪后的 RGB FloatBuffer 和坐标还原变换。
data class WholeBodyInput(
    // 输入 tensor 数据。
    val buffer: FloatBuffer,
    // whole-body 输入坐标到整帧归一化坐标的变换。
    val transform: WholeBodyInputTransform
)

// whole-body 裁剪输入的坐标变换。
data class WholeBodyInputTransform(
    // 模型输入宽度。真实px值
    val inputWidth: Int,
    // 模型输入高度。真实px值
    val inputHeight: Int,
    // 输入对应的原图裁剪框。 归一化过的数据
    val cropBounds: V2Rect
) {
    // 将 whole-body 输入空间中的点还原为整帧归一化点。
    fun pointToNormalized(point: V2Point): V2Point =
        // 构造归一化点。
        V2Point(
            // 输入 x 按输入宽度归一化后映射到 cropBounds 内。
            x = (cropBounds.left + (point.x / inputWidth) * cropBounds.width).coerceIn(0f, 1f),
            // 输入 y 按输入高度归一化后映射到 cropBounds 内。
            y = (cropBounds.top + (point.y / inputHeight) * cropBounds.height).coerceIn(0f, 1f)
        )
}

// 扩展矩形框，并限制在 0..1 范围内。
private fun V2Rect.expand(padding: Float): V2Rect =
    // 四边同时扩展 padding。
    V2Rect(left - padding, top - padding, right + padding, bottom + padding).clamped()

// 将矩形框调整为目标宽高比，同时尽量保持中心点不变。
private fun V2Rect.fitAspect(targetAspect: Float): V2Rect {
    // 宽度至少给一个极小正数，避免除以 0。
    val safeWidth = width.coerceAtLeast(0.001f)
    // 高度至少给一个极小正数，避免除以 0。
    val safeHeight = height.coerceAtLeast(0.001f)
    // 当前矩形宽高比。
    val currentAspect = safeWidth / safeHeight
    // 调整后的宽度变量。
    val adjustedWidth: Float
    // 调整后的高度变量。
    val adjustedHeight: Float
    // 如果当前框比目标更宽，就保持宽度、增加高度。
    if (currentAspect > targetAspect) {
        // 保持原宽。
        adjustedWidth = safeWidth
        // 根据目标宽高比计算需要的高度。
        adjustedHeight = safeWidth / targetAspect
    } else {
        // 当前框更窄时保持高度。
        adjustedHeight = safeHeight
        // 根据目标宽高比增加宽度。
        adjustedWidth = safeHeight * targetAspect
    }
    // 最终宽度不能超过整幅图归一化宽度 1。
    val finalWidth = adjustedWidth.coerceAtMost(1f)
    // 最终高度不能超过整幅图归一化高度 1。
    val finalHeight = adjustedHeight.coerceAtMost(1f)
    // 尽量以原中心为中心计算左边界，并保证不越界。
    val adjustedLeft = (centerX - finalWidth / 2f).coerceIn(0f, 1f - finalWidth)
    // 尽量以原中心为中心计算上边界，并保证不越界。
    val adjustedTop = (centerY - finalHeight / 2f).coerceIn(0f, 1f - finalHeight)
    // 返回调整后的矩形。
    return V2Rect(
        // 左边界。
        left = adjustedLeft,
        // 上边界。
        top = adjustedTop,
        // 右边界。
        right = adjustedLeft + finalWidth,
        // 下边界。
        bottom = adjustedTop + finalHeight
    )
}
