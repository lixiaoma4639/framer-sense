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

**职责**：功能模块，包含 MyModel 功能的 UI（Screen + ViewModel）和导航入口注册。是用户界面的核心实现。

| 文件 | 作用 |
|---|---|
| `ui/MyModelViewModel.kt` | `@HiltViewModel`，注入 Repository，管理 UI 状态 |
| `ui/MyModelScreen.kt` | Compose UI 界面（输入框 + 保存按钮 + 列表展示） |
| `navigation/EntryProvider.kt` | Navigation3 入口注册，将 `Main` 路由键映射到 `MyModelScreen` |

**核心代码**：

```kotlin
// ViewModel - UI 状态管理
@HiltViewModel
class MyModelViewModel @Inject constructor(
    myModelRepository: MyModelRepository   // 注入接口，不依赖具体实现
) : ViewModel() {
    val uiState: StateFlow<MyModelUiState> = myModelRepository.myModels
        .map { Success(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Loading)

    fun addMyModel(name: String) = viewModelScope.launch {
        myModelRepository.add(name)
    }
}

// UI 状态密封接口
sealed interface MyModelUiState {
    data object Loading : MyModelUiState
    data class Error(val throwable: Throwable) : MyModelUiState
    data class Success(val data: List<String>) : MyModelUiState
}
```

**关键设计**：
- ViewModel 仅依赖 `MyModelRepository` 接口，不依赖数据库层
- UI 状态使用 sealed interface 确保类型安全的状态管理
- `stateIn` 的 `WhileSubscribed(5000)` 实现 5 秒超时的状态共享，避免配置变更时重新查询

---

### 4.7 `app` 模块

**职责**：应用主入口模块，负责组装各模块、提供 Application 类和主 Activity。

| 文件 | 作用 |
|---|---|
| `MyApplication.kt` | `@HiltAndroidApp` Application 类，触发 Hilt 依赖图生成 |
| `ui/MainActivity.kt` | `@AndroidEntryPoint` 主 Activity，全面屏 + Compose setContent |
| `ui/Navigation.kt` | Navigation3 导航配置，注册 `MyModelEntryProvider` |

**核心代码**：

```kotlin
// Navigation.kt - 模块组装的核心
@Composable
fun MainNavigation() {
    val backstack = rememberNavBackStack(Main)   // 初始路由 Main 来自 navigation 模块
    NavDisplay(backstack, entryProvider = {
        MyModelEntryProvider(onItemClick = { /* 预留导航扩展 */ })  // 来自 feature-mymodel
    }, decorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),  // 状态保存
        rememberViewModelStoreNavEntryDecorator()        // ViewModel 管理
    ))
}
```

**关键设计**：
- `app` 是唯一的 "组装点"，将 navigation 键和 feature UI 连接起来
- 通过 `entryProvider` 注册机制实现松耦合
- 测试时通过 `TestFakeDataModule`（支持 add 操作的 Fake）替换真实数据源

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
DatabaseModule (core-database)
    │ 提供 AppDatabase, MyModelDao
    ▼
DataModule (core-data)
    │ @Binds DefaultMyModelRepository → MyModelRepository
    ▼
MyModelViewModel (feature-mymodel)
    │ @Inject MyModelRepository
    ▼
MyModelScreen (feature-mymodel)
    │ hiltViewModel() → MyModelViewModel
    ▼
