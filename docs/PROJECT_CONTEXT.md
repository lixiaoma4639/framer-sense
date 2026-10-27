# Framer_Sense 项目开发上下文

2026-10-27 历史抽屉占窗口宽度 3/4，标题沿用安全区后的 48dp 行高；两个设置入口位于标题行右侧，直接打开弹窗并保留抽屉，返回优先关设置。相机 AI 入口相对整行居中。相机顶部由 `rememberCameraSafeInsets` 在进入当前布局时锁定安全距离，状态栏／刘海及设备资源补足首帧数据，抽屉显隐不重算，仅布局方向／显示密度变化重算（不以 screenHeightDp 为键）；底部固定预留 24dp 时间线空间（节点 20dp、图标 16dp），不再读取动态手势／导航栏高度移动 UI。`VlmCameraScreen` 上报抽屉状态，App `NavigationSystemBars` 在抽屉打开时同时显示顶部状态栏和底部系统栏，关闭时恢复隐藏，焦点／前台恢复沿用此策略；抽屉背景铺满窗口高度，拍摄页导航栏透明、无独立黑底／对比度遮罩／分隔线，系统横条叠在抽屉背景上；内部内容避让系统栏，不影响底层相机坐标和预览尺寸。

2026-10-26 相机本地历史：`feature-camera-vlm` 新增 `CameraCompositionHistory`，通过 `core-data/CameraHistoryRepository` 访问 `core-database/CameraHistoryDatabase`（独立 `camera-history.db`，不改 MyModel）。保存云端成功流程、提示词／版本／参数、方向／来源／扫描快照和稳定风格键，图片留私有文件并排除云备份。左侧 Material3 历史抽屉可置顶、删除、恢复到构图选择；右上白色 X 保留原退出行为。恢复后原流程续生使用当前模型和最新模板，临时锁原画幅，重新应用必须再次对齐；不自动恢复未完成的收费请求。详见 `docs/CAMERA_HISTORY.md`。

2026-10-25 生图完整预览与人像应用解耦：`SeedreamLayerCompositor` 将底图与全部前景按层级、官方边界框合成；优先绝对坐标并处理底图降采样，归一化坐标作后备。人物筛选不再阻断完整图预览。资源清单的 `portraitPath` 可为空，`sourceLayerCount` 保存实际层数，`hasPortrait`／`canApply` 控制人像应用，无人像时仍可选择与放大完整图。仍为单次请求，不增加模型或重试；详见 `docs/CAMERA_GENERATED_REFERENCE_PLAN.md` 4.2。

2026-10-25 最新生图排查要求：当前每次仅生成一张，创建一个候选并提交一次 Seedream 原生图层请求，客户端并发上限为 1；已有请求在途时生成、换一张、重试均不追加提交。此条覆盖下方历史双候选描述，保留已有构图与相机交互。确认框文案同步为一张；本次用于排查并发因素，尚未确认 HTTP 400 根因。已更新测试代码，未运行自动化测试。

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

当前应用主体验是三栏导航 App；首页／我的保留底部导航，拍摄页横竖屏均使用沉浸式全屏：

- 首页：推荐流和系统相册。
- 拍照：CameraX 冻结构图、离线/云端 VLM 导演、Rocketbox GLB 人像参考和系统相册保存。
- 我的：个人主页、内容 Tab、扫一扫说明页、消息列表页和设置页。

拍照模块当前按 MVI 组织：Composable 负责渲染状态、转发 Intent 和执行一次性 Effect，ViewModel 负责状态归约。非拍照模块当前按 MVVM 组织：Composable 负责渲染和事件转发，ViewModel 持有页面 UI 状态。模板中的 MyModel Repository/Room 数据层仍保留在 `core-data`、`core-database`，但不再接入底部导航中的“我的”主页。

## 2. 模块结构

### app

主应用模块，负责应用入口、主题装配和主导航组装。

- `MainActivity`：Activity 入口，启用 edge-to-edge，注入 `MyApplicationTheme`，渲染 `MainNavigation`。
- `Navigation.kt`：当前主 UI 入口，收集 `MainNavigationViewModel` 状态并组装主导航；首页／我的保留底部导航，拍摄使用沉浸式全屏。
- `MainNavigationViewModel`：管理底部 Tab、“我的”模块内部路由和底部栏显隐状态。
- `MyApplication`：Hilt Application 入口。

### core-* 模块

核心模块提供可被多个 feature 复用的基础能力。

| 模块 | 当前职责 |
| --- | --- |
| `core-ui` | Compose 主题、颜色、排版等 UI 基础设施。 |
| `core-data` | Repository 层，封装 MyModel 与相机构图历史的数据访问。 |
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

云端默认地址、模型 ID、视觉请求路径与网关业务路径统一维护在 `config/cloud-models.json`；Android 构建生成 BuildConfig 后由 `CloudModelConfig` 访问，Python 网关启动时读取同一文件。QWEN 与 Seed 官方直连的 Base URL 和模型 ID 在界面只读展示，运行时以当前 JSON 为准；QWEN 保留原设置归一化，Seed 独立通过配置访问。两家 API Key 与网关令牌分开用 Keystore 加密，旧设置有网关令牌且缺少对应接入方式字段时保留该供应商网关入口。其他设备设置及网关部署环境变量继续保留优先级。独立部署网关需携带该文件并保留仓库相对目录结构；操作说明见 `docs/FEATURE_CAMERA_VLM.md` 的云端模型统一配置章节。

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

首页和“我的”在横竖屏均使用底部导航；进入拍照后隐藏底部导航和系统栏，左上返回退出到进入前的 Tab（直接进入默认首页）。返回目标由 Compose 保存状态管理。拍摄 Tab 只负责导航，独立快门由拍照页管理。底部导航状态不会因“我的”模块内部页面而移除。当用户进入“我的 -> 扫一扫”“我的 -> 设置”或“我的 -> 消息”页面时，app 层使用 Navigation3 `NavDisplay` 和 `MyModelNavKey` 维护内部返回栈，内部页面作为全屏覆盖层盖住上一页全部内容，物理返回键会从 Navigation3 back stack 返回我的主页。主导航 ViewModel 只管理底部 Tab 状态，Compose 侧使用 `rememberSaveableStateHolder` 保存各 Tab 页面状态。

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

2026-10-25 生图诊断与操作区回归：Android 日志标签 `VlmImageGen` 记录请求／响应安全摘要、上游请求 ID／错误码、图层与解码、文件写入和异常位置，不记录照片、提示词、Key、签名链接或异常原始消息；网关失败返回安全 `result.failure`。AI 参考接回原 `Controls`／操作栏／生成确认／详情应用组和紧凑卡片，不单独设计默认主题操作栏；操作文字白色，AI 详情／应用黄色，卡片选框白色。五步时间线与相机布局保留，首个候选就绪即可应用，放大和换一张进入展开详情。复测步骤见 `CAMERA_GENERATED_REFERENCE_PLAN.md`。

