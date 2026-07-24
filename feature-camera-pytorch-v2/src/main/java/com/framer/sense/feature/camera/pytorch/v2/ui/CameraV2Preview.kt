package com.framer.sense.feature.camera.pytorch.v2.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun CameraV2Preview(
    // 当前用户身体资料，分析器会用身高体重计算引导框和姿态提示。
    bodyProfile: BodyProfile,
    // 拍照触发令牌；每次外部想拍照时递增该值。
    captureToken: Int,
    // 输出实时引导结果，例如人体框、提示文案、姿态质量。
    onGuide: (CameraV2Guide) -> Unit,
    // 照片保存成功后把 Uri 回传给上层状态机。
    onPhotoSaved: (Uri) -> Unit,
    // 照片保存失败时把异常回传给上层状态机。
    onPhotoError: (Throwable) -> Unit,
    // 相机绑定、预览或帧分析出错时回传异常。
    onCameraError: (Throwable) -> Unit,
    // CameraX 输出目标旋转角度，默认按设备自然竖屏处理。
    targetRotation: Int = Surface.ROTATION_0,
    // 外部传入的 Compose 布局修饰符。
    modifier: Modifier = Modifier
) {
    // 记录已经处理过的最后一个拍照令牌，避免重组时重复保存同一张照片。
    var lastHandledCaptureToken by rememberSaveable { mutableIntStateOf(0) }
    // 身高或体重变化时重建内部预览实例，让帧分析器使用新的身体资料。
    key(bodyProfile.safeHeightCm, bodyProfile.safeWeightKg) {
        // 真正承载 CameraX 预览、分析和拍照逻辑的内部组件。
        CameraV2PreviewInstance(
            // 传入当前身体资料。
            bodyProfile = bodyProfile,
            // 传入当前拍照令牌。
            captureToken = captureToken,
            // 传入引导回调。
            onGuide = onGuide,
            // 传入保存成功回调。
            onPhotoSaved = onPhotoSaved,
            // 传入保存失败回调。
            onPhotoError = onPhotoError,
            // 传入相机错误回调。
            onCameraError = onCameraError,
            // 传入上次已处理的拍照令牌。
            lastHandledCaptureToken = lastHandledCaptureToken,
            // 内部完成拍照触发处理后，更新外层记录。
            onCaptureTokenHandled = { lastHandledCaptureToken = it },
            // 传入目标旋转角度。
            targetRotation = targetRotation,
            // 传入布局修饰符。
            modifier = modifier
        )
    }
}

