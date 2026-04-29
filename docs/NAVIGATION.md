# Framer Sense - 底部导航模块文档

## 项目概述

Framer Sense 是一个采用 **多模块架构** 的 Android 应用，使用 Jetpack Compose 构建UI。应用包含三个主要功能模块，通过底部导航栏进行切换。

## 技术栈

| 技术 | 版本 |
|------|------|
| Kotlin | 2.3.10 |
| Compose BOM | 2026.02.00 |
| Material3 | - |
| Navigation 3 | 1.0.1 |
| Hilt | 2.59.2 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 23 |

## 项目结构

```
framer-sense/
├── app/                          # 主应用模块（入口、主导航）
│   └── src/main/java/android/template/ui/
│       ├── MainActivity.kt       # Activity 入口
│       └── Navigation.kt         # 底部导航实现 ★
├── feature-home/                 # 首页功能模块 ★
│   └── src/main/.../home/ui/
│       └── HomeScreen.kt         # 首页页面
├── feature-camera/               # 拍照功能模块 ★
│   └── src/main/.../camera/ui/
│       └── CameraScreen.kt       # 拍照页面
├── feature-mymodel/              # 我的功能模块 ★
│   └── src/main/.../mymodel/ui/
│       ├── MyModelMainScreen.kt  # 我的页面（底部导航用）★
│       ├── MyModelScreen.kt      # 我的详细页面（原有）
│       └── MyModelViewModel.kt   # ViewModel
├── core-ui/                      # 公共 UI 组件（Theme、Color、Type）
├── core-data/                    # 数据层
├── core-database/                # 数据库层
├── core-network/                 # 网络层
├── core-common/                  # 公共工具
└── core-testing/                 # 测试公共代码
```

> 标记 ★ 的文件为本次新增或修改的文件。

---

## 底部导航设计

### 导航结构

```
┌──────────────────────────────┐
│                              │
│                              │
│     当前选中模块的内容区域     │
│                              │
│                              │
├──────────────────────────────┤
│    🏠 首页 │ 📷 拍照 │ 👤 我的  │   ← NavigationBar
└──────────────────────────────┘
```

### 导航行为

| 特性 | 说明 |
|------|------|
| 切换方式 | **仅支持点击** 底部 NavigationBarItem 切换 |
| 滑动切换 | **不支持** 左右滑动切换 |
| 默认页面 | 首页（`BottomNavTab.HOME`） |
| Tab 数量 | 3 个固定 Tab |

### Tab 定义 (`Navigation.kt`)

```kotlin
enum class BottomNavTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home),
    CAMERA("拍照", Icons.Default.CameraAlt),
    MY_MODEL("我的", Icons.Default.Person)
}
```

### 核心实现逻辑

使用 `mutableIntStateOf` 维护当前选中的 Tab 索引，通过 `when` 表达式条件渲染对应模块的 Composable：

```kotlin
var selectedTab by remember { mutableIntStateOf(0) }

Scaffold(bottomBar = { /* NavigationBar */ }) { innerPadding ->
    Box(Modifier.padding(innerPadding)) {
        when (selectedTab) {
            BottomNavTab.HOME.ordinal     -> HomeScreen()
            BottomNavTab.CAMERA.ordinal   -> CameraScreen()
            BottomNavTab.MY_MODEL.ordinal -> MyModelMainScreen()
        }
    }
}
```

---

## 各模块说明

### 1. feature-home（首页）

- **包名**: `android.template.feature.home.ui`
- **入口**: `HomeScreen()`
- **展示内容**: 居中显示标题 "首页" + 模块标识 "当前模块：Home"
- **依赖**: `:core-ui`, Compose UI/Material3

### 2. feature-camera（拍照）

- **包名**: `android.template.feature.camera.ui`
- **入口**: `CameraScreen()`
- **展示内容**: 居中显示标题 "拍照" + 模块标识 "当前模块：Camera"
- **依赖**: `:core-ui`, Compose UI/Material3

### 3. feature-mymodel（我的）

- **包名**: `android.template.feature.mymodel.ui`
- **入口**: `MyModelMainScreen()`（新增，用于底部导航）
- **原有入口**: `MyModelScreen()`（保留，含 ViewModel 和数据操作）
- **展示内容**: 居中显示标题 "我的" + 模块标识 "当前模块：MyModel"
- **依赖**: `:core-ui`, `:core-data`, Hilt, Compose, Navigation3

---

## 模块依赖关系

```
app
├── :core-ui          （公共主题、颜色、字体）
├── :feature-home      （首页模块）
├── :feature-camera    （拍照模块）
├── :feature-mymodel   （我的模块）
└── :core-testing      （测试依赖）
```

---

## 测试覆盖

### 单元测试（Compose UI Test）

| 测试类 | 文件路径 | 覆盖内容 |
|--------|----------|----------|
| `HomeScreenTest` | `feature-home/src/test/.../ui/HomeScreenTest.kt` | 首页标题和模块名称显示 |
| `CameraScreenTest` | `feature-camera/src/test/.../ui/CameraScreenTest.kt` | 拍照标题和模块名称显示 |
| `MyModelMainScreenTest` | `feature-mymodel/src/test/.../ui/MyModelMainScreenTest.kt` | 我的标题和模块名称显示 |

### Instrumented Test（Hilt Android Test）

| 测试类 | 文件路径 | 覆盖内容 |
|--------|----------|----------|
| `NavigationTest` | `app/src/androidTest/.../ui/NavigationTest.kt` | 完整导航流程测试 |

#### NavigationTest 用例清单

| 用例名 | 验证内容 |
|--------|----------|
| `defaultScreen_showsHome` | 默认显示首页模块 |
| `homeTab_isSelectedByDefault` | 首页 Tab 默认选中 |
| `clickCameraTab_showsCameraScreen` | 点击拍照 Tab 显示拍照页面 |
| `clickCameraTab_cameraTabBecomesSelected` | 点击拍照后拍照 Tab 被选中 |
| `clickCameraTab_homeContentNo LongerVisible` | 切换后首页内容消失 |
| `clickMyModelTab_showsMyModelScreen` | 点击我的 Tab 显示我的页面 |
| `clickMyModelTab_myModelTabBecomesSelected` | 点击我的后我的 Tab 被选中 |
| `switchBetweenAllTabs_displaysCorrectContent EachTime` | 三轮切换均正确显示 |
| `allThreeTabs_areAlwaysVisibleInNavigationBar` | 3 个 Tab 始终可见 |

---

## 构建与运行

```bash
# 全量构建
./gradlew assembleDebug

# 运行所有单元测试
./gradlew testDebugUnitTest

# 运行所有 Instrumented Test（需要连接设备或模拟器）
./gradlew connectedDebugAndroidTest

# 仅运行导航相关测试
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=android.template.ui.NavigationTest
```

---

## 关键设计决策

1. **不使用 HorizontalPager**：明确要求不支持滑动切换，因此采用简单的状态驱动条件渲染（`when` 表达式），而非 Pager 方案。
2. **独立模块 Screen**：每个 Feature 模块提供独立的 Screen Composable，通过 app 模块的 `Navigation.kt` 统一组装。
3. **MyModel 双屏设计**：`MyModelMainScreen` 用于底部导航展示（轻量），原有的 `MyModelScreen`（含 ViewModel/Repository 数据操作）保留供后续深度功能使用。