MainActivity (app)
```

### 5.2 接口抽象隔离

| 隔离层 | 机制 | 效果 |
|---|---|---|
| 数据库实体 vs 业务模型 | `MyModelRepository` 返回 `List<String>` | UI 层完全不知道 `MyModel` 实体存在 |
| Repository 接口 vs 实现 | Hilt `@Binds` | ViewModel 依赖接口，实现可替换 |
| 导航键 vs UI 实现 | `feature-mymodel-navigation` 独立模块 | 其他模块无需依赖完整 feature 即可导航 |
| 测试替换 | `@TestInstallIn(replaces = [...])` | 测试中无缝替换 DI 模块 |

### 5.3 导航键解耦

```
feature-mymodel-navigation       feature-mymodel              app
┌──────────────────┐    ┌───────────────────────┐    ┌───────────────────┐
│ Main : NavKey    │◄───│ MyModelEntryProvider  │    │ MainNavigation    │
│ (路由键定义)      │    │ (注册 Main → Screen)   │    │ (使用 Main 作初始路由)│
└──────────────────┘    └───────────────────────┘    └───────────────────┘
         ▲                            │                         │
         │                            │                         │
         └────────────────────────────┘◄────────────────────────┘
              两者都依赖 navigation 模块，但不直接相互依赖
```

### 5.4 数据流向

```
用户输入名称 → 点击 Save
       │
       ▼
MyModelScreen.onSave()
       │
       ▼
MyModelViewModel.addMyModel(name)
       │
       ▼
MyModelRepository.add(name: String)          ← 接口调用，不感知实现
       │
       ▼
DefaultMyModelRepository.add()
       │
       ▼
MyModelDao.insertMyModel(MyModel(name))      ← String → Entity 映射
       │
       ▼
Room Database (SQLite)                       ← 数据持久化


数据库变化 (Room Flow 自动通知)
       │
       ▼
MyModelDao.getMyModels() → Flow<List<MyModel>>
       │
       ▼
DefaultMyModelRepository.myModels            ← Entity → String 映射
       │
       ▼
MyModelViewModel.uiState (StateFlow)         ← Loading / Error / Success
       │
       ▼
MyModelScreen 渲染列表                        ← Compose 重组
```

---

## 六、模块隔离策略总结

| 隔离维度 | 实现方式 | 具体体现 |
|---|---|---|
| **数据层隔离** | Repository 接口 + 实体映射 | 上层只知道 `String`，不知道 `MyModel` 实体 |
| **依赖倒置** | Hilt `@Binds` 接口绑定 | ViewModel 依赖 `MyModelRepository` 接口而非实现类 |
| **导航解耦** | 独立 navigation 模块 | 路由键定义与 UI 实现分离 |
| **主题复用** | 独立 core-ui 模块 | 主题定义可跨 feature 复用 |
| **测试隔离** | `@TestInstallIn` 替换 | 生产/测试使用不同的 DI 绑定 |
| **测试基础设施** | 独立 core-testing 模块 | `HiltTestRunner` 被所有模块复用 |
| **构建隔离** | 各模块独立 build.gradle.kts | 每个模块可独立配置依赖和构建选项 |

---

## 七、架构特点

1. **标准分层架构**：`database → data → feature → app`，层次清晰，依赖方向单一
2. **Navigation3**：采用最新的 Navigation3 框架，使用 `NavDisplay` + `entryProvider` 模式替代 Navigation2 的 NavGraph
3. **全面的测试策略**：
   - 单元测试：Repository（core-data）、ViewModel（feature-mymodel）
   - UI 测试：Screen（feature-mymodel）
   - 集成测试：Navigation（app）
   - 端到端测试（test-app）
4. **模板化设计**：`customizer.sh` 可一键重命名包名/模块名/数据模型名，快速适配新项目
5. **可扩展性**：新增 feature 只需创建对应的 feature 模块 + navigation 模块，在 app 层组装即可

---

## 八、扩展指南

新增功能模块的标准步骤：

1. **创建 `feature-xxx` 模块**：包含 ViewModel + Screen + EntryProvider
2. **创建 `feature-xxx-navigation` 模块**：定义 `NavKey` 路由键
3. **在 `app` 模块注册**：`MainNavigation` 中添加 `entryProvider` 映射
4. **如需数据支持**：在 `core-database` 添加 Entity + DAO，在 `core-data` 添加 Repository 接口和实现
5. **编写测试**：单元测试、UI 测试、集成测试
