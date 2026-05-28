# Framer_Sense 项目架构分析

## 一、项目概述

Framer_Sense 是一个基于 **Android 官方架构模板 (architecture-templates multimodule)** 的多模块 Android 项目。采用现代化 Android 技术栈：

| 技术 | 版本 | 用途 |
|---|---|---|
| Kotlin | 2.3.10 | 开发语言 |
| Compose (BOM) | 2026.02.00 | 声明式 UI |
| Hilt | 2.59.2 | 依赖注入 |
| Room | 2.8.4 | 本地数据库 |
| Navigation3 | 1.0.1 | 导航框架 |
| KSP | 2.3.6 | 注解处理 |

- **compileSdk**: 36 / **minSdk**: 24（app 与 `feature-camera-pytorch`，ONNX Runtime 要求）/ **targetSdk**: 36

核心功能：一个简单的 "MyModel" 数据增删查应用 —— 用户可以输入名称保存，列表展示最近 10 条记录。

---

## 二、模块总览

项目共 **8 个模块**，按职责分为四层：

```
┌─────────────────────────────────────────────────────────┐
│                    测试层 (Test)                          │
│              test-app                                    │
├─────────────────────────────────────────────────────────┤
│                    应用层 (App)                           │
│              app                                         │
├─────────────────────────────────────────────────────────┤
│                   功能层 (Feature)                        │
│        feature-mymodel    feature-mymodel-navigation     │
├─────────────────────────────────────────────────────────┤
│                   核心层 (Core)                           │
│     core-data    core-database    core-ui    core-testing │
└─────────────────────────────────────────────────────────┘
```

---

## 三、模块依赖关系图

```
                    ┌─────────────┐
                    │  test-app   │
                    └──┬──┬──┬──┬─┘
                       │  │  │  │
         ┌─────────────┘  │  │  └──────────────┐
         │                │  │                  │
         ▼                ▼  ▼                  ▼
    ┌─────────┐   ┌──────────────┐   ┌──────────────────────┐
    │  app    │   │ core-testing │   │ feature-mymodel      │
    └┬──┬──┬──┘   └──────────────┘   └┬──────┬──────────────┘
     │  │  │                            │      │
     │  │  └────────────────────────────┘      │
     │  │                                      │
     │  ▼                                      ▼
     │  ┌──────────────────┐    ┌──────────────────────────┐
     │  │ feature-mymodel- │    │ core-data                │
     │  │ navigation       │    └┬─────────────────────────┘
     │  └──────────────────┘     │
     │                           ▼
     │                    ┌──────────────┐
     │                    │ core-database│
     │                    └──────────────┘
     │
     ▼
  ┌────────┐
  │core-ui │
  └────────┘
```

**简化依赖链**：

```
app ──▶ core-ui
app ──▶ feature-mymodel ──▶ core-data ──▶ core-database
                         ──▶ core-ui
                         ──▶ feature-mymodel-navigation
app ──▶ feature-mymodel-navigation

test-app ──▶ app, core-data, core-testing, feature-mymodel, feature-mymodel-navigation
```

**无项目内依赖的底层模块**：`core-database`、`core-ui`、`core-testing`、`feature-mymodel-navigation`

---

## 四、各模块详细分析

### 4.1 `core-database` 模块

**职责**：数据库层，封装 Room 数据库，提供数据实体定义和 DAO 接口。是数据持久化的最底层。

| 文件 | 作用 |
|---|---|
| `MyModel.kt` | Room `@Entity` 实体（字段 `name: String`，主键 `uid: Int` 自增）+ `@Dao` 接口（查询最新10条、插入） |
| `AppDatabase.kt` | Room `@Database` 抽象类，版本1，暴露 `myModelDao()` |
| `di/DatabaseModule.kt` | Hilt Module，提供 `AppDatabase` 和 `MyModelDao` 单例 |

**核心代码**：

