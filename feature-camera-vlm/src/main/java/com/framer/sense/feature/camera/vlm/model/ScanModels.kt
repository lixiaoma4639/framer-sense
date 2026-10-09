package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable

object ScanLimits {
    const val MAX_FRAMES = 32
    const val MAX_PREFERRED = 3
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_DURATION_NS = 90_000_000_000L
    const val SAMPLE_INTERVAL_NS = 250_000_000L
    const val MAX_UPLOAD_FRAMES = 8
    const val MAX_REQUEST_BYTES = 3L * 1024 * 1024
    const val MAX_SESSION_BYTES = 12L * 1024 * 1024
    const val MAX_MODEL_CALLS = 6
    const val MAX_SELECTION_CALLS = 2
    const val VIEW_ANGLE = 12f
}

@Serializable data class ScanPose(val basisId: String, val timestampNs: Long, val heading: Float?, val pitch: Float, val angularTravel: Float? = null)
@Serializable data class ScanCameraConfig(val version: Int, val rotation: Int, val zoom: Float,
    val crop: CropRect, val realtimeTimestamp: Boolean,
    val arSessionId: String? = null, val cameraIntrinsics: List<Float> = emptyList(),
    val arCameraPose: List<Float> = emptyList())
data class ScanSession(val id: String, val startedNs: Long, val endedNs: Long? = null)
data class ScanFrame(val id: String, val scanId: String, val timestampNs: Long, val receivedNs: Long,
    val config: ScanCameraConfig, val pose: ScanPose?, val quality: Float, val scene: SceneSnapshot,
    val bytes: Long, val preferred: Boolean = false)
data class ScanSnapshot(val session: ScanSession, val frames: List<ScanFrame>, val totalTurn: Float)
data class ScanProgress(val frames: Int = 0, val preferred: Int = 0, val elapsedSeconds: Int = 0,
    val totalTurn: Float = 0f, val paused: Boolean = false)
@Serializable data class PhotoViewBinding(val scanId: String, val frameId: String, val configVersion: Int, val arSessionId: String? = null)
@Serializable data class ViewCandidate(val frameId: String, val subject: String, val reason: String,
    val backgroundRegions: List<CropRect>, val subjectRegions: List<CropRect>, val uncertainties: List<String>)
@Serializable data class ViewSelection(val version: Int = 2, val scanId: String,
    val primary: ViewCandidate, val alternatives: List<ViewCandidate>)
@Serializable data class ViewImage(val frameId: String, val imageBase64: String, val preferred: Boolean,
    val width: Int, val height: Int)
@Serializable data class ViewSelectionRequest(val provider: ProviderId, val requestId: String, val scanId: String,
    val images: List<ViewImage>, val prompt: String, val version: Int = 2)
@Serializable data class ViewSelectionReply(val model: String, val raw: String, val selection: ViewSelection? = null,
    val elapsedMs: Long = 0)
@Serializable data class MultiImageCapability(val provider: ProviderId, val model: String,
    val configured: Boolean, val multiImage: Boolean, val maxImages: Int, val directorV2: Boolean = false)
@Serializable data class MultiImageCapabilities(val providers: List<MultiImageCapability>, val version: Int = 2)

enum class BackgroundStatus { SEARCHING, ALIGNED_AUTO, ALIGNED_MANUAL }
data class BackgroundGuidance(val status: BackgroundStatus = BackgroundStatus.SEARCHING,
    val headingError: Float? = null, val pitchError: Float? = null, val matched: Boolean = false)
