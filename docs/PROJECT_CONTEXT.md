# Framer_Sense 项目开发上下文

本文档面向后续编程使用，以当前源码为准，整理项目结构、模块边界、导航入口、主要数据流、测试方式和开发约定。已有 `README.md`、`ARCHITECTURE.md`、`docs/NAVIGATION.md`、`docs/HOME_MODULE.md` 可作为历史记录参考；若旧文档与源码不一致，后续开发优先参考本文档和实际源码。

## 1. 项目概览

Framer_Sense 是一个基于 Android 官方多模块架构模板演进而来的 Android 应用，使用 Kotlin 和 Jetpack Compose 构建 UI。

核心技术栈：

| 类型 | 技术 |
| --- | --- |
| 语言与构建 | Kotlin、Gradle Kotlin DSL、Version Catalog |
| UI | Jetpack Compose、Material3、Compose Preview |
| 依赖注入 | Hilt |
| 数据存储 | Room、KSP |
| 异步与状态 | Kotlin Coroutines、Flow、Lifecycle Compose |
| 导航 | 底部导航状态切换、Navigation3 模板能力保留 |
| 图片加载 | Coil 3 |
| 相机与端侧 AI | CameraX、OpenCV 4.12.0、MNN 3.6.1 离线 VLM、云端导演网关、Filament 1.75.1；旧 ONNX 与 ML Kit 方案保留 |
| 测试 | JUnit、Compose UI Test、Android Instrumented Test、Hilt Test |

当前源码基准包名和 app `applicationId` 为 `com.framer.sense`。各 module 的 Gradle `namespace` 默认以 `com.framer.sense` 为前缀，并按模块边界追加 `core.*`、`feature.*`、`test.*` 等后缀。

当前应用主体验是一个三栏导航 App；拍照 Tab 在横屏时改用右侧竖向导航栏，其余状态保持底部导航：

- 首页：推荐流和系统相册。
- 拍照：CameraX 冻结构图、离线/云端 VLM 导演、Rocketbox GLB 人像参考和系统相册保存。
- 我的：个人主页、内容 Tab、扫一扫说明页、消息列表页和设置页。

拍照模块当前按 MVI 组织：Composable 负责渲染状态、转发 Intent 和执行一次性 Effect，ViewModel 负责状态归约。非拍照模块当前按 MVVM 组织：Composable 负责渲染和事件转发，ViewModel 持有页面 UI 状态。模板中的 MyModel Repository/Room 数据层仍保留在 `core-data`、`core-database`，但不再接入底部导航中的“我的”主页。

## 2. 模块结构

### app

主应用模块，负责应用入口、主题装配和主导航组装。

- `MainActivity`：Activity 入口，启用 edge-to-edge，注入 `MyApplicationTheme`，渲染 `MainNavigation`。
- `Navigation.kt`：当前主 UI 入口，收集 `MainNavigationViewModel` 状态并组装自适应主导航；仅拍照横屏使用右侧竖向导航栏。
- `MainNavigationViewModel`：管理底部 Tab、“我的”模块内部路由和底部栏显隐状态。
- `MyApplication`：Hilt Application 入口。

### core-* 模块

核心模块提供可被多个 feature 复用的基础能力。

| 模块 | 当前职责 |
| --- | --- |
| `core-ui` | Compose 主题、颜色、排版等 UI 基础设施。 |
| `core-data` | Repository 层，封装 MyModel 数据访问并对上层暴露业务接口。 |
| `core-database` | Room 数据库、实体、DAO 和数据库 DI。 |
| `core-testing` | 共享测试基础设施，例如 Hilt 测试 Runner。 |
| `core-common` | 公共能力预留模块，目前主要是模块占位与示例测试。 |
| `core-network` | 网络能力预留模块，目前主要是模块占位与示例测试。 |

### feature-* 模块

功能模块承载具体页面和业务 UI。

