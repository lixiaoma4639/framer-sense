package com.framer.sense.feature.camera.vlm.data

import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.Serializable

/** 与运行时类型分开，显式保留 SceneSnapshot 的 Transient 几何和来源字段。 */
@Serializable internal data class HistoryScene(
    val id: String, val width: Int, val height: Int, val zoom: Float, val image: String, val modelImage: String,
    val orientation: PhotoOrientation, val binding: PhotoViewBinding?
)
@Serializable internal data class HistoryFrame(
    val id: String, val scanId: String, val timestampNs: Long, val receivedNs: Long, val config: ScanCameraConfig,
    val pose: ScanPose?, val quality: Float, val sceneId: String, val bytes: Long, val preferred: Boolean
)
@Serializable internal data class HistoryScan(
    val id: String, val startedNs: Long, val endedNs: Long?, val totalTurn: Float, val frames: List<HistoryFrame>
)
@Serializable internal data class HistoryContext(
    val version: Int = 1, val aspectRatio: CaptureAspectRatio, val imported: Boolean,
    val scenes: List<HistoryScene>, val scan: HistoryScan?, val views: List<ViewCandidate>
)
@Serializable internal data class HistoryCandidate(
    val id: String, val batchId: String, val sceneId: String, val provider: ProviderId, val modelId: String,
    val instruction: String, val variation: Int, val binding: PhotoViewBinding?, val revision: Int,
    val assets: GeneratedReferenceAssets, val order: Int, val preferences: GeneratedPortraitPreferences,
    val sceneDescription: String, val styleIndex: Int, val style: GeneratedStyleChoice
)

data class HistoryCapture(
    val generated: GeneratedReferenceState, val scene: SceneSnapshot?, val scan: ScanSnapshot?,
    val views: List<ViewCandidate>, val aspectRatio: CaptureAspectRatio, val imported: Boolean
)
data class RestoredCameraHistory(
    val generated: GeneratedReferenceState, val scan: ScanSnapshot?, val views: List<ViewCandidate>,
    val aspectRatio: CaptureAspectRatio, val imported: Boolean, val instruction: String
)