2026-10-25 模型与生图选择统一：模型设置移除中国／国际切换，千问、豆包 Seed、GPT、Gemini 横向展示，仅显示当前品牌配置，切换保留各 Key。`ModelRouting` 使用所选品牌，自动模式只允许退本地，不跨品牌；区域字段按品牌归一化兼容协议。Seedream 复用 `seedApiKey`、`seedAccess` 和已有网关配置，`imageAccess` 仅作旧字段兼容，不再独立控制接入。Flash／Pro 迁至 AI 构图对话输入框内右下角，显示「快速／专家」，菜单显示完整名称与对应标签，默认 Flash 并持久化。`SeedreamModelSelected` 不改文本或已应用参考，设置写入用互斥锁串行。非豆包品牌显示「不支持」，生成时提示，不借用 Seedream 或自动转离线。豆包自动模式继续根据互联网、共享凭证及全部候选服务失败退本地；强制离线始终 GLB，指定云端不降级。实际来源由 `CameraReferenceRouting` 与 `VlmUiState.automaticReferenceSource` 管理；切换品牌取消在途任务、清理旧构图并隔离回传，换 Flash／Pro 只影响后续请求。已补充回归代码，未运行自动化测试。

2026-10-24 生成式人像参考首轮接入：模型设置新增独立的「摄影参考方式」和 Seedream Flash／Pro 选择，保留默认 GLB 与原有 VLM 设置，不添加 4.0。AI 人像要求云端许可和普通相机；使用官方单图图层模式（`layer_decomposition=true`）提交一次候选请求，本地按底图与层坐标合成完整图和同尺寸透明人像，不追加 VLM 规划、复查或第二次付费生图。首轮两个独立候选，按资源包完成顺序追加，首个即可应用、原图对齐和拍照；生成任务与应用令牌分离，另一候选完成不改变当前选择、对齐或拍摄记录。生成式方案不伪造 `CompositionPlan`／GLB 动作，背景匹配抽出 `BackgroundReferenceTarget` 共用。新增候选重试、单张换图、会话批次历史及整图放大，隐藏生成路线的 GLB 动作、表情滑块、AR 和独立脸部示范。资源保存在 App 私有目录；网关新增 SQLite 幂等任务与受保护的原始图层文件，按单进程部署，重启未决任务标中断且不重发。主 Kotlin 与 JVM 测试源码编译通过；Android 测试源码编译存在旧 UI 测试错误，未扩大修复；本轮未运行自动化测试或真实 API。图层模式能否同时按提示词创作新姿势、照片质量与费用仍待真机验收；端侧进程恢复、历史清理和正式隐私流程仍待完成。状态、限制与步骤见 `docs/CAMERA_GENERATED_REFERENCE_PLAN.md`。

2026-10-20 快门左侧相册：按最新交互改为固定白色图案，40dp 透明底单一相册图案／点击区 52dp，与时间线共用 360dp 最大宽度并左对齐第一个时间线图标；快门继续居中，原操作栏高度、预览及时间线位置不变。删除缩略图查询、内容观察、保存 URI 回传、照片读取授权及相机模块新增读取权限，不影响首页相册。点击通过 `ACTION_MAIN + CATEGORY_APP_GALLERY` 或图片目录入口查找相册，使用实际默认处理程序，未设置默认时优先系统相册并稳定选取显式组件；不调用 chooser、不退回隐式选择弹窗或图片选择器。Manifest 仅增加两个入口的包可见性查询。无可用相册时提示，返回沿用原会话恢复逻辑；原照片保存／时间线及忙碌门控保留。补充固定图案、左对齐、快门位置、默认／多候选／Resolver／不可用组件回归代码，未执行 Gradle／Android 测试；真机清单见 `CAMERA_UI_FRAMING_ACCEPTANCE.md`。

2026-10-20 相机流程时间线：扫描、生成构图、选择构图、对齐、拍摄五个图标及连接线独立叠放在原快门与屏幕底部之间，不参与快门栏测量，保留原快门与控件位置；利用底部留白靠底显示并避开显示切口，留白不足时紧凑显示。未完成白色，执行中白色小进度圈，完成绿色；节点只读，不新增导航。进度由 MVI 状态派生：采集／整理期间扫描运行，扫描帧就绪后的视角比较归入生成；应用成功才完成选择，自动／手动确认共用对齐状态，实时相机的单张构图同样绑定本次照片，应用后等待自动／手动背景确认；无绑定的历史或导入参考保留兼容行为。回退时只保留连续完成的前置步骤与当前节点，其后全部白色；重开构图选择／详情时按等待选择展示，背景确认与人像业务逻辑不变。保存请求使用现有 token 隔离，仅成功回调记录当前背景与已应用方案版本，失败、旧回调或保存期间失去对齐不能点亮拍摄。重启扫描、重新生成／应用、返回实时取景及打开详情清除拍摄记录，对齐失效也清除旧记录，再次确认不复活旧拍摄进度。普通快门仍可直接拍摄，时间线不越过未完成的前置节点。时间线只旋转图标，沿用四档动画；相机预览与视口不变。补充纯状态、保存回调及 UI 回归代码，未运行 Gradle／Android 测试。

背景匹配与虚拟人像解耦：方案 3 曾出现 referencePoints=0；限制排除面积后，用户半身日志仍因约 30% 的排除区域只剩 124 个参考点及不足的描述子匹配而失败。模板来自原始背景照片，分析帧来自 CameraX／AR 原始相机图像，均不含后续渲染的虚拟人像，故取消按虚拟人像布局排除特征，移除旧掩码策略。参考缓存键使用背景、方案 ID／版本、裁剪、配置与参考 epoch，不再包含 avatar；仅改人物大小／站位不会改变背景特征或清计时，切换方案／重建参考仍重新确认。RANSAC、覆盖／象限、几何与稳定门限保留，实际遮挡可能导致重新寻找或需要人工确认。本次不修改构图、倍率或人像渲染。重建日志标记 featureSource=RAW_BACKGROUND、avatarMask=NONE、excludedArea=0；回归覆盖有效纹理恰落在旧排除区域、不同大小人像共用背景及真实 NV21 自动确认，未运行 Gradle／Android 测试。

