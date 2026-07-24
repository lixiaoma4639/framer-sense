# Android ONNX / YOLO 模型集成参考

本文记录 `feature-camera-pytorch-v2` 在 Android 端集成 YOLOv8 / ONNX 模型时可参考的资料，重点覆盖相机帧输入、预处理、ONNX Runtime 推理、模型输出解析和后处理。

## 总体说明

严格来说，没有一篇官方文档完整覆盖“YOLOv8n ONNX 集成到 Android”的全部流程。当前实现需要组合以下资料：

- CameraX：获取 `ImageProxy` 相机帧。
- ONNX Runtime Java / Android：创建 tensor、运行 ONNX session。
- Ultralytics YOLO：复刻 Python 端的 letterbox 预处理、输出解析和 NMS 后处理。

因此 Android 端 Kotlin 实现本质上是在本地复刻这条链路：

```text
ImageProxy 相机帧
-> YUV 转 RGB
-> letterbox resize + padding
-> FloatBuffer / OnnxTensor
-> OrtSession.run(...)
-> 解析 YOLO 输出 tensor
-> 置信度过滤 + NMS
-> 坐标还原到原图归一化坐标
```

## 参考资料

### 1. ONNX Runtime Android / Java API

- [ONNX Runtime Android install](https://onnxruntime.ai/docs/install/#install-on-android)
- [ONNX Runtime Java get started](https://onnxruntime.ai/docs/get-started/with-java.html)
- [OnnxTensor Java API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OnnxTensor.html)

对应实现点：

- `OrtEnvironment`
- `OrtSession`
- `OnnxTensor.createTensor(...)`
- `session.run(...)`

对应代码：

- `OnnxSessionPool.kt`
- `CameraV2OnnxAnalyzer.kt`

### 2. Ultralytics YOLO Export

- [Ultralytics Export docs](https://docs.ultralytics.com/modes/export/)

对应实现点：

- 导出 ONNX 模型。
- 确认 `imgsz`，例如 `640`。
- 确认导出的模型是否内置 NMS。
- 根据实际导出的输出 tensor 格式调整 Android 端解析逻辑。

对应代码：

- `OnnxSessionPool.kt`
- `YoloTensorLayout`
- `YoloOutputParser`

### 3. Ultralytics LetterBox 预处理

- [Ultralytics LetterBox API](https://docs.ultralytics.com/reference/data/augment/#ultralytics.data.augment.LetterBox)

对应实现点：

- 等比缩放到固定输入尺寸。
- 对短边补 padding。
- 使用常见 padding 值 `114`。
- 记录 `scale`、`padLeft`、`padTop`。
- 将模型输出坐标从 YOLO 输入空间还原回原始相机画面坐标。

对应代码：

- `FramePreprocessor.toYoloRgbFloatBuffer(...)`
- `YoloInputTransform.xywhToNormalizedRect(...)`
- `YoloInputTransform.xyxyToNormalizedRect(...)`
- `YoloInputTransform.pointToNormalized(...)`

### 4. Ultralytics YOLO 后处理 / NMS

- [DetectionPredictor.postprocess](https://docs.ultralytics.com/reference/models/yolo/detect/predict/#ultralytics.models.yolo.detect.predict.DetectionPredictor.postprocess)
- [Ultralytics NMS utils](https://docs.ultralytics.com/reference/utils/nms/#ultralytics.utils.nms.non_max_suppression)

对应实现点：

- 解析候选框。
- 解析类别分数。
- 按置信度阈值过滤候选。
- 按 IoU 阈值执行 NMS。
- 兼容 raw YOLO 输出和已 NMS 输出。

对应代码：

- `YoloOutputParser.parseObjects(...)`
- `YoloOutputParser.parseRawObjects(...)`
- `YoloOutputParser.parseNmsObjects(...)`
- `YoloOutputParser.nms(...)`

### 5. Ultralytics YOLOv8 ONNX Runtime 示例

- [YOLOv8-ONNXRuntime example](https://github.com/ultralytics/ultralytics/tree/main/examples/YOLOv8-ONNXRuntime)

对应实现点：

- 预处理输入图片。
- 使用 ONNX Runtime 执行 YOLOv8 ONNX 模型。
- 后处理模型输出。

注意：该示例是 Python 参考，不是 Android 示例。Android 端需要用 Kotlin 按相同流程实现。

对应代码：

- `FramePreprocessor.kt`
- `CameraV2OnnxAnalyzer.kt`

### 6. CameraX 图像输入

- [CameraX Image analysis](https://developer.android.com/media/camera/camerax/analyze)

对应实现点：

- 使用 `ImageAnalysis` 获取实时帧。
- 分析器接收 `ImageProxy`。
- 读取 `imageInfo.rotationDegrees`。
- 分析完成后必须调用 `ImageProxy.close()`。
- 将 `ImageProxy` 的 YUV 数据转换为 RGB 后再喂给模型。

对应代码：

- `CameraV2Preview.kt`
- `CameraV2FrameAnalyzer.kt`
- `FramePreprocessor.kt`

## 关键结论

`yolov8n.onnx` 的 Android 集成主要参考：

- ONNX Runtime Java / Android API：负责模型加载和推理执行。
- Ultralytics LetterBox：负责输入 resize、padding 和坐标还原。
- Ultralytics NMS / 后处理逻辑：负责解析输出、过滤候选和去重。
- CameraX ImageAnalysis：负责提供实时相机帧。

输入预处理和输出后处理不是 ONNX Runtime 自动完成的，需要 Android 端按导出模型的实际输入输出格式手写实现。