```kotlin
// MyModel.kt - 数据库实体
@Entity
data class MyModel(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val name: String
)

// MyModelDao - 数据访问对象
@Dao
interface MyModelDao {
    @Query("SELECT * FROM MyModel ORDER BY uid DESC LIMIT 10")
    fun getMyModels(): Flow<List<MyModel>>

    @Insert
    suspend fun insertMyModel(item: MyModel)
}
```

**关键设计**：
- 不依赖任何项目内模块，是最底层的独立模块
- DAO 返回 `Flow` 实现响应式数据查询
- 通过 Hilt `DatabaseModule` 对外提供 DAO 实例

---

### 4.2 `core-data` 模块

**职责**：数据仓库层，封装数据访问逻辑，对外提供统一的 Repository 接口。实现数据库实体到业务模型的映射。

| 文件 | 作用 |
|---|---|
| `MyModelRepository.kt` | Repository 接口 + `DefaultMyModelRepository` 默认实现 |
| `di/DataModule.kt` | Hilt Module，通过 `@Binds` 绑定接口到实现；含 `FakeMyModelRepository` 和 `fakeMyModels` 常量 |
| `DefaultMyModelRepositoryTest.kt` | 单元测试，使用 Fake DAO 验证逻辑 |

**核心代码**：

```kotlin
// Repository 接口 - 关键隔离层
interface MyModelRepository {
    val myModels: Flow<List<String>>   // 返回业务模型 String，非数据库实体 MyModel
    suspend fun add(name: String)
}

// 默认实现 - 做实体到模型的映射
class DefaultMyModelRepository @Inject constructor(
    private val myModelDao: MyModelDao
) : MyModelRepository {
    override val myModels: Flow<List<String>> =
        myModelDao.getMyModels().map { items -> items.map { it.name } }

    override suspend fun add(name: String) =
        myModelDao.insertMyModel(MyModel(name = name))
}
```

**关键设计**：
- **接口隔离**：`MyModelRepository` 接口返回 `List<String>` 而非 `List<MyModel>`，上层完全不知道数据库实体存在
- **依赖倒置**：上层依赖接口，不依赖具体实现
- **可替换性**：测试时通过 `@TestInstallIn` 替换为 Fake 实现
- `FakeMyModelRepository` + `fakeMyModels`（`["One", "Two", "Three"]`）供测试模块复用

---

### 4.3 `core-ui` 模块

**职责**：UI 基础设施模块，提供统一的 Compose 主题、颜色和排版定义。

| 文件 | 作用 |
|---|---|
| `Color.kt` | 定义 6 种颜色（暗色 Purple80/Grey80/Pink80，亮色 Purple40/Grey40/Pink40） |
| `Theme.kt` | `MyApplicationTheme` Composable，支持 Dynamic Color (Android 12+) 和静态配色回退 |
| `Type.kt` | Material3 Typography 定义 |

**关键设计**：
- 不依赖任何项目内模块，纯 UI 基础层
- 主题定义与功能 UI 分离，可跨 feature 复用
- 支持 Material You 动态取色

---

### 4.4 `core-testing` 模块

**职责**：测试基础设施模块，提供共享的测试 Runner。

| 文件 | 作用 |
|---|---|
| `HiltTestRunner.kt` | 继承 `AndroidJUnitRunner`，重写 `newApplication()` 返回 `HiltTestApplication` |

**关键设计**：
- 不依赖任何项目内模块
- `HiltTestRunner` 被多个模块引用为 `testInstrumentationRunner`
- 所有仪器测试的基础设施

---

### 4.5 `feature-mymodel-navigation` 模块

**职责**：导航键定义模块，仅包含导航路由常量。将导航键与 feature 实现解耦。

| 文件 | 作用 |
|---|---|
| `NavigationKeys.kt` | 定义 `@Serializable data object Main : NavKey`，Navigation3 路由键 |

**核心代码**：

```kotlin
@Serializable
data object Main : NavKey
```

**关键设计**：
- 不依赖任何项目内模块，零业务依赖
- 使用 `kotlinx.serialization` 支持序列化（Navigation3 要求）
- **解耦关键**：`app` 模块只需依赖此模块即可获取导航键，无需依赖完整 `feature-mymodel`
- 新增 feature 时，只需添加对应的 navigation 模块即可

