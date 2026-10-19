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
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FrozenCameraFrame(val bitmap: Bitmap, val zoom: Float,
    val orientation: PhotoOrientation, val arConfig: ScanCameraConfig? = null)

/** 只负责平台相机资源；UI 销毁时必须解绑，不持有 ViewModel。 */
class VlmCameraController(private val context: Context) {
    companion object {
        private const val ZOOM_LOG_TAG = "VlmCameraZoom"
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
    fun installAr(value: com.framer.sense.feature.camera.vlm.ar.ArSharedCameraController) {
        check(ar == null && camera == null)
        value.photoOrientationAt = orientations::at
        ar = value
    }
    private val orientations = PhotoOrientationHistory()
    fun orientation(value: PhotoOrientation) { orientations.record(SystemClock.elapsedRealtimeNanos(), value) }
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
        android.util.Log.i(ZOOM_LOG_TAG, "变焦范围 config=$version zoomStatePresent=${state != null} min=$minimum max=$maximum requested=$initialZoom actual=${state?.zoomRatio}")
        if (state == null) android.util.Log.w(ZOOM_LOG_TAG, "变焦范围读取为空，临时使用 1×，不能据此判断设备只有一个倍率 config=$version")
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
                    val orientation = orientations.at(if (realtime) captured else received)
                    val config = ScanCameraConfig(version, orientation.uprightDegrees(image.imageInfo.rotationDegrees), camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: initialZoom,
                        CropRect(r.left.toFloat() / w, r.top.toFloat() / h, r.right.toFloat() / w, r.bottom.toFloat() / h), realtime,
                        orientation = orientation)
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
        val lensRatios = deviceLensRatios(camera!!.cameraInfo, instance.availableCameraInfos)
        val options = CameraZoomPolicy.presets(minimum, maximum)
        android.util.Log.i(ZOOM_LOG_TAG, "最终倍率按钮 config=$version range=$minimum..$maximum lensEstimates=$lensRatios buttons=$options actual=${camera!!.cameraInfo.zoomState.value?.zoomRatio} presetPolicy=ONE_TWO_THREE_AND_DEVICE_MAX lensMetadataDiagnosticOnly=true")
        android.util.Log.i("VlmCamera", "倍率快捷档 camera=${Camera2CameraInfo.from(camera!!.cameraInfo).cameraId} range=$minimum..$maximum lensEstimates=$lensRatios presets=$options actual=${camera!!.cameraInfo.zoomState.value?.zoomRatio}")
        return CameraCapabilities(minimum, maximum, options)
    }