| 模块 | 当前职责 |
| --- | --- |
| `feature-home` | 首页模块，包含推荐流和相册页面；首页 Tab、推荐流、相册读取分别由对应 ViewModel 管理状态。 |
| `feature-camera` | 旧 ML Kit 拍照模块，包含 CameraX 预览、ML Kit 画面分析、构图引导虚线覆盖层、拍摄保存和相机权限 UI；当前不再作为 app 拍照入口。 |
| `feature-camera-pytorch` | 上一版 ONNX 拍照模块，包含 CameraX 预览、ONNX Runtime SSD MobileNet 端侧检测、构图引导虚线覆盖层、拍摄保存和相机权限 UI；当前不再作为 app 拍照入口。 |
| `feature-camera-pytorch-v2` | 保留的 ONNX v2 模块，当前不再作为 app 拍照入口，包含 CameraX 预览、ONNX Runtime YOLO/YOLO Pose/可选 YOLO Seg/可选 WholeBody Landmark 约定模型加载、基于物体检测的场景推断、场景构图评分、线条式 3D 虚拟人像覆盖层、拍摄保存和相机权限 UI。 |
| `feature-camera-vlm` | 当前拍照入口，采用 MVI，负责 CameraX 扫描/冻结图、多图选景、OpenCV 背景找回、VLM 路由、本地单次/云端两轮导演、App 内模型下载、目录/ZIP 导入、MNN JNI 和 Filament 人偶。 |
| `feature-mymodel` | 我的模块，包含个人主页、扫一扫说明页、消息列表页和设置页；主页资料、内容 Tab、扫一扫说明、消息列表、设置项列表由 ViewModel 管理状态。 |

### services/vlm-gateway

可本地运行的 Python FastAPI 服务，用 HTTPX 适配千问、Seed 2.0、GPT、Gemini；供应商密钥和模型 ID 只在服务端配置。单次请求只做一轮模型决策，不负责会话和自动切换。

### *-navigation 模块

导航模块用于隔离 feature 的路由声明，避免其他模块直接依赖 feature 实现。

| 模块 | 当前状态 |
| --- | --- |
| `feature-mymodel-navigation` | 定义“我的”模块内部 Navigation3 `NavKey`，供 app 层组装内部返回栈。 |
| `feature-home-navigation` | 首页导航能力预留模块。 |
| `feature-camera-navigation` | 拍照导航能力预留模块。 |

### test-app

测试专用应用模块，用于集成测试和测试依赖装配。

## 3. 当前 UI 与导航

### 应用入口

启动路径：

```text
MyApplication
  -> MainActivity
  -> MyApplicationTheme
  -> MainNavigation
```

`MainNavigation` 收集 `MainNavigationViewModel` 暴露的 `MainNavigationUiState`，默认使用 `Scaffold` 和 `NavigationBar` 组织底部导航。当前包含 3 个固定 Tab：

| Tab | Composable |
| --- | --- |
| 首页 | `HomeScreen()` |
| 拍照 | `VlmCameraScreen()` |
| 我的 | `MyModelMainScreen()` |

当当前 Tab 为“拍照”且设备横屏时，主导航改为屏幕右侧的竖向 `NavigationRail`，相机预览占用左侧剩余区域；三个 Tab 沿右侧高度均匀分布，不使用默认的紧凑连续排列。两个横屏方向均固定在当前界面右侧，图标与文字保持正向。拍照竖屏、首页和“我的”继续使用底部导航。底部导航状态不会因“我的”模块内部页面而移除。当用户进入“我的 -> 扫一扫”“我的 -> 设置”或“我的 -> 消息”页面时，app 层使用 Navigation3 `NavDisplay` 和 `MyModelNavKey` 维护内部返回栈，内部页面作为全屏覆盖层盖住上一页全部内容，物理返回键会从 Navigation3 back stack 返回我的主页。主导航 ViewModel 只管理底部 Tab 状态，Compose 侧使用 `rememberSaveableStateHolder` 保存各 Tab 页面状态。

### 首页模块

`HomeScreen` 使用顶部 `SecondaryScrollableTabRow` 和 `HorizontalPager` 联动，Tab 选中状态由 `HomeViewModel` 暴露的 `HomeUiState` 管理。当前 Tab 顺序为：

1. 推荐
2. 相册

交互方式：

- 点击顶部 Tab 切换页面。
- 左右滑动 `HorizontalPager` 切换页面。
- Tab 选中状态跟随 Pager 当前页变化。
- 点击当前已选中的 Tab 不会重复触发滚动动画。

### 推荐页面

`RecommendScreen` 收集 `RecommendViewModel` 暴露的 `RecommendUiState`，当前使用本地假数据构建类小红书风格推荐流：

- 数据模型：`RecommendItem`
- 布局：`LazyVerticalGrid` 固定 2 列。
- 图片：Coil `AsyncImage` 加载网络图片。
- 视觉效果：不同 `aspectRatio` 模拟瀑布流高低错落。
- 图片区域有固定比例和占位背景，降低网络图片加载导致的布局跳动。
- 点赞数：通过 `formatLikes` 格式化为 `k` 或 `w`。

