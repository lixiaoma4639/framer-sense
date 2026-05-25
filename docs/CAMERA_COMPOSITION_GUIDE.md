# 相机构图引导模块（旧 ML Kit 方案）

本文档记录 `feature-camera` 中 CameraX + ML Kit 端侧构图引导的实现约定。当前 App 拍照入口已切换到 `feature-camera-pytorch` 的 ONNX Runtime 方案，新方案见 `docs/FEATURE_CAMERA_PYTORCH.md`。项目总体上下文仍以 `docs/PROJECT_CONTEXT.md` 为入口。

## 功能目标

- 打开拍照 Tab 后提供实时相机预览。
- 根据实时画面检测结果绘制虚线人物构图框，让真人可以走进虚线区域。
- 虚线人物包含简化 pose：头部、躯干、肩线、手臂和腿部。
- 场景不适合拍摄时，提示用户移动手机或调整人物位置。
- 点击拍摄后自动保存照片到系统相册。
- 本版不实现滤镜或自定义模型推理。

## 技术方案

相机能力使用 CameraX：

- `PreviewView` 显示后置摄像头实时预览。
- `Preview` 负责预览流。
- `ImageAnalysis` 负责实时帧分析。
- `ImageCapture` 负责拍摄照片。
- 分析策略使用 `STRATEGY_KEEP_ONLY_LATEST`，避免端侧 AI 推理阻塞预览。
- 实时分析做了节流控制，约每 450ms 处理一帧最新画面；如果单帧分析超过 2 秒未完成，会丢弃旧帧继续处理新帧，保证提示持续刷新。

端侧 AI 使用 Google ML Kit：

- Object Detection：检测画面中的主要物体和可能遮挡构图区域的大块物体。
- Pose Detection：检测真人姿态，用于判断人物是否进入虚线区域。
- 不使用 TensorFlow Lite、ExecuTorch 或自定义 PyTorch 模型。

当前依赖位于 `gradle/libs.versions.toml` 和 `feature-camera/build.gradle.kts`：

- `androidx.camera:camera-core`
- `androidx.camera:camera-camera2`
- `androidx.camera:camera-lifecycle`
- `androidx.camera:camera-view`
- `com.google.mlkit:object-detection`
- `com.google.mlkit:pose-detection`

应用需要在 `app/src/main/AndroidManifest.xml` 声明 `android.permission.CAMERA`。
`feature-camera/src/main/AndroidManifest.xml` 也声明该权限，确保模块被其他宿主 app 复用时仍能合并权限。
Android 9 及以下保存到系统相册需要 `WRITE_EXTERNAL_STORAGE`，该权限使用 `maxSdkVersion=28` 声明；Android 10 及以上通过 `MediaStore` 保存，不需要额外写入权限。

## 数据流

```text
CameraScreen
  -> CameraPreview
  -> CameraX PreviewView + ImageAnalysis + ImageCapture
  -> CameraGuideAnalyzer
  -> ML Kit Object Detection / Pose Detection
  -> CompositionGuideEngine
  -> CameraGuideState
  -> CameraGuideOverlay

CameraCaptureControls
  -> ImageCapture.takePicture
  -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  -> Pictures/Framer Sense
```

职责边界：

- `CameraScreen`：权限、页面状态和错误态。
- `CameraPreview`：CameraX 生命周期绑定、预览承载、拍摄保存。
- `CameraGuideAnalyzer`：将 `ImageProxy` 转为 ML Kit 输入，并输出轻量检测结果。
- `CompositionGuideEngine`：纯 Kotlin 构图规则，负责从检测结果生成引导状态。
- `CameraGuideOverlay`：绘制虚线人形和实时提示。

## 构图规则

当前默认使用通用人像构图：

- 优先在画面中部和左右三分线附近生成站位区域。
- 避开 Object Detection 返回的大面积障碍物。
- 暗光场景输出换光线或移动手机提示。
- 未检测到真人时提示“实时检测中：未检测到人物，请移动相机对准人物”。
- 检测到真人后，根据 pose 外接框与虚线区域的位置关系判断当前构图是否正确。
- 构图偏左、偏右、偏上、偏下时，分别提示“向左/向右/向上/向下移动相机”。
- 人物过大或过小时，提示后退或靠近。
- 对齐后提示“实时检测：构图正确，保持相机位置”。

`CompositionGuideEngine` 不直接依赖 CameraX 或 ML Kit 类型，后续如需接入自定义 PyTorch/ExecuTorch 模型，可替换输入映射或增强该引擎。

## UI 状态

`CameraUiState` 包含：

- `Loading`：相机启动中。
- `PermissionDenied`：没有相机权限，显示授权入口。
- `Ready`：相机与构图引导可用。
- `Error`：相机启动或分析异常。

`PhotoCaptureStatus` 包含：

- `Idle`：可拍摄。
- `Saving`：照片正在保存到相册。
- `Saved`：照片已保存到系统相册。
- `Error`：拍摄或保存失败。

覆盖层使用 Compose `Canvas` 绘制：

- 虚线圆角人物区域。
- 虚线头部圆形。
- 虚线 pose 骨架。
- 顶部实时提示文案。
- 底部拍摄按钮和保存状态文案。

## 测试

常用验证命令：

```bash
./gradlew :feature-camera:testDebugUnitTest
./gradlew :feature-camera:connectedDebugAndroidTest
./gradlew assembleDebug
```

测试覆盖：

- `CompositionGuideEngineTest` 覆盖空旷场景、左侧障碍物、真人偏离虚线和暗光场景。
- `CameraScreenTest` 位于 `androidTest`，覆盖权限拒绝、Ready 构图提示和错误态。

真机验证时重点检查：

- 首次进入拍照 Tab 的相机权限流程。
- 后置摄像头预览是否正常显示。
- 虚线人形是否覆盖在预览之上。
- 人物进入画面后提示是否随姿态变化。
- 暗光、遮挡和复杂背景下是否能给出可理解的移动建议。
- 点击拍摄后是否显示保存中、保存成功，并能在系统相册中看到照片。

设备建议：

- 当前 app 因 ONNX Runtime 新拍照入口要求已提升到 `minSdk = 24`；本旧 ML Kit 模块自身仍保持 `minSdk = 23`。
- 推荐使用 Android 10 及以上、arm64、后置摄像头正常的真机测试，Pixel、三星 Galaxy S/Note/A 系列、国内主流 Snapdragon/天玑机型都可以。
- 不要求专用 NPU 或 TensorFlow Lite/ExecuTorch 运行环境；ML Kit 推理会在端侧执行。
- 姿态检测更依赖画面条件：人物需要在画面中足够完整、光线较好、身体关键点不要被大面积遮挡。只拍近距离局部身体或复杂遮挡时，提示可能停留在“实时检测中：未检测到人物，请移动相机对准人物”。
- 模拟器也可验证 UI 和权限，但实时相机构图建议优先用真机验证。

## 后续扩展

- 增加前后摄像头切换。
- 增加半身头像、全身穿搭等不同构图模式。
- 将 `CompositionGuideEngine` 替换或增强为自定义模型推理结果。
- 若后续采用 PyTorch 路线，优先评估 ExecuTorch，并保持 UI 与 CameraX 层不感知具体推理框架。
