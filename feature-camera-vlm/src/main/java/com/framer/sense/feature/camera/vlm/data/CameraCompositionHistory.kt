package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.framer.sense.core.data.*
import com.framer.sense.feature.camera.vlm.agent.GeneratedPortraitStylePolicy
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream

interface CameraCompositionHistory {
    val entries: Flow<List<CameraHistoryEntry>>
    suspend fun save(capture: HistoryCapture, candidate: GeneratedCandidate, title: String, modelName: String)
    suspend fun load(id: String): RestoredCameraHistory
    suspend fun pin(id: String, pinned: Boolean)
    suspend fun select(id: String, candidateId: String)
    suspend fun styles(id: String, keys: Set<String>)
    suspend fun delete(id: String)
    suspend fun flush()
}

/** 写入任务归应用生命周期，页面销毁不会截断已经收到的成功结果；清理源缓存前 flush。 */
class LocalCameraCompositionHistory(context: Context, private val records: CameraHistoryRepository) : CameraCompositionHistory {
    private val files = context.filesDir.canonicalFile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pending = mutableSetOf<Job>()
    private fun <T> enqueue(block: suspend () -> T): Deferred<T> {
        val task = scope.async(start = CoroutineStart.LAZY) { mutex.withLock { block() } }
        synchronized(pending) { pending += task }
        task.invokeOnCompletion { synchronized(pending) { pending -= task } }
        task.start()
        return task
    }
    override suspend fun flush() {
        while (true) {
            val jobs = synchronized(pending) { pending.toList() }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }
    private fun safeId(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,160}"))) }
    private fun file(path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute)
        return File(files, path).canonicalFile.also { require(it.path.startsWith(files.path + File.separator)) }
    }
    private fun relative(path: String): String = File(path).canonicalFile.let {
        require(it.path.startsWith(files.path + File.separator))
        it.relativeTo(files).invariantSeparatorsPath
    }
    private fun existing(path: String) = file(path).also { require(it.isFile && it.length() > 0) }
    private fun atomicCopy(source: File, target: File) {
        if (target.isFile && target.length() > 0) return
        require(source.isFile && source.length() > 0)
        target.parentFile!!.mkdirs()
        val staging = File(target.path + ".pending")
        try {
            FileOutputStream(staging).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            check(staging.renameTo(target))
        } finally { staging.delete() }
    }
    override val entries = records.entries.map { rows -> rows.map { row ->
        row.copy(thumbnail = runCatching { file(row.thumbnail).path }.getOrDefault(""))
    } }

    override suspend fun save(capture: HistoryCapture, candidate: GeneratedCandidate, title: String, modelName: String) {
        enqueue {
            val sessionId = safeId(capture.generated.historyId)
            val assets = requireNotNull(candidate.assets)
            require(candidate.ready)
            require(capture.generated.candidates.any { it.id == candidate.id && it.batchId == candidate.batchId &&
                it.scene.id == candidate.scene.id && it.revision == candidate.revision && it.model == candidate.model })
            val allScenes = (capture.scan?.frames.orEmpty().map { it.scene } +
                capture.generated.candidates.map { it.scene } + listOfNotNull(capture.scene, candidate.scene)).distinctBy { it.id }
            val scenes = allScenes.map { scene ->
                val dir = "camera-history/$sessionId/scenes/${safeId(scene.id)}"
                val image = "$dir/scene.jpg"; val input = "$dir/input.jpg"
                atomicCopy(File(scene.imagePath), file(image)); atomicCopy(File(scene.modelImagePath), file(input))
                HistoryScene(scene.id, scene.width, scene.height, scene.currentZoom, image, input, scene.orientation, scene.captureBinding)
            }
            val scan = capture.scan?.let { snapshot -> HistoryScan(snapshot.session.id, snapshot.session.startedNs,
                snapshot.session.endedNs, snapshot.totalTurn, snapshot.frames.map { frame ->
                    HistoryFrame(frame.id, frame.scanId, frame.timestampNs, frame.receivedNs, frame.config,
                        frame.pose, frame.quality, frame.scene.id, frame.bytes, frame.preferred)
                }) }
            val context = HistoryContext(aspectRatio = capture.aspectRatio, imported = capture.imported, scenes = scenes,
                scan = scan, views = capture.views)
            val storedAssets = assets.copy(compositePath = relative(assets.compositePath),
                portraitPath = assets.portraitPath.takeIf(String::isNotBlank)?.let(::relative).orEmpty())
            existing(storedAssets.compositePath)
            if (storedAssets.hasPortrait) existing(storedAssets.portraitPath)
            val style = candidate.styleChoice ?: GeneratedPortraitStylePolicy.choice(candidate.styleIndex)
            val stored = HistoryCandidate(candidate.id, candidate.batchId, candidate.scene.id, candidate.model.provider,
                candidate.model.apiId, candidate.instruction, candidate.variation, candidate.viewBinding, candidate.revision,
                storedAssets, candidate.completedOrder ?: capture.generated.candidates.count { it.ready }, candidate.portraitPreferences,
                candidate.sceneDescription, candidate.styleIndex, style)
            val thumbnail = "camera-history/$sessionId/thumbnail.jpg"
            val first = (capture.generated.candidates.filter { it.ready } + candidate)
                .minWithOrNull(compareBy<GeneratedCandidate> { it.completedAt ?: Long.MAX_VALUE }.thenBy { it.completedOrder ?: Int.MAX_VALUE })!!
            if (!file(thumbnail).isFile) createThumbnail(File(requireNotNull(first.assets).compositePath), file(thumbnail))
            val now = candidate.completedAt ?: System.currentTimeMillis()
            val entry = CameraHistoryEntry(sessionId, title, modelName, thumbnail, first.completedAt ?: now, now, null)
            val previousKeys = records.usedStyles(sessionId)?.let { VlmJson.decodeFromString<Set<String>>(it) }.orEmpty()
            val keys = previousKeys + capture.generated.usedStyleKeys + capture.generated.candidates.map {
                (it.styleChoice ?: GeneratedPortraitStylePolicy.choice(it.styleIndex)).key
            } + style.key
            records.append(CameraHistoryRecord(entry, capture.generated.selectedId ?: candidate.id, VlmJson.encodeToString(keys),
                VlmJson.encodeToString(context), emptyList()), CameraHistoryResult(candidate.id, now, VlmJson.encodeToString(stored)))
        }.await()
    }

