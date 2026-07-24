// 声明当前文件属于 Camera PyTorch v2 功能的 UI 包。
package com.framer.sense.feature.camera.pytorch.v2.ui

// 导入 Android 权限常量，用来引用相机和旧版外部存储权限。
import android.Manifest
// 导入 Activity 类型，用于从 Compose 的 Context 中找到宿主 Activity。
import android.app.Activity
// 导入 Context 类型，用于访问系统资源、权限检查和 Activity 查找。
import android.content.Context
// 导入 ContextWrapper，用于递归拆开包装后的 Context。
import android.content.ContextWrapper
// 导入屏幕方向常量，用于临时开启全传感器方向。
import android.content.pm.ActivityInfo
// 导入权限检查结果常量，用于判断权限是否已授予。
import android.content.pm.PackageManager
// 导入配置常量，用于判断当前横竖屏方向。
import android.content.res.Configuration
// 导入系统版本信息，用于兼容旧版存储权限。
import android.os.Build
// 将 android.view.Surface 别名为 DisplaySurface，避免和 Compose Surface 同名冲突。
import android.view.Surface as DisplaySurface
// 导入 Compose 的 Activity Result 启动器记忆函数，用于发起权限请求。
import androidx.activity.compose.rememberLauncherForActivityResult
// 导入 Activity Result 权限请求契约。
import androidx.activity.result.contract.ActivityResultContracts
// 导入背景修饰符，用于设置黑色背景。
import androidx.compose.foundation.background
// 导入布局排列工具，用于设置子项间距。
import androidx.compose.foundation.layout.Arrangement
// 导入 Box 容器，用于叠放相机预览、覆盖层和状态栏。
import androidx.compose.foundation.layout.Box
// 导入 Column 容器，用于纵向排列文案、进度和按钮。
import androidx.compose.foundation.layout.Column
// 导入填满父容器尺寸的修饰符。
import androidx.compose.foundation.layout.fillMaxSize
// 导入填满父容器宽度的修饰符。
import androidx.compose.foundation.layout.fillMaxWidth
// 导入内边距修饰符。
import androidx.compose.foundation.layout.padding
// 导入圆角形状，用于消息面板。
import androidx.compose.foundation.shape.RoundedCornerShape
// 导入 Material3 按钮组件。
import androidx.compose.material3.Button
// 导入 Material3 圆形进度组件。
import androidx.compose.material3.CircularProgressIndicator
// 导入 Material 主题，用于读取排版样式。
import androidx.compose.material3.MaterialTheme
// 导入 Compose 的 Surface 组件，用于消息面板背景和内容色。
import androidx.compose.material3.Surface
// 导入文本组件。
import androidx.compose.material3.Text
// 导入 Composable 注解，标记可组合 UI 函数。
import androidx.compose.runtime.Composable
// 导入 DisposableEffect，用于在组件进入/离开组合时设置和恢复副作用。
import androidx.compose.runtime.DisposableEffect
// 导入 LaunchedEffect，用于在组合期间启动协程副作用。
import androidx.compose.runtime.LaunchedEffect
// 导入 getValue 委托，用于以 by 方式读取 State。
import androidx.compose.runtime.getValue
// 导入 rememberUpdatedState，保证副作用中使用最新回调。
import androidx.compose.runtime.rememberUpdatedState
// 导入对齐方式。
import androidx.compose.ui.Alignment
// 导入 Modifier，承载布局和绘制修饰。
import androidx.compose.ui.Modifier
// 导入 Color，用于设置背景和文字颜色。
import androidx.compose.ui.graphics.Color
// 导入当前 Compose Context。
import androidx.compose.ui.platform.LocalContext
// 导入当前设备配置。
import androidx.compose.ui.platform.LocalConfiguration
// 导入当前 Android View，用于读取显示旋转角度。
import androidx.compose.ui.platform.LocalView
// 导入颜色资源读取函数。
import androidx.compose.ui.res.colorResource
// 导入字符串资源读取函数。
import androidx.compose.ui.res.stringResource
// 导入文本对齐方式。
import androidx.compose.ui.text.style.TextAlign
// 导入预览注解，用于 Android Studio 预览。
import androidx.compose.ui.tooling.preview.Preview
// 导入 dp 单位。
import androidx.compose.ui.unit.dp
// 导入兼容库权限检查方法。
import androidx.core.content.ContextCompat
// 导入生命周期感知的 Flow 收集函数，避免页面不可见时继续无意义收集。
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// 导入 Compose ViewModel 获取函数。
import androidx.lifecycle.viewmodel.compose.viewModel
// 导入应用主题，用于预览包裹 UI。
import com.framer.sense.core.ui.MyApplicationTheme
// 导入本模块资源索引。
import com.framer.sense.feature.camera.pytorch.v2.R

