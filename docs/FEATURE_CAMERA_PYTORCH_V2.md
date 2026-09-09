# feature-camera-pytorch-v2 ONNX 3D 构图引导

本文档记录 `feature-camera-pytorch-v2` 的全新拍照入口实现。旧 `feature-camera-pytorch` 和 `feature-camera` 保留为历史模块，不再作为 App 当前拍照 Tab 入口。

## 功能目标

- 打开拍照 Tab 后显示 CameraX 后置相机预览。
- 使用 ONNX Runtime 在端侧分析实时帧，组合人物/物体检测、可选人体分割和 RTMPose WholeBody 133 点 landmark，并基于检测到的物体推断场景类型。
- 根据场景类别、障碍物、亮度、人物位置和三分线候选区域生成推荐站位。
- 内置项目自建的 24 套“自然易模仿”目标 Pose（全身 10、半身 8、近景 6），并自动生成左右镜像版本；场景构图规则自动推荐目标 Pose，用户可在同场景候选中点击“换一个姿势”。
- 虚拟人始终由固定目标 Pose 的完整 133 点（17 身体、6 脚、68 脸、42 手）驱动，并以程序化身体、发型、脸、手掌、四肢和鞋部渲染成半透明 2.5D 人物；133 点细节低透明度叠加，不使用第三方人像图片。RTMPose 的 133 个高置信度节点独立绘制为用户实时蓝色轮廓，并用于生成一条最高优先级的模仿调整提示，segmentation mask 则提供人物外轮廓。
- RTMPose 缺失或身体锚点不可靠时，仍显示目标虚拟人、构图框和 Pose 文字说明，仅隐藏实时对齐反馈；不回退到 YOLO Pose。
- 构图不满足要求时提示移动手机或调整距离；即使不满足要求，也持续绘制 3D 虚拟人像。
- 进入“相机”Tab 后，主导航中当前选中的“相机”Tab 显示为带蓝色背景的“拍摄”；点击后通过 CameraX `ImageCapture` 保存照片到系统相册，离开后恢复“相机”Tab，预览内不再提供独立拍摄按钮。
- 进入拍照 Tab 时启用设备全方向传感器；`MainActivity` 在同一实例内处理方向配置变化，横竖屏切换后会重绑 CameraX 的 Preview、ImageAnalysis 和 ImageCapture，并同步更新三者的目标旋转角度，避免窗口销毁期间的预览缓冲区错配。
- ONNX 输入统一按 `ImageProxy.rotationDegrees` 转为当前展示方向；覆盖层使用与 `PreviewView.FILL_CENTER` 相同的比例裁切映射，因此 YOLO、YOLO Seg 和 RTMPose WholeBody 结果在横竖屏下均与预览对齐。
- `MyApplication` 创建后会在后台预热三个 ONNX Runtime session。预热、首次相机分析、Tab 切换和页面重建共享同一加载任务与 session；预览销毁不会关闭 session，直到应用进程结束才由系统回收。若预热失败，会在当前进程内复用同一失败结果，避免反复加载失败模型。
- `OnnxSessionLoadState` 明确表达模型的未启动、加载中、就绪和失败状态；拍照页仅在加载中展示 ONNX 加载文案，其余相机预览重建阶段展示“正在启动相机分析”。

## 模型资产

v2 约定模型放在：

```text
feature-camera-pytorch-v2/src/main/assets/models/
```

当前代码按以下文件名加载：

- `yolov8n.onnx`：人物和环境物体检测。
- `yolov8n-seg.onnx`：可选人物实例分割，增强人物轮廓包裹；缺失时退化为 person box 包裹。
- `rtmpose_wholebody_256x192.onnx`：必需的 OpenMMLab RTMPose/RTMW WholeBody SimCC 模型，输入 192x256、输出 133 个 COCO-WholeBody 关键点；身体、脚、脸和双手的高置信度节点均参与用户实时轮廓与对齐。缺失或姿态不可靠时仍展示完整 133 点目标 Pose，仅隐藏实时对齐。

