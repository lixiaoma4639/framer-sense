# 首页模块 (feature-home) 设计文档

## 模块概述

首页模块 (`feature-home`) 是应用的核心内容展示模块，包含 **顶部 Tab 切换栏** 和 **两个子页面**。

## 结构设计

```
┌─────────────────────────────┐
│   ┌──────┐ ┌──────┐        │  ← ScrollableTabRow（可扩展）
│   │ 相册 │ │ 推荐 │        │     当前2个Tab，可扩展为多个
│   └──────┘ └──────┘        │
├─────────────────────────────┤
│                             │
│     根据 Tab 显示对应页面     │  ← 内容区域（占满剩余空间）
│                             │
│   ┌───────────────────┐    │
│   │                   │    │
│   │   相册 / 推荐页面   │    │
│   │                   │    │
│   └───────────────────┘    │
│                             │
└─────────────────────────────┘
```

## 文件清单

```
feature-home/src/main/java/android/template/feature/home/ui/
├── HomeScreen.kt              # 首页主入口 + 顶部Tab栏 + 页面切换逻辑 ★
├── album/
│   ├── AlbumScreen.kt         # 相册页面（3列网格展示系统照片）★
│   └── AlbumViewModel.kt      # 相册ViewModel（权限管理+照片查询）★
└── recommend/
    └── RecommendScreen.kt     # 推荐页面（两列瀑布流+假数据）★

app/src/main/
├── AndroidManifest.xml        # 新增相册读取权限 ★
└── build.gradle.kts           # 已有依赖

gradle/libs.versions.toml      # 新增 coil 依赖定义 ★
```

> 标记 ★ 为本次新增或修改的文件

---

## 子页面详细说明

### 1. 相册页面 (AlbumScreen)

**文件**: `album/AlbumScreen.kt` + `album/AlbumViewModel.kt`

#### 功能特性

| 特性 | 说明 |
|------|------|
| 权限请求 | 自动请求 `READ_MEDIA_IMAGES`(API 33+) 或 `READ_EXTERNAL_STORAGE`(< API 33) |
| 照片来源 | 通过 `MediaStore.Images` ContentResolver 查询系统相册 |
| 布局 | `LazyVerticalGrid` 3列等比网格 |
| 图片加载 | Coil `AsyncImage` 加载 URI |
| 权限拒绝处理 | 显示提示文案 + "重新授权"按钮 |

#### 状态机 (AlbumUiState)

```
                    ┌────────────┐
                    │  Loading   │ ← 默认状态 / 加载中
                    └─────┬──────┘
                          │ 查询成功
              ┌───────────▼──────────┐
              │      Success         │ ← 照片列表(可能为空)
              └───────────┬──────────┘
                          │ 权限被拒
              ┌───────────▼──────────┐
              │ PermissionDenied     │ ← 提示用户去设置开启
              └───────────┬──────────┘
                          │ 发生异常
              ┌───────────▼──────────┐
              │       Error          │ ← 错误信息展示
              └──────────────────────┘
```

### 2. 推荐页面 (RecommendScreen)

**文件**: `recommend/RecommendScreen.kt`

#### 功能特性

| 特性 | 说明 |
|------|------|
| 数据源 | 本地假数据（16条模拟小红书风格内容） |
| 布局 | `LazyVerticalGrid` 固定2列 |
| 卡片结构 | 图片(不同宽高比模拟瀑布流) + 标题(最多2行) + 作者名 + 点赞数 |
| 图片加载 | Coil `AsyncImage` 加载网络URL(picsum.photos) |

#### 假数据字段

```kotlin
data class RecommendItem(
    val id: Int,            // 唯一ID
    val title: String,      // 标题文本
    val imageUrl: String,   // 图片URL
    val author: String,     // 作者名称
    val likes: Int          // 点赞数（自动格式化为 k/w）
)
```

#### 瀑布流效果实现方式

通过为每张卡片设置不同的 `aspectRatio` 来模拟高低错落的瀑布流：

```kotlin
aspectRatio(
    when {
        item.id % 3 == 0 -> 0.85f   // 较矮的卡片
        item.id % 2 == 0 -> 1.05f   // 较高的卡片
        else -> 0.95f               // 中等高度
    }
)
```

---

## 顶部 Tab 设计

### Tab 定义 (HomeTab enum)

```kotlin
enum class HomeTab(val title: String) {
    ALBUM("相册"),
    RECOMMEND("推荐")
    // 未来可扩展：VIDEO("视频"), LIVE("直播") 等
}
```

### 可扩展性

使用 `ScrollableTabRow` + `enum class` + `when` 表达式：

1. **添加新Tab**：在 `HomeTab` 枚举中新增一项
2. **添加新页面**：创建对应的 Screen Composable
3. **注册路由**：在 `when` 分支中添加新的 case

无需修改任何框架级代码，完全符合开闭原则。

---

## 权限配置

### AndroidManifest.xml 新增权限

```xml
<!-- Android 13+ (API 33+) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<!-- Android 12及以下 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

### 权限请求流程

```
进入首页 → AlbumScreen 组合 → 
  ├─ 首次渲染 → checkAndRequestPermission() → permissionLauncher.launch()
  │                                              ↓
  │                                    用户授权弹窗
  │                                              ↓
  │                              ┌───────────────┼───────────────┐
  │                              ↓ 允许            ↓ 拒绝
  │                        loadAlbumPhotos()    PermissionDenied 状态
  │                              ↓                (显示重试按钮)
  │                        Success 状态
  │                         (显示照片)
```

---

## 新增依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `coil-compose` (io.coil-kt.coil3) | 3.0.0 | 异步图片加载（系统URI + 网络URL） |
| `hilt-android` + `ksp` | 2.59.2 | AlbumViewModel 依赖注入 |
| `androidx.lifecycle.*` | 2.10.0 | collectAsStateWithLifecycle |
| `material-icons-extended` | BOM 管理 | 图标资源（预留） |

---

## 测试覆盖

### 单元测试 (HomeScreenTest)

| 用例名 | 验证内容 |
|--------|----------|
| `homeScreen_displaysAlbumTabByDefault` | 默认显示相册Tab |
| `homeScreen_displaysRecommendTab` | 推荐Tab可见 |
| `homeScreen_clickRecommendTab_switchesToRecommend` | 点击推荐切换成功，出现推荐卡片内容 |
| `homeScreen_clickAlbumTab_staysOnAlbum` | 推荐→相册切换正常 |
| `recommendScreen_showsMultipleCards` | 推荐页多条数据正常渲染 |

### 运行测试

```bash
# 单元测试
./gradlew :feature-home:testDebugUnitTest

# Instrumented Test（需要设备）
./gradlew :feature-home:connectedDebugAndroidTest
```
