# feature-camera-pytorch ONNX 构图引导

本文档记录 `feature-camera-pytorch` 中 CameraX + ONNX Runtime 的端侧 AI 构图引导实现。项目总体上下文仍以 `docs/PROJECT_CONTEXT.md` 为入口；旧 ML Kit 方案见 `docs/CAMERA_COMPOSITION_GUIDE.md`。

## 功能目标

- 打开拍照 Tab 后提供实时后置相机预览。
- 使用 ONNX Runtime 在端侧分析实时帧，检测画面中的人物和环境物体。
- 根据环境障碍物选择人物推荐站位，用虚线人形和简化 pose 骨架绘制在预览上。
- 检测到真人后判断人物是否进入虚线区域，并提示向上、下、左、右移动手机或调整距离。
- 场景偏暗或遮挡严重时输出可恢复提示。
- 点击拍摄后保存照片到系统相册。

## 技术方案

相机能力使用 CameraX：

- `PreviewView` 显示后置摄像头实时预览。
- `ImageAnalysis` 使用 `STRATEGY_KEEP_ONLY_LATEST` 获取最新帧。
- `ImageCapture` 负责拍摄并通过 `MediaStore` 保存照片。
- 实时分析约每 520ms 处理一帧，避免 ONNX 推理阻塞预览。

端侧 AI 使用 ONNX Runtime Android：

- 依赖：`com.microsoft.onnxruntime:onnxruntime-android:1.26.0`。
- 最低系统版本：ONNX Runtime Android 1.26.0 要求 `minSdk = 24`，因此当前 app 与 `feature-camera-pytorch` 最低支持 Android 7.0。
- 模型资产：`feature-camera-pytorch/src/main/assets/models/ssd_mobilenet_v1_12-int8.onnx`。
- 模型来源：ONNX Model Zoo / Hugging Face `onnxmodelzoo/ssd_mobilenet_v1_12-int8`。
- 模型输入：`[1, 300, 300, 3]` NHWC `UINT8` RGB 图像。
- 模型输出：`detection_boxes`、`detection_scores`、`detection_classes`、`num_detections`。

当前首版使用 SSD MobileNet 检测框判断人物位置，不接真实人体关键点模型；覆盖层中的 pose 为模板骨架，用于引导用户走进推荐构图区域。

## 数据流

```text
CameraScreen
  -> CameraPreview
  -> CameraX PreviewView + ImageAnalysis + ImageCapture
  -> CameraGuideAnalyzer
  -> OnnxCameraAiDetector
  -> ONNX Runtime SSD MobileNet
  -> CompositionGuideEngine
  -> CameraGuideState
  -> CameraGuideOverlay

CameraCaptureControls
  -> ImageCapture.takePicture
  -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  -> Pictures/Framer Sense
```

职责边界：

- `CameraScreen`：权限、页面状态、拍照状态和错误态。
- `CameraPreview`：CameraX 生命周期绑定、实时帧分析和拍照保存。
- `CameraGuideAnalyzer`：节流实时帧，调用 ONNX 检测器并分发结果。
- `OnnxCameraAiDetector`：加载 assets 中的 ONNX 模型，将 YUV 帧转为 RGB 输入并解析检测输出。
- `CompositionGuideEngine`：纯 Kotlin 构图规则，将人物和物体检测结果转成 UI 引导状态。
- `CameraGuideOverlay`：绘制虚线人形、模板 pose、顶部提示和方向提示。

## 构图规则

- 优先在画面中部和左右三分线附近生成站位区域。
- 检测到非人物物体后，避开大面积障碍物，选择更空旷的候选站位。
- 障碍物遮挡推荐站位时，根据左右上下区域占比提示移动手机。
- 未检测到人物时提示“请让人物走进虚线区域”。
- 检测到人物后，根据人物框与虚线区域中心的偏移提示向左、右、上、下移动手机。
- 人物框过大时提示后退，过小时提示靠近。
- 暗光场景提示朝光线更好的方向移动手机。
- 对齐后提示“构图正确，保持相机位置”。

## 模型替换

如果后续要替换为自训练 PyTorch 模型：

1. 使用 PyTorch 导出 ONNX，保持输入为单张 RGB 图像，推荐固定到 300x300 或 320x320。
2. 将模型放入 `feature-camera-pytorch/src/main/assets/models/`。
3. 更新 `OnnxCameraAiDetector` 中的模型路径、输入尺寸、输入类型和输出解析。
4. 如果模型输出真实人体关键点，可将关键点映射为 `NormalizedLine`，替换当前模板 pose。

如果后续切换 ExecuTorch，建议保留 `CompositionGuideEngine` 和 `CameraGuideOverlay`，只替换 `OnnxCameraAiDetector` 对应的推理实现。

## 测试

常用验证命令：

```bash
./gradlew :feature-camera-pytorch:testDebugUnitTest
./gradlew :feature-camera-pytorch:connectedDebugAndroidTest
./gradlew assembleDebug
```

测试覆盖：

- `CompositionGuideEngineTest` 覆盖空旷场景、左右上下障碍物、人物偏离虚线、人物对齐和暗光场景。
- `CameraScreenTest` 位于 `androidTest`，覆盖权限拒绝、Ready 构图提示和错误态。

真机验证时重点检查：

- 首次进入拍照 Tab 的相机权限流程。
- 后置摄像头预览是否正常显示。
- 首次模型加载是否会在短暂等待后显示实时提示。
- 虚线人形是否覆盖在预览之上。
- 人物进入画面后提示是否能随位置变化。
- 暗光、遮挡和复杂背景下是否能给出可理解的移动建议。
- 点击拍摄后是否显示保存中、保存成功，并能在系统相册中看到照片。

## 注意事项

- 当前模型是通用 COCO 目标检测模型，人物框可以满足首版构图引导，但不会输出真实人体姿态关键点。
- ONNX Runtime 推理在端侧执行，不依赖云端服务；性能取决于设备 CPU/NNAPI 支持和当前相机帧尺寸。
- 模拟器可验证 UI 和权限，但实时构图效果建议使用 Android 10 及以上真机验证；安装设备需 Android 7.0 及以上。
