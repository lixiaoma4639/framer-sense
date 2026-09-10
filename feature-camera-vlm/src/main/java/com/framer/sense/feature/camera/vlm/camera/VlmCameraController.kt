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
import com.framer.sense.feature.camera.vlm.model.CameraCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 只负责平台相机资源；UI 销毁时必须解绑，不持有 ViewModel。 */
class VlmCameraController(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var zoomOperation: ListenableFuture<Void>? = null
    private val executor = ContextCompat.getMainExecutor(context)

    /** 绑定同一视口的预览与拍照。
     * @param view 已完成布局的预览控件。
     * @param owner 当前 UI 生命周期。
     * @param initialZoom 恢复方案时需要设置的倍率。
     * @return 当前设备允许的变焦范围和常用倍率。
     */
    suspend fun bind(view: PreviewView, owner: LifecycleOwner, initialZoom: Float): CameraCapabilities {
        val future = ProcessCameraProvider.getInstance(context)
        val instance = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get()) } catch (e: Exception) { continuation.resumeWithException(e) }
                }
            }, executor)
        }
        unbind()
        check(view.width > 0 && view.height > 0) { "相机预览尚未完成布局" }
        val rotation = view.display.rotation
        val p = Preview.Builder().setTargetRotation(rotation).build()
        val c = ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        p.setSurfaceProvider(view.surfaceProvider)
        val group = UseCaseGroup.Builder().setViewPort(ViewPort.Builder(Rational(view.width, view.height), rotation).setScaleType(ViewPort.FILL_CENTER).build()).addUseCase(p).addUseCase(c).build()
        provider = instance
        preview = p
        capture = c
        camera = instance.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        val state = camera!!.cameraInfo.zoomState.value
        val minimum = state?.minZoomRatio ?: 1f
        val maximum = state?.maxZoomRatio ?: 1f
        zoomOperation = camera!!.cameraControl.setZoomRatio(initialZoom.coerceIn(minimum, maximum))
        awaitZoom()
        return CameraCapabilities(minimum, maximum, (listOf(minimum, 1f, 2f, 3f, maximum)).filter { it in minimum..maximum }.distinct().sorted())
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
    suspend fun freeze(): Pair<Bitmap, Float> {
        val active = capture ?: error("相机尚未准备完成")
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
    suspend fun capturePhoto(): Uri? {
        val active = capture ?: error("相机尚未准备完成")
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
    fun unbind() {
        val useCases = listOfNotNull(preview, capture).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        zoomOperation = null
        camera = null
        preview = null
        capture = null
    }
}
