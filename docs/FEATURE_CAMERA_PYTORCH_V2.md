# feature-camera-pytorch-v2 ONNX 3D 构图引导

本文档记录 `feature-camera-pytorch-v2` 的全新拍照入口实现。旧 `feature-camera-pytorch` 和 `feature-camera` 保留为历史模块，不再作为 App 当前拍照 Tab 入口。

## 功能目标

- 打开拍照 Tab 后显示 CameraX 后置相机预览。
- 使用 ONNX Runtime 在端侧分析实时帧，组合人物/物体检测、可选人体分割和 RTMPose WholeBody 133 点 landmark，并基于检测到的物体推断场景类型。
- 根据场景类别、障碍物、亮度、人物位置和三分线候选区域生成推荐站位。
- 根据传入的身高体重生成线条式 3D 虚拟人像；RTMPose 身体点驱动基础姿势，全部 133 个高置信度身体、脚、脸和双手节点绘制为语义轮廓与节点，segmentation mask 则提供人物外轮廓。
- RTMPose 缺失或身体锚点不可靠时，虚线人像回退为可随身高体重缩放的汉服女性引导模板：盘发、微侧低首、双手交叠、宽袖与垂地长裙。
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
- `rtmpose_wholebody_256x192.onnx`：必需的 OpenMMLab RTMPose/RTMW WholeBody SimCC 模型，输入 192x256、输出 133 个 COCO-WholeBody 关键点；身体、脚、脸和双手的高置信度节点均参与引导绘制。缺失或姿态不可靠时退化为汉服构图引导模板。

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
- `CameraV2Preview`：绑定 CameraX 生命周期、预览、帧分析和拍照保存；屏幕旋转时以当前显示方向重绑三个 CameraX use case，保证预览、分析帧和输出照片使用同一方向。
- `CameraV2FrameAnalyzer`：对实时帧节流，调用 ONNX 分析器并输出构图状态。
- `CameraV2OnnxSessionManager`：应用进程级 ONNX session 管理器，后台预热并向各相机分析器提供同一组模型 session。
- `CameraV2OnnxAnalyzer`：运行 ONNX Runtime session，解析检测、可选 segmentation 和 RTMPose WholeBody landmark 结果，并根据 COCO 物体类别推断室内、城市、户外或未知场景。
- `CameraV2CompositionEngine`：纯 Kotlin 构图规则，不依赖 Android UI。
- `VirtualHumanProjector`：根据身高体重、pose 模板、RTMPose 133 点、人物轮廓生成伪 3D 线框投影，并提供默认汉服女性虚线引导模板。
- `CameraV2Overlay`：按深度排序绘制默认汉服女性虚线路径或 ONNX 驱动的 3D 虚拟人像、人物外轮廓虚线、人物内轮廓虚线、移动提示和模型状态。

## 测试

新增测试覆盖：

- `CameraV2CompositionEngineTest`：构图评分、暗光、场景偏好、模型缺失降级、人物轮廓/人框跟随、WholeBody 内轮廓接入。
- `VirtualHumanProjectorTest`：默认汉服女性模板、不同身高体重下的人像比例、pose-aware 骨架、WholeBody 内轮廓、深度和边界 clamp。
- `PoseTemplateSelectorTest`：pose 模板选择。
- `CameraV2ViewModelTest`：MVI 状态归约、权限/拍摄 effect 和 ONNX 加载状态同步。
- `CameraV2ScreenTest`：权限、Ready、错误 UI，以及 ONNX 加载与相机启动文案的区分。
- `CameraV2PreviewTransformTest`：横竖屏下 `FILL_CENTER` 预览裁切与 ONNX 归一化坐标的映射一致性。
- `SingleFlightValueLoaderTest`：ONNX session 预热、并发获取和加载失败结果均只执行一次加载。
- `NavigationTest`：App 默认拍照入口显示 v2 页面标识。

按仓库规则，开发中只新增或更新测试代码，不主动运行 Gradle/Android 自动化测试。