### 相册页面

`AlbumScreen` 和 `AlbumViewModel` 负责读取系统相册图片：

- Android 13 及以上使用 `READ_MEDIA_IMAGES`，Android 12 及以下使用 `READ_EXTERNAL_STORAGE`。
- 进入页面时先检查权限，已授权则直接加载，未授权才触发系统权限请求。
- 通过 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 查询图片，查询运行在 `Dispatchers.IO`，避免阻塞主线程。
- 成功后以 3 列 `LazyVerticalGrid` 展示系统图片 URI。
- 网格图片项使用稳定 key/contentType 和固定占位背景。
- UI 状态包括 `Loading`、`Success`、`PermissionDenied`、`Error`。

注意：相册读取依赖运行时权限，真机或模拟器环境会影响展示结果。

### 拍照页面

`VlmCameraScreen` 来自 `feature-camera-vlm`，按 MVI 组织，ViewModel 管理冻结会话、方案版本和异步请求令牌：

- CameraX 预览和拍照使用同一 ViewPort。冻结独立图像后释放 ImageProxy，模型输入 JPEG 长边最多 768 像素。
- 流程为实时预览、冻结、生成三方案、选中与拖动/缩放/文字修改、应用参考后恢复实时相机。
- `DirectorAgent` 最多两次模型决策，使用能力查询、方案校验和实际投影渲染工具；不执行任意代码。
- 模型设置支持大陆/海外、强制离线/指定云端/自动，以及网关令牌和离线模型管理。离线模型主要通过 OkHttp + 协程直接下载官方 MNN 目录，使用 dataSync 前台服务、固定提交和断点续传；保留目录/ZIP 导入。下载完成后仅在相机前台空闲时加载，原生加载成功才激活。
- 大陆自动路由为千问、Seed、离线；海外为 GPT、Gemini、离线。内容拒绝、取消和参数错误不触发自动切换。
- 首个离线目标为 Qwen3-VL-2B-Instruct 的 MNN 3.6.1 模型包，ARM64 CPU JNI 串行推理；权重不随 APK 打包。
- 离线加载及首 token 不限时；开始输出后连续 39 秒无新 token 才终止等待，正常持续输出不受总时长限制。本地构图使用 50 字内短场景观察，子任务最多调用一次并限制输出 96 token；兼容旧意图/数组协议，校验失败直接结束，修改保留未选中方案；云端构图保留最多两轮。离线多图选景另执行一次真实调用，离线推理不读取或消耗云端扫描预算，不设用户重复操作的会话累计次数上限。完整 JSON 的展示包装兼容不放宽业务校验。
- Filament 1.75.1 使用四个 Rocketbox 骨骼 GLB，支持 12 个受限姿势与 6 个表情，另有身体/手臂/头部/站姿的细分动作。表情使用实际 GLB 通道名称；缺失必需骨骼/表情或渲染为空时明确失败。卡片按需离屏渲染，应用后只叠加屏幕参考，不做真实空间锚定。
- P2 的版本化动作目录支持基础受控姿势、完整骨骼快照与命名 GLB 动画静帧采样；筛选角色、景别、资源/骨架和条件，外物接触动作及五个待素材验收的专业样片不可选。每次恢复全部绑定节点与表情，再执行动作、布局与最终表情，最后更新蒙皮。
- `ResolvedPhotoSpec` 统一最终布局、动作/角色资源版本、采样、表情与指导，不进入 v1 网关 JSON。`CompositionGuidance` 从最终方案派生本地化指导；模型原文单独作为分析依据。`AvatarPreview.appliedAvatar/appliedSpec → PlanPreview.appliedPlan → CompositionResult/referencePlan` 回传实际渲染布局，更新预览与文字后才能应用。校准标记只在 App 内保存，半身/特写校准保留裁切比例；拖动/缩放时清除旧预览，并以请求令牌和 revision 拦截迟到结果。
- 手动动作/表情/强度仅修改选中方案、递增 revision，不调用大模型；失败保留原方案。切换、拖动和缩放保留选择，重新生成/导演修改按新结果解析。同角色脸部示范独立按需渲染；缓存键包含动作/资产版本、采样、用途与校准标记，预览缓存最多六键、脸部最多两张。
- 保留主导航蓝色“拍摄”和横屏侧栏；冻结、生成、修改和资源管理期间禁止拍摄，不排队补拍。真实照片通过 MediaStore 保存，不合入虚拟人偶。
- 横竖屏变化保留 ViewModel 会话；进程回收后回到实时相机并提示重新扫描。
- App 不再启动预热 ONNX；旧相机模块和专题文档保留作为历史实现。