// 定义外部拍照入口需要的动作数据。
data class CameraV2CaptureAction(
    // 点击拍照按钮时执行的回调。
    val onClick: () -> Unit,
    // 指示外部拍照按钮当前是否可点击。
    val enabled: Boolean
)

// 标记 CameraScreen 是 Compose UI 入口。
@Composable
// 定义 Camera v2 页面，负责连接 ViewModel、副作用、权限和实际 UI 内容。
fun CameraScreen(
    // 接收用户身高，默认取 BodyProfile 的默认身高。
    heightCm: Int = BodyProfile.DEFAULT_HEIGHT_CM,
    // 接收用户体重，默认取 BodyProfile 的默认体重。
    weightKg: Int = BodyProfile.DEFAULT_WEIGHT_KG,
    // 接收外部传入的布局修饰符。
    modifier: Modifier = Modifier,
    // 获取或接收 CameraV2ViewModel，页面状态和事件都通过它流转。
    viewModel: CameraV2ViewModel = viewModel(),
    // 向外部同步拍照动作；为 null 时表示当前页面不提供拍照动作。
    onCaptureActionChanged: (CameraV2CaptureAction?) -> Unit = {}
) {
    // 读取当前 Compose 上下文，后续用于权限检查和查找 Activity。
    val context = LocalContext.current
    // 读取当前设备配置，后续用于判断横竖屏。
    val configuration = LocalConfiguration.current
    // 读取当前显示旋转角度；如果显示对象为空，则默认使用 ROTATION_0。
    val displayRotation = LocalView.current.display?.rotation ?: DisplaySurface.ROTATION_0
    // 生命周期感知地收集 ViewModel 暴露的页面状态。
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 生命周期感知地收集 ONNX 会话加载状态。
    val onnxLoadState by CameraV2OnnxSessionManager.loadState.collectAsStateWithLifecycle()
    // 保存最新的外部拍照动作回调，避免副作用闭包持有旧回调。
    val currentOnCaptureActionChanged by rememberUpdatedState(onCaptureActionChanged)
    // 页面进入组合后允许相机页面按传感器自动旋转。
    EnableCameraAutoRotation(context)
    // 创建相机权限请求启动器。
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        // 指定该启动器只请求单个运行时权限。
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 权限结果返回后，转成 MVI Intent 交给 ViewModel 处理。
        viewModel.onIntent(CameraV2Intent.CameraPermissionResult(granted))
    }
    // 创建旧版外部存储写入权限请求启动器。
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        // 指定该启动器只请求单个运行时权限。
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 权限结果返回后，转成旧版存储权限结果 Intent。
        viewModel.onIntent(CameraV2Intent.LegacyStoragePermissionResult(granted))
    }

    // 定义局部函数，用于检查相机权限是否已经授予。
    fun hasCameraPermission(): Boolean =
        // 调用兼容库检查 CAMERA 权限，并和已授权常量比较。
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // 定义局部函数，用于判断拍照保存前是否还需要旧版写存储权限。
    fun needsLegacyStoragePermission(): Boolean =
        // Android 9 及以下才需要 WRITE_EXTERNAL_STORAGE。
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            // 检查旧版写外部存储权限。
            ContextCompat.checkSelfPermission(
                // 使用当前上下文做权限检查。
                context,
                // 指定检查 WRITE_EXTERNAL_STORAGE 权限。
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            // 如果权限不是已授予，就表示需要申请。
            ) != PackageManager.PERMISSION_GRANTED

    // 当身高或体重参数变化时，通知 ViewModel 页面已进入或资料已更新。
    LaunchedEffect(heightCm, weightKg) {
        // 向 ViewModel 发送 Entered Intent。
        viewModel.onIntent(
            // 构造进入页面事件，携带权限和身体资料。
            CameraV2Intent.Entered(
                // 把当前相机权限状态作为初始条件交给状态机。
                hasCameraPermission = hasCameraPermission(),
                // 传入当前身高。
                heightCm = heightCm,
                // 传入当前体重。
                weightKg = weightKg
            )
        )
    }

    // 当 ONNX 加载状态变化时，同步给 ViewModel。
    LaunchedEffect(onnxLoadState) {
        // ViewModel 根据加载状态决定页面提示和可用状态。
        viewModel.onIntent(CameraV2Intent.OnnxLoadStateChanged(onnxLoadState))
    }

    // 在当前 ViewModel 生命周期内收集一次性 Effect。
    LaunchedEffect(viewModel) {
        // 持续收集 ViewModel 发出的副作用事件。
        viewModel.effects.collect { effect ->
            // 根据副作用类型执行对应平台动作。
            when (effect) {
                // 请求相机权限时，启动 CAMERA 权限弹窗。
                CameraV2Effect.RequestCameraPermission -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                // 请求旧版存储权限时，进入单独代码块启动权限弹窗。
                CameraV2Effect.RequestLegacyStoragePermission -> {
                    // 启动 WRITE_EXTERNAL_STORAGE 权限弹窗。
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    // 当页面状态或拍照状态变化时，向外部同步拍照按钮动作。
    LaunchedEffect(state.screenState, state.captureState) {
        // 只有正在预览流时，外部拍照按钮才有可执行动作。
        val captureAction = if (state.screenState == CameraV2ScreenState.Streaming) {
            // 构造一个包含点击行为和可用状态的拍照动作。
            CameraV2CaptureAction(
                // 点击时把拍照请求转成 Intent。
                onClick = {
                    // 同时告知 ViewModel 当前是否需要先申请旧版存储权限。
                    viewModel.onIntent(CameraV2Intent.CapturePressed(needsLegacyStoragePermission()))
                },
                // 保存中禁用按钮，避免重复触发拍照保存。
                enabled = state.captureState !is PhotoV2CaptureState.Saving
            )
        } else {
            // 非预览状态下清空外部拍照动作。
            null
        }
        // 把最新拍照动作同步给外部宿主。
        currentOnCaptureActionChanged(captureAction)
    }

    // 在该 Composable 离开组合时清理外部拍照动作。
    DisposableEffect(Unit) {
        // 页面销毁或退出时通知外部不再显示/使用拍照动作。
        onDispose { currentOnCaptureActionChanged(null) }
    }

    // 渲染 Camera v2 的纯 UI 内容。
    return CameraV2ScreenContent(
        // 传入当前页面状态。
        state = state,
        // 传入权限按钮点击逻辑。
        onRequestPermission = {
            // 点击权限按钮时，把当前权限状态一并交给 ViewModel。
            viewModel.onIntent(CameraV2Intent.PermissionButtonClicked(hasCameraPermission()))
        },
        // 直接把 UI 子组件产生的 Intent 转发给 ViewModel。
        onIntent = viewModel::onIntent,
        // 根据系统配置判断当前是否横屏。
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        // 把显示旋转角度传给相机预览。
        targetRotation = displayRotation,
        // 应用外部传入的修饰符。
        modifier = modifier
    )
}

// 标记 CameraV2ScreenContent 是 Compose UI 函数。
@Composable
// 定义无 ViewModel 依赖的内容层，便于预览和测试不同状态。
internal fun CameraV2ScreenContent(
    // 当前页面完整状态。
    state: CameraV2State,
    // 权限申请按钮点击回调。
    onRequestPermission: () -> Unit,
    // 子组件向上发送业务 Intent 的回调。
    onIntent: (CameraV2Intent) -> Unit,
    // 外部布局修饰符。
    modifier: Modifier = Modifier,
    // 是否真实显示相机预览；预览模式下可以关闭。
    showCameraPreview: Boolean = true,
    // 当前是否横屏。
    isLandscape: Boolean = false,
    // 相机目标旋转角度，默认竖屏正向。
    targetRotation: Int = DisplaySurface.ROTATION_0
) {
    // 使用 Box 作为全屏叠层容器。
    Box(
        // 应用外部修饰符并设置全屏黑底。
        modifier = modifier
            // 填满父布局。
            .fillMaxSize()
            // 设置相机页面背景为黑色。
            .background(Color.Black),
        // 默认把非显式对齐的内容放在中心。
        contentAlignment = Alignment.Center
    ) {
        // 根据页面状态选择要展示的 UI 分支。
        when (val screenState = state.screenState) {
            // 启动中状态展示加载消息。
            CameraV2ScreenState.Booting -> CameraV2Message(
                // 使用相机页面标题。
                title = stringResource(R.string.camera_v2_title),
                // 根据 ONNX 是否仍在加载选择提示文案。
                message = stringResource(
                    // ONNX 模型加载中时优先展示模型加载提示。
                    if (state.onnxLoadState == OnnxSessionLoadState.LOADING) {
                        // 返回 ONNX 加载中的字符串资源。
                        R.string.camera_v2_hint_onnx_loading
                    } else {
                        // 返回普通启动中的字符串资源。
                        R.string.camera_v2_booting_message
                    }
                ),
                // 启动中显示进度圈。
                showProgress = true
            )

            // 缺少相机权限时展示权限说明和操作按钮。
            CameraV2ScreenState.PermissionRequired -> CameraV2Message(
                // 使用相机页面标题。
                title = stringResource(R.string.camera_v2_title),
                // 使用权限说明文案。
                message = stringResource(R.string.camera_v2_permission_message),
                // 使用权限按钮文案。
                actionText = stringResource(R.string.camera_v2_permission_action),
                // 点击按钮时触发外部权限请求逻辑。
                onAction = onRequestPermission
            )

            // 正在相机预览流中时展示预览、引导层和拍照状态。
            CameraV2ScreenState.Streaming -> {
                // 如果允许显示相机预览，则挂载真实预览组件。
                if (showCameraPreview) {
                    // 渲染相机预览和拍照保存管线。
                    CameraV2Preview(
                        // 传入身体资料，用于引导或保存时的元数据。
                        bodyProfile = state.bodyProfile,
                        // 传入拍照 token，token 变化通常用于触发一次拍照。
                        captureToken = state.captureToken,
                        // 预览组件产生引导结果时，上报 GuideProduced Intent。
                        onGuide = { onIntent(CameraV2Intent.GuideProduced(it)) },
                        // 照片保存成功时，上报 PhotoSaved Intent。
                        onPhotoSaved = { onIntent(CameraV2Intent.PhotoSaved) },
                        // 照片保存失败时，上报 PhotoSaveFailed Intent 并携带错误。
                        onPhotoError = { onIntent(CameraV2Intent.PhotoSaveFailed(it)) },
                        // 相机出现错误时，上报 CameraIssue Intent。
                        onCameraError = { onIntent(CameraV2Intent.CameraIssue(it)) },
                        // 传入目标旋转角度，保证相机输出方向正确。
                        targetRotation = targetRotation,
                        // 预览铺满整个页面。
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 在相机预览之上绘制检测引导覆盖层。
                CameraV2Overlay(
                    // 传入当前引导数据。
                    guide = state.guide,
                    // 传入经过 displayHint 处理后的提示。
                    hint = state.displayHint(),
                    // 传入横屏状态，让覆盖层调整布局。
                    isLandscape = isLandscape,
                    // 覆盖层铺满整个页面。
                    modifier = Modifier.fillMaxSize()
                )
                // 展示身体资料和拍照保存状态。
                CameraV2CaptureStatus(
                    // 传入当前拍照状态。
                    captureState = state.captureState,
                    // 传入身体资料。
                    bodyProfile = state.bodyProfile,
                    // 传入横屏状态。
                    isLandscape = isLandscape,
                    // 根据横竖屏决定状态栏位置。
                    modifier = if (isLandscape) {
                        // 横屏时把状态栏放到右侧中间。
                        Modifier
                            // 在 Box 内对齐到右侧居中。
                            .align(Alignment.CenterEnd)
                            // 与右边缘保持 16dp 距离。
                            .padding(end = 16.dp)
                    } else {
                        // 竖屏时把状态栏放到底部居中。
                        Modifier.align(Alignment.BottomCenter)
                    }
                )
            }

            // 相机或流程失败时展示错误消息。
            is CameraV2ScreenState.Failure -> CameraV2Message(
                // 使用相机错误标题。
                title = stringResource(R.string.camera_v2_camera_error_title),
                // 优先展示具体错误详情；没有详情时展示 hint 对应的资源文案。
                message = screenState.detail ?: stringResource(screenState.hint.messageRes),
                // 复用权限按钮文案作为重试/处理入口文案。
                actionText = stringResource(R.string.camera_v2_permission_action),
                // 点击按钮时触发权限/重试入口。
                onAction = onRequestPermission
            )
        }
    }
}

// 定义 CameraV2State 的展示提示扩展函数。
private fun CameraV2State.displayHint(): CameraV2Hint =
    // 当相机刚启动且 ONNX 仍在加载时，用模型加载提示覆盖普通相机启动提示。
    if (
        // 当前引导提示为相机启动中。
        guide.hint == CameraV2Hint.CAMERA_STARTING &&
            // 同时 ONNX 会话仍处于加载状态。
            onnxLoadState == OnnxSessionLoadState.LOADING
    ) {
        // 返回 ONNX 加载提示，让用户知道等待原因是模型加载。
        CameraV2Hint.ONNX_LOADING
    } else {
        // 其他情况下直接展示引导层给出的原始提示。
        guide.hint
    }

// 标记 CameraV2CaptureStatus 是 Compose UI 函数。
@Composable
// 定义拍照状态栏，显示身体资料以及保存成功/失败/进行中状态。
private fun CameraV2CaptureStatus(
    // 当前拍照状态。
    captureState: PhotoV2CaptureState,
    // 当前身体资料。
    bodyProfile: BodyProfile,
    // 当前是否横屏。
    isLandscape: Boolean,
    // 外部布局修饰符。
    modifier: Modifier = Modifier
) {
    // 使用 Column 纵向排列身体资料和状态文案。
    Column(
        // 合并外部修饰符和本组件自己的布局背景。
        modifier = modifier.then(
            // 横屏时只加水平内边距；竖屏时填满宽度形成底部状态条。
            if (isLandscape) Modifier.padding(horizontal = 8.dp) else Modifier.fillMaxWidth()
        )
            // 使用半透明黑色背景，避免遮挡预览但保证文字可读。
            .background(Color.Black.copy(alpha = 0.30f))
            // 设置状态栏内部留白。
            .padding(horizontal = 22.dp, vertical = 12.dp),
        // 子项水平居中。
        horizontalAlignment = Alignment.CenterHorizontally,
        // 子项之间保持 8dp 间距。
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 显示身高和体重信息。
        Text(
            // 从字符串资源格式化身体资料。
            text = stringResource(
                // 字符串资源里包含身高和体重占位符。
                R.string.camera_v2_body_profile,
                // 使用安全处理后的身高值。
                bodyProfile.safeHeightCm,
                // 使用安全处理后的体重值。
                bodyProfile.safeWeightKg
            ),
            // 使用较小标签字体。
            style = MaterialTheme.typography.labelMedium,
            // 使用半透明白色弱化资料展示。
            color = Color.White.copy(alpha = 0.82f)
        )
        // 根据拍照状态决定是否显示额外状态文案。
        when (captureState) {
            // 空闲状态不显示额外内容。
            PhotoV2CaptureState.Idle -> Unit
            // 保存中状态显示保存中文案。
            PhotoV2CaptureState.Saving -> Text(
                // 读取保存中的字符串资源。
                text = stringResource(R.string.camera_v2_capture_saving),
                // 保存中使用白色文字。
                color = Color.White,
                // 使用正文中号字体。
                style = MaterialTheme.typography.bodyMedium
            )
            // 保存成功状态显示成功文案。
            PhotoV2CaptureState.Saved -> Text(
                // 读取保存成功的字符串资源。
                text = stringResource(R.string.camera_v2_capture_saved),
                // 成功状态使用模块定义的正向颜色。
                color = colorResource(R.color.camera_v2_good),
                // 使用正文中号字体。
                style = MaterialTheme.typography.bodyMedium
            )
            // 保存失败状态显示错误提示。
            is PhotoV2CaptureState.Error -> Text(
                // 根据错误携带的 hint 读取对应文案。
                text = stringResource(captureState.hint.messageRes),
                // 错误状态使用模块定义的负向颜色。
                color = colorResource(R.color.camera_v2_poor),
                // 使用正文中号字体。
                style = MaterialTheme.typography.bodyMedium,
                // 错误文案居中显示。
                textAlign = TextAlign.Center
            )
        }
    }
}

// 标记 EnableCameraAutoRotation 是 Compose 副作用函数。
@Composable
// 定义相机页面的自动旋转开关。
private fun EnableCameraAutoRotation(context: Context) {
    // 从 Context 中查找宿主 Activity；找不到就直接结束。
    val activity = context.findActivity() ?: return
    // 当 Activity 进入组合时设置方向，离开组合时恢复。
    DisposableEffect(activity) {
        // 记录进入页面前的屏幕方向设置。
        val previousOrientation = activity.requestedOrientation
        // 临时允许根据所有传感器方向旋转，适配相机横竖屏使用。
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        // 注册清理逻辑。
        onDispose {
            // 只有当前仍是本函数设置的方向时才恢复，避免覆盖外部后续修改。
            if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR) {
                // 恢复进入页面前的方向设置。
                activity.requestedOrientation = previousOrientation
            }
        }
    }
}

// 定义从 Context 递归查找 Activity 的尾递归扩展函数。
private tailrec fun Context.findActivity(): Activity? =
    // 根据当前 Context 实际类型决定如何查找。
    when (this) {
        // 如果当前 Context 本身就是 Activity，直接返回。
        is Activity -> this
        // 如果当前 Context 是包装器，则继续检查它的 baseContext。
        is ContextWrapper -> baseContext.findActivity()
        // 其他类型无法取得 Activity，返回 null。
        else -> null
    }

// 标记 CameraV2Message 是 Compose UI 函数。
@Composable
// 定义通用消息面板，用于启动中、权限缺失和错误状态。
private fun CameraV2Message(
    // 面板标题。
    title: String,
    // 面板正文。
    message: String,
    // 外部布局修饰符。
    modifier: Modifier = Modifier,
    // 是否显示加载进度圈。
    showProgress: Boolean = false,
    // 可选按钮文字；为空则不显示按钮。
    actionText: String? = null,
    // 可选按钮点击回调；为空则不显示按钮。
    onAction: (() -> Unit)? = null
) {
    // 使用 Surface 承载消息面板的颜色和形状。
    Surface(
        // 面板填满可用宽度，并与屏幕左右保持距离。
        modifier = modifier
            // 填满父容器宽度。
            .fillMaxWidth()
            // 左右各留 28dp。
            .padding(horizontal = 28.dp),
        // 面板背景为黑色。
        color = Color.Black,
        // 默认内容颜色为白色。
        contentColor = Color.White,
        // 面板使用 8dp 圆角。
        shape = RoundedCornerShape(8.dp)
    ) {
        // 使用 Column 纵向排列进度、标题、正文和按钮。
        Column(
            // 设置面板内部留白。
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
            // 子项水平居中。
            horizontalAlignment = Alignment.CenterHorizontally,
            // 子项之间保持 14dp 间距。
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 根据参数决定是否显示加载进度。
            if (showProgress) {
                // 显示白色圆形加载指示器。
                CircularProgressIndicator(color = Color.White)
            }
            // 显示标题文本。
            Text(
                // 标题内容来自参数。
                text = title,
                // 标题使用小号 headline 样式。
                style = MaterialTheme.typography.headlineSmall,
                // 标题居中对齐。
                textAlign = TextAlign.Center
            )
            // 显示正文文本。
            Text(
                // 正文内容来自参数。
                text = message,
                // 正文使用半透明白色。
                color = Color.White.copy(alpha = 0.82f),
                // 正文使用 bodyMedium 样式。
                style = MaterialTheme.typography.bodyMedium,
                // 正文居中对齐。
                textAlign = TextAlign.Center
            )
            // 只有按钮文字和点击回调都存在时才显示按钮。
            if (actionText != null && onAction != null) {
                // 创建操作按钮，点击后调用 onAction。
                Button(onClick = onAction) {
                    // 按钮内显示传入的操作文案。
                    Text(actionText)
                }
            }
        }
    }
}

// 声明该函数用于 Compose 预览，并显示背景。
@Preview(showBackground = true)
// 标记预览函数是 Compose UI。
@Composable
// 定义 Camera v2 流式状态的预览。
private fun CameraV2ScreenPreview() {
    // 使用应用主题包裹预览内容。
    MyApplicationTheme {
        // 渲染无真实相机预览的 CameraV2ScreenContent。
        CameraV2ScreenContent(
            // 构造预览用状态。
            state = CameraV2State(
                // 预览展示 Streaming 分支。
                screenState = CameraV2ScreenState.Streaming,
                // 使用初始引导数据。
                guide = CameraV2Guide.initial()
            ),
            // 预览中权限按钮不执行任何操作。
            onRequestPermission = {},
            // 预览中业务 Intent 不执行任何操作。
            onIntent = {},
            // 关闭真实相机预览，避免预览依赖设备相机。
            showCameraPreview = false
        )
    }
}
