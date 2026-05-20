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
| 测试 | JUnit、Compose UI Test、Android Instrumented Test、Hilt Test |

当前应用主体验是一个三栏底部导航 App：

- 首页：推荐流和系统相册。
- 拍照：当前为占位页面。
- 我的：个人主页、内容 Tab 和设置页。

同时项目保留了模板中的 MyModel 数据链路，用于演示 Room + Repository + ViewModel 的读写流程。

## 2. 模块结构

### app

主应用模块，负责应用入口、主题装配和主导航组装。

- `MainActivity`：Activity 入口，启用 edge-to-edge，注入 `MyApplicationTheme`，渲染 `MainNavigation`。
- `Navigation.kt`：当前主 UI 入口，维护底部导航状态和“我的”模块内部页面状态。
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
| `feature-home` | 首页模块，包含推荐流和相册页面。 |
| `feature-camera` | 拍照模块，目前是占位 UI。 |
| `feature-mymodel` | 我的模块，包含个人主页、设置页和模板 MyModel 数据功能。 |

### *-navigation 模块

导航模块用于隔离 feature 的路由声明，避免其他模块直接依赖 feature 实现。

| 模块 | 当前状态 |
| --- | --- |
| `feature-mymodel-navigation` | 定义 Navigation3 `NavKey`，保留模板式路由能力。 |
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

`MainNavigation` 使用 `Scaffold` 和 `NavigationBar` 组织底部导航，当前包含 3 个固定 Tab：

| Tab | Composable |
| --- | --- |
| 首页 | `HomeScreen()` |
| 拍照 | `CameraScreen()` |
| 我的 | `MyModelMainScreen()` |

底部导航只在“我的”模块主页面显示。当用户进入“我的 -> 设置”页面时，底部导航隐藏；物理返回键会从设置页返回我的主页。

### 首页模块

`HomeScreen` 使用顶部 `SecondaryScrollableTabRow` 和 `HorizontalPager` 联动，当前 Tab 顺序为：

1. 推荐
2. 相册

交互方式：

- 点击顶部 Tab 切换页面。
- 左右滑动 `HorizontalPager` 切换页面。
- Tab 选中状态跟随 Pager 当前页变化。

### 推荐页面

`RecommendScreen` 使用本地假数据构建类小红书风格推荐流：

- 数据模型：`RecommendItem`
- 布局：`LazyVerticalGrid` 固定 2 列。
- 图片：Coil `AsyncImage` 加载网络图片。
- 视觉效果：不同 `aspectRatio` 模拟瀑布流高低错落。
- 点赞数：通过 `formatLikes` 格式化为 `k` 或 `w`。

### 相册页面

`AlbumScreen` 和 `AlbumViewModel` 负责读取系统相册图片：

- Android 13 及以上请求 `READ_MEDIA_IMAGES`。
- Android 12 及以下请求 `READ_EXTERNAL_STORAGE`。
- 通过 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 查询图片。
- 成功后以 3 列 `LazyVerticalGrid` 展示系统图片 URI。
- UI 状态包括 `Loading`、`Success`、`PermissionDenied`、`Error`。

注意：相册读取依赖运行时权限，真机或模拟器环境会影响展示结果。

### 拍照页面

`CameraScreen` 当前为占位页面，仅展示：

- 标题：`拍照`
- 模块标识：`当前模块：Camera`

后续接入 CameraX、系统相机或自定义拍摄流程时，应优先在 `feature-camera` 内实现。

### 我的模块

`MyModelMainScreen` 是底部导航中的“我的”主页：

- 顶部操作栏：扫一扫、消息、设置。
- 个人信息区：头像、用户名、简介。
- 内容 Tab：作品、点赞、收藏、评论。
- Tab 使用 `PrimaryTabRow` 与 `HorizontalPager` 联动。
- 设置按钮通过 `MainNavigation` 切换到 `SettingsScreen`。

`SettingsScreen` 是“我的”模块内部设置页，不通过底部导航直接暴露。

### MyModel 模板数据功能

项目仍保留原模板的 MyModel 数据功能：

- `MyModelScreen`：包含输入、列表展示等模板 UI。
- `MyModelViewModel`：连接 UI 与 Repository。
- `MyModelRepository`：对上层暴露 `Flow<List<String>>` 和 `add(name)`。
- `core-database`：Room 实体 `MyModel`、DAO 和 `AppDatabase`。

该链路可作为后续真实数据功能的参考，但当前底部导航“我的”主页使用的是 `MyModelMainScreen`。

## 4. 数据与依赖流

主要数据依赖链路：

```text
feature-mymodel
  -> core-data
  -> core-database
```

职责边界：

- UI 层只依赖 ViewModel 或明确的 UI 状态，不直接操作 Room DAO。
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
- 新增页面状态时，优先使用明确的 UI state 类型，避免在 Composable 中堆叠复杂业务判断。
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
- `feature-home`：首页 Compose 单元测试。
- `feature-camera`：拍照页 Compose 单元测试。
- `feature-mymodel`：我的主页、MyModel ViewModel、MyModel UI 相关测试。
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
- 拍照模块尚未接入真实相机能力。
- 推荐页使用网络图片 URL，网络环境会影响图片加载结果。
- 相册页依赖系统权限和设备媒体库，测试或演示时可能出现空相册、权限拒绝或加载失败。
- 旧文档可以保留用于理解演进历史；后续编程优先以本文档和源码为准。