构图间对齐差异修复：用户失败日志有 99～116 个内点，但特征仅覆盖两个象限／约 17% 区域，旧分布门限导致候选反复归零。实时指导新增强局部证据通道（至少 45 匹配／30 内点／65% 内点比例／15% 覆盖／2 象限），原标准证据与扫描去重保持不变。仍须至少 3 帧合格结果、跨度达 500 ms；单帧边界几何波动（8%／8°／18% 内）可以跨过但不能确认或计入合格帧数，连续边界帧、无匹配、明显偏离及合格帧间隔超过 500 ms 则重置。日志新增候选跨帧次数与 DISTRIBUTED／STRONG_PARTIAL／INSUFFICIENT 证据类型；增加用户失败指标回放及稳定窗口边界测试，未运行。

自动确认回归诊断：用户日志显示 CameraX 持续采样且匹配一直返回 SEARCHING，目前未确定具体未通过的条件。本轮仅增加 `VlmScan` 日志，保留原判定、配置通知、状态合并及确认行为，不继续调整阈值。日志覆盖参考缓存重建、帧拒绝原因、特征不足／描述子匹配不足／单应矩阵失败、匹配几何与置信度指标、稳定计时／候选帧数／重置次数，以及自动结果是否提交与人像显示条件。常规判定与拒绝日志限两秒一次，确认状态切换另外记录；处理异常不再静默。`VlmSessionTest` 增加真实 NV21 → ORB → 稳定计时 → MVI 的自动确认回放，与手动确认比较人像参考及拍摄状态；诊断映射及计时计数回归也已补充，均未运行，待用户真机日志定位。

自动对齐范围优化：实时找回背景改用 `BackgroundMatchPolicy.alignedForGuidance`，角点偏差／旋转／缩放偏差从 3%／3°／8% 放宽到 6%／6°／15%，连续稳定时间从 700 ms 缩短到 500 ms；仍要求原匹配数量、内点比例、覆盖与象限分布。扫描合并重复帧继续使用原 `aligned` 标准，避免放宽对齐时丢失不同视角。已补阈值／稳定重置及真实 ORB 小偏移／错景回归代码，未运行。

最新局部交互：顶部返回图标保持进入页面时的原始方向，不参与四档旋转；千问／Seed Key 与网关令牌共用 `SecretKeyField`，默认密文，眼睛按钮只切换当前字段的显示状态，不改值或保存配置。相关 Compose 回归代码已更新，未运行。

2026-10-16 弹窗横竖屏第2步：拍照设置、模型设置、构图详情备用弹窗、人像样片、模型库及其筛选／详情／删除／流量确认共用 `CameraAdaptiveDialog`。四方向只作用于各弹窗的内容坐标，横向交换可用宽高并提供弹窗局部配置，保持 300ms 最短路径动画；主相机窗口继续锁定进入时的方向，不重建或旋转预览。嵌套弹窗继承原设备方向，内部图标角度归零，避免重复旋转。普通设置正文可滚动，关闭／确认操作保持可达；比例选择使用可整体转正的居中选项弹窗；模型库搜索、筛选和模型卡片共用滚动列表，窄高与键盘出现时仍能访问模型，筛选面板在同一旋转坐标中处理拖动。输入草稿、模式选择、筛选、滚动位置和下载任务不以方向作为重建键。按用户最新决定，AI 构图输入面板（含删除图标）保留当前固定竖屏交互，原生键盘不随弹窗转向；其他输入框也沿用宿主原生键盘方向。系统选图／文件／目录页由系统提供，保持原调用。只处理 UI，扫描图片方向和虚拟人像坐标留第3步。补充 `CameraAdaptiveDialogTest` 设备回归代码，未运行 Gradle、Android 测试或 GPU 验证。

2026-10-19 弹窗滚动兼容：Compose UI 1.10.3 的 `DefaultVelocityTracker.addPointerInputChangeWithFix` 使用窗口 `originalEventPosition`，旋转层内拖动与松手速度不在同一坐标系。`CameraRotatingViewport` 存续期间将全局实验开关 `VelocityTrackerAddPointsFix` 暂设为 false，使用局部位移累计速度；按存活视口计数，最后一个退出时恢复进入前的值，避免嵌套弹窗提前恢复。升级 Compose 时需核对上游实现再决定移除此兼容；不永久修改全局配置。设备回归增加普通滚动与懒列表四方向真实拖动／惯性延续／静置位置，以及开关恢复测试，未运行。

2026-10-21 单张构图对齐：普通相机冻结与 AR 冻结统一通过 `SceneSnapshot.captureBinding` 保留照片来源；普通单张以本次图片 ID 建立独立 `PhotoViewBinding`，冻结时记录来源配置，之后相机配置变化不改变生成／应用的校验依据。扫描仍优先使用扫描帧绑定。单张构图在首次使用及重新开始后，应用时均进入背景对齐，未确认前隐藏虚拟人像；自动识别和手动确认共用现有后续行为。每次应用增加对齐 epoch 并重建参考，不能复用上一次稳定计时。绑定不进入模型网关 JSON。生成浮层移除圆形进度，仅底部时间线显示运行进度；说明字号减小 2sp、最大宽度 300dp、内边距横向 12dp／纵向 8dp，保留四方向转正与浮层取消入口。新增／更新单张重启对齐、真实 NV21 自动确认及紧凑浮层测试代码，未运行 Gradle／Android 测试。

2026-10-19 照片方向第3步：四档方向监听同时上报 `PhotoOrientationChanged`，只更新采集方向与背景确认，不改变主取景布局、窗口方向、相机 epoch、扫描会话或生成令牌。`PhotoOrientationHistory` 保留有界历史，REALTIME 分析帧按曝光时间取方向，未知时钟按接收时间取；CameraX／AR 保持原预览几何，先按原视口裁切，再按该帧握持方向转正。`ScanCameraConfig.orientation → SceneSnapshot.orientation` 随冻结来源保存，混合方向不互当重复帧；模型大图与缩略图像素已转正，导演和 Filament 均以选中照片的宽高／坐标生成。缓存加入照片方向及尺寸。按用户选择，结果保持拍摄时方向：`CameraPhotoViewport` 将缩略图、冻结图、整张构图和透明参考层统一映回拍摄时的固定取景坐标，不跟随之后的握持方向；内层拖动仍使用照片坐标。AR 自动落点先从构图裁切映回整张照片，再旋转到固定预览坐标，真实空间人像继续使用 AR 重力投影。普通快门在请求开始时锁定握持方向，等待变焦后仅设置 ImageCapture.targetRotation，再由 CameraX 直接向 MediaStore 保存 JPEG、EXIF 并发布；不手动修改方向或宽高索引。拍照成功、失败或取消后恢复 ImageCapture 原目标方向，后续冻结继续沿用已验证的固定视口及帧方向转换；Preview／ImageAnalysis 不修改，不重新绑定相机。AR 快门锁定 JPEG 方向及交换后的裁切比例。系统导入图片继续按自身 EXIF 转正。新增方向映射／延迟帧／混合扫描、像素与模型尺寸／预览合成、MVI 生成期间转向、固定结果与拖动映射及真实 GLB 横向输出回归代码，未执行 Gradle、Android 测试或 GPU 验证。