---

### 4.6 `feature-mymodel` 模块

**职责**：功能模块，包含底部导航中的“我的”主页和设置页。该模块按 MVVM 组织，Composable 只渲染状态并转发事件。

| 文件 | 作用 |
|---|---|
| `ui/MyModelMainScreen.kt` | 我的主页 UI，展示资料、操作栏和内容 Tab |
| `ui/MyModelMainViewModel.kt` | `@HiltViewModel`，管理主页资料、当前 Tab 和空状态文案 |
| `ui/SettingsScreen.kt` | 设置页 UI |
| `ui/SettingsViewModel.kt` | `@HiltViewModel`，管理设置项列表 |

**关键设计**：
- `MyModelMainUiState` 暴露用户名、简介、当前内容 Tab 和各 Tab 空状态文案。
- `SettingsUiState` 暴露设置项列表，设置项数据不直接写在 Composable 中。
- 模板 MyModel Repository/Room 数据层仍保留在 `core-data`、`core-database`，但当前不再接入“我的”模块 UI。

---

### 4.7 `app` 模块

**职责**：应用主入口模块，负责组装各模块、提供 Application 类和主 Activity。

| 文件 | 作用 |
|---|---|
| `MyApplication.kt` | `@HiltAndroidApp` Application 类，触发 Hilt 依赖图生成 |
| `ui/MainActivity.kt` | `@AndroidEntryPoint` 主 Activity，全面屏 + Compose setContent |
| `ui/Navigation.kt` | 底部导航 UI 组装，收集 `MainNavigationViewModel` 状态 |
| `ui/MainNavigationViewModel.kt` | 管理底部 Tab、“我的”内部路由和底部栏显隐 |

**核心代码**：

```kotlin
@Composable
fun MainNavigation(viewModel: MainNavigationViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MainNavigationContent(
        uiState = uiState,
        onTabSelected = viewModel::onTabSelected,
        onSettingsClick = viewModel::onSettingsClick,
        onSettingsBack = viewModel::onSettingsBack
    )
}
```

**关键设计**：
- `app` 是当前底部导航的组装点，直接挂载首页、拍照、我的三个 feature。
- 主导航状态由 `MainNavigationViewModel` 管理，Compose 侧只保留 `rememberSaveableStateHolder` 保存各 Tab 页面状态。
- 拍照入口仍指向 `feature-camera-pytorch`，后续可单独演进到 MVI。

---

### 4.8 `test-app` 模块

**职责**：独立测试模块（`com.android.test` 插件），运行于 `:app` 之上进行端到端仪器测试。

| 文件 | 作用 |
|---|---|
| `AppTest.kt` | `@HiltAndroidTest`，启动 `MainActivity`，验证 `fakeMyModels` 数据出现在界面 |
| `testdi/TestDatabaseModule.kt` | `@TestInstallIn(replaces = [DataModule::class])`，注入只读 Fake 数据 |

**关键设计**：
- `targetProjectPath = ":app"`，测试运行在 app 的 APK 上
- 使用 `FakeMyModelRepository`（来自 core-data），注意其 `add()` 会抛出 `NotImplementedError`
- 与 app 模块的 `TestFakeDataModule` 不同（后者支持 add 操作）

---

## 五、模块间交互方式

### 5.1 依赖注入 (Hilt) —— 核心交互机制

```
MainNavigationViewModel (app)
    │ 管理底部 Tab 与我的内部路由
    ▼
MainNavigation (app)
    │ 组装 HomeScreen / CameraScreen / MyModelMainScreen
    ▼
MainActivity (app)

HomeViewModel / RecommendViewModel / MyModelMainViewModel / SettingsViewModel
    │ 管理各自页面 UI state
    ▼
对应 Screen 收集状态并渲染

DatabaseModule / DataModule
    │ 保留 MyModel Repository 与 Room 数据层能力
    ▼
后续真实数据功能可通过 Repository 接入对应 ViewModel
```

