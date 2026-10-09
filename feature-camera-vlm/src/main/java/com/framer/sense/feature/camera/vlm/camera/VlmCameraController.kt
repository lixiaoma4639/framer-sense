package com.framer.sense.feature.camera.vlm.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.util.Rational
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.AnalysisSample
import com.framer.sense.feature.camera.vlm.scan.BackgroundMatchPolicy
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 只负责平台相机资源；UI 销毁时必须解绑，不持有 ViewModel。 */
class VlmCameraController(private val context: Context) {
    companion object {
        // Tab／Activity 重建不能绕过上一控制器尚未完成的 CameraDevice.onClosed。
        @Volatile private var retiringAr: com.framer.sense.feature.camera.vlm.ar.ArSharedCameraController? = null
    }
    // 相机所有权互斥：AR 回退只能在 CameraDevice.onClosed 后清除这个引用。
    var ar: com.framer.sense.feature.camera.vlm.ar.ArSharedCameraController? = null
        private set
    suspend fun releaseForAr() {
        val info = camera?.cameraInfo ?: closingCameraInfo
        unbind()
        releaseForNormal()
        if (info == null) return
        kotlinx.coroutines.withTimeout(5_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val observer = object : androidx.lifecycle.Observer<CameraState> {
                    override fun onChanged(value: CameraState) {
                        if (value.type == CameraState.Type.CLOSED) {
                            info.cameraState.removeObserver(this)
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                info.cameraState.observeForever(observer)
                continuation.invokeOnCancellation { executor.execute { info.cameraState.removeObserver(observer) } }
            }
        }
    }
    private var closingCameraInfo: CameraInfo? = null
    fun installAr(value: com.framer.sense.feature.camera.vlm.ar.ArSharedCameraController) { check(ar == null && camera == null); ar = value }
    private val ownershipScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    fun removeAr(value: com.framer.sense.feature.camera.vlm.ar.ArSharedCameraController) {
        if (ar === value) ownershipScope.launch { value.closeAndAwait(); if (ar === value) ar = null }
    }
    suspend fun releaseForNormal() {
        val previous = ar ?: retiringAr ?: return
        previous.closeAndAwait()
        if (ar === previous) ar = null
        if (retiringAr === previous) retiringAr = null
    }
    fun cameraForeground(visible: Boolean) { ar?.foreground(visible) }
    private var bindingEpoch = 0
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val poses = CameraPoseTracker(context)
    fun foreground(visible: Boolean) { if (visible) poses.start() else poses.stop() }
    fun close() {
        ar?.let { retiringAr = it; it.close() }; ar = null
        unbind(); poses.stop(); analysisExecutor.shutdown()
    }
    private var zoomOwner: LifecycleOwner? = null
    private var zoomOperation: ListenableFuture<Void>? = null
    private val executor = ContextCompat.getMainExecutor(context)

    /** 绑定同一视口的预览与拍照。
     * @param view 已完成布局的预览控件。
     * @param owner 当前 UI 生命周期。
     * @param initialZoom 恢复方案时需要设置的倍率。
     * @return 当前设备允许的变焦范围和常用倍率。
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    suspend fun bind(view: PreviewView, owner: LifecycleOwner, initialZoom: Float,
        targetId: String? = null, scanStartedNs: Long? = null,
        sampling: () -> Boolean = { true },
        onConfigured: (Int) -> Unit = {}, onSample: (AnalysisSample) -> Unit = {}, onZoom: (Float) -> Unit = {}): CameraCapabilities {
        releaseForNormal()
        check(ar == null) { "AR_CAMERA_OWNS_DEVICE" }
        val bindStarted = SystemClock.elapsedRealtime()
        android.util.Log.i("VlmCamera", "CameraX 绑定开始 view=${view.width}x${view.height} rotation=${view.display?.rotation} target=${targetId?.take(16)} zoom=$initialZoom")
        val epoch = ++bindingEpoch
        val future = ProcessCameraProvider.getInstance(context)
        val instance = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get()) } catch (e: Exception) { continuation.resumeWithException(e) }
                }
            }, executor)
        }
        if (epoch != bindingEpoch || ar != null) throw kotlinx.coroutines.CancellationException("旧相机绑定已结束")
        unbind(invalidatePending = false)
        check(view.width > 0 && view.height > 0) { "相机预览尚未完成布局" }
        val rotation = view.display.rotation
        val p = Preview.Builder().setTargetRotation(rotation).build()
        val c = ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        p.setSurfaceProvider(view.surfaceProvider)
        val version = com.framer.sense.feature.camera.vlm.camera.CameraConfigVersions.next()
        fun bindGroup(resolution: android.util.Size) {
            val a = if (targetId != null) ImageAnalysis.Builder().setTargetRotation(rotation)
                .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder().setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(resolution, androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() else null
            val group = UseCaseGroup.Builder().setViewPort(ViewPort.Builder(Rational(view.width, view.height), rotation)
                .setScaleType(ViewPort.FILL_CENTER).build()).addUseCase(p).addUseCase(c)
            a?.let { group.addUseCase(it) }
            provider = instance; preview = p; capture = c; analysis = a
            camera = instance.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
        }
        try { bindGroup(android.util.Size(1280, 960)) }
        catch (failure: IllegalArgumentException) {
            if (targetId == null) throw failure
            unbind(invalidatePending = false); bindGroup(android.util.Size(640, 480))
        }
        zoomOwner = owner
        camera!!.cameraInfo.zoomState.observe(owner) { current ->
            if (epoch == bindingEpoch && ar == null) onZoom(current.zoomRatio)
        }
        val state = camera!!.cameraInfo.zoomState.value
        val minimum = state?.minZoomRatio ?: 1f
        val maximum = state?.maxZoomRatio ?: 1f
        zoomOperation = camera!!.cameraControl.setZoomRatio(initialZoom.coerceIn(minimum, maximum))
        awaitZoom()
        if (epoch != bindingEpoch || ar != null) throw kotlinx.coroutines.CancellationException("旧相机绑定已结束")
        android.util.Log.i("VlmCamera", "CameraX 绑定完成 config=$version elapsedMs=${SystemClock.elapsedRealtime()-bindStarted} zoomRange=$minimum..$maximum analysis=${analysis != null}")
        onConfigured(version)
        if (targetId != null) {
            val realtime = Camera2CameraInfo.from(camera!!.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            var lastSampleNs = 0L
            var lastLogNs = 0L
            var samplesCopied = 0
            // 新建的分析用例只接收开始后建立的流；未知时钟禁止跨传感器比较。
            analysis?.setAnalyzer(analysisExecutor) { image ->
                var sample: AnalysisSample? = null
                try {
                    val received = SystemClock.elapsedRealtimeNanos()
                    if (!sampling() || received - lastSampleNs < if (scanStartedNs != null) ScanLimits.SAMPLE_INTERVAL_NS else BackgroundMatchPolicy.ANALYSIS_INTERVAL_NS) return@setAnalyzer
                    val captured = image.imageInfo.timestamp
                    if (scanStartedNs != null && realtime && captured < scanStartedNs) return@setAnalyzer
                    lastSampleNs = received
                    val w = image.width; val h = image.height
                    val bytes = Yuv420Copy.toNv21(w, h, image.planes.map { Yuv420Copy.Plane(it.buffer, it.rowStride, it.pixelStride) })
                    val r = image.cropRect
                    val config = ScanCameraConfig(version, image.imageInfo.rotationDegrees, camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: initialZoom,
                        CropRect(r.left.toFloat() / w, r.top.toFloat() / h, r.right.toFloat() / w, r.bottom.toFloat() / h), realtime)
                    sample = AnalysisSample(targetId, captured, received, config, if (realtime) poses.at(captured) else null, w, h, bytes)
                } catch (failure: Exception) {
                    android.util.Log.w("VlmCamera", "分析帧复制失败", failure)
                } finally { image.close() }
                sample?.let { copied ->
                    samplesCopied++
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (now-lastLogNs >= 2_000_000_000L) {
                        android.util.Log.i("VlmCamera", "CameraX 分析采样 count=$samplesCopied size=${copied.width}x${copied.height} config=$version clockReliable=$realtime")
                        lastLogNs = now; samplesCopied = 0
                    }
                    onSample(copied)
                }
            }
        }
        return CameraCapabilities(minimum, maximum, CameraZoomPolicy.presets(minimum, maximum))
    }

    private var pendingZoom: Float? = null
    private var pendingZoomView: android.view.View? = null
    private var pendingZoomTask: Runnable? = null

    /** 高频手势只保留最后值，每个显示帧最多一次提交。 */
    fun queueZoom(view: android.view.View, ratio: Float) {
        pendingZoom = ratio
        if (pendingZoomTask != null) return
        pendingZoomView = view
        val task = Runnable { flushZoom() }
        pendingZoomTask = task
        view.postOnAnimation(task)
    }
    private fun flushZoom(requested: Float? = null) {
        val value = requested ?: pendingZoom
        cancelQueuedZoom()
        if (value != null) zoom(value)
    }
    private fun cancelQueuedZoom() {
        pendingZoomTask?.let { pendingZoomView?.removeCallbacks(it) }
        pendingZoomTask = null; pendingZoomView = null; pendingZoom = null
    }

    /** 设置支持范围内的倍率。
     * @param ratio 界面请求的倍率。
     */
    fun zoom(ratio: Float) {
        val active = camera ?: return
        val limits = active.cameraInfo.zoomState.value ?: return
        zoomOperation = active.cameraControl.setZoomRatio(ratio.coerceIn(limits.minZoomRatio, limits.maxZoomRatio))
    }

    /** 等待最近的倍率设置反映到相机请求；无参数，不取消共享 CameraControl 操作。 */
    private suspend fun awaitZoom() {
        val operation = zoomOperation ?: return
        suspendCancellableCoroutine<Unit> { continuation ->
            operation.addListener({
                if (continuation.isActive) {
                    try { operation.get(); continuation.resume(Unit) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }, executor)
        }
    }

    /** 对原始相机内容点击对焦和测光。
     * @param view 提供坐标映射的预览控件。
     * @param x 点击横坐标，单位像素。
     * @param y 点击纵坐标，单位像素。
     */
    fun focus(view: PreviewView, x: Float, y: Float) {
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /** 拍摄一帧供冻结，不写入系统相册；无参数。
     * @return 已按预览裁剪并转正的独立 Bitmap 与拍摄倍率，由调用方回收 Bitmap。
     */
    suspend fun freeze(requestedZoom: Float? = null): Pair<Bitmap, Float> {
        ar?.let { return it.freeze() }
        val active = capture ?: error("相机尚未准备完成")
        flushZoom(requestedZoom)
        awaitZoom()
        val ratio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        return suspendCancellableCoroutine { continuation ->
            active.takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
                /** 复制并关闭相机帧。@param image 本次 JPEG 帧，所有路径都必须关闭。 */
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        if (!continuation.isActive) return
                        val decoded = image.toBitmap()
                        val rect = image.cropRect
                        val cropped = Bitmap.createBitmap(decoded, rect.left, rect.top, rect.width(), rect.height())
                        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                        val upright = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
                        if (cropped !== decoded && cropped !== upright) cropped.recycle()
                        if (decoded !== upright) decoded.recycle()
                        continuation.resume(upright to ratio) { _, abandoned, _ -> abandoned.first.recycle() }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally { image.close() }
                }
                /** 回传拍摄错误。@param exception CameraX 拍摄失败原因。 */
                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            })
        }
    }

    /** 保存真实照片，不合入人偶；无参数。
     * @return 系统相册 URI；Android 9 及以下由 UI 先取得写入权限。
     */
    suspend fun capturePhoto(requestedZoom: Float? = null): Uri? {
        ar?.let { return it.capturePhoto() }
        val active = capture ?: error("相机尚未准备完成")
        flushZoom(requestedZoom)
        awaitZoom()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Framer_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FramerSense")
        }
        val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        return suspendCancellableCoroutine { continuation ->
            active.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                /** 保存完成后回传 URI。@param outputFileResults CameraX 保存结果。 */
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(outputFileResults.savedUri)
                }
                /** 保存失败后回传错误。@param exception 相机或存储异常。 */
                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            })
        }
    }

    /** 解绑本实例使用的相机用例；无参数，不解绑其他模块的相机资源。 */
    fun unbind(invalidatePending: Boolean = true) {
        cancelQueuedZoom()
        if (invalidatePending) bindingEpoch++
        camera?.cameraInfo?.let { closingCameraInfo = it }
        zoomOwner?.let { camera?.cameraInfo?.zoomState?.removeObservers(it) }; zoomOwner = null
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, capture, analysis).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        zoomOperation = null
        camera = null
        preview = null
        capture = null
        analysis = null
    }
}