详细接入、模型准备、网关启动、协议和验收说明见 `docs/FEATURE_CAMERA_VLM.md`。2026-10-02 的 P1 与 P2 软件接入完成，累计 73 项 JVM 测试通过，主 App/设备测试代码编译和模块 Lint 通过。P2 未生成 APK，五个专业样片仍待素材验收，接入规范见 `docs/CAMERA_POSE_ASSET_INTEGRATION.md`；真实设备、视觉质量与供应商流程由用户验收。P3＋P4 软件接入完成，扫描、多图与背景确认见 `docs/CAMERA_SCAN_P3_P4_ACCEPTANCE.md`；P5/P6 尚未实施，进度见 `docs/CAMERA_SCAN_AGENT_IMPLEMENTATION_PLAN.md`。后续拍照功能优先在 `feature-camera-vlm` 内实现。

### 我的模块

`MyModelMainScreen` 是底部导航中的“我的”主页：

- 顶部操作栏：扫一扫、消息、设置。
- 个人信息区：头像、用户名、简介。
- 内容 Tab：作品、点赞、收藏、评论。
- Tab 使用 `PrimaryTabRow` 与 `HorizontalPager` 联动。
- 点击当前已选中的内容 Tab 不会重复触发滚动动画。
- 扫一扫按钮通过 `MainNavigation` 切换到 `ScanScreen`。
- 消息按钮通过 `MainNavigation` 切换到 `MessageListScreen`。
- 设置按钮通过 `MainNavigation` 切换到 `SettingsScreen`。

`MyModelMainViewModel` 负责主页资料、当前内容 Tab 和各 Tab 空状态文案。`SettingsScreen`、`MessageListScreen` 与 `ScanScreen` 是“我的”模块内部页面，不通过底部导航直接暴露；`SettingsViewModel` 负责设置项列表，设置项保持 56dp 以上触控高度，`MessageListViewModel` 当前提供本地静态示例消息列表，`ScanViewModel` 当前提供简单的扫码入口说明。

### MyModel 模板数据功能

项目仍保留原模板的 MyModel 数据层能力，但当前不再提供对应页面入口：

- `MyModelRepository`：对上层暴露 `Flow<List<String>>` 和 `add(name)`。
- `core-database`：Room 实体 `MyModel`、DAO 和 `AppDatabase`。

该数据层可作为后续真实数据功能的参考；如重新接入 UI，应按当前 MVVM 约定新增对应 ViewModel 和明确的 UI state。

## 4. 数据与依赖流

保留的模板数据依赖链路：

```text
feature-mymodel
  -> core-data
  -> core-database
```

相机构图引导数据流：

```text
VlmCameraScreen -> VlmIntent -> VlmCameraViewModel -> VlmUiState + VlmEffect
CameraX ImageCapture -> SnapshotStore -> 冻结 JPEG + 768px 模型图片
CompositionRepository -> DirectorAgent -> VisionModelProvider
  -> GatewayProvider -> services/vlm-gateway -> 区域内云端供应商
  -> MnnProvider -> JNI -> MNN 3.6.1 Omni 视觉编码与生成
DirectorAgent -> PlanValidator + PreviewStore -> FilamentAvatarRenderer
  -> 三方案 -> 选中修改 -> 应用倍率与透明人偶参考
CameraX ImageCapture -> MediaStore.Images -> 系统相册真实照片
```

职责边界：

- UI 层只依赖 ViewModel 或明确的 UI 状态，不直接操作 Room DAO。
- 拍照模块默认采用 MVI；非拍照模块默认采用 MVVM。
- Repository 负责屏蔽数据来源细节，对 feature 暴露业务语义接口。
- Room 实体和 DAO 保持在 `core-database`。
- Hilt Module 负责绑定接口和提供数据库实例。

共享能力：

- 通用主题、颜色、排版放在 `core-ui`。
- 测试 Runner、测试基础设施放在 `core-testing`。
- 跨功能公共工具优先放 `core-common`。
- 网络基础能力优先放 `core-network`。

## 5. 后续开发约定