2026-10-19 构图区域原位转正：按用户确认，三张构图卡片各自的内容和构图详情跟随当前设备四档方向，复用 `CameraRotatingViewport` 的 300ms 最短路径动画；卡片外框、排列、详情所在区域及底部操作位置保持固定，不旋转整组布局。方向变化不修改选中方案、修改要求或重建滚动状态。`preservePhotoDirection` 让内层照片抵消父层实时动画角度，照片与人像仍保持第3步拍摄方向，避免双重旋转；未生成缩略图时沿用所选照片比例。速度兼容开关按所有存活旋转视口计数，覆盖弹窗与内嵌面板，最后一个退出时恢复原值。补充 `CompositionOrientationUiTest` 与照片像素补偿回归代码，未运行 Gradle／Android 测试。

2026-10-19 应用后的界面切换（按用户最新确认）：移除旧 `vlm_reference_retake` 提示；合法应用请求立即收起构图选择／详情，应用成功后显示本次参考的背景对齐文案与人工确认按钮。`showBackgroundAlignmentControls` 直接由实时参考及详情展开状态派生：展开构图选择／详情时隐藏对齐模块，收起后恢复显示，两组界面互斥，不另存永久隐藏标记。应用失败回滚编辑可见性；后台匹配、确认状态及人像／AR 渲染规则保持原机制，不因界面切换自动确认背景。补充 `VlmSessionTest` 与 `ScanBackgroundTest` 回归代码，未运行。

2026-10-19 对齐提示位置与状态：`BackgroundAlignmentOverlay` 从底部操作区移入相机预览，位于当前阅读方向的顶部，通过 `CameraRotatingViewport` 四档动画转正，不修改预览几何或图片坐标。寻找态沿用左右／上下误差指导及“我已对齐背景”入口；按最新交互，自动稳定匹配或手动确认后统一显示“构图已就绪，开始拍照”，移除实时预览中的长指导、应用参考通知和“屏幕参考”短标签，详细指导保留在构图详情。人像继续由原 `referencePresentation` 门控，首次置信匹配不提前放行。

确认行为统一：`mergeBackgroundAlignment` 让自动／手动确认共用原人工确认的保持范围，朝向与俯仰偏差均小于 12°（或暂不可用）时不因短暂匹配波动退回寻找态，保留确认来源；明显转动时采用当前匹配结果，原方向／配置／跟踪失效仍可清除确认。`showReferenceGuidanceOverlay` 统一控制简洁提示与构图编辑互斥，无背景绑定的旧单图参考也显示拍摄提示。提示沿用 `bodyMedium`，四方向字号不变，其他操作通知及表情预览保持原位置。补充两种确认保持／解除阈值、仅一条顶部文案、四方向字号、人像显示及应用后无旧通知的回归代码，未运行 Gradle／Android 测试。

2026-10-16 相机控件四方向转正：相机页使用 `SCREEN_ORIENTATION_LOCKED` 保持进入时的当前显示方向，退出恢复原方向策略；不指定横屏／竖屏、不启用 FULL_SENSOR，也不旋转整页。原布局、尺寸和按钮命中区域保持不变；返回、模型设置（含 AR 标记）、拍照设置、AI 构图、构图展开、输入历史删除图案，以及顶部取景比例标签、全部快捷变焦倍率标签在自身中心转正，其余文字保持原方向。独立 OrientationEventListener 提供握持方向，归为 0°／90°／180°／270° 四档；45° 边界增加 10° 回差防止跳向，档位之间采用最短路径的 300ms 缓入缓出动画，不逐度跟随，平放保留最近方向；前台监听、后台停止，不受系统自动旋转开关影响。CameraX、AR、采集和人像渲染链路保持原实现。本次已实现图标旋转并补充自动化测试代码，仅进行源码检查，未执行 Gradle／Android 测试；弹窗适配见第2步，照片／人像方向一致性留第3步。



2026-10-11 离线环境描述：离线摄影组合仍使用六选一受约束决策；合法编号产生后，对同一选中单图追加一次自由文本视觉推理，请求模型依据当前照片给出摄影分析，移除条数、字数及每条一行要求，内容长度、段落和重点由模型决定；App 不按字符数拒收或截断有效文案，长文在详情中滚动查看；界面只展示模型描述／选景观察／模型理由，去掉 App 指导与缺失提示兜底，缺少模型文字时隐藏环境栏，展示仅去掉空白行而不改写或重新排列原文；独立 JNI describeScene 上限为192 tokens；普通推理保留96-token上限，取消仍使用算子边界。首 token 前不启动停滞计时。描述绑定 imageId，作为 App 内 transient 数据附加到当前背景方案，不改网关序列化或云端预算；修改任务不影响未选中方案。单项缓存按照片 ID、路径、文件大小／时间、模型实例和描述协议版本匹配，重生成可复用，卸载／换模型清空。普通非摄影组合离线协议、云端调用数量及网关协议保持原样；云端摄影 reason 提示词同步要求细化可见环境与摄影依据。环境栏目位于方案描述最后，构图选择卡片及选中边框使用直角。描述异常或无效时保留合法构图，不用模板伪造环境；取消仍取消整个请求。预览缓存复用图像时同步最新描述。日志包括单图描述开始、耗时／有效性、Debug 原始输出和缓存命中。仅新增测试代码，未执行 Gradle／Android 测试。

2026-10-11 取景与快捷倍率：预览按可用屏幕宽度和所选比例计算高度，底部控件不缩窄取景；构图卡片按缩略图比例包裹，内边距 2dp、编号浮在照片底部。移除倍率栏末尾重复数值。快捷档依据当前逻辑相机成员镜头的焦距／传感器尺寸推算，过滤至实际 ZoomState 范围；缺少信息时提供可用的 1×／2×及小于 1×的最小倍率。此为公开信息推算，不保证等于厂商相机档位或触发对应物理镜头；双指仍支持完整范围，AR 保持 1×。

2026-10-09 构图选择面板：生成后默认在底部展开三卡片，原详情整体位于卡片上方并可滚动；面板不越过顶部工具栏。构图选择按钮带展开／收起图标，同时控制详情与三卡片；应用构图仅在详情内，详情与应用均绑定当前选中方案。扫描无结果时的手动背景选择现改用内联横向卡片，见下述手动选景说明。

