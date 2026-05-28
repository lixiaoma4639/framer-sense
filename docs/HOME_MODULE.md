# 首页模块 (feature-home) 设计文档

## 模块概述

首页模块 (`feature-home`) 是应用的内容展示入口，使用 Jetpack Compose 实现顶部 Tab 与页面联动，并按 MVVM 管理页面状态。

当前 Tab 顺序：

1. 推荐
2. 相册

`HomeScreen` 收集 `HomeViewModel` 暴露的 `HomeUiState`。用户可以点击顶部 Tab 切换，也可以左右滑动切换；点击当前已选中的 Tab 会被忽略，避免重复触发滚动动画。`PagerState` 只保留在 Compose 层，当前页变化会同步回 ViewModel。

## 文件结构

```text
feature-home/src/main/java/android/template/feature/home/ui/
├── HomeScreen.kt              # 首页入口、顶部 Tab、Pager 联动
├── HomeViewModel.kt           # 首页 Tab 状态
├── album/
│   ├── AlbumScreen.kt         # 相册页面，3 列系统图片网格
│   └── AlbumViewModel.kt      # 权限检查、相册查询与 UI 状态
└── recommend/
    ├── RecommendScreen.kt     # 推荐页，两列内容流
    └── RecommendViewModel.kt  # 推荐流 UI 状态与当前本地假数据
```

## 推荐页面

`RecommendScreen` 收集 `RecommendViewModel` 暴露的 `RecommendUiState`。当前推荐数据仍是本地假数据，后续可替换为 Repository 或网络分页数据。

核心行为：

- 数据模型为 `RecommendItem`，包含标题、图片 URL、作者和点赞数。
- 布局使用 `LazyVerticalGrid` 固定 2 列，并为 item 设置稳定 key 和 contentType。
- 图片使用 Coil `AsyncImage` 加载网络图片。
- 图片区域使用固定 `aspectRatio` 和 `surfaceVariant` 占位背景，减少网络图片加载时的布局跳动。
- 点赞数通过 `formatLikes` 格式化为 `k` 或 `w`。

推荐页面 UI 状态：

- `Loading`：加载中。
- `Success`：推荐内容列表。
- `Error`：推荐内容加载失败。

## 相册页面

`AlbumScreen` 和 `AlbumViewModel` 展示系统相册图片。

核心行为：

- Android 13 及以上使用 `READ_MEDIA_IMAGES`，Android 12 及以下使用 `READ_EXTERNAL_STORAGE`。
- 进入页面先检查权限；已授权直接加载相册，未授权才发起系统权限请求。
- 用户拒绝权限后展示说明和明确的“重新授权”按钮。
- `MediaStore` 查询运行在 `Dispatchers.IO`，避免大量图片查询阻塞主线程。
- 图片以 3 列 `LazyVerticalGrid` 展示，网格项使用稳定 key/contentType 和固定占位背景。

UI 状态：

- `Loading`：加载中。
- `Success`：查询成功，照片列表可能为空。
- `PermissionDenied`：用户未授权或查询时无权限。
- `Error`：其他异常。

## 交互优化

- 顶部 Tab 点击会先判断目标页是否已经选中，避免重复动画。
- 推荐流和相册网格都使用稳定 item 类型，帮助 LazyGrid 复用。
- 图片加载前有固定尺寸占位，避免首帧或滚动过程中出现明显抖动。
- 相册权限流程避免已授权时重复请求权限。

## 测试

页面级 Compose 测试位于：

```text
feature-home/src/androidTest/java/android/template/feature/home/ui/HomeScreenTest.kt
```

常用命令：

```bash
# 本地单元测试，不依赖 Compose UI Test 运行环境
./gradlew :feature-home:testDebugUnitTest

# 页面级 Compose 测试，需要设备或模拟器
./gradlew :feature-home:connectedDebugAndroidTest
```