- Gradle 插件版本由 Version Catalog 管理，根 `build.gradle.kts` 使用 `apply false` 统一声明公共插件，子模块按需应用，避免 Kotlin 插件被多个子项目重复加载；AGP 9 使用内置 Kotlin，不额外应用 `org.jetbrains.kotlin.android`。
- 新增用户可见功能时，优先放入对应 `feature-*` 模块。
- 只有多个模块复用的 UI、工具、数据能力才下沉到 `core-*`。
- 数据访问应经过 Repository，不要让 Compose 页面直接依赖 DAO 或数据库实体。
- 新增数据库字段或表时，需要同步更新 Room schema 和相关测试。
- 新增页面状态时，优先使用 ViewModel + 明确的 UI state 类型，避免在 Composable 中堆叠复杂业务判断。
- 新增导航入口时，先判断是 app 级底部导航、feature 内部页面，还是跨 feature 路由。
- 测试 Fake、测试 DI 和测试 Runner 不应混入正式业务实现。
- 修改旧文档前先确认是否是历史记录；通用项目上下文优先维护本文档。
- 后续只要发生代码更改，并且影响项目结构、模块职责、功能行为、导航流程、数据流、依赖、构建命令或测试方式，就必须同步更新对应 Markdown 文档。
- 文档同步范围不限于本文档；应优先更新最贴近变更内容的文档，例如 `README.md`、`ARCHITECTURE.md`、`docs/NAVIGATION.md`、`docs/HOME_MODULE.md` 或其他 `docs/` 下的专题文档。

## 6. 常用命令

在项目根目录执行：

```bash
# VLM 首次构建前准备固定 MNN 源码，不下载权重
bash feature-camera-vlm/scripts/prepare_mnn.sh

# 构建 Debug 包
./gradlew assembleDebug

# 运行所有本地单元测试
./gradlew testDebugUnitTest

# 运行指定模块单元测试
./gradlew :feature-home:testDebugUnitTest
./gradlew :feature-camera:testDebugUnitTest
./gradlew :feature-camera-pytorch:testDebugUnitTest
./gradlew :feature-camera-pytorch-v2:testDebugUnitTest
./gradlew :feature-camera-vlm:testDebugUnitTest
./gradlew :feature-mymodel:testDebugUnitTest
./gradlew :core-data:testDebugUnitTest

# 运行仪器测试，需要连接设备或启动模拟器
./gradlew connectedDebugAndroidTest

# 运行 app 模块仪器测试
./gradlew :app:connectedDebugAndroidTest
```

如果只修改文档，不需要运行 Android 构建或测试。

## 7. 测试现状

当前仓库包含多类测试：

- `core-data`：Repository 单元测试。
- `feature-camera-vlm`：协议、坐标、区域路由和导演预算，下载续传与校验，真实 GLB 元数据、动作能力/版本、快照/动画采样与最终指导一致性共 73 项 JVM 测试已通过；模型导入、加载事务、会话门控、校准回传、Compose 指导、手动选择/失败保留、迟到脸部示范和真实 GPU 姿态/动画/表情重置设备测试已编译，设备运行待用户执行。
- `services/vlm-gateway`：模拟四家上游及鉴权、拒绝、限流的 pytest 测试。本次未执行。
- `feature-camera-pytorch-v2`：ONNX 3D 构图规则、3D 人像投影、pose 模板、MVI ViewModel 本地单元测试和拍照页 Compose 仪器测试。
- `feature-camera-pytorch`：上一版 ONNX 构图规则本地单元测试和拍照页 Compose 仪器测试。
- `feature-camera`：旧 ML Kit 构图规则本地单元测试和拍照页 Compose 仪器测试。
- `feature-home`：首页页面级 Compose 测试位于 `androidTest`，本地单元测试命令不依赖 Compose UI Test 运行环境。
- `feature-mymodel`：我的主页页面级 Compose 测试位于 `androidTest`，本地单元测试覆盖 MyModel ViewModel 等非页面级逻辑。
- `app`：主导航相关 Android Instrumented Test。
- 多个模块仍保留模板生成的 `ExampleUnitTest` 或 `ExampleInstrumentedTest`。

后续新增功能时，测试范围按影响面选择：

- 纯状态或数据映射：优先写本地单元测试。
- Compose 页面显示和交互：写 Compose UI Test。
- 依赖 Hilt、权限、系统组件或跨模块导航：写 Android Instrumented Test。

## 8. 注意事项

