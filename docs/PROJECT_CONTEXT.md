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
| 相机与端侧 AI | CameraX、ONNX Runtime、SSD MobileNet ONNX、ML Kit 旧方案保留 |
| 测试 | JUnit、Compose UI Test、Android Instrumented Test、Hilt Test |

当前源码基准包名和 app `applicationId` 为 `com.framer.sense`。各 module 的 Gradle `namespace` 默认以 `com.framer.sense` 为前缀，并按模块边界追加 `core.*`、`feature.*`、`test.*` 等后缀。

当前应用主体验是一个三栏底部导航 App：

- 首页：推荐流和系统相册。
- 拍照：CameraX 实时预览、ONNX Runtime 端侧构图引导和拍摄保存到系统相册。
- 我的：个人主页、内容 Tab、扫一扫说明页、消息列表页和设置页。

拍照模块当前按 MVI 组织：Composable 负责渲染状态、转发 Intent 和执行一次性 Effect，ViewModel 负责状态归约。非拍照模块当前按 MVVM 组织：Composable 负责渲染和事件转发，ViewModel 持有页面 UI 状态。模板中的 MyModel Repository/Room 数据层仍保留在 `core-data`、`core-database`，但不再接入底部导航中的“我的”主页。

## 2. 模块结构

### app

主应用模块，负责应用入口、主题装配和主导航组装。

- `MainActivity`：Activity 入口，启用 edge-to-edge，注入 `MyApplicationTheme`，渲染 `MainNavigation`。
- `Navigation.kt`：当前主 UI 入口，收集 `MainNavigationViewModel` 状态并组装底部导航。
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
| `feature-camera-pytorch` | 当前拍照入口模块，包含 CameraX 预览、ONNX Runtime SSD MobileNet 端侧检测、构图引导虚线覆盖层、拍摄保存和相机权限 UI。 |
| `feature-mymodel` | 我的模块，包含个人主页、扫一扫说明页、消息列表页和设置页；主页资料、内容 Tab、扫一扫说明、消息列表、设置项列表由 ViewModel 管理状态。 |

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

`MainNavigation` 收集 `MainNavigationViewModel` 暴露的 `MainNavigationUiState`，并使用 `Scaffold` 和 `NavigationBar` 组织底部导航。当前包含 3 个固定 Tab：

| Tab | Composable |
| --- | --- |
| 首页 | `HomeScreen()` |
| 拍照 | `CameraScreen()` |
| 我的 | `MyModelMainScreen()` |

底部导航状态不会因“我的”模块内部页面而移除。当用户进入“我的 -> 扫一扫”“我的 -> 设置”或“我的 -> 消息”页面时，app 层使用 Navigation3 `NavDisplay` 和 `MyModelNavKey` 维护内部返回栈，内部页面作为全屏覆盖层盖住上一页全部内容，物理返回键会从 Navigation3 back stack 返回我的主页。主导航 ViewModel 只管理底部 Tab 状态，Compose 侧使用 `rememberSaveableStateHolder` 保存各 Tab 页面状态。

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

`CameraScreen` 当前来自 `feature-camera-pytorch`，是 CameraX + ONNX Runtime 的实时构图引导页面：

- 首次进入先检查 `CAMERA` 权限，未授权时展示权限说明和重新授权按钮。
- 页面按 MVI 组织，`CameraViewModel` 统一处理 `CameraIntent`、归约 `CameraUiState`，并通过 `CameraEffect` 触发权限请求等一次性平台动作。
- 已授权后使用 CameraX `PreviewView` 显示后置摄像头实时预览。
- `ImageAnalysis` 使用 `STRATEGY_KEEP_ONLY_LATEST` 获取实时帧，交给 `CameraGuideAnalyzer` 进行端侧分析。
- `CameraGuideAnalyzer` 对实时帧做约 520ms 节流，调用 `OnnxCameraAiDetector` 执行端侧检测。
- `OnnxCameraAiDetector` 使用 assets 中的 SSD MobileNet ONNX int8 模型检测人物和环境物体。
- `CompositionGuideEngine` 将检测结果转为通用人像构图建议，生成虚线人物区域、模板 pose 线条和引导文案。
- `CameraGuideOverlay` 在预览上使用 Compose `Canvas` 绘制虚线人形，并显示“走进虚线内”“向右移动手机”等提示。
- 页面底部提供拍摄按钮，使用 CameraX `ImageCapture` 拍照，并通过 `MediaStore` 保存到系统相册。

ONNX 相机构图功能的详细设计、数据流、模型来源和扩展方向见 `docs/FEATURE_CAMERA_PYTORCH.md`。旧 ML Kit 方案见 `docs/CAMERA_COMPOSITION_GUIDE.md`。后续拍照保存、滤镜或自定义模型能力应优先在 `feature-camera-pytorch` 内实现。

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
CameraScreen
  -> CameraIntent
  -> CameraViewModel
  -> CameraUiState + CameraEffect
  -> CameraX Preview + ImageAnalysis
  -> CameraGuideAnalyzer
  -> OnnxCameraAiDetector
  -> ONNX Runtime SSD MobileNet
  -> CompositionGuideEngine
  -> CameraGuideOverlay

CameraX ImageCapture
  -> MediaStore.Images
  -> 系统相册
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
# 构建 Debug 包
./gradlew assembleDebug

# 运行所有本地单元测试
./gradlew testDebugUnitTest

# 运行指定模块单元测试
./gradlew :feature-home:testDebugUnitTest
./gradlew :feature-camera:testDebugUnitTest
./gradlew :feature-camera-pytorch:testDebugUnitTest
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
- `feature-camera-pytorch`：ONNX 构图规则本地单元测试和拍照页 Compose 仪器测试。
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
- 当前拍照模块依赖相机权限、CameraX、ONNX Runtime 和内置 ONNX 模型；无权限、模型加载失败或无可用后置摄像头时只显示可恢复提示。
- 当前 app 与 `feature-camera-pytorch` 因 `onnxruntime-android:1.26.0` 要求提升到 `minSdk = 24`；其他旧模块仍可保持各自 `minSdk = 23`。
- 推荐页使用网络图片 URL，网络环境会影响图片加载结果。
- 相册页依赖系统权限和设备媒体库，测试或演示时可能出现空相册、权限拒绝或加载失败。
- 旧文档可以保留用于理解演进历史；后续编程优先以本文档和源码为准。
