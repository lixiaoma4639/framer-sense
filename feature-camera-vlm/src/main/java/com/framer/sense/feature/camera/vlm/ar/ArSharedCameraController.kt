package com.framer.sense.feature.camera.vlm.ar

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import com.google.ar.core.*
import com.framer.sense.feature.camera.vlm.camera.CameraPoseTracker
import com.framer.sense.feature.camera.vlm.camera.Yuv420Copy
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.AnalysisSample
import com.framer.sense.feature.camera.vlm.scan.BackgroundMatchPolicy
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import java.util.EnumSet

/** Camera2 与 ARCore 共用设备；CameraX 必须先退出，回退必须等 onClosed 后发布。 */
class ArSharedCameraController(private val context: Context,
    private val onState: (ArUiState) -> Unit,
    private val onConfigured: (Int) -> Unit,
    private val onReady: (CameraCapabilities) -> Unit,
    private val onSample: (AnalysisSample) -> Unit) : AutoCloseable {
    private val thread = HandlerThread("vlm-ar-shared").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val poses = CameraPoseTracker(context)
    private var session: Session? = null
    private var shared: SharedCamera? = null
    private var device: CameraDevice? = null
    private var deviceOpening = false
    private val gate = ArReferenceGate()
    private var captureSession: CameraCaptureSession? = null
    private var jpeg: ImageReader? = null
    private var egl: ArEglContext? = null
    private var scene: ArFilamentScene? = null
    private var textureId = 0
    private var surface: Surface? = null
    private var width = 1; private var height = 1; private var rotation = 0
    private var cameraRotation = 0
    private var realtime = false
    private var configVersion = 0
    private var sessionId = ""
    private var generation = 0
    private var running = false
    private var closing = false
    private var disposed = false
    private var foreground = false
    private var fallback = false
    private var sharedJpeg = true
    private var capturing = false
    private var depthSupported = false
    private var pendingPhoto: CancellableContinuation<ByteArray>? = null
    private var photoTimeout: Runnable? = null
    private var frame: Frame? = null
    private var anchor: Anchor? = null
    private var heading = 0f
    private var manualAnchor = false
    private var planKey: String? = null
    private var automaticPlacementTried = false
    private var lastSampleNs = 0L
    private var sampleTarget: String? = null
    private var sampleBoundary = Long.MIN_VALUE
    private var lastState: ArUiState? = null
    private val performance = ArPerformanceLog()
    private var previousFrameTimestamp = 0L
    private var lastProbeNs = 0L
    private var outputProbeQueued = false
    private var nextTickNs = 0L
    private var openStartedNs = 0L
    private var captureLogNs = 0L
    private var captureCount = 0
    @Volatile private var startupStep = "CREATED"
    @Volatile private var firstFrameSeen = false
    private val firstFrameWatch = Runnable {
        if (!firstFrameSeen) Log.w("VlmAr", "首帧尚未到达 phase=$startupStep generation=$generation elapsedMs=${(SystemClock.elapsedRealtimeNanos()-openStartedNs)/1_000_000}")
    }
    private fun phase(name: String) { startupStep = name; Log.i("VlmAr", "启动节点 phase=$name generation=$generation session=${sessionId.take(8)} elapsedMs=${(SystemClock.elapsedRealtimeNanos()-openStartedNs)/1_000_000}") }

    @Volatile var lastFrozenConfig: ScanCameraConfig? = null; private set
    @Volatile private var request: Request = Request()
    data class Request(val targetId: String? = null, val startedNs: Long? = null,
        val sampling: Boolean = false, val plan: CompositionPlan? = null, val backgroundConfirmed: Boolean = false,
        val render: Boolean = true)
    fun update(request: Request) {
        val old = this.request
        this.request = request
        if (old.targetId != request.targetId || old.sampling != request.sampling || old.render != request.render || old.backgroundConfirmed != request.backgroundConfirmed || old.plan?.id != request.plan?.id || old.plan?.revision != request.plan?.revision) {
            Log.i("VlmAr", "请求变化 target=${request.targetId?.take(16)} sampling=${request.sampling} render=${request.render} background=${request.backgroundConfirmed} plan=${request.plan?.id} revision=${request.plan?.revision}")
            handler.post { performance.reset(); captureCount = 0; captureLogNs = 0L; lastProbeNs = 0L }
        }
    }
    fun attach(surface: Surface, width: Int, height: Int, rotation: Int) { handler.post {
        Log.i("VlmAr", "Surface 挂接 size=${width}x$height rotation=$rotation valid=${surface.isValid} sessionPresent=${session != null}")
        this.surface = surface; resizeInternal(width, height, rotation)
        if (session == null && !closing && !disposed) open()
        else scene?.surface(surface, width, height)
    } }
    fun detachSurface() { handler.post { Log.i("VlmAr", "Surface 分离 session=${sessionId.take(8)}"); surface = null; scene?.detachSurface() } }
    fun resize(width: Int, height: Int, rotation: Int) { handler.post { resizeInternal(width, height, rotation) } }
    private fun resizeInternal(w: Int, h: Int, r: Int) {
        val changed = width != w || height != h || rotation != r
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1); rotation = r
        session?.setDisplayGeometry(rotation, width, height); scene?.resize(width, height)
        if (changed) Log.i("VlmAr", "视口变化 size=${width}x$height rotation=$rotation invalidatesAnchor=${session != null}")
        if (changed && session != null) { invalidateAnchor(); configured() }
    }
    fun foreground(visible: Boolean) { handler.post {
        foreground = visible
        Log.i("VlmAr", "前台变化 visible=$visible running=$running closing=$closing")
        if (!visible) { state(ArTrackingState.PAUSED); closeCamera(false) }
        else if (!closing && session == null && surface?.isValid == true && !disposed) open()
    } }
    fun tap(x: Float, y: Float) { handler.post {
        val current = frame ?: return@post
        if (request.backgroundConfirmed && request.plan != null && current.camera.trackingState == TrackingState.TRACKING)
            place(current, x, y, true)
    } }
    private fun configured() { configVersion = com.framer.sense.feature.camera.vlm.camera.CameraConfigVersions.next(); val v = configVersion; main.post { if (!disposed) onConfigured(v) } }
    private fun state(status: ArTrackingState, depth: Boolean = false) {
        val value = ArUiState(ArSource.SHARED_CAMERA, status, sessionId.ifBlank { null }, depth, manualAnchor)
        if (value != lastState) { lastState = value; Log.i("VlmAr", "空间状态 session=${sessionId.take(8)} tracking=$status depth=$depth manual=$manualAnchor"); main.post { if (!disposed) onState(value) } }
    }
    @SuppressLint("MissingPermission")
    private fun open() {
        if (disposed || !foreground || closing || surface?.isValid != true) return
        val epoch = ++generation
        openStartedNs = SystemClock.elapsedRealtimeNanos(); firstFrameSeen = false
        previousFrameTimestamp = 0L; lastProbeNs = 0L; outputProbeQueued = false; captureCount = 0; captureLogNs = 0L; performance.reset()
        main.removeCallbacks(firstFrameWatch); main.postDelayed(firstFrameWatch, 5_000)
        phase("OPEN_BEGIN")
        try {
            fallback = false; running = false; sessionId = newVlmId(); gate.session(sessionId); configured()
            phase("EGL_BEGIN"); egl = ArEglContext(); phase("EGL_READY")
            Log.i("VlmAr", "设备 model=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} GL=${GLES20.glGetString(GLES20.GL_VERSION)} renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}")
            val names = IntArray(1); GLES20.glGenTextures(1, names, 0); textureId = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            phase("AR_SESSION_BEGIN")
            val ar = Session(context, EnumSet.of(Session.Feature.SHARED_CAMERA)); session = ar
            val configs = ar.getSupportedCameraConfigs(CameraConfigFilter(ar).setFacingDirection(CameraConfig.FacingDirection.BACK).setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE)))
            check(configs.isNotEmpty()) { "AR_CAMERA_CONFIG_UNAVAILABLE" }; ar.cameraConfig = configs.first()
            depthSupported = ar.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            val config = Config(ar).apply {
                focusMode = Config.FocusMode.AUTO
                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            try { ar.configure(config) }
            catch (failure: com.google.ar.core.exceptions.UnsupportedConfigurationException) {
                if (!depthSupported) throw failure
                Log.w("VlmAr", "共享相机不支持当前深度组合，关闭遮挡后继续", failure)
                depthSupported = false; config.depthMode = Config.DepthMode.DISABLED; ar.configure(config)
            }
            phase("AR_SESSION_READY")
            Log.i("VlmAr", "相机配置 cameraId=${ar.cameraConfig.cameraId} cpu=${ar.cameraConfig.imageSize} gpu=${ar.cameraConfig.textureSize} depth=$depthSupported")
            ar.setCameraTextureName(textureId); ar.setDisplayGeometry(rotation, width, height)
            shared = ar.sharedCamera
            val id = ar.cameraConfig.cameraId; val characteristics = manager.getCameraCharacteristics(id)
            cameraRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            realtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val size = sizes.filter { it.width <= 2048 && it.height <= 2048 }.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { it.width.toLong() * it.height }
            if (size != null) jpeg = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { reader ->
                reader.setOnImageAvailableListener({ readJpeg(it) }, handler)
            }
            // JPEG 不加入实时预览请求，避免 ARCore 将其作为额外实时输出持续编码。
            sharedJpeg = false
            Log.i("VlmAr", "拍照配置 jpeg=$size mode=PAUSED_JPEG previewJpeg=false timestampRealtime=$realtime")
            phase("FILAMENT_BEGIN")
            scene = ArFilamentScene(context, requireNotNull(egl), textureId).also { it.surface(requireNotNull(surface), width, height) }
            phase("FILAMENT_READY")
            poses.start(); state(ArTrackingState.STARTING)
            deviceOpening = true; phase("CAMERA_OPEN_REQUEST")
            manager.openCamera(id, shared!!.createARDeviceStateCallback(object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    deviceOpening = false; device = camera; phase("CAMERA_OPENED")
                    if (disposed || closing || epoch != generation || !foreground) camera.close() else configureShared()
                }
                override fun onDisconnected(camera: CameraDevice) { deviceOpening = false; device = camera; fail(IllegalStateException("AR_CAMERA_DISCONNECTED")) }
                override fun onError(camera: CameraDevice, error: Int) { deviceOpening = false; device = camera; fail(IllegalStateException("AR_CAMERA_ERROR:$error")) }
                override fun onClosed(camera: CameraDevice) { Log.i("VlmAr", "相机已关闭 session=${sessionId.take(8)} fallback=$fallback"); if (device === camera) device = null; releaseAfterCameraClosed() }
            }, handler), handler)
        } catch (failure: LinkageError) {
            // JNI 装载错误属于 Error，Exception 捕获不到；释放已创建资源后回退 CameraX。
            deviceOpening = false
            fail(IllegalStateException("AR_NATIVE_INITIALIZATION_FAILED", failure))
        } catch (failure: Exception) { deviceOpening = false; fail(failure) }
    }
    @Suppress("DEPRECATION")
    private fun configureShared() {
        val camera = device ?: return
        val ar = session ?: return
        if (closing || disposed || !foreground) return
        val surfaces = shared!!.arCoreSurfaces.toMutableList()
        if (sharedJpeg) jpeg?.let { surfaces += it.surface }
        shared!!.setAppSurfaces(ar.cameraConfig.cameraId, if (sharedJpeg) listOfNotNull(jpeg?.surface) else emptyList())
        phase("CAPTURE_SESSION_REQUEST")
        Log.i("VlmAr", "预览流 surfaceCount=${surfaces.size} arSurfaces=${shared!!.arCoreSurfaces.size} jpeg=$sharedJpeg")
        try { camera.createCaptureSession(surfaces, shared!!.createARSessionStateCallback(object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                if (closing || disposed || session !== ar) { value.close(); return }
                captureSession = value; phase("CAPTURE_SESSION_CONFIGURED")
                try {
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                shared!!.arCoreSurfaces.forEach(builder::addTarget)
                shared!!.setCaptureCallback(captureDiagnostics, handler)
                value.setRepeatingRequest(builder.build(), null, handler)
                phase("REPEATING_REQUEST_SET")
                main.post { if (!disposed) onReady(CameraCapabilities()) }
                } catch (failure: Exception) { fail(failure) }
            }
            override fun onActive(value: CameraCaptureSession) {
                if (session === ar && value === captureSession && !running && !capturing && !closing && !disposed) try {
                    phase("AR_RESUME_BEGIN"); ar.resume(); phase("AR_RESUMED"); performance.reset(); nextTickNs = 0L; running = true; handler.removeCallbacks(tick); handler.post(tick)
                } catch (failure: Exception) { fail(failure) }
            }
            override fun onConfigureFailed(value: CameraCaptureSession) {
                Log.w("VlmAr", "预览流配置失败 jpeg=$sharedJpeg")
                value.close()
                if (closing || disposed || session !== ar) return
                if (sharedJpeg) { sharedJpeg = false; configureShared() } else fail(IllegalStateException("AR_SHARED_SESSION_UNSUPPORTED"))
            }
        }, handler), handler) } catch (failure: Exception) { fail(failure) }
    }
    private val captureDiagnostics = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(cameraSession: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (cameraSession !== captureSession || closing || !performance.collecting) return
            captureCount++
            val now = SystemClock.elapsedRealtimeNanos()
            if (now-captureLogNs < 5_000_000_000L) return
            val stamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            val deliveryMs = if (realtime && stamp != null && now >= stamp) (now-stamp)/1_000_000 else null
            Log.i("VlmAr", "相机出帧 captures=$captureCount sensorTimestamp=$stamp callbackDelayMs=$deliveryMs AE=${result.get(CaptureResult.CONTROL_AE_STATE)} AF=${result.get(CaptureResult.CONTROL_AF_STATE)} exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} ISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)}")
            captureCount = 0; captureLogNs = now
        }
    }
    private fun probeImage(current: Frame, now: Long) {
        if (!performance.collecting || now-lastProbeNs < 5_000_000_000L) return
        lastProbeNs = now
        try { current.acquireCameraImage().use { image ->
            val plane = image.planes[0]; val data = plane.buffer.duplicate(); val base = data.position()
            var low = 255; var high = 0; var total = 0; var samples = 0
            for (y in 0 until image.height step maxOf(1,image.height/16)) for (x in 0 until image.width step maxOf(1,image.width/16)) {
                val value = data.get(base+y*plane.rowStride+x*plane.pixelStride).toInt() and 255
                low = minOf(low,value); high = maxOf(high,value); total += value; samples++
            }
            val age = if (realtime && now >= image.timestamp) (now-image.timestamp)/1_000_000 else null
            Log.i("VlmAr", "输入亮度探针 size=${image.width}x${image.height} Yavg=${total/samples} Ymin=$low Ymax=$high imageAgeMs=$age clockReliable=$realtime")
        } } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) { Log.d("VlmAr", "亮度探针尚无 CPU 图像") }
        catch (failure: Exception) { Log.w("VlmAr", "亮度探针失败，继续预览", failure) }
    }
    /** 只抽查一次 Surface 缓冲，不保存图像，不在实时循环中同步等待像素回读。 */
    private fun queueOutputProbe() {
        if (!com.framer.sense.feature.camera.vlm.BuildConfig.DEBUG || outputProbeQueued) return
        val target = surface ?: return
        outputProbeQueued = true
        val epoch = generation
        handler.postDelayed({
            if (disposed || closing || !running || generation != epoch || surface !== target || !target.isValid) return@postDelayed
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val started = SystemClock.elapsedRealtime()
            try {
                // 回调在主线程回收小位图，即使 AR 工作线程已退出也不遗留临时资源。
                PixelCopy.request(target, bitmap, { result ->
                    try {
                        var total = 0; var low = 255; var high = 0; var alpha = 0
                        if (result == PixelCopy.SUCCESS) for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                            val pixel = bitmap.getPixel(x, y)
                            val brightness = (77 * (pixel ushr 16 and 255) + 150 * (pixel ushr 8 and 255) + 29 * (pixel and 255)) / 256
                            total += brightness; low = minOf(low, brightness); high = maxOf(high, brightness)
                            alpha += pixel ushr 24 and 255
                        }
                        val stats = if (result == PixelCopy.SUCCESS) "RGBavg=${total / 256} RGBmin=$low RGBmax=$high alphaAvg=${alpha / 256}" else "pixelsUnavailable=true"
                        val elapsed = SystemClock.elapsedRealtime() - started
                        handler.post {
                            if (!disposed && !closing && generation == epoch && surface === target)
                                Log.i("VlmAr", "输出画面探针 generation=$epoch status=$result surfacePixelsVerified=${result == PixelCopy.SUCCESS} $stats elapsedMs=$elapsed oneShot=true")
                        }
                    } finally { bitmap.recycle() }
                }, main)
            } catch (failure: Exception) {
                bitmap.recycle()
                Log.w("VlmAr", "输出画面探针失败，继续预览", failure)
            }
        }, 1_500)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running || disposed || closing || capturing) return
            val tickStart = SystemClock.elapsedRealtimeNanos()
            performance.count("tick")
            if (nextTickNs > 0) performance.time("handlerLag",(tickStart-nextTickNs).coerceAtLeast(0L))
            try {
                egl!!.makeCurrent()
                val updateStart = SystemClock.elapsedRealtimeNanos()
                val current = session!!.update(); GLES20.glFlush(); frame = current
                performance.time("arUpdate",SystemClock.elapsedRealtimeNanos()-updateStart)
                if (current.timestamp == 0L) performance.count("zeroTimestamp")
                else if (current.timestamp == previousFrameTimestamp) performance.count("duplicateFrame")
                else {
                    performance.count("newFrame"); previousFrameTimestamp = current.timestamp
                    if (!firstFrameSeen) {
                        firstFrameSeen = true; main.removeCallbacks(firstFrameWatch); phase("FIRST_AR_FRAME")
                        Log.i("VlmAr", "首帧 timestamp=${current.timestamp} configuredTexture=$textureId actualTexture=${current.cameraTextureName} textureValid=${GLES20.glIsTexture(current.cameraTextureName)} tracking=${current.camera.trackingState} surfaceValid=${surface?.isValid} glError=${GLES20.glGetError()}")
                    }
                }
                if (surface?.isValid != true) performance.count("invalidSurface")
                if (current.timestamp != 0L && surface?.isValid == true) {
                    val wanted = request
                    val key = wanted.plan?.let { "${it.id}:${it.revision}:${it.resolvedSpec?.action?.cacheIdentity()}" }
                    if (key != planKey) { invalidateAnchor(); planKey = key; gate.plan(key); automaticPlacementTried = false }
                    val tracking = current.camera.trackingState == TrackingState.TRACKING
                    if (!tracking) { invalidateAnchor(); automaticPlacementTried = false; scene!!.hide(); state(ArTrackingState.LOST) }
                    else {
                        val loadStart = SystemClock.elapsedRealtimeNanos()
                        wanted.plan?.let { scene!!.load(it) }
                        performance.time("poseLoad",SystemClock.elapsedRealtimeNanos()-loadStart)
                        if (wanted.backgroundConfirmed && wanted.plan != null && anchor == null && !automaticPlacementTried) {
                            automaticPlacementTried = true
                            ArGeometry.suggestedGround(wanted.plan)?.let { foot -> place(current, foot.x * width, foot.y * height, false) }
                        }
                    }
                    val model = if (tracking && wanted.backgroundConfirmed && anchor?.trackingState == TrackingState.TRACKING)
                        FloatArray(16).also { matrix -> Matrix.setIdentityM(matrix, 0); val p = anchor!!.pose.translation
                            Matrix.translateM(matrix, 0, p[0], p[1], p[2]); Matrix.rotateM(matrix, 0, heading, 0f, 1f, 0f) } else null
                    val depth = scene!!.render(current, if (wanted.render) model else null, tracking && depthSupported && wanted.render && model != null)
                    val renderTiming = scene!!.lastRenderTiming
                    performance.time("render",renderTiming.totalNs); performance.time("depth",renderTiming.depthNs)
                    performance.time("submit",renderTiming.submitNs); performance.time("gpuWait",renderTiming.waitNs)
                    performance.count(if (renderTiming.submitted) "submitted" else "skippedDraw")
                    if (renderTiming.submitted) queueOutputProbe()
                    if (tracking) state(if (model != null) ArTrackingState.PLACED else if (wanted.backgroundConfirmed && wanted.plan != null) ArTrackingState.NEEDS_GROUND else ArTrackingState.TRACKING, depth)
                    val now = SystemClock.elapsedRealtimeNanos()
                    val analysisStart = SystemClock.elapsedRealtimeNanos()
                    if (wanted.sampling && wanted.targetId != null && now - lastSampleNs >= if (wanted.startedNs != null) ScanLimits.SAMPLE_INTERVAL_NS else BackgroundMatchPolicy.ANALYSIS_INTERVAL_NS) {
                        try {
                            val acquireStart = SystemClock.elapsedRealtimeNanos()
                            val image = current.acquireCameraImage()
                            performance.time("imageAcquire", SystemClock.elapsedRealtimeNanos() - acquireStart)
                            val sample = image.use {
                                if (sampleTarget != wanted.targetId) { sampleTarget = wanted.targetId; sampleBoundary = image.timestamp; lastSampleNs = now; return@use null }
                                if (image.timestamp <= sampleBoundary) return@use null
                                sampleBoundary = image.timestamp
                                val copyStart = SystemClock.elapsedRealtimeNanos()
                                copy(image, current, wanted.targetId, now).also {
                                    performance.time("imageCopy", SystemClock.elapsedRealtimeNanos() - copyStart)
                                }
                            }
                            // 回调只持有独立缓冲，相机 Image 已经关闭。
                            if (sample != null && (wanted.startedNs == null || !realtime || sample.timestampNs >= wanted.startedNs)) { lastSampleNs = now; performance.count("analysisSample"); onSample(sample) }
                        } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) { performance.count("imageUnavailable") }
                    }
                    performance.time("analysis",SystemClock.elapsedRealtimeNanos()-analysisStart)
                    if (!wanted.sampling) probeImage(current,now)
                }
            } catch (failure: Exception) { fail(failure); return }
            val workNs = SystemClock.elapsedRealtimeNanos()-tickStart
            performance.time("loop",workNs)
            performance.report("session=${sessionId.take(8)} viewport=${width}x$height running=$running render=${request.render} sampling=${request.sampling} plan=${request.plan?.id} phase=$startupStep")
            val delay = ArPreviewCadence.delayMillis(workNs,request.render)
            nextTickNs = SystemClock.elapsedRealtimeNanos()+delay*1_000_000L
            handler.postDelayed(this,delay)
        }
    }
    private fun invalidateAnchor() { gate.invalidate(); anchor?.detach(); anchor = null; scene?.hide(); manualAnchor = false }
    private fun place(current: Frame, x: Float, y: Float, manual: Boolean) {
        val hit = current.hitTest(x, y).firstOrNull { value ->
            val plane = value.trackable as? Plane
            plane != null && plane.trackingState == TrackingState.TRACKING && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                plane.isPoseInPolygon(value.hitPose) && value.distance in .4f..15f
        } ?: return
        val key = planKey ?: return
        if (!gate.place(sessionId, key)) return
        invalidateAnchor(); anchor = hit.createAnchor(); gate.place(sessionId, key); manualAnchor = manual
        val camera = current.camera.pose.translation; val target = hit.hitPose.translation
        heading = Math.toDegrees(atan2((camera[0] - target[0]).toDouble(), (camera[2] - target[2]).toDouble())).toFloat() + (request.plan?.avatar?.yaw ?: 0f)
        Log.i("VlmAr", "地面落点 session=${sessionId.take(8)} plan=$planKey manual=$manual")
    }
    private fun config(current: Frame, image: Image): ScanCameraConfig {
        val corners = FloatArray(8)
        current.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f), Coordinates2d.IMAGE_NORMALIZED, corners)
        val xs = listOf(corners[0], corners[2], corners[4], corners[6]); val ys = listOf(corners[1], corners[3], corners[5], corners[7])
        val crop = CropRect(xs.min().coerceIn(0f,1f), ys.min().coerceIn(0f,1f), xs.max().coerceIn(0f,1f), ys.max().coerceIn(0f,1f))
        val intrinsics = current.camera.imageIntrinsics
        return ScanCameraConfig(configVersion, (cameraRotation - rotation * 90 + 360) % 360, 1f, crop, realtime,
            sessionId, intrinsics.focalLength.toList() + intrinsics.principalPoint.toList() + intrinsics.imageDimensions.map(Int::toFloat),
            if (current.camera.trackingState == TrackingState.TRACKING) current.camera.pose.translation.toList() + current.camera.pose.rotationQuaternion.toList() else emptyList())
    }
    private fun copy(image: Image, current: Frame, target: String, now: Long): AnalysisSample {
        val w = image.width; val h = image.height
        val bytes = Yuv420Copy.toNv21(w, h, image.planes.map { Yuv420Copy.Plane(it.buffer, it.rowStride, it.pixelStride) })
        return AnalysisSample(target, image.timestamp, now, config(current, image), if (realtime) poses.at(image.timestamp) else null, w, h, bytes)
    }
    suspend fun freeze(): Pair<Bitmap, Float> = suspendCancellableCoroutine { continuation -> handler.post {
        try {
            val current = frame ?: error("AR_CAMERA_NOT_READY")
            val sample = current.acquireCameraImage().use { image -> copy(image, current, "freeze", SystemClock.elapsedRealtimeNanos()) }
            lastFrozenConfig = sample.config
            val bitmap = sample.bitmap()
            if (continuation.isActive) continuation.resume(bitmap to 1f) { _, abandoned, _ -> abandoned.first.recycle() } else bitmap.recycle()
        } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
    } }
    suspend fun capturePhoto(): Uri = withContext(Dispatchers.IO) {
        val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
            handler.post { beginPhoto(continuation) }
            continuation.invokeOnCancellation { handler.post { if (pendingPhoto === continuation) finishPhoto(null, CancellationException()) } }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Framer_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FramerSense"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("AR_PHOTO_WRITE_FAILED")
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (failure: Exception) { context.contentResolver.delete(uri, null, null); throw failure }
    }
    @Suppress("DEPRECATION")
    private fun beginPhoto(continuation: CancellableContinuation<ByteArray>) {
        if (!continuation.isActive) return
        if (capturing || closing || !running || jpeg == null) { continuation.resumeWithException(IllegalStateException("AR_PHOTO_UNAVAILABLE")); return }
        try {
            capturing = true; pendingPhoto = continuation; session?.pause(); running = false
            handler.removeCallbacks(tick); invalidateAnchor(); frame = null
            photoTimeout = Runnable { finishPhoto(null, IllegalStateException("AR_PHOTO_TIMEOUT")) }.also { handler.postDelayed(it, 15_000) }
            if (sharedJpeg) takeStill() else {
                captureSession?.close(); captureSession = null
                device!!.createCaptureSession(listOf(jpeg!!.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) { captureSession = value; if (capturing) takeStill() else value.close() }
                    override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); finishPhoto(null, IllegalStateException("AR_PHOTO_SESSION_FAILED")) }
                }, handler)
            }
        } catch (failure: Exception) { finishPhoto(null, failure) }
    }
    private fun takeStill() {
        try {
            val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jpeg!!.surface); builder.set(CaptureRequest.JPEG_ORIENTATION, (cameraRotation - rotation * 90 + 360) % 360)
            captureSession!!.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { finishPhoto(null, IllegalStateException("AR_PHOTO_CAPTURE_FAILED")) }
            }, handler)
        } catch (failure: Exception) { finishPhoto(null, failure) }
    }
    private fun readJpeg(reader: ImageReader) {
        reader.acquireLatestImage()?.use { image ->
            val pending = pendingPhoto ?: return
            if (!pending.isActive) return
            Log.i("VlmAr", "原始 JPEG 返回 session=${sessionId.take(8)} bytes=${image.planes[0].buffer.remaining()} sharedJpeg=$sharedJpeg avatarComposited=false")
            val buffer = image.planes[0].buffer; val data = ByteArray(buffer.remaining()); buffer.get(data)
            finishPhoto(data, null)
        }
    }
    private fun finishPhoto(bytes: ByteArray?, failure: Exception?) {
        val pending = pendingPhoto; pendingPhoto = null
        photoTimeout?.let(handler::removeCallbacks); photoTimeout = null
        if (!capturing) return
        capturing = false
        if (!closing && !disposed && foreground) { captureSession?.close(); captureSession = null; configured(); configureShared() }
        if (pending?.isActive == true) { if (bytes != null) pending.resume(bytes) else pending.resumeWithException(failure ?: IllegalStateException("AR_PHOTO_FAILED")) }
    }
    private fun fail(failure: Exception) { Log.e("VlmAr", "AR 失败 generation=$generation session=${sessionId.take(8)}", failure); closeCamera(true) }
    private fun closeCamera(toScreen: Boolean) {
        Log.i("VlmAr", "关闭请求 toScreen=$toScreen phase=$startupStep running=$running cameraOpening=$deviceOpening")
        main.removeCallbacks(firstFrameWatch)
        fallback = fallback || toScreen
        if (closing) return
        closing = true; running = false; handler.removeCallbacks(tick); poses.stop()
        pendingPhoto?.let { if (it.isActive) it.resumeWithException(CancellationException("AR_CAMERA_CLOSED")) }; pendingPhoto = null; capturing = false
        photoTimeout?.let(handler::removeCallbacks); photoTimeout = null
        invalidateAnchor(); frame = null
        runCatching { session?.pause() }; captureSession?.close(); captureSession = null
        val camera = device
        if (camera != null) camera.close() else if (!deviceOpening) releaseAfterCameraClosed()
    }
    private fun releaseAfterCameraClosed() {
        runCatching { scene?.close() }; scene = null
        jpeg?.close(); jpeg = null
        runCatching { session?.close() }; session = null; shared = null
        egl?.let { gl -> runCatching { gl.makeCurrent(); if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0); gl.close() } }; egl = null; textureId = 0
        closing = false
        if (disposed) { thread.quitSafely(); return }
        if (fallback) main.post { onState(ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED)) }
        else if (foreground && surface?.isValid == true) open()
    }
    override fun close() { handler.post { disposed = true; closeCamera(false) } }
}
