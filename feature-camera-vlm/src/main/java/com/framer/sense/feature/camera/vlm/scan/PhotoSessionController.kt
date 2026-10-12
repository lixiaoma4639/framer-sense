package com.framer.sense.feature.camera.vlm.scan

import android.content.Context
import android.graphics.*
import androidx.core.graphics.scale
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

/** 相机已经关闭 ImageProxy，缓冲区的所有权交给串行会话工作者。 */
data class AnalysisSample(val targetId: String, val timestampNs: Long, val receivedNs: Long,
    val config: ScanCameraConfig, val pose: ScanPose?, val width: Int, val height: Int, val nv21: ByteArray) {
    fun bitmap(): Bitmap {
        val decoded = YuvBitmapDecoder.decode(nv21, width, height)
        val r = config.crop
        val x = (r.left * width).toInt().coerceIn(0, width - 1); val y = (r.top * height).toInt().coerceIn(0, height - 1)
        val w = ((r.right - r.left) * width).toInt().coerceIn(1, width - x); val h = ((r.bottom - r.top) * height).toInt().coerceIn(1, height - y)
        val upright = Bitmap.createBitmap(decoded, x, y, w, h, Matrix().apply { postRotate(config.rotation.toFloat()) }, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }
}

/** 生命周期归 ViewModel 所有；文件操作与特征处理串行，模型预算归扫描会话所有。 */
class PhotoSessionController(context: Context, private val vision: OpenCvSceneTools = OpenCvSceneTools()) {
    private val processingDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "vlm-scan-analysis")
    }.asCoroutineDispatcher()
    private val root = File(context.cacheDir, "vlm-scans/${newVlmId()}")
    init { synchronized(activeRoots) { activeRoots += root.path; root.mkdirs() } }
    private val mutex = Mutex()
    private var collector: ScanCollector? = null
    private val features = mutableMapOf<String, SceneFeatures?>()
    var budget = PhotoSessionBudget(); private set
    private var referenceId: String? = null
    private var referenceFeatures: SceneFeatures? = null
    private val stability = BackgroundStability()
    @Volatile private var latestConfig = 0
    private val referenceEpoch = AtomicInteger()

    suspend fun start(atNs: Long): ScanSession = withContext(Dispatchers.IO) { mutex.withLock {
        // 所有本控制器的文件读写均持有此锁；切换后旧帧会因 ID 不同被拒绝。
        synchronized(activeRoots) {
            root.parentFile?.listFiles()?.filter { it.path !in activeRoots }?.forEach { it.deleteRecursively() }
        }
        root.listFiles()?.forEach { it.deleteRecursively() }
        val session = ScanSession(newVlmId(), atNs)
        collector = ScanCollector(session); features.clear(); budget = PhotoSessionBudget(); resetReference()
        session
    } }
    fun configure(version: Int) { latestConfig = version; collector?.configure(version); resetReference() }
    fun stop(atNs: Long) { collector?.stop(atNs) }
    fun pause() { collector?.pause() }
    fun progress(atNs: Long) = collector?.progress(atNs) ?: ScanProgress()
    fun prefer(atNs: Long) = collector?.preferLatest(atNs) == true

    suspend fun collect(sample: AnalysisSample): ScanProgress = withContext(processingDispatcher) { mutex.withLock {
        val active = collector ?: return@withLock ScanProgress()
        if (!active.accepts(sample.targetId, sample.timestampNs, sample.receivedNs, sample.config)) return@withLock active.progress(sample.receivedNs)
        active.observePose(sample.pose)
        val bitmap = sample.bitmap()
        try {
            val feature = vision.extract(bitmap)
            if (feature?.unusable == true) return@withLock active.progress(sample.receivedNs)
            val retained = active.frames()
            // 姿态不可用时也只用强视觉验证合并，不仅凭图像哈希认定相同背景。
            val duplicate = retained.asReversed().firstOrNull { old ->
                (sample.pose == null && old.pose == null || sameDirection(old.pose, sample.pose)) &&
                    vision.match(features[old.id], feature)?.let(BackgroundMatchPolicy::aligned) == true
            }
            if (duplicate != null && (duplicate.preferred || (feature?.quality ?: 0f) <= duplicate.quality * 1.05f)) return@withLock active.progress(sample.receivedNs)
            if (!active.accepts(sample.targetId, sample.timestampNs, sample.receivedNs, sample.config)) return@withLock active.progress(sample.receivedNs)
            val id = newVlmId(); val dir = File(root, "${active.session.id}/$id").apply { mkdirs() }
            try {
                val scene = save(bitmap, sample.config.zoom, id, dir)
                val bytes = dir.listFiles().orEmpty().sumOf { it.length() }
                val frame = ScanFrame(id, active.session.id, sample.timestampNs, sample.receivedNs, sample.config, sample.pose, feature?.quality ?: 0f, scene, bytes)
                if (!active.accepts(sample.targetId, sample.timestampNs, sample.receivedNs, sample.config)) {
                    dir.deleteRecursively(); return@withLock active.progress(sample.receivedNs)
                }
                val removed = active.add(frame, duplicate?.id)
                if (removed?.id == frame.id) dir.deleteRecursively() else {
                    features[id] = feature
                    removed?.let { File(root, "${active.session.id}/${it.id}").deleteRecursively(); features.remove(it.id) }
                }
            } catch (failure: Throwable) { dir.deleteRecursively(); throw failure }
            active.progress(sample.receivedNs)
        } finally { bitmap.recycle() }
    } }

    suspend fun finish(): ScanSnapshot = mutex.withLock { requireNotNull(collector).snapshot() }
    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        collector = null; features.clear(); root.listFiles()?.forEach { it.deleteRecursively() }; resetReference()
    } }

    suspend fun align(sample: AnalysisSample, plan: CompositionPlan, scene: SceneSnapshot, targetPose: ScanPose?): BackgroundGuidance =
        withContext(processingDispatcher) { mutex.withLock {
            if (sample.config.version != latestConfig) return@withLock BackgroundGuidance()
            val key = "${scene.id}:${plan.revision}:${plan.crop}:${plan.avatar}:${sample.config.version}:${referenceEpoch.get()}"
            if (referenceId != key) {
                val original = requireNotNull(BitmapFactory.decodeFile(scene.imagePath))
                val r = plan.crop
                val x = (r.left * original.width).toInt().coerceIn(0, original.width - 1)
                val y = (r.top * original.height).toInt().coerceIn(0, original.height - 1)
                val cropped = Bitmap.createBitmap(original, x, y,
                    ((r.right - r.left) * original.width).toInt().coerceIn(1, original.width - x), ((r.bottom - r.top) * original.height).toInt().coerceIn(1, original.height - y))
                try {
                    val a = plan.avatar
                    val exclude = CropRect((a.foot.x - a.height * .3f).coerceIn(0f, 1f), (a.foot.y - a.height).coerceIn(0f, 1f),
                        (a.foot.x + a.height * .3f).coerceIn(0f, 1f), a.foot.y.coerceIn(0f, 1f))
                    referenceFeatures = vision.extract(cropped, exclude)
                    referenceId = key; stability.reset()
                } finally { if (cropped !== original) cropped.recycle(); original.recycle() }
            }
            val bitmap = sample.bitmap()
            // AR 不切镜头：选中图和当前原图采用同一裁切后再匹配，避免裁切被误判成倍率不一致。
            val r=if (plan.spatialPlacement != null) plan.crop else CropRect()
            val x=(r.left*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
            val y=(r.top*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
            val matching=if (plan.spatialPlacement != null) Bitmap.createBitmap(bitmap,x,y,
                ((r.right-r.left)*bitmap.width).toInt().coerceIn(1,bitmap.width-x),
                ((r.bottom-r.top)*bitmap.height).toInt().coerceIn(1,bitmap.height-y)) else bitmap
            val metrics = try { vision.match(referenceFeatures, vision.extract(matching)) } finally {
                if (matching !== bitmap) matching.recycle(); bitmap.recycle()
            }
            val aligned = stability.update(metrics?.let(BackgroundMatchPolicy::aligned) == true, sample.receivedNs)
            val sameBasis = targetPose != null && sample.pose != null && targetPose.basisId == sample.pose.basisId
            BackgroundGuidance(if (aligned) BackgroundStatus.ALIGNED_AUTO else BackgroundStatus.SEARCHING,
                if (sameBasis && targetPose.heading != null && sample.pose.heading != null) angleDelta(targetPose.heading, sample.pose.heading) else null,
                if (sameBasis) targetPose.pitch - sample.pose.pitch else null,
                metrics?.let(BackgroundMatchPolicy::confident) == true)
        } }

    suspend fun dispose() {
        try {
            clear()
            withContext(Dispatchers.IO) { synchronized(activeRoots) { root.deleteRecursively(); activeRoots.remove(root.path) } }
        } finally { processingDispatcher.close() }
    }

    fun resetReference() { referenceEpoch.incrementAndGet() }

    private companion object { val activeRoots = mutableSetOf<String>() }

    private fun save(bitmap: Bitmap, zoom: Float, id: String, dir: File): SceneSnapshot {
        val display = File(dir, "scene.jpg"); display.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        val ratio = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
        val small = bitmap.scale(maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()))
        val input = File(dir, "input.jpg")
        try { input.outputStream().use { check(small.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
        finally { if (small !== bitmap) small.recycle() }
        return SceneSnapshot(id, bitmap.width, bitmap.height, zoom, display.path, input.path)
    }
}
