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
    private val onSample: (AnalysisSample) -> Unit,
    private val onGround: (ArGroundSnapshot) -> Unit = {},
    private val onGroundPoint: (String, Int, ArGroundSnapshot, String, SpatialPoint) -> Unit = { _, _, _, _, _ -> }) : AutoCloseable {
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
    private var startupFailure: ArUiState? = null
    private var sharedJpeg = true
    private var capturing = false
    private var depthSupported = false
    private var pendingPhoto: CancellableContinuation<ByteArray>? = null
    private var photoTimeout: Runnable? = null
    private var frame: Frame? = null
    private var displayedCrop = CropRect()
    private var anchor: Anchor? = null
    private var heading = 0f
    private var manualAnchor = false
    private var planKey: String? = null
    private val placementRetry = ArPlacementRetry()
    private val groundTracking = ArGroundTracking<Plane>()
    private var groundSampleNs = 0L
    private val groundDelivery = ArGroundDelivery()
    private var groundDiagnostic: String? = null
    private var lastTrackingProblem: String? = null
    private var lastPointCloudFailure: String? = null
    private var lastBackgroundRenderNs: Long? = null
    private var lastRenderedDepth = false
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
        val wanted = request
        val plan = wanted.plan ?: return@post
        if (wanted.backgroundConfirmed && current.camera.trackingState == TrackingState.TRACKING) {
            val tap = PlanCoordinates.toOriginal(Point2(x/width.coerceAtLeast(1),y/height.coerceAtLeast(1)),displayedCrop)
            val hit = current.hitTest(tap.x*width,tap.y*height).firstOrNull { value ->
                val plane = value.trackable as? Plane
                plane != null && plane.trackingState == TrackingState.TRACKING && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.isPoseInPolygon(value.hitPose) && value.distance in ArScaleSolver.MIN_DISTANCE..ArScaleSolver.MAX_DISTANCE
            } ?: return@post
            val ground = groundSnapshot()
            val plane = hit.trackable as Plane
            val id = groundTracking.findId(plane) ?: return@post
            val t = hit.hitPose.translation; val point = SpatialPoint(t[0],t[1],t[2])
            main.post { if (!disposed && request.plan?.arReferenceKey() == plan.arReferenceKey()) onGroundPoint(plan.id,plan.revision,ground,id,point) }
        }
    } }
    private fun configured() { configVersion = com.framer.sense.feature.camera.vlm.camera.CameraConfigVersions.next(); val v = configVersion; main.post { if (!disposed) onConfigured(v) } }
    private fun state(status: ArTrackingState, depth: Boolean = false) {
        val value = ArUiState(ArSource.SHARED_CAMERA, status, sessionId.ifBlank { null }, depth, manualAnchor,
            planKey.takeIf { status == ArTrackingState.PLACED })
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
            fallback = false; startupFailure = null; running = false; sessionId = newVlmId(); gate.session(sessionId)
            groundTracking.reset(); groundDelivery.reset(); groundDiagnostic = null; lastTrackingProblem = null; lastPointCloudFailure = null; groundSampleNs = 0L; lastBackgroundRenderNs = null; lastRenderedDepth = false
            configured()
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
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
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
            Log.i("VlmAr", "相机配置 cameraId=${ar.cameraConfig.cameraId} cpu=${ar.cameraConfig.imageSize} gpu=${ar.cameraConfig.textureSize} depth=$depthSupported planeFinding=${ar.config.planeFindingMode}")
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
                    val key = wanted.plan?.arReferenceKey()
                    if (key != planKey) { invalidateAnchor(); planKey = key; gate.plan(key) }
                    val tracking = current.camera.trackingState == TrackingState.TRACKING
                    if (!tracking) {
                        val reason = current.camera.trackingFailureReason.toString()
                        if (reason != lastTrackingProblem) {
                            lastTrackingProblem = reason
                            Log.i("VlmArScale", "跟踪受阻 session=${sessionId.take(8)} reason=$reason state=${current.camera.trackingState}")
                        }
                        invalidateAnchor(); scene!!.hide(); groundDelivery.reset(); groundTracking.lost(); state(ArTrackingState.LOST)
                    } else {
                        lastTrackingProblem = null
                        val loadStart = SystemClock.elapsedRealtimeNanos()
                        wanted.plan?.takeIf { it.spatialPlacement != null }?.let { scene!!.load(it) }
                        performance.time("poseLoad",SystemClock.elapsedRealtimeNanos()-loadStart)
                        if (!wanted.backgroundConfirmed) {
                            if (anchor != null) invalidateAnchor()
                            placementRetry.reset()
                        } else if (wanted.plan != null) {
                            if (anchor?.trackingState == TrackingState.STOPPED) invalidateAnchor()
                            if (anchor == null && placementRetry.shouldAttempt(SystemClock.elapsedRealtimeNanos())) {
                                val placed = placeResolved(wanted.plan)
                                if (!placed && placementRetry.attempts == 1)
                                    Log.i("VlmArScale", "等待已校准地面站位 plan=${wanted.plan.id} revision=${wanted.plan.revision} screenReference=false")
                            }
                        }
                    }
                    val model = if (tracking && wanted.backgroundConfirmed && anchor?.trackingState == TrackingState.TRACKING)
                        FloatArray(16).also { matrix -> Matrix.setIdentityM(matrix, 0); val p = anchor!!.pose.translation
                            Matrix.translateM(matrix, 0, p[0], p[1], p[2]); Matrix.rotateM(matrix, 0, heading, 0f, 1f, 0f) } else null
                    // 找回背景和短暂丢失跟踪时仍保持同一取景；锚点只控制人像是否可见。
                    displayedCrop = if(wanted.plan?.photography != null) {
                        wanted.plan.photographyDisplayCrop(wanted.backgroundConfirmed)
                    } else if (wanted.render) wanted.plan?.spatialPlacement?.takeIf {
                        it.camera.sessionId == sessionId && it.camera.configVersion == configVersion
                    }?.framingCrop ?: CropRect() else CropRect()
                    val renderNow = SystemClock.elapsedRealtimeNanos()
                    if (ArPreviewCadence.shouldRender(renderNow, lastBackgroundRenderNs, wanted.render)) {
                        lastRenderedDepth = scene!!.render(current, if (wanted.render) model else null,
                            tracking && depthSupported && wanted.render && model != null, displayedCrop)
                        lastBackgroundRenderNs = renderNow
                        val renderTiming = scene!!.lastRenderTiming
                        performance.time("render",renderTiming.totalNs); performance.time("depth",renderTiming.depthNs)
                        performance.time("submit",renderTiming.submitNs); performance.time("gpuWait",renderTiming.waitNs)
                        performance.count(if (renderTiming.submitted) "submitted" else "skippedDraw")
                        if (renderTiming.submitted) queueOutputProbe()
                    }
                    // 先回传会话/跟踪状态，再回传地面；恢复首帧不能被旧 LOST 状态拦截。
                    if (tracking) {
                        state(if (model != null && scene!!.avatarPresented) ArTrackingState.PLACED
                            else if (wanted.backgroundConfirmed && wanted.plan != null) ArTrackingState.NEEDS_GROUND
                            else ArTrackingState.TRACKING, lastRenderedDepth && wanted.render && model != null)
                        publishGround(renderNow)
                    }
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
            val delay = ArPreviewCadence.delayMillis(workNs,request.render, !groundTracking.stable)
            nextTickNs = SystemClock.elapsedRealtimeNanos()+delay*1_000_000L
            handler.postDelayed(this,delay)
        }
    }
    private fun invalidateAnchor() { displayedCrop=CropRect(); gate.invalidate(); anchor?.detach(); anchor = null; scene?.hide(); manualAnchor = false; placementRetry.reset() }
    private fun groundSnapshot(observe: Boolean = false): ArGroundSnapshot {
        val all = session?.getAllTrackables(Plane::class.java).orEmpty()
        val tracking = all.filter { it.trackingState == TrackingState.TRACKING }
        val horizontal = tracking.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.subsumedBy == null }
        var invalidPolygons = 0
        val planes = horizontal.sortedByDescending { it.extentX * it.extentZ }.take(8).mapNotNull { plane ->
            val points = plane.polygon.duplicate(); points.rewind()
            val count = points.remaining()/2
            if (count !in 3..128) { invalidPolygons++; return@mapNotNull null }
            val vertices = List(count) {
                val p = plane.centerPose.transformPoint(floatArrayOf(points.get(),0f,points.get()))
                SpatialPoint(p[0],p[1],p[2])
            }
            if (vertices.any { !it.finite() }) { invalidPolygons++; return@mapNotNull null }
            val id = if (observe) groundTracking.observe(plane, vertices.first().y) else groundTracking.id(plane)
            ArGroundPlane(id,vertices,groundTracking.count(id))
        }
        if (observe) {
            groundTracking.retain(planes.map { it.id }.toSet())
            val diagnostics = "all=${all.size} tracking=${tracking.size} upward=${horizontal.size} invalidPolygons=$invalidPolygons accepted=${planes.size} stable=${planes.count { it.stableObservations >= 3 }}"
            if (diagnostics != groundDiagnostic) {
                groundDiagnostic = diagnostics
                Log.i("VlmArScale", "地面采集 session=${sessionId.take(8)} config=$configVersion $diagnostics trackingFailure=${frame?.camera?.trackingFailureReason}")
            }
        }
        return ArGroundSnapshot(sessionId,configVersion,planes)
    }
    private fun publishGround(now: Long) {
        if (now-groundSampleNs < 1_000_000_000L) return
        groundSampleNs = now
        val snapshot = groundSnapshot(true)
        if (!groundDelivery.shouldPublish(snapshot.fingerprint(), now)) return
        main.post { if (!disposed && sessionId == snapshot.sessionId && configVersion == snapshot.configVersion) onGround(snapshot) }
    }
    private fun placeResolved(plan: CompositionPlan): Boolean {
        val placement = plan.spatialPlacement ?: return false
        if (placement.camera.sessionId != sessionId || placement.camera.configVersion != configVersion || plan.arReferenceKey() != planKey) return false
        val pose = Pose.makeTranslation(placement.position.x,placement.position.y,placement.position.z)
        val next = if (placement.estimated) session?.createAnchor(pose) ?: return false else {
            val ground = session?.getAllTrackables(Plane::class.java)?.firstOrNull { plane ->
            plane.trackingState == TrackingState.TRACKING && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.subsumedBy == null &&
                groundTracking.findId(plane) == placement.groundId && groundTracking.count(placement.groundId) >= 3 &&
                kotlin.math.abs(plane.centerPose.ty()-placement.position.y) <= .05f &&
                plane.isPoseInPolygon(Pose.makeTranslation(placement.position.x,placement.position.y,placement.position.z))
        } ?: return false
            ground.createAnchor(Pose.makeTranslation(placement.position.x,placement.position.y,placement.position.z))
        }
        invalidateAnchor(); anchor = next; gate.plan(planKey); gate.place(sessionId,plan.arReferenceKey())
        manualAnchor = placement.manual; heading = placement.yaw
        Log.i("VlmArScale", "空间站位已放置 session=${sessionId.take(8)} plan=${plan.id} height=${placement.heightMeters} distance=${placement.distanceMeters} manual=$manualAnchor estimated=${placement.estimated}")
        return true
    }
    private fun config(current: Frame, image: Image): ScanCameraConfig {
        val corners = FloatArray(8)
        current.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f), Coordinates2d.IMAGE_NORMALIZED, corners)
        val xs = listOf(corners[0], corners[2], corners[4], corners[6]); val ys = listOf(corners[1], corners[3], corners[5], corners[7])
        val crop = CropRect(xs.min().coerceIn(0f,1f), ys.min().coerceIn(0f,1f), xs.max().coerceIn(0f,1f), ys.max().coerceIn(0f,1f))
        val intrinsics = current.camera.imageIntrinsics
        return ScanCameraConfig(configVersion, (cameraRotation - rotation * 90 + 360) % 360, 1f, crop, realtime,
            sessionId, intrinsics.focalLength.toList() + intrinsics.principalPoint.toList() + intrinsics.imageDimensions.map(Int::toFloat),
            if (current.camera.trackingState == TrackingState.TRACKING) current.camera.pose.translation.toList() + current.camera.pose.rotationQuaternion.toList() else emptyList(),
            if (current.camera.trackingState == TrackingState.TRACKING) scaleCamera(current,image,crop) else null)
    }
    private fun scaleCamera(current: Frame, image: Image, crop: CropRect): ArScaleSnapshot {
        val projection = FloatArray(16); val view = FloatArray(16)
        current.camera.getProjectionMatrix(projection,0,ArGeometry.NEAR,ArGeometry.FAR)
        current.camera.getViewMatrix(view,0)
        val points = FloatArray(6)
        current.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED,floatArrayOf(0f,0f,1f,0f,0f,1f),Coordinates2d.IMAGE_NORMALIZED,points)
        val orientation = (cameraRotation-rotation*90+360)%360
        val upright = List(3) { i ->
            val x = (points[i*2]-crop.left)/(crop.right-crop.left)
            val y = (points[i*2+1]-crop.top)/(crop.bottom-crop.top)
            when (orientation) { 90 -> Point2(1f-y,x); 180 -> Point2(1f-x,1f-y); 270 -> Point2(y,1f-x); else -> Point2(x,y) }
        }
        val adjusted = SpatialMatrices.imageProjection(projection.toList(),upright)
        val t = current.camera.pose.translation
        val w = ((crop.right-crop.left)*image.width).toInt().coerceAtLeast(1)
        val h = ((crop.bottom-crop.top)*image.height).toInt().coerceAtLeast(1)
        val depth=if (depthSupported) ArDepthSampler.capture(current,crop,orientation) else ArDepthSampler.Result(emptyList(),"UNSUPPORTED")
        val features = try {
            current.acquirePointCloud().use { cloud ->
                val values = cloud.points.duplicate()
                val count = values.remaining()/4
                val stride = maxOf(1,(count+127)/128)
                (0 until count step stride).mapNotNull { index ->
                    val base = values.position()+index*4
                    val point = SpatialPoint(values.get(base),values.get(base+1),values.get(base+2))
                    val confidence = values.get(base+3)
                    if (point.finite() && confidence.isFinite() && confidence >= .5f)
                        ArFeaturePoint(point,confidence) else null
                }
            }.also { lastPointCloudFailure = null }
        } catch (failure: Exception) {
            val name = failure.javaClass.simpleName
            if (lastPointCloudFailure != name) {
                lastPointCloudFailure = name
                Log.w("VlmArScale", "空间特征点暂不可用，继续其他比例估算 reason=$name", failure)
            }
            emptyList()
        }
        return ArScaleSnapshot(sessionId,configVersion,current.timestamp,view.toList(),adjusted,SpatialPoint(t[0],t[1],t[2]),
            if (orientation%180 == 0) w else h, if (orientation%180 == 0) h else w,
            depth.points,depth.status,features)
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
        val croppedBytes = cropPhotoToViewport(bytes)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Framer_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FramerSense"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(croppedBytes) } ?: error("AR_PHOTO_WRITE_FAILED")
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (failure: Exception) { context.contentResolver.delete(uri, null, null); throw failure }
    }
    /** JPEG 的 EXIF 先转正，再与当前预览视口同中心裁切；不包含 Filament 图像。 */
    private fun cropPhotoToViewport(bytes: ByteArray): ByteArray {
        val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        val matrix = android.graphics.Matrix().apply {
            postRotate(exif.rotationDegrees.toFloat())
            if (exif.isFlipped) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        // 保存开始后取景尺寸锁定，避免后台线程读取到下一次旋转的尺寸。
        val target = photoViewportRatio
        val viewport = centerCropToAspect(upright.width, upright.height, target)
        val rect = ArFraming.combine(viewport,photoFramingCrop)
        val w = ((rect.right - rect.left) * upright.width).toInt().coerceIn(1, upright.width)
        val h = ((rect.bottom - rect.top) * upright.height).toInt().coerceIn(1, upright.height)
        val x=(rect.left*upright.width).toInt().coerceIn(0,upright.width-w)
        val y=(rect.top*upright.height).toInt().coerceIn(0,upright.height-h)
        val cropped = Bitmap.createBitmap(upright,x,y,w,h)
        return try { java.io.ByteArrayOutputStream().use { out ->
            check(cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)); out.toByteArray()
        } } finally {
            if (cropped !== upright) cropped.recycle()
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }
    @Volatile private var photoViewportRatio = 3f / 4f
    @Volatile private var photoFramingCrop = CropRect()
    @Suppress("DEPRECATION")
    private fun beginPhoto(continuation: CancellableContinuation<ByteArray>) {
        if (!continuation.isActive) return
        if (capturing || closing || !running || jpeg == null) { continuation.resumeWithException(IllegalStateException("AR_PHOTO_UNAVAILABLE")); return }
        try {
            photoViewportRatio = width.toFloat() / height.coerceAtLeast(1)
            photoFramingCrop = displayedCrop
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
    private fun fail(failure: Exception) {
        val reason=ArFailure.from(failure)
        startupFailure=ArUiState(ArSource.SCREEN,ArTrackingState.UNSUPPORTED,failure=reason,failurePhase=startupStep)
        Log.e("VlmAr", "AR 失败 code=$reason phase=$startupStep generation=$generation session=${sessionId.take(8)}", failure)
        closeCamera(true)
    }
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
        if (disposed) { closed.complete(Unit); thread.quitSafely(); return }
        if (fallback) {
            val failure=startupFailure ?: ArUiState(ArSource.SCREEN,ArTrackingState.UNSUPPORTED,failure=ArFailure.SESSION_START)
            main.post { onState(failure) }
        }
        else if (foreground && surface?.isValid == true) open()
    }
    private val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
    suspend fun closeAndAwait() { close(); closed.await() }
    override fun close() { if (!closed.isCompleted) handler.post { if (!disposed) { disposed = true; closeCamera(false) } } }
}
