# 我的模块 (feature-mymodel) 设计文档

## 模块概述

`feature-mymodel` 承载底部导航中的“我的”主页、设置页，以及模板保留的 MyModel 数据示例功能。

底部导航实际使用：

- `MyModelMainScreen()`：我的主页。
- `SettingsScreen()`：我的模块内部设置页，由 app 层 `MainNavigation` 控制进入和返回。

模板数据能力保留：

- `MyModelScreen()`：原模板数据输入与列表页面。
- `MyModelViewModel`：连接 Repository 和 UI。
- `MyModelRepository`、Room DAO 和实体位于 `core-data`、`core-database`。

## 我的主页

`MyModelMainScreen` 由三部分组成：

- 顶部操作栏：扫一扫、消息、设置。
- 个人信息区：头像、用户名、个人简介。
- 内容 Tab：作品、点赞、收藏、评论。

内容 Tab 使用 `PrimaryTabRow` 和 `HorizontalPager` 联动。用户可以点击 Tab 切换，也可以左右滑动切换；点击当前已选中的 Tab 会被忽略，避免重复触发滚动动画。

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
- 进入设置页时底部导航隐藏。
- 点击返回按钮或系统返回键时回到我的主页。
- 设置项行保持 56dp 以上触控高度，适配手机端点击。

## 导航关系

`MainNavigation` 持有 `MyModelRoute`：

```kotlin
enum class MyModelRoute {
    MAIN,
    SETTINGS
}
```

- `MAIN` 渲染 `MyModelMainScreen`。
- `SETTINGS` 渲染 `SettingsScreen`。
- `BackHandler` 只在设置页启用，用于回到 `MAIN`。

## 测试

页面级 Compose 测试位于：

```text
feature-mymodel/src/androidTest/java/android/template/feature/mymodel/ui/MyModelMainScreenTest.kt
```

模板数据与 ViewModel 本地测试仍位于：

```text
feature-mymodel/src/test/java/android/template/feature/mymodel/ui/
```

常用命令：

```bash
# 本地单元测试
./gradlew :feature-mymodel:testDebugUnitTest

# 页面级 Compose 测试，需要设备或模拟器
./gradlew :feature-mymodel:connectedDebugAndroidTest
```