2026-10-09 沉浸式全屏：拍摄横竖屏隐藏 Tab 与系统栏，支持边缘滑动临时显示。App `NavigationSystemBars` 统一管理窗口状态，并在前台恢复／弹窗关闭重新应用；普通页面 `FramerAppTheme` 固定白底黑字并保留强调色。`VlmCameraScreen.onExit` 连接 App 保存的返回目标，左上退出在保存照片时禁用；系统返回先关闭弹窗、取消流程／返回实时页，再退出拍摄。预览保留比例与变焦，安全边距只用于刘海与手势区域，不预留隐藏的导航／状态栏高度。本次只写测试并检查源码，不运行 Gradle／Android 测试或 APK；自测见 `CAMERA_UI_FRAMING_ACCEPTANCE.md`。此条替代下方历史记录中的“横竖屏统一保留底部导航”。


2026-10-09 UI 与取景：CameraX 普通模式默认，AR 由拍照设置开关主动启用，模式切换等待旧相机 CLOSED 并用配置 epoch 拦截旧回调。新增本地 PhotoPreferences（比例、角色、外观、要求、脸部示范）与 CameraMode；3:4／9:16 的固定视口共用于普通采集，AR 原始 JPEG 转正后按视口裁切。倍率读 ZoomState、快捷档位在实际范围内、双指连续变焦按显示帧合并，不因变焦重绑。主页面为黑底白控件，顶部两个设置入口、底部扫描／倍率／圆形快门与常显三构图；动作和修改进入详情弹窗。扫描与快速取消保持；本轮未构建、测试或生成 APK，自测见 `CAMERA_UI_FRAMING_ACCEPTANCE.md`。


`VlmCameraScreen` 来自 `feature-camera-vlm`，按 MVI 组织，ViewModel 管理冻结会话、方案版本和异步请求令牌：

- CameraX 预览和拍照使用同一 ViewPort。冻结独立图像后释放 ImageProxy，模型输入 JPEG 长边最多 768 像素。
- 流程为实时预览、冻结、生成三方案、选中与拖动/缩放/文字修改、应用参考后恢复实时相机。
- `DirectorAgent` 最多两次模型决策，使用能力查询、方案校验和实际投影渲染工具；不执行任意代码。
- 模型设置首次默认强制离线，已有保存的模式继续保留；强制离线仅展示离线模型管理，指定云端仅展示供应商对应配置，自动切换同时展示云端和离线配置，切换模式保留隐藏配置。QWEN 新配置默认官方直连，预填北京百炼 Base URL 与 `qwen3-vl-plus`，用户填写 API Key；单图导演与最多八张扫描选景复用官方 Chat Completions 图像通道、有限修正预算和取消。Key 独立用 Keystore AES-GCM 加密，不进入配置 JSON；旧设置仅在已保存网关令牌时保留网关接入。检查已保存 QWEN 配置发送少量文本请求，不上传照片，不代表真实构图验收。Seed 新增独立 OkHttp 官方直连，模型由共享 JSON 配置；官方 Key 与检查入口独立，复用原 Agent/预算/取消，保留 Seed 网关兼容；GPT/Gemini 仍使用原网关。离线模型主要通过 OkHttp + 协程直接下载官方 MNN 目录，使用 dataSync 前台服务、固定提交和断点续传；保留目录/ZIP 导入。下载完成后仅在相机前台空闲时加载，原生加载成功才激活。
- 云端使用当前所选品牌；自动模式不跨品牌，仅允许所选品牌失败后退本地。生图目前仅接入 Seedream，非豆包品牌明确提示不支持，自动模式不因此转离线。内容拒绝、取消和参数错误不触发自动切换。
- 摄影视觉模型市场从相机离线设置进入，独立 MVVM／Compose，复用 MNN Chat 官方 Vision 目录及双源接口。中文系统首次默认魔搭，其他语言默认 HF，下载源独立保存。开放 Qwen3-VL-2B／4B／8B Instruct（2B、4B 用户已验证；8B 未通过，保留测试入口）及 Gemma-4-E2B／E4B-it-MNN 测试候选；保持现有视觉校验及 12 GiB 上限。多任务串行传输、独立断点、多模型保留，校验后前台空闲自动应用，指定云端不加载。失败恢复此前句柄，兼容旧安装与任务；来源与验收见 `docs/CAMERA_MODEL_MARKET.md`。
- 首个离线目标为 Qwen3-VL-2B-Instruct 的 MNN 3.6.1 模型包，ARM64 CPU JNI 串行推理；权重不随 APK 打包。
- 离线加载及首 token 不限时；开始输出后连续 39 秒无新 token 才终止等待，正常持续输出不受总时长限制。本地构图使用 50 字内短场景观察，子任务最多调用一次并限制输出 96 token；兼容旧意图/数组协议，校验失败直接结束，修改保留未选中方案；云端构图保留最多两轮。离线多图选景另执行一次真实调用，离线推理不读取或消耗云端扫描预算，不设用户重复操作的会话累计次数上限。完整 JSON 的展示包装兼容不放宽业务校验。
- Filament 1.75.1 使用四个 Rocketbox 骨骼 GLB，支持 12 个受限姿势与 6 个表情，另有身体/手臂/头部/站姿的细分动作。表情使用实际 GLB 通道名称；缺失必需骨骼/表情或渲染为空时明确失败。卡片按需离屏渲染，应用后只叠加屏幕参考，不做真实空间锚定。
- P2 的版本化动作目录支持基础受控姿势、完整骨骼快照与命名 GLB 动画静帧采样；筛选角色、景别、资源/骨架和条件，外物接触动作及五个待素材验收的专业样片不可选。每次恢复全部绑定节点与表情，再执行动作、布局与最终表情，最后更新蒙皮。
- GLB 能力筛选和 JNI 加载前校验图元顶点缓冲槽保守上界（Filament 1.75.1 上限 8，预留法线与 dummy 槽，morph 独立）。避免多余颜色层造成原生 SIGABRT；Rain 样片版本 2 已禁用烘焙后冗余颜色导出，上界 6，手机复验待用户执行。
- `ResolvedPhotoSpec` 统一最终布局、动作/角色资源版本、采样、表情与指导，不进入 v1 网关 JSON。`CompositionGuidance` 从最终方案派生本地化指导；模型原文单独作为分析依据。`AvatarPreview.appliedAvatar/appliedSpec → PlanPreview.appliedPlan → CompositionResult/referencePlan` 回传实际渲染布局，更新预览与文字后才能应用。校准标记只在 App 内保存，半身/特写校准保留裁切比例；拖动/缩放时清除旧预览，并以请求令牌和 revision 拦截迟到结果。
- 手动动作/表情/强度仅修改选中方案、递增 revision，不调用大模型；失败保留原方案。切换、拖动和缩放保留选择，重新生成/导演修改按新结果解析。同角色脸部示范独立按需渲染；缓存键包含动作/资产版本、采样、用途与校准标记，预览缓存最多六键、脸部最多两张。
- 2026-10-08：人像样片验证使用 Rain 动画电影风卡通角色，平静／闭嘴微笑／露齿笑由完整面部求值烘焙为 morph。写实样片被用户拒绝，保留历史资源并撤出目录；新 `draft.rain_natural_stand` 为 DRAFT，手动验收，不进入自动推荐。脸部节点按资源配置，卡通关闭旧颜色滤镜；资源／配置／节点／滤镜参与缓存。保持独立 MVI 令牌与关闭／后台取消。ARCore 与 Camera2 尚未接入。资源、源工程与验收见 `docs/CAMERA_AR_PHOTO_SAMPLE.md`。
- 卡通外观目录版本 3：样片窗口和成年女性卡通动作可选白种人／亚洲人／黑人，使用 Rain 基础与两个派生 GLB；脸部造型和材质均有设计参数，保持站姿及三表情。用户选择保存在本地偏好与 App-only `AvatarPlacement.ethnicity`／`ResolvedPhotoAction.ethnicity`，不进入网关，不由模型或照片推断。切换当前方案以 revision／令牌事务刷新，动作资源及外观参与缓存；旧动作切换清除无对应资源的外观标记。按用户要求本次未编译、测试或渲染验证，三个外观仍待用户验收。
- 保留主导航蓝色“拍摄”和横屏侧栏；冻结、生成、修改和资源管理期间禁止拍摄，不排队补拍。真实照片通过 MediaStore 保存，不合入虚拟人偶。
- 横竖屏变化保留 ViewModel 会话；进程回收后不自动恢复收费任务，用户可从本地历史主动恢复成功的云端构图；未保存流程需重新扫描。
- App 不再启动预热 ONNX；旧相机模块和专题文档保留作为历史实现。

