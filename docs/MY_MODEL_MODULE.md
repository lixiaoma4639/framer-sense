# 我的模块 (feature-mymodel) 设计文档

## 模块概述

`feature-mymodel` 承载底部导航中的“我的”主页、扫一扫说明页、设置页和消息列表页，并按 MVVM 管理主页资料、内容 Tab、扫一扫说明、设置项列表和消息列表。内部页面由 app 层使用 Navigation3 back stack 组织。

底部导航实际使用：

- `MyModelMainScreen()`：我的主页。
- `ScanScreen()`：我的模块内部扫一扫说明页，由 app 层 `MainNavigation` 通过 Navigation3 控制进入和返回。
- `SettingsScreen()`：我的模块内部设置页，由 app 层 `MainNavigation` 通过 Navigation3 控制进入和返回。
- `MessageListScreen()`：我的模块内部消息列表页，由 app 层 `MainNavigation` 通过 Navigation3 控制进入和返回。

模板 MyModel 数据层仍保留在 `core-data`、`core-database`，但当前不再接入“我的”模块 UI。

## 文件结构

```text
feature-mymodel/src/main/java/com/framer/sense/feature/mymodel/ui/
├── MyModelMainScreen.kt       # 我的主页 UI
├── MyModelMainViewModel.kt    # 主页资料、内容 Tab 和空状态文案
├── MessageListScreen.kt       # 消息列表页 UI
├── MessageListViewModel.kt    # 静态示例消息列表状态
├── ScanScreen.kt              # 扫一扫说明页 UI
├── ScanViewModel.kt           # 扫一扫说明状态
├── SettingsScreen.kt          # 设置页 UI
└── SettingsViewModel.kt       # 设置项列表状态
```

## 我的主页

`MyModelMainScreen` 由三部分组成：

- 顶部操作栏：扫一扫、消息、设置。
- 个人信息区：头像、用户名、个人简介。
- 内容 Tab：作品、点赞、收藏、评论。

内容 Tab 使用 `PrimaryTabRow` 和 `HorizontalPager` 联动。用户可以点击 Tab 切换，也可以左右滑动切换；点击当前已选中的 Tab 会被忽略，避免重复触发滚动动画。

`MyModelMainViewModel` 暴露 `MyModelMainUiState`，包含：

- 个人资料：用户名、简介。
- 当前选中的内容 Tab。
- 各内容 Tab 的空状态文案。

点击顶部“扫一扫”图标会进入 `ScanScreen`；点击顶部“消息”图标会进入 `MessageListScreen`；点击顶部“设置”图标会进入 `SettingsScreen`。

## 扫一扫页

`ScanScreen` 是“我的”模块内部页面，不作为底部 Tab 直接暴露。

当前页面展示简单的扫码功能入口说明，后续可接入二维码识别、相册扫码或扫码结果处理。

交互规则：

- 扫一扫页顶部提供返回按钮。
- 进入扫一扫页时页面全屏覆盖上一页内容。
- 点击返回按钮或系统返回键时回到我的主页。
- `ScanViewModel` 暴露 `ScanUiState`，说明文案不直接写在 Composable 中。

## 消息列表页

`MessageListScreen` 是“我的”模块内部页面，不作为底部 Tab 直接暴露。

当前消息列表使用本地静态示例数据，行内容包含：

- 标题
- 摘要
- 时间
- 未读标记

交互规则：

- 消息页顶部提供返回按钮。
- 进入消息页时页面全屏覆盖上一页内容。
- 点击返回按钮或系统返回键时回到我的主页。
- `MessageListViewModel` 暴露 `MessageListUiState`，消息数据不直接写在 Composable 中。

## 设置页

`SettingsScreen` 是“我的”模块内部页面，不作为底部 Tab 直接暴露。

当前设置项：

- 隐私条款
- 个人信息收集清单
- 第三方信息共享清单
- 开源软件声明
- 关于
- 用户协议
- 应用权限

交互规则：

- 设置页顶部提供返回按钮。
- 进入设置页时页面全屏覆盖上一页内容。
- 点击返回按钮或系统返回键时回到我的主页。
- 设置项行保持 56dp 以上触控高度，适配手机端点击。
- `SettingsViewModel` 暴露 `SettingsUiState`，设置项数据不直接写在 Composable 中。

## 导航关系

`MainNavigationViewModel` 只持有底部 Tab 状态；“我的”内部页面由 app 层 `MainNavigation` 使用 Navigation3 `NavDisplay` 和 `MyModelNavKey` 维护返回栈：

```kotlin
sealed interface MyModelNavKey : NavKey

data object Main : MyModelNavKey
data object Settings : MyModelNavKey
data object Messages : MyModelNavKey
data object Scan : MyModelNavKey
```

- `Main` 是内部 back stack 根节点，对应 `MyModelMainScreen`。
- `Settings` 渲染 `SettingsScreen`。
- `Messages` 渲染 `MessageListScreen`。
- `Scan` 渲染 `ScanScreen`。
- 内部页面返回按钮和系统返回键都通过 Navigation3 `onBack` 出栈。
- 内部页面的横向滑动动画由 app 层 `NavDisplay` 统一管理；后续新增页面只需扩展 `MyModelNavKey` 与 `entryProvider`。

## 测试

页面级 Compose 测试位于：

```text
feature-mymodel/src/androidTest/java/com/framer/sense/feature/mymodel/ui/MyModelMainScreenTest.kt
feature-mymodel/src/androidTest/java/com/framer/sense/feature/mymodel/ui/SettingsScreenTest.kt
feature-mymodel/src/androidTest/java/com/framer/sense/feature/mymodel/ui/MessageListScreenTest.kt
feature-mymodel/src/androidTest/java/com/framer/sense/feature/mymodel/ui/ScanScreenTest.kt
```

ViewModel 本地测试位于：

```text
feature-mymodel/src/test/java/com/framer/sense/feature/mymodel/ui/
```

常用命令：

```bash
# 本地单元测试
./gradlew :feature-mymodel:testDebugUnitTest

# 页面级 Compose 测试，需要设备或模拟器
./gradlew :feature-mymodel:connectedDebugAndroidTest
```