### 5.2 接口抽象隔离

| 隔离层 | 机制 | 效果 |
|---|---|---|
| 数据库实体 vs 业务模型 | `MyModelRepository` 返回 `List<String>` | UI 层完全不知道 `MyModel` 实体存在 |
| Repository 接口 vs 实现 | Hilt `@Binds` | ViewModel 依赖接口，实现可替换 |
| 导航键 vs UI 实现 | `feature-mymodel-navigation` 独立模块 | 其他模块无需依赖完整 feature 即可导航 |
| 测试替换 | `@TestInstallIn(replaces = [...])` | 测试中无缝替换 DI 模块 |

### 5.3 主导航状态

```
MainNavigationViewModel
    │ MainNavigationUiState(selectedTab, myModelRoute)
    ▼
MainNavigationContent
    │ 渲染底部栏、当前 Tab、设置页覆盖层
    ▼
HomeScreen / CameraScreen / MyModelMainScreen / SettingsScreen
```

### 5.4 数据流向

```
首页 Tab 点击/滑动
       ▼
HomeViewModel.onTabSelected/onPageChanged()
       ▼
HomeUiState(selectedTab)
       ▼
HomeScreen 渲染 Tab 与 Pager

推荐页进入
       ▼
RecommendViewModel.uiState
       ▼
RecommendScreen 渲染推荐流

我的页 Tab 点击/滑动
       ▼
MyModelMainViewModel.onTabSelected/onPageChanged()
       ▼
MyModelMainUiState(profile, selectedTab, tabContents)
       ▼
MyModelMainScreen 渲染资料与内容空状态
```

---

## 六、模块隔离策略总结

| 隔离维度 | 实现方式 | 具体体现 |
|---|---|---|
| **数据层隔离** | Repository 接口 + 实体映射 | 上层只知道 `String`，不知道 `MyModel` 实体 |
| **依赖倒置** | Hilt `@Binds` 接口绑定 | ViewModel 依赖 `MyModelRepository` 接口而非实现类 |
| **导航解耦** | app 层统一组装 + navigation 模块预留 | 当前底部导航由 app 组装，Navigation3 路由键能力保留 |
| **主题复用** | 独立 core-ui 模块 | 主题定义可跨 feature 复用 |
| **测试隔离** | `@TestInstallIn` 替换 | 生产/测试使用不同的 DI 绑定 |
| **测试基础设施** | 独立 core-testing 模块 | `HiltTestRunner` 被所有模块复用 |
| **构建隔离** | 各模块独立 build.gradle.kts | 每个模块可独立配置依赖和构建选项 |

---

## 七、架构特点

1. **标准分层架构**：`database → data → feature → app`，层次清晰，依赖方向单一
2. **MVVM 状态管理**：非拍照模块由 ViewModel 暴露 UI state，Composable 只负责渲染与事件转发
3. **全面的测试策略**：
   - 单元测试：Repository（core-data）、ViewModel（app、feature-home、feature-mymodel）
   - UI 测试：Screen/Content（feature-home、feature-mymodel）
   - 集成测试：Navigation（app）
   - 端到端测试（test-app）
4. **模板化设计**：`customizer.sh` 可一键重命名包名/模块名/数据模型名，快速适配新项目
5. **可扩展性**：新增 feature 只需创建对应的 feature 模块 + navigation 模块，在 app 层组装即可

---

## 八、扩展指南

新增功能模块的标准步骤：

1. **创建 `feature-xxx` 模块**：包含 ViewModel + Screen，按 MVVM 暴露 UI state
2. **按需创建 `feature-xxx-navigation` 模块**：跨模块路由需要稳定路由键时定义 `NavKey`
3. **在 `app` 模块组装**：`MainNavigation` 中添加底部 Tab 或 feature 内部入口
4. **如需数据支持**：在 `core-database` 添加 Entity + DAO，在 `core-data` 添加 Repository 接口和实现
5. **编写测试**：单元测试、UI 测试、集成测试