    private fun createThumbnail(source: File, target: File) {
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, info)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(info.outWidth, info.outHeight) / inSampleSize > 512) inSampleSize *= 2
        }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, options))
        val staging = File(target.path + ".pending")
        try {
            target.parentFile!!.mkdirs()
            FileOutputStream(staging).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)); it.fd.sync() }
            check(staging.renameTo(target))
        } finally { bitmap.recycle(); staging.delete() }
    }

    override suspend fun load(id: String): RestoredCameraHistory = withContext(Dispatchers.IO) { mutex.withLock {
        val record = requireNotNull(records.load(id))
        val context = VlmJson.decodeFromString<HistoryContext>(record.context)
        require(context.version == 1)
        val scenes = context.scenes.associate { scene -> scene.id to SceneSnapshot(scene.id, scene.width, scene.height, scene.zoom,
            existing(scene.image).path, existing(scene.modelImage).path, scene.binding, scene.orientation) }
        val candidates = record.results.map { result ->
            val c = VlmJson.decodeFromString<HistoryCandidate>(result.payload)
            val assets = c.assets.copy(compositePath = existing(c.assets.compositePath).path,
                portraitPath = c.assets.portraitPath.takeIf(String::isNotBlank)?.let { existing(it).path }.orEmpty())
            GeneratedCandidate(c.id, c.batchId, scenes.getValue(c.sceneId), HistoricalImageModel(c.provider, c.modelId), c.instruction,
                c.variation, c.binding, revision = c.revision, status = GeneratedCandidateStatus.READY, assets = assets,
                completedOrder = c.order, portraitPreferences = c.preferences, sceneDescription = c.sceneDescription,
                styleIndex = c.styleIndex, styleChoice = c.style, completedAt = result.completedAt)
        }.sortedBy { it.completedOrder }
        require(candidates.isNotEmpty())
        val selected = record.selectedId?.takeIf { value -> candidates.any { it.id == value } } ?: candidates.last().id
        val scan = context.scan?.let { s -> ScanSnapshot(ScanSession(s.id, s.startedNs, s.endedNs), s.frames.map { frame ->
            ScanFrame(frame.id, frame.scanId, frame.timestampNs, frame.receivedNs, frame.config,
                null, frame.quality, scenes.getValue(frame.sceneId), frame.bytes, frame.preferred)
        }, s.totalTurn) }
        RestoredCameraHistory(GeneratedReferenceState(epoch = newVlmId(), candidates = candidates, selectedId = selected,
            historyId = record.entry.id, usedStyleKeys = VlmJson.decodeFromString(record.usedStyles)), scan, context.views,
            context.aspectRatio, context.imported, candidates.last().instruction)
    } }

    override suspend fun pin(id: String, pinned: Boolean) { enqueue { records.pin(id, if (pinned) System.currentTimeMillis() else null) }.await() }
    override suspend fun select(id: String, candidateId: String) { enqueue { records.select(id, candidateId) }.await() }
    override suspend fun styles(id: String, keys: Set<String>) {
        enqueue {
            val previous = records.usedStyles(id) ?: return@enqueue
            records.styles(id, VlmJson.encodeToString(VlmJson.decodeFromString<Set<String>>(previous) + keys))
        }.await()
    }
    override suspend fun delete(id: String) {
        enqueue {
            val record = records.load(id) ?: return@enqueue
            records.delete(id)
            file("camera-history/${safeId(id)}").deleteRecursively()
            record.results.forEach { result ->
                runCatching { VlmJson.decodeFromString<HistoryCandidate>(result.payload) }.getOrNull()?.let {
                    file("generated-references/${safeId(it.id)}").deleteRecursively()
                }
            }
        }.await()
    }
}