卡通目录／资源缓存版本 4 修复两个派生 GLB 的动画名称 `.001` 后缀与目录不一致的问题，制作时先清除旧 Action 再创建动作；运行时仍精确校验，错误日志列出预期与实际动画名。仅修复资源 JSON，不改变 BIN 数据；回归代码补充三个外观的动画名称断言，未编译、测试或渲染验证。

卡通外观目录 v5：sRGB 显示转换修复后用户要求白种人更浅，并确认亚洲人需独立五官造型；新增 `white.glb`、更新 `asian.glb`，两者资源版本 5，黑人保持原 GLB 与资源版本 4。原始 `avatar.glb` 仅作为制作输入。脚本默认只制作 WHITE／ASIAN，表情、动作和缓存仍按实际版本解析；用户对黑人外观给出正向反馈，其他两者待自测，本次不运行编译、测试或渲染验证。

AR 摄影示范第二批：四个 Rain 全骨骼快照与自然站立组成五个可手动自测的 DRAFT；共用三外观和三表情，`rain-poses-v1/` 每个快照覆盖 69 关节，不改 GLB。样片增加动作选择，外观／表情变化保留动作；构图手动选择、外观切换和拖动／缩放保留最终结果。完整动作指导从制作步骤与最终表情生成，避免额外布局 yaw 与实际身体朝向混淆。制作数据并补充 JVM／设备回归代码，未编译、测试、渲染或打包；专业素材验收、VLM 摄影组合、AR 空间接入仍待后续。

详细接入、模型准备、网关启动、协议和验收说明见 `docs/FEATURE_CAMERA_VLM.md`。2026-10-02 的 P1 与 P2 软件接入完成，累计 73 项 JVM 测试通过，主 App/设备测试代码编译和模块 Lint 通过。P2 未生成 APK，五个专业样片仍待素材验收，接入规范见 `docs/CAMERA_POSE_ASSET_INTEGRATION.md`；真实设备、视觉质量与供应商流程由用户验收。P3＋P4 软件接入完成，扫描、多图与背景确认见 `docs/CAMERA_SCAN_P3_P4_ACCEPTANCE.md`；P5/P6 尚未实施，进度见 `docs/CAMERA_SCAN_AGENT_IMPLEMENTATION_PLAN.md`。后续拍照功能优先在 `feature-camera-vlm` 内实现。

### AR 摄影参考后续接入（2026-10-08）

用户暂停扶腰／步行的动作修正，VLM 新路径仅从自然站立、侧身、回眸三个 DRAFT 参考选择，不登记 REVIEWED。云端查询实际模型的 directorV2 能力后使用 `/v2/director/step`，旧网关回退 v1 并提示；离线四帧选景后，以真实视觉 logits 从六个完整摄影组合选择一项，其他两项为 App 备选，默认仅展示一项。`ResolvedPhotoSpec` 统一背景保护、来源、动作、表情、布局及版本。

新增 `ar/` 包、ARCore 1.54.0 和 Optional Manifest。Camera2 SharedCamera 与 CameraX 互斥管理相机，两者共用递增配置序号。空间会话贯穿扫描／模型分析／应用参考，实时 Filament 共用相机背景、AR 投影、人像、软接触阴影和可用的软件深度几何。后台／旋转／跟踪失效清除落点与背景确认；JPEG 保存真实图像，拍照后重新对齐。拍照 UI 新状态／意图保持 MVI，固定文案置于 strings.xml，LIVE 可放大同参数表情。

本条更新替代上文历史记录中的“ARCore 与 Camera2 尚未接入”和“VLM 摄影组合、AR 空间接入仍待后续”。当前状态为软件实现已接入、尚未编译验证；专业与 AR 真机质量未验收。配置和验收见 `docs/CAMERA_AR_PHOTO_SAMPLE.md`，网关升级见其 README。本轮只补测试代码，不执行 Gradle、pytest、GPU 渲染或 APK 任务。