    /** 枚举全部公开后置相机及其成员用于诊断，不决定变焦快捷按钮。 */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun deviceLensRatios(info: CameraInfo, available: List<CameraInfo>): List<Float> = runCatching {
        android.util.Log.i(ZOOM_LOG_TAG, "镜头枚举开始 device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} cameraXAvailableCount=${available.size}")
        logCamera2PublicInventory(available.mapNotNull { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() }.toSet())
        val camera2 = Camera2CameraInfo.from(info)
        android.util.Log.i(ZOOM_LOG_TAG, "读取当前相机参数 camera=${camera2.cameraId}")
        val referenceFocal = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.filter { it.isFinite() && it > 0f }?.minOrNull()
        val referenceSize = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        android.util.Log.i(ZOOM_LOG_TAG, "倍率参考 camera=${camera2.cameraId} focalMm=$referenceFocal sensorMm=$referenceSize")
        if (referenceFocal == null || referenceSize == null)
            android.util.Log.w(ZOOM_LOG_TAG, "倍率参考参数缺失，无法估算镜头倍率 camera=${camera2.cameraId} focalMissing=${referenceFocal == null} sensorMissing=${referenceSize == null}")
        android.util.Log.i(ZOOM_LOG_TAG, "读取当前相机成员 camera=${camera2.cameraId}")
        val members = info.physicalCameraInfos
        val controllableIds = members.map { Camera2CameraInfo.from(it).cameraId }.toSet() + camera2.cameraId
        android.util.Log.i(ZOOM_LOG_TAG, "当前相机成员 camera=${camera2.cameraId} physicalCount=${members.size} controllableIds=$controllableIds")
        if (members.isEmpty()) android.util.Log.w(ZOOM_LOG_TAG, "当前相机未公开成员镜头，继续查询独立后置相机 camera=${camera2.cameraId}")
        val backCameras = CameraSelector.DEFAULT_BACK_CAMERA.filter(available)
        // 独立暴露的其他后置相机也记录，但不能仅因为数字变焦范围覆盖就假装已切换到它。
        val exposed = (backCameras.flatMap { candidate ->
            listOf(candidate) + runCatching { candidate.physicalCameraInfos.toList() }.getOrElse {
                android.util.Log.w(ZOOM_LOG_TAG, "读取后置相机成员失败 camera=${Camera2CameraInfo.from(candidate).cameraId}", it)
                emptyList()
            }
        } + members).distinctBy { Camera2CameraInfo.from(it).cameraId }
        android.util.Log.i("VlmCamera", "公开后置镜头 current=${camera2.cameraId} cameraCount=${backCameras.size} exposedCount=${exposed.size} controllable=$controllableIds")
        android.util.Log.i(ZOOM_LOG_TAG, "后置镜头枚举完成 current=${camera2.cameraId} backCameraIds=${backCameras.map { Camera2CameraInfo.from(it).cameraId }} exposedIds=${exposed.map { Camera2CameraInfo.from(it).cameraId }}")
        exposed.flatMap { exposedInfo ->
            runCatching lens@ {
                val physical = Camera2CameraInfo.from(exposedInfo)
                android.util.Log.i(ZOOM_LOG_TAG, "读取公开镜头参数 id=${physical.cameraId}")
                val size = physical.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (size == null) {
                    android.util.Log.w(ZOOM_LOG_TAG, "跳过公开镜头 id=${physical.cameraId} reason=MISSING_SENSOR_PHYSICAL_SIZE")
                    return@lens emptyList<Float>()
                }
                val focalLengths = physical.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: floatArrayOf()
                val ratios = if (referenceFocal != null && referenceSize != null) focalLengths.toList().mapNotNull { focal ->
                    CameraZoomPolicy.lensRatio(focal, size.width, size.height,
                        referenceFocal, referenceSize.width, referenceSize.height)
                } else emptyList()
                val controllable = physical.cameraId in controllableIds
                android.util.Log.i(ZOOM_LOG_TAG, "公开镜头参数 id=${physical.cameraId} focalMm=${focalLengths.toList()} sensorMm=${size.width}x${size.height} relativeZoom=$ratios currentCameraMember=$controllable")
                if (ratios.isEmpty()) android.util.Log.w(ZOOM_LOG_TAG, "镜头倍率估算为空 id=${physical.cameraId} reason=${if (focalLengths.isEmpty()) "MISSING_FOCAL_LENGTHS" else if (referenceFocal == null || referenceSize == null) "MISSING_REFERENCE" else "INVALID_LENS_PARAMETERS"}")
                if (!controllable) android.util.Log.i(ZOOM_LOG_TAG, "独立镜头不能由当前相机直接控制 id=${physical.cameraId} reason=REQUIRES_CAMERA_REBIND")
                android.util.Log.i("VlmCamera", "公开镜头 id=${physical.cameraId} focalMm=${focalLengths.toList()} sensorMm=${size.width}x${size.height} relativeZoom=$ratios currentCameraMember=$controllable")
                if (controllable) ratios else emptyList()
            }.getOrElse {
                android.util.Log.w(ZOOM_LOG_TAG, "读取公开镜头参数失败，跳过该镜头", it)
                emptyList()
            }
        }
    }.getOrElse {
        android.util.Log.w(ZOOM_LOG_TAG, "镜头枚举失败，快捷按钮仍按 ZoomState 实际范围生成", it)
        emptyList()
    }