@Composable
private fun CameraV2PreviewInstance(
    // 当前身体资料，主要交给帧分析器使用。
    bodyProfile: BodyProfile,
    // 当前拍照令牌。
    captureToken: Int,
    // 引导结果回调。
    onGuide: (CameraV2Guide) -> Unit,
    // 保存成功回调。
    onPhotoSaved: (Uri) -> Unit,
    // 保存失败回调。
    onPhotoError: (Throwable) -> Unit,
    // 相机错误回调。
    onCameraError: (Throwable) -> Unit,
    // 外层记录的最后已处理拍照令牌。
    lastHandledCaptureToken: Int,
    // 通知外层某个拍照令牌已处理。
    onCaptureTokenHandled: (Int) -> Unit,
    // 当前相机输出目标旋转角度。
    targetRotation: Int,
    // 外部传入的布局修饰符。
    modifier: Modifier = Modifier
) {
    // 读取当前 Context，用于创建 PreviewView、获取 CameraProvider、访问 MediaStore。
    val context = LocalContext.current
    // 读取当前 LifecycleOwner，CameraX 会跟随它自动管理相机生命周期。
    val lifecycleOwner = LocalLifecycleOwner.current
    // 保存最新的引导回调，避免异步分析线程持有旧 lambda。
    val currentOnGuide by rememberUpdatedState(onGuide)
    // 保存最新的保存成功回调。
    val currentOnPhotoSaved by rememberUpdatedState(onPhotoSaved)
    // 保存最新的保存失败回调。
    val currentOnPhotoError by rememberUpdatedState(onPhotoError)
    // 保存最新的相机错误回调。
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    // 创建并记住 Android 原生 PreviewView，作为 CameraX 预览画面的承载视图。
    val previewView = remember {
        // 用当前 Context 构造 PreviewView。
        PreviewView(context).apply {
            // 使用兼容模式，优先保证不同设备上预览稳定显示。
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // 预览按中心裁剪填充，保证画面铺满容器。
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // 创建并记住 ImageCapture；旋转角度变化时重建，确保拍照方向正确。
    val imageCapture = remember(targetRotation) {
        // 开始构建 CameraX 拍照用例。
        ImageCapture.Builder()
            // 使用低延迟模式，优先缩短按下拍照到输出照片的时间。
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // 设置照片输出目标方向。
            .setTargetRotation(targetRotation)
            // 构建 ImageCapture 实例。
            .build()
    }
    // 创建单线程分析执行器，保证帧分析按顺序执行，避免并发处理多帧。
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // 获取主线程执行器，用于把分析结果和 CameraX 回调安全切回主线程。
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    // 创建并记住帧分析器；身体资料变化时重建，使用新的身高体重参数。
    val analyzer = remember(bodyProfile.safeHeightCm, bodyProfile.safeWeightKg) {
        // 构造相机帧分析器，内部负责模型推理/姿态判断并生成引导。
        CameraV2FrameAnalyzer(
            // 分析器需要 Context 访问资源或初始化底层能力。
            context = context,
            // 分析器根据身体资料调整引导判断。
            bodyProfile = bodyProfile,
            // 回调切回主线程执行，便于更新 Compose 状态。
            callbackExecutor = mainExecutor,
            // 分析成功时把引导结果传给当前最新回调。
            onGuide = { currentOnGuide(it) },
            // 分析失败时把错误传给当前最新错误回调。
            onError = { currentOnCameraError(it) }
        )
    }

    // 把 Android 原生 PreviewView 嵌入 Compose UI 树。
    AndroidView(
        // factory 返回之前创建的 PreviewView。
        factory = { previewView },
        // 应用外部布局修饰符。
        modifier = modifier
    )

    // 当相机依赖对象变化时，重新绑定 CameraX 用例。
    LaunchedEffect(context, lifecycleOwner, previewView, analyzer, imageCapture, targetRotation) {
        // 捕获相机初始化或绑定期间的异常。
        try {
            // 异步等待 ProcessCameraProvider 可用。
            val provider = context.awaitCameraProvider()
            // 创建预览用例。
            val preview = Preview.Builder()
                // 设置预览目标旋转角度。
                .setTargetRotation(targetRotation)
                // 构建 Preview 用例。
                .build()
                // 把 Preview 的画面输出绑定到 PreviewView。
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // 创建图像分析用例。
            val imageAnalysis = ImageAnalysis.Builder()
                // 分析来不及时只保留最新帧，避免旧帧堆积导致引导滞后。
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // 设置分析帧的目标旋转角度。
                .setTargetRotation(targetRotation)
                // 构建 ImageAnalysis 用例。
                .build()
                // 指定分析线程和分析器。
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            // 先解绑旧用例，避免重复绑定或用例冲突。
            provider.unbindAll()
            // 将预览、拍照和分析三个用例绑定到当前生命周期。
            provider.bindToLifecycle(
                // 使用 Compose 当前宿主生命周期。
                lifecycleOwner,
                // 选择默认后置摄像头。
                CameraSelector.DEFAULT_BACK_CAMERA,
                // 绑定预览用例。
                preview,
                // 绑定拍照用例。
                imageCapture,
                // 绑定图像分析用例。
                imageAnalysis
            )
        // 捕获所有绑定阶段异常。
        } catch (throwable: Throwable) {
            // 通知上层相机不可用或初始化失败。
            currentOnCameraError(throwable)
        }
    }

    // 当拍照令牌变化时，检查是否需要触发一次保存。
    LaunchedEffect(captureToken) {
        // 只有新令牌大于已处理令牌才执行，避免重组重复拍照。
        if (captureToken > lastHandledCaptureToken) {
            // 先标记该令牌已处理，避免保存过程中重组造成重复触发。
            onCaptureTokenHandled(captureToken)
            // 调用 ImageCapture 扩展函数，把照片保存到系统相册。
            imageCapture.saveToGallery(
                // 传入 Context 以访问 ContentResolver。
                context = context,
                // 保存成功时使用最新成功回调。
                onSaved = currentOnPhotoSaved,
                // 保存失败时使用最新错误回调。
                onError = currentOnPhotoError
            )
        }
    }

    // 当组件离开组合时释放分析器、线程和相机绑定。
    DisposableEffect(context, analyzer) {
        // 注册清理逻辑。
        onDispose {
            // 关闭帧分析器，释放模型或图像处理资源。
            analyzer.close()
            // 关闭分析线程，避免线程泄漏。
            analysisExecutor.shutdown()
            // 重新获取 CameraProvider，用于解绑相机用例。
            val providerFuture = ProcessCameraProvider.getInstance(context)
            // CameraProvider 可用后执行解绑。
            providerFuture.addListener(
                // 解绑所有 CameraX 用例，释放摄像头。
                { providerFuture.get().unbindAll() },
                // 解绑操作放到主线程执行。
                ContextCompat.getMainExecutor(context)
            )
        }
    }
}

// 定义 ImageCapture 扩展函数，把当前相机帧拍成照片并保存到系统相册。
private fun ImageCapture.saveToGallery(
    // 用于访问 ContentResolver 和系统资源。
    context: Context,
    // 保存成功后返回图片 Uri。
    onSaved: (Uri) -> Unit,
    // 保存失败后返回异常。
    onError: (Throwable) -> Unit
) {
    // 构造写入 MediaStore 的图片元数据。
    val values = ContentValues().apply {
        // 设置图片显示名称，使用时间戳避免重名。
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "FramerSenseV2_${System.currentTimeMillis()}")
        // 声明图片 MIME 类型为 JPEG。
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        // Android 10 及以上使用分区存储的相对路径。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 设置图片保存到 Pictures/Framer Sense 目录。
            put(
                // MediaStore 的相对路径字段。
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                // 目标相册目录。
                "${Environment.DIRECTORY_PICTURES}/Framer Sense"
            )
        }
    }
    // 构造 CameraX 输出选项，目标是系统图片 MediaStore。
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        // 使用系统 ContentResolver 写入媒体库。
        context.contentResolver,
        // 指定外部图片集合 Uri。
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        // 附带刚才构造的图片元数据。
        values
    // 完成输出选项构建。
    ).build()

    // 调用 CameraX 拍照并保存。
    takePicture(
        // 指定输出位置和元数据。
        outputOptions,
        // 拍照结果回调在主线程执行。
        ContextCompat.getMainExecutor(context),
        // 实现 CameraX 保存回调。
        object : ImageCapture.OnImageSavedCallback {
            // 保存成功时被 CameraX 调用。
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // 如果 CameraX 返回了 Uri，就回调成功；否则按异常处理。
                outputFileResults.savedUri?.let(onSaved)
                    // 理论上保存成功应有 Uri，没有 Uri 会让上层无法定位照片。
                    ?: onError(IllegalStateException("Photo saved without returned uri"))
            }

            // 保存失败时被 CameraX 调用。
            override fun onError(exception: ImageCaptureException) {
                // 把 CameraX 异常传给上层。
                onError(exception)
            }
        }
    )
}

// 定义挂起函数，把 CameraProvider 的 ListenableFuture 转成协程可等待结果。
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    // 创建可取消挂起点，等待 CameraProvider 初始化完成。
    suspendCancellableCoroutine { continuation ->
        // 获取 CameraProvider 的 Future。
        val future = ProcessCameraProvider.getInstance(this)
        // 给 Future 注册完成监听。
        future.addListener(
            // Future 完成后执行该代码块。
            {
                // 捕获 future.get 或协程恢复期间的异常。
                try {
                    // 成功取得 CameraProvider 后恢复协程。
                    continuation.resume(future.get())
                // 捕获获取失败异常。
                } catch (throwable: Throwable) {
                    // 用异常恢复协程，让调用方进入 catch 分支。
                    continuation.resumeWithException(throwable)
                }
            },
            // Future 监听器在主线程执行。
            ContextCompat.getMainExecutor(this)
        )
        // 如果协程被取消，同时尝试取消底层 Future。
        continuation.invokeOnCancellation { future.cancel(true) }
    }