- 当前源码中首页支持左右滑动切换；部分旧文档可能仍描述为不支持滑动。
- 当前首页默认页是 `HomeTab.RECOMMEND`，因为 `HomeTab` 枚举顺序为“推荐、相册”。
- `core-common`、`core-network`、`feature-home-navigation`、`feature-camera-navigation` 当前更偏预留模块，不应误判为已有完整业务能力。
- 当前拍照模块依赖 CameraX、Filament、MNN 源码以及用户导入的离线权重或已配置网关。模型缺失或云端不可用时明确报错，不以生产假方案代替推理。
- 当前 app 与 VLM 模块保持 `minSdk = 24`；原生离线目标为 ARM64。旧 ONNX v2 模块的 minSdk 约束保持不变。Debug 允许本地 HTTP 网关；Release 要求 HTTPS。
- 推荐页使用网络图片 URL，网络环境会影响图片加载结果。
- 相册页依赖系统权限和设备媒体库，测试或演示时可能出现空相册、权限拒绝或加载失败。
- 旧文档可以保留用于理解演进历史；后续编程优先以本文档和源码为准。

### P3＋P4 数据流、依赖与验证补充

拍照仍采用 MVI：`CameraX ImageAnalysis → 独立 YUV 缓冲 → 有界串行 ScanCollector/PhotoSessionController → 不可变 ScanSnapshot → ScanViewAgent → DirectorAgent → ResolvedPhotoSpec/预览 → 背景确认 → 人像参考 → 手动拍照`。扫描期关闭构图/渲染/拍照/倍率调整；后台中断保留帧，旋转重绑建立新配置。只有 REALTIME 时间源才与光轴姿态关联。扫描来源只在 App 内保存，统一校验最终方案、预览缓存和应用参考。

每个扫描的云端请求共同限制 6 次调用、12 MiB 上传，云端选景累计两次；离线选景和构图不读取或消耗这些额度，即使云端预算耗尽，离线仍可继续。云端最多 8 张/3 MiB；强制离线使用 `OfflineScanViewAgent → MnnProvider.compareViews → MnnNative.inferImages`，最多 4 张独立视觉输入，一次短协议选景后自动冻结并调用一次原离线三构图，不查询网关、零上传。4 张是应用限制；JPEG 副本长边最多 448，保留到原生任务退出后删除，编号严格绑定本次提交帧。旧网关/未声明多图保留主动展开的手动选帧与 v1 构图。背景 ORB/RANSAC 每秒最多 5 次，不上传；自动和人工确认分别记录，未验收真人姿势。P5/P6 尚未实施。

App 与模块保持 ARM64、NDK 28.2.13676358；OpenCV 4.12.0 官方 AAR 转换仅移除重复 C++ 库，其他 AAR 原样转交，统一使用工程 NDK 运行库。使用 `:app:mergeDebugNativeLibs` 和 `python3 feature-camera-vlm/scripts/verify_native_alignment.py` 验证 16 KiB ELF 对齐与运行库来源。网关模拟测试在 `services/vlm-gateway` 中执行 `python -m pytest tests -q`；不需要 APK。部署与真机边界见 `docs/CAMERA_SCAN_P3_P4_ACCEPTANCE.md`。

2026-10-03 交互约定：扫描后的默认流程由 Agent 选择一张最佳背景并自动生成构图，App 自动精简云端最多 8 张、强制离线最多 4 张作为多图输入；缓存帧列表默认不展示。多图降级必须说明具体原因，人工选帧与更换背景仅作为主动展开的备用入口，不要求用户在正常流程逐帧选择。离线视觉输入前缀测试用 `python3 feature-camera-vlm/scripts/verify_local_image_prompt.py` 在宿主 C++17 编译器运行，不加载权重、不打包 APK。

离线空景任务明确人物稍后入镜，选景读取一个 1..N 的数字；兼容旧 SELECT 及说明字段，缺失说明保持未知，禁止把 0 或无效输出自动映射为首帧。2026-10-06 默认 JNI 改为一次视觉预填充后，在真实模型合法编号分数中选最高项，避免自由文本格式错误；校验 tokenizer、分数与输出位置，异常保持扫描输入并报错。后续单图构图保持原文本解码并建议未来人物站位和姿势。选景等待及失败时以 `VlmUiState.displayScene` 显示保存的扫描预览并标注未选定；该显示字段不改变构图输入 `scene`、来源绑定或触发模型。

离线当前最多四帧：保留偏爱及不同视角的代表帧，按采集时间提交；仅离线改变呈现顺序，云端不变。摄影选景标准侧重人物空间和轮廓分离、背景主体与干扰、层次/引导线/视觉重心、可见曝光色彩和清晰度；偏爱在摄影条件相近时优先。日志版本为 `rubric=PORTRAIT_BACKGROUND_V2`，实际比较数量在界面显示。一次选景仍只有一次推理，实际质量、耗时与内存由真机验收。