AR 预览诊断补充：针对用户报告黑屏和延迟，新增启动节点、Surface／页面窗口、实际相机出帧与曝光、Debug 输入亮度探针，以及每五秒汇总的 AR update／渲染／深度／GPU 等待／分析／队列耗时。扫描与 ORB 匹配限频记录耗时。预览在内容区顶部对齐，AR 状态移到操作区之后；处理耗时计入帧调度周期，避免再追加完整一帧等待。JPEG 不再加入 AR 额外实时输出与预览会话，只在暂停 AR 拍照时创建 JPEG 会话。2026-10-09 用户日志显示相机约 30 帧／秒、预览约 10 帧／秒：已修正模型加载导致 LIVE 被误降帧的问题，冻结分析仍降低后台绘制频率。首帧记录实际纹理 ID，Debug 每个空间会话仅一次异步抽查 Surface 亮度，不保存像素。暂保留跨 GL 上下文的 GPU 等待（本次日志稳定约 3–5 ms）。黑屏及流畅性尚未验收，本次未运行构建、测试或 GPU 验证；复现步骤和日志标签见 `CAMERA_AR_PHOTO_SAMPLE.md`。

AR 预览第二轮用户回归：新帧率约 29.5 帧／秒，相机画面已可见。针对整页黑底与镜像，页面显式绘制主题背景，预览 Compose／原生 Canvas 限定绘制边界，Surface 使用 RGBA8888、Filament 使用 OPAQUE；相机材质关闭默认 UV 翻转，避免与 ARCore 坐标转换叠加，UV 只在几何变化时更新。定期诊断仅 Debug 启动／关键请求变化后的三个五秒窗口，随后停止；错误和状态变化保留。系统 `identical ... lines` 需查看被合并的原始行及 Tag，尚不能确定来源。这些修改由用户构建和真机复验，本次未运行构建或测试。

扫描性能接入调整：AR／CameraX 的 YUV 采样改为批量读取，共用 `Yuv420Copy`，相机图像仍在独立缓冲复制后立即关闭；NV21 优先直接转 RGBA，避免 JPEG 中间转换。`PhotoSessionController` 用生命周期内的低优先级串行分析线程，释放时关闭线程；有界队列和四帧／秒限制保持。AR 汇总新增获取／复制耗时；用户本轮提供的是 `sampling=false` 日志，扫描改善尚待 `sampling=true` 真机记录确认。三张构图默认全部并排显示，不再折叠；离线首张推荐与两张 App 备选的来源标记保留。静态生成预览与应用方案后的空间人像区别在 UI 和文档中明确说明，新增应用参考日志。本轮补充 YUV／颜色方向／三方案 UI 测试代码，未执行构建或测试。

首页模型预加载与流程重启：`MainActivity` 通过已有 Hilt 单例 `MnnProvider` 在后台读取已保存模式，强制离线预加载已激活安装，指定云端跳过离线预加载且拍照页不自动加载，自动切换保留预加载；进程内仅一次，不下载；拍照页共享原生加载状态，Tab／Activity 重建复用句柄，主动卸载／失败不因切换自动重试。下载的新候选仍走原激活事务。初始等待状态保留“开始扫描”和“单张构图”入口；扫描中、冻结中及第二至第四步统一提供“重新开始”，始终位于操作栏最左侧，不再直接重开扫描。第二至第四步关闭单张构图入口及对应事件门控。RestartFlow 取消本轮任务、更新令牌，清空扫描帧／全部构图批次与人物调整／背景对齐／拍摄进度，倍率回到可用的 1x，回到 LIVE 等待用户点击开始扫描，不自动创建扫描或冻结请求；模型配置与拍摄偏好保留。操作栏不再提供各阶段的取消按钮及点击事件，“重新开始”始终位于最左，整排居中；加载面板与系统返回的中断逻辑保留。生成期间仍可取消后继续生成同一背景，冻结重试更换令牌；旧窗口和异步回传被隔离，新扫描重新建立预算，同背景继续生成不重置云端预算。原生视觉/语言计算通过官方算子回调取消，安全传播空输出与取消状态、清理部分视觉/KV 后通过串行屏障交接，不强制销毁模型；加载及输入预处理仍需到安全节点，取消延迟待真机测量；等待文案在 strings.xml，模型管理／照片保存／样片期间继续禁止抢占。回归测试代码已补充，未执行构建或测试；操作与日志见 `CAMERA_AR_PHOTO_SAMPLE.md`。

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

相机本地历史依赖链路：`feature-camera-vlm → core-data → core-database`。专用 DTO 和图片文件管理留在相机模块，Room 实体、DAO、事务、Schema 与初始化归 `core-database`。

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

拍照仍采用 MVI：`CameraX ImageAnalysis → 独立 YUV 缓冲 → 有界串行 ScanCollector/PhotoSessionController → 不可变 ScanSnapshot → ScanViewAgent → DirectorAgent → ResolvedPhotoSpec/预览 → 背景确认 → 人像参考 → 手动拍照`。扫描期关闭构图/渲染/拍照/倍率调整；后台中断保留帧。握持方向切换不重绑或创建新配置；真正的模式／取景比例／窗口配置变化仍建立新配置。只有 REALTIME 时间源才与光轴姿态关联。扫描来源只在 App 内保存，统一校验最终方案、预览缓存和应用参考。

每个扫描的云端请求共同限制 8 次调用、24 MiB 上传（最多两次选景、三个背景各最多两轮构图），云端选景累计两次；重试、重生成、修改及切换供应商共享预算。离线选景和构图不读取或消耗这些额度，即使云端预算耗尽，离线仍可继续。云端最多 8 张/3 MiB；强制离线使用 `OfflineScanViewAgent → MnnProvider.compareViews → MnnNative.inferImages`，最多 4 张独立视觉输入，一次选景后自动冻结第一名并生成三构图，其他背景按需生成，不查询网关、零上传。4 张是应用限制；JPEG 副本长边最多 448，保留到原生任务退出后删除，编号严格绑定本次提交帧。旧网关/未声明多图保留主动展开的手动选帧与 v1 构图。背景 ORB/RANSAC 每秒最多 5 次，不上传；自动和人工确认分别记录，未验收真人姿势。P5/P6 尚未实施。

App 与模块保持 ARM64、NDK 28.2.13676358；OpenCV 4.12.0 官方 AAR 转换仅移除重复 C++ 库，其他 AAR 原样转交，统一使用工程 NDK 运行库。使用 `:app:mergeDebugNativeLibs` 和 `python3 feature-camera-vlm/scripts/verify_native_alignment.py` 验证 16 KiB ELF 对齐与运行库来源。网关模拟测试在 `services/vlm-gateway` 中执行 `python -m pytest tests -q`；不需要 APK。部署与真机边界见 `docs/CAMERA_SCAN_P3_P4_ACCEPTANCE.md`。

