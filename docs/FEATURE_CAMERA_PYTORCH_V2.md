# feature-camera-pytorch-v2 ONNX 3D 构图引导

本文档记录 `feature-camera-pytorch-v2` 的全新拍照入口实现。旧 `feature-camera-pytorch` 和 `feature-camera` 保留为历史模块，不再作为 App 当前拍照 Tab 入口。

## 功能目标

- 打开拍照 Tab 后显示 CameraX 后置相机预览。
- 使用 ONNX Runtime 在端侧分析实时帧，组合人物/物体检测和人体 pose 关键点，并基于检测到的物体推断场景类型。
- 根据场景类别、障碍物、亮度、人物位置和三分线候选区域生成推荐站位。
- 根据传入的身高体重生成线条式 3D 虚拟人像；检测到人体 pose 时优先跟随真实姿势，检测到 segmentation mask 时按人物轮廓绘制包裹虚线。
- 构图不满足要求时提示移动手机或调整距离；即使不满足要求，也持续绘制 3D 虚拟人像。
- 点击拍摄后保存照片到系统相册。

## 模型资产

v2 约定模型放在：

```text
feature-camera-pytorch-v2/src/main/assets/models/
```

当前代码按以下文件名加载：

- `yolov8n.onnx`：人物和环境物体检测。
- `yolov8n-pose.onnx`：人体 17 点 pose 关键点。
- `yolov8n-seg.onnx`：可选人物实例分割，增强人物轮廓包裹；缺失时退化为 person box + pose 包裹。

如果模型文件缺失，页面不会崩溃，会显示模型资产未就绪提示，并继续绘制可用的 3D 构图占位。放入真实 ONNX 文件后，`OnnxSessionPool` 会自动加载对应 session。

参考来源：

- ONNX Runtime YOLOv8 移动端检测/姿态示例：`https://onnxruntime.ai/docs/tutorials/mobile/pose-detection.html`
- Ultralytics Pose ONNX 导出：`https://docs.ultralytics.com/tasks/pose/`
- Ultralytics Segmentation ONNX 导出：`https://docs.ultralytics.com/tasks/segment/`

## 数据流

```text
CameraScreen(heightCm, weightKg)
  -> CameraV2Intent
  -> CameraV2ViewModel
  -> CameraV2State + CameraV2Effect
  -> CameraV2Preview
  -> CameraX PreviewView + ImageAnalysis + ImageCapture
  -> CameraV2FrameAnalyzer
  -> CameraV2OnnxAnalyzer
  -> ONNX Runtime: YOLO / YOLO Pose / YOLO Seg
  -> CameraV2CompositionEngine
  -> VirtualHumanProjector
  -> CameraV2Guide
  -> CameraV2Overlay

CameraX ImageCapture
  -> MediaStore.Images
  -> Pictures/Framer Sense
```

职责边界：

- `CameraScreen`：渲染 MVI 状态、转发用户操作、执行权限请求。
- `CameraV2ViewModel`：处理 `CameraV2Intent`，归约 `CameraV2State`，发出一次性 `CameraV2Effect`。
- `CameraV2Preview`：绑定 CameraX 生命周期、预览、帧分析和拍照保存。
- `CameraV2FrameAnalyzer`：对实时帧节流，调用 ONNX 分析器并输出构图状态。
- `CameraV2OnnxAnalyzer`：运行 ONNX Runtime session，解析检测、pose 和可选 segmentation 结果，并根据 COCO 物体类别推断室内、城市、户外或未知场景。
- `CameraV2CompositionEngine`：纯 Kotlin 构图规则，不依赖 Android UI。
- `VirtualHumanProjector`：根据身高体重、pose 模板、可用人体关键点和人物轮廓生成伪 3D 线框投影。
- `CameraV2Overlay`：按深度排序绘制 3D 虚拟人像、人物轮廓虚线、构图区域、移动提示和模型状态。

## 测试

新增测试覆盖：

- `CameraV2CompositionEngineTest`：构图评分、暗光、场景偏好、模型缺失降级、人物轮廓/人框跟随。
- `VirtualHumanProjectorTest`：不同身高体重下的人像比例、pose-aware 骨架、深度和边界 clamp。
- `PoseTemplateSelectorTest`：pose 模板选择。
- `CameraV2ViewModelTest`：MVI 状态归约和权限/拍摄 effect。
- `CameraV2ScreenTest`：权限、Ready 和错误 UI。
- `NavigationTest`：App 默认拍照入口显示 v2 页面标识。

按仓库规则，开发中只新增或更新测试代码，不主动运行 Gradle/Android 自动化测试。