`yolov8n-pose.onnx` 资产和历史解析代码仍保留，但当前拍照业务不再加载、预热、推理或使用它的状态。

如果模型文件缺失，页面不会崩溃，会显示模型资产未就绪提示，并继续绘制可用的 3D 构图占位。放入真实 ONNX 文件后，`OnnxSessionPool` 会自动加载对应 session。

参考来源：

- ONNX Runtime YOLOv8 移动端检测/姿态示例：`https://onnxruntime.ai/docs/tutorials/mobile/pose-detection.html`
- Ultralytics Pose ONNX 导出：`https://docs.ultralytics.com/tasks/pose/`
- Ultralytics Segmentation ONNX 导出：`https://docs.ultralytics.com/tasks/segment/`
- OpenMMLab RTMPose WholeBody ONNX：`https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose`

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
  -> ONNX Runtime: YOLO / optional YOLO Seg / RTMPose WholeBody
  -> CameraV2CompositionEngine（构图 + 目标 Pose 推荐）
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
- `CameraV2Preview`：绑定 CameraX 生命周期、预览、帧分析和拍照保存；屏幕旋转时以当前显示方向重绑三个 CameraX use case，保证预览、分析帧和输出照片使用同一方向。
- `CameraV2FrameAnalyzer`：对实时帧节流，调用 ONNX 分析器并输出构图状态。
- `CameraV2OnnxSessionManager`：应用进程级 ONNX session 管理器，后台预热并向各相机分析器提供同一组模型 session。
- `CameraV2OnnxAnalyzer`：运行 ONNX Runtime session，解析检测、可选 segmentation 和 RTMPose WholeBody landmark 结果，并根据 COCO 物体类别推断室内、城市、户外或未知场景。
- `TargetPoseLibrary`：维护 24 套自建完整 133 点目标 Pose、严格左右镜像索引、景别和场景兼容标签。
- `PoseRecommendationEngine`：根据场景选择兼容景别和候选 Pose；ViewModel 保持自动推荐或用户手动切换后的稳定选择。
- `PoseAlignmentEngine`：将实时 133 点骨架按肩宽归一化，与固定目标比较后只输出一条可执行调整建议。
- `CameraV2CompositionEngine`：纯 Kotlin 构图规则，输出推荐目标 Pose 和实时观察数据，不依赖 Android UI。
- `VirtualHumanProjector`：根据身高体重和固定目标 Pose 生成实体化 2.5D 虚拟人；实时 133 点只形成用户轮廓，不改变目标人物。
- `CameraV2Overlay`：按深度排序绘制目标虚拟人、低透明度目标 133 点细节、用户实时轮廓、构图移动提示、当前 Pose 与“换一个姿势”入口。

## 测试

新增测试覆盖：

- `CameraV2CompositionEngineTest`：构图评分、暗光、场景偏好、模型缺失降级、人物轮廓/人框跟随、WholeBody 内轮廓接入。
- `TargetPoseLibraryTest`、`PoseRecommendationEngineTest`、`PoseAlignmentEngineTest`：24 套基础模板与镜像、场景兼容推荐、低置信度降级和单条对齐反馈。
- `VirtualHumanProjectorTest`：默认汉服女性模板、不同身高体重下的人像比例、实体化目标虚拟人、WholeBody 内轮廓、深度和边界 clamp。
- `PoseTemplateSelectorTest`：pose 模板选择。
- `CameraV2ViewModelTest`：MVI 状态归约、权限/拍摄 effect 和 ONNX 加载状态同步。
- `CameraV2ScreenTest`：权限、Ready、错误 UI，以及 ONNX 加载与相机启动文案的区分。
- `CameraV2PreviewTransformTest`：横竖屏下 `FILL_CENTER` 预览裁切与 ONNX 归一化坐标的映射一致性。
- `SingleFlightValueLoaderTest`：ONNX session 预热、并发获取和加载失败结果均只执行一次加载。
- `NavigationTest`：App 默认拍照入口显示 v2 页面标识。

按仓库规则，开发中只新增或更新测试代码，不主动运行 Gradle/Android 自动化测试。