    /** 只读对照原始 Camera2 目录，不尝试猜测隐藏 ID，也不打开或切换镜头。 */
    private fun logCamera2PublicInventory(cameraXIds: Set<String>) {
        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val publicIds = manager.cameraIdList.toSet()
            android.util.Log.i(ZOOM_LOG_TAG, "Camera2 原始枚举开始 publicIds=$publicIds cameraXIds=$cameraXIds onlyInCamera2=${publicIds - cameraXIds}")
            fun read(id: String, parent: String?): CameraCharacteristics? = runCatching {
                val metadata = manager.getCameraCharacteristics(id)
                val facing = when (metadata.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val capabilities = metadata.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()
                val physicalIds = if (Build.VERSION.SDK_INT >= 28) metadata.physicalCameraIds else emptySet()
                val zoomRange = if (Build.VERSION.SDK_INT >= 30) metadata.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
                android.util.Log.i(ZOOM_LOG_TAG, "Camera2 原始镜头 id=$id parent=$parent facing=$facing listed=${id in publicIds} inCameraX=${id in cameraXIds} focalMm=${metadata.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()} sensorMm=${metadata.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} zoomRange=$zoomRange maxDigitalZoom=${metadata.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)} physicalIds=$physicalIds capabilities=$capabilities")
                metadata
            }.getOrElse {
                android.util.Log.w(ZOOM_LOG_TAG, "Camera2 公开参数读取失败 id=$id parent=$parent", it)
                null
            }
            publicIds.forEach { id ->
                val metadata = read(id, null)
                if (Build.VERSION.SDK_INT >= 29) metadata?.physicalCameraIds?.filterNot { it in publicIds }?.forEach { physicalId ->
                    read(physicalId, id)
                }
            }
            android.util.Log.i(ZOOM_LOG_TAG, "Camera2 原始枚举完成 publicCount=${publicIds.size} source=READ_ONLY_METADATA")
        }.onFailure {
            android.util.Log.w(ZOOM_LOG_TAG, "Camera2 原始枚举失败，继续原 CameraX 流程", it)
        }
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
        if (!ratio.isFinite()) return
        val active = camera ?: return
        val limits = active.cameraInfo.zoomState.value ?: return
        val applied = ratio.coerceIn(limits.minZoomRatio, limits.maxZoomRatio)
        val operation = active.cameraControl.setZoomRatio(applied)
        zoomOperation = operation
        operation.addListener({
            try {
                operation.get()
            } catch (failure: Exception) {
                if (camera === active && zoomOperation === operation)
                    android.util.Log.w("VlmCamera", "倍率应用失败 requested=$ratio applied=$applied", failure)
            }
        }, executor)
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
     * @return 按固定视口裁切、按拍摄方向转正的独立 Bitmap、倍率及方向，由调用方回收 Bitmap。
     */
    suspend fun freeze(requestedZoom: Float? = null): FrozenCameraFrame {
        ar?.let {
            val (bitmap, zoom) = it.freeze()
            val config = requireNotNull(it.lastFrozenConfig)
            return FrozenCameraFrame(bitmap, zoom, config.orientation, config)
        }
        // 在请求开始时锁定方向；等待变焦或 JPEG 回调期间再转手机不能改变这次照片。
        val orientation = orientations.at(SystemClock.elapsedRealtimeNanos())
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
                        val matrix = Matrix().apply { postRotate(orientation.uprightDegrees(image.imageInfo.rotationDegrees).toFloat()) }
                        val upright = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
                        if (cropped !== decoded && cropped !== upright) cropped.recycle()
                        if (decoded !== upright) decoded.recycle()
                        continuation.resume(FrozenCameraFrame(upright, ratio, orientation)) { _, abandoned, _ -> abandoned.bitmap.recycle() }
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
        val orientation = orientations.at(SystemClock.elapsedRealtimeNanos())
        flushZoom(requestedZoom)
        awaitZoom()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Framer_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FramerSense")
        }
        val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            active.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(outputFileResults.savedUri)
                }
                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            })
        }
        // CameraX 继续负责 JPEG、裁切及元数据；只修正快门时的 EXIF，不解码和重压照片。
        if (uri != null && orientation.previewDegrees != 0) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { file ->
                    androidx.exifinterface.media.ExifInterface(file.fileDescriptor).apply {
                        rotate(-orientation.previewDegrees)
                        saveAttributes()
                    }
                } ?: error(context.getString(com.framer.sense.feature.camera.vlm.R.string.vlm_photo_orientation_failed))
            }
        }
        return uri
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
