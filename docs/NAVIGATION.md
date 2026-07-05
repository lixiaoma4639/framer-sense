# Framer Sense 导航文档

## 概述

应用入口为 `MainActivity -> MyApplicationTheme -> MainNavigation`。`MainNavigation` 使用 `Scaffold` 和 Material3 `NavigationBar` 组织三个底部 Tab：

| Tab | 入口 Composable |
| --- | --- |
| 首页 | `HomeScreen()` |
| 拍照 | `CameraScreen()` |
| 我的 | `MyModelMainScreen()` |

底部 Tab 只支持点击切换，不支持在 app 级别左右滑动切换。首页和我的模块内部各自使用 `HorizontalPager` 支持模块内滑动。

## 主导航行为

- 默认选中首页。
- 当前底部 Tab 使用可保存状态保存，配置变化或重组后尽量保持原选中状态。
- 点击已选中的底部 Tab 会被忽略，避免重复触发页面重组。
- 每个底部 Tab 的页面状态通过保存状态容器保留，减少来回切换时的状态丢失。
- 用户进入“我的 -> 扫一扫”“我的 -> 设置”或“我的 -> 消息”时不从状态中移除底部导航，内部页面通过 Navigation3 `NavDisplay` 作为全屏覆盖层盖住上一页全部内容；从内部页面返回时从内部 back stack 出栈并恢复“我的”主页。

## 底部 Tab 定义

```kotlin
enum class BottomNavTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home),
    CAMERA("拍照", Icons.Default.CameraAlt),
    MY_MODEL("我的", Icons.Default.Person)
}
```

## 我的模块内部导航

“我的”模块内部使用 `feature-mymodel-navigation` 中的 `MyModelNavKey` 定义 Navigation3 路由键：

```kotlin
sealed interface MyModelNavKey : NavKey

data object Main : MyModelNavKey
data object Settings : MyModelNavKey
data object Messages : MyModelNavKey
data object Scan : MyModelNavKey
```

行为规则：

- `Main` 是内部返回栈根节点，对应底部 Tab 中的 `MyModelMainScreen`。
- `Settings` 显示全屏覆盖的 `SettingsScreen`。
- `Messages` 显示全屏覆盖的 `MessageListScreen`。
- `Scan` 显示全屏覆盖的 `ScanScreen`。
- 内部页面点击返回按钮或系统返回键都会从 Navigation3 back stack 出栈。
- 主页和内部页面之间使用 `NavDisplay` 横向滑动动画切换；页面级 `ViewModel` 通过 Navigation3 entry decorator 绑定到对应 `NavEntry`。

## 模块说明

### 首页

- 入口：`HomeScreen()`
- 顶部 Tab 顺序：`推荐`、`相册`
- 模块内支持点击 Tab 和左右滑动切换。
- 点击当前已选中的顶部 Tab 不会重复触发滚动动画。

### 拍照

- 入口：`CameraScreen()`
- 当前入口来自 `feature-camera-pytorch-v2`，为 CameraX + ONNX Runtime 3D 构图引导页，显示实时相机预览、线条式 3D 虚拟人像、推荐构图区域、移动提示和拍摄按钮。
- 点击拍摄后，照片会保存到系统相册。
- 详细实现见 `docs/FEATURE_CAMERA_PYTORCH_V2.md`；上一版 ONNX 方案见 `docs/FEATURE_CAMERA_PYTORCH.md`，旧 ML Kit 方案见 `docs/CAMERA_COMPOSITION_GUIDE.md`。

### 我的

- 入口：`MyModelMainScreen()`
- 包含顶部操作栏、个人信息区、内容 Tab、扫一扫入口、消息入口和设置入口。
- 内容 Tab 顺序：`作品`、`点赞`、`收藏`、`评论`。
- 扫一扫入口由 app 层 `MainNavigation` 将 `Scan` 压入 Navigation3 内部 back stack。
- 设置入口由 app 层 `MainNavigation` 将 `Settings` 压入 Navigation3 内部 back stack。
- 消息入口由 app 层 `MainNavigation` 将 `Messages` 压入 Navigation3 内部 back stack。

## 测试

导航相关测试位于：

```text
app/src/androidTest/java/com/framer/sense/ui/NavigationTest.kt
```

覆盖内容：

- 默认显示首页。
- 底部 Tab 选中状态正确。
- 点击拍照、我的、首页能切换到对应内容。
- 切换到扫一扫页、设置页或消息列表页时显示全屏覆盖页，并可通过返回回到我的主页。

常用命令：

```bash
# 构建 Debug 包
./gradlew assembleDebug

# 运行 app 导航仪器测试，需要设备或模拟器
./gradlew :app:connectedDebugAndroidTest
```