2026-10-22 手动选景内联化：自动选景失败或取消后，“手动选择背景”与“构图选择”共用带上下箭头的展开／收起按钮，在底部原卡片区域展示横向视角列表，不再打开备用弹窗。`rankedBackgroundFrames` 在没有推荐候选时使用全部缓存扫描帧；展开不默认选中，点击卡片仅派发 `PreviewScanView` 显示原图，点击“生成构图”并确认才派发 Generate。生成后继续沿用同一混排列表与缓存，按钮改为“构图选择”；收起保留列表状态，返回键优先收起列表。Generate 统一检查既有三个已生成背景上限，保留全部旧构图且允许为已有背景继续生成。补充列表、返回、手动预览与生成上限回归代码，未运行 Gradle／Android 测试。

2026-10-20 多背景构图：多图比较返回最多三个有序背景，首次仅自动生成第一名的三方案；构图面板仅保留一行横向列表：已生成背景展开为各轮构图卡片，未生成背景按名次以原图缩略卡片占位。点击未生成背景先派发 `PreviewScanView`：缓存当前方案、切到该原图的 FROZEN 预览、清除当前方案／人像引用并选中该背景，详情隐藏但横向列表保留；点击卡片不弹窗、不调用模型。用户点击“生成构图”（原“生成方案”）或选择已有构图后点击“继续生成”，才弹出适配横竖屏的“继续生成构图”确认框，提示当前背景名次与保留旧构图。确认统一派发 Generate，按当前原图生成或追加一批三个方案；取消或关闭只放弃生成，保留选中的预览、不调用模型；仍可随时从列表选回已生成构图，恢复合成预览。构图列表与详情正文分开控制：`compositionDetailsExpanded` 默认 false，首次／继续生成及重新打开选择器时收起，点击“构图详情”独立展开／收起，卡片和“应用构图”始终可用。操作区改为正文上方的 zIndex 悬浮层，不占正文高度；左“构图详情”＋右“应用构图”紧凑矩形按钮按内容宽度居中排列，不铺满整行，高 28dp、间隔 1dp、距区域底部 4dp，文字用 labelSmall、图标 14dp，黄色沿用 AI 构图对话入口并保留 78% 不透明度，深色前景维持清晰；仅此操作组取消额外的最小布局尺寸。正文占满原区域，滚动末尾按按钮实测高度留白，末尾控件可滚出遮挡；文字右侧分别为线条眼睛／勾选圆圈图标。详情正文继续独立转正与原生滚动，操作按钮沿用带文字控件的固定方向，不挤压主取景或改变快门位置。生成卡片统一标为“构图 x-x”，待生成卡片仅显示单行“备选 x”，不显示“点击生成”；选择备选原图时生成按钮文字使用与选中 1x 相同的黄色，切回已有构图时恢复白色；两类标签共用 `CompositionCardCaption`，同字号、左对齐、同底边距与内边距。生成后在原名次位置展开；例如先生成背景 3，其三方案仍位于未生成的背景 2 之后。`VlmUiState.compositionCache` 按 `SceneSnapshot.id` 保存各组方案、预览、动作／表情及选中项，切换前保存当前人物调整；全部构图与未生成背景共用一个横向 `LazyRow`，最多三个背景，不另设背景列表。按钮“继续生成”每次成功追加一批三方案，同背景从 1-1／1-2／1-3 顺延为 1-4／1-5／1-6；即使从旧方案发起，也追加在该背景所有已有批次之后。失败、取消或迟到结果不追加、不占编号，旧方案与人物调整保留。`BackgroundComposition.allBatches` 按生成顺序保留各批三方案，当前 `result` 仍只有选中批次的三方案；当前批次修改时仅更新该批。列表键与跨批选择使用背景 ID＋批次 ID＋方案 ID，不改模型内部 p1/p2/p3 协议；拍摄进度键也包含批次，避免同背景同编号同 revision 的不同批次复用拍摄完成状态。云端继续共用已有扫描预算，离线沿用现有规则。切回已生成背景直接恢复；调整尚未渲染完即切走，恢复时仅补缺失预览，不重复请求模型。生成失败、取消或预算耗尽保留其他背景；应用时恢复对应原图、拍摄方向和 `PhotoViewBinding`，不使用当前握持方向替换。重新扫描、退出本次扫描、重新冻结单图或导入新图清空本次多背景结果；手动备用入口也最多保留三个背景，不淘汰旧方案。扫描代表帧范围仍为云端最多八张、离线最多四张，不声称全部三十二张缓存均已交给模型。

多图降级必须说明具体原因，人工选帧保留主动展开的备用入口。离线视觉输入前缀测试用 `python3 feature-camera-vlm/scripts/verify_local_image_prompt.py` 在宿主 C++17 编译器运行，不加载权重、不打包 APK。本次补充按需生成、跨背景同名方案、人物调整、取消／失败、来源和横向滚动回归代码；未运行构建或测试。

离线空景任务明确人物稍后入镜，提示词仍要求选出一个最佳背景。JNI 在同一次视觉预填充的合法编号分数中取前三名并返回有序 CSV（例如 `3,1,2`），不足三张按实际数量；不增加视觉推理次数，不把分数当成摄影质量百分比。Kotlin 校验数量、范围和唯一性，兼容旧单数字、SELECT 及说明字段，旧单编号不会凭规则补造其余排名。云端同一次选景请求要求返回前三名，沿用 primary／alternatives 协议与来源校验。缺失说明保持未知，禁止把 0 或无效输出自动映射为首帧。后续单图构图保持原摄影协议，不把多图排名用于六选一摄影组合。选景等待及失败时以 `VlmUiState.displayScene` 显示保存的扫描预览并标注未选定；该显示字段不改变构图输入 `scene`、来源绑定或触发模型。

离线当前最多四帧：保留偏爱及不同视角的代表帧，按采集时间提交；仅离线改变呈现顺序，云端不变。摄影选景标准侧重人物空间和轮廓分离、背景主体与干扰、层次/引导线/视觉重心、可见曝光色彩和清晰度；偏爱在摄影条件相近时优先。日志版本为 `rubric=PORTRAIT_BACKGROUND_V2`，实际比较数量在界面显示。一次选景仍只有一次推理，实际质量、耗时与内存由真机验收。

2026-10-09 推理取消补丁：`feature-camera-vlm/patches/mnn-3.6.1-cancellation.patch` 由 CMake/Git 应用到构建目录副本，不修改 `third_party/MNN`，不匹配则明确失败。加载前启用视觉/语言图的回调模式，JNI 逐轮绑定取消标记，使用官方 `CALL_BACK_STOP`；新扫描在旧原生退出及清理后才采样。新增模拟运行时设备回归和可选微型 CPU 图原生探针，均未执行，用户复验步骤见 `CAMERA_AR_PHOTO_SAMPLE.md`。
