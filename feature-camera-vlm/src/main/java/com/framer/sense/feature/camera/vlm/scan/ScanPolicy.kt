package com.framer.sense.feature.camera.vlm.scan

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.*

/** 纯状态逻辑可在 JVM 回放；文件和原生特征由平台层管理。 */
class ScanCollector(val session: ScanSession) {
    private var endedNs: Long? = null
    private var configVersion = 0
    private val retained = mutableListOf<ScanFrame>()
    private var previousHeading: Float? = null
    private var previousBasis: String? = null
    private var previousTravel: Float? = null
    private var turn = 0f
    private var full = false
    @Synchronized fun configure(version: Int) { configVersion = version }
    @Synchronized fun accepts(scanId: String, capturedNs: Long, receivedNs: Long, config: ScanCameraConfig): Boolean =
        scanId == session.id && config.version == configVersion && !full &&
            receivedNs >= session.startedNs && receivedNs <= (endedNs ?: Long.MAX_VALUE) &&
            receivedNs - session.startedNs <= ScanLimits.MAX_DURATION_NS &&
            (!config.realtimeTimestamp || capturedNs in session.startedNs..(endedNs ?: Long.MAX_VALUE))
    @Synchronized fun stop(atNs: Long) { if (endedNs == null) endedNs = max(atNs, session.startedNs) }
    @Synchronized fun pause() { full = true }
    @Synchronized fun isPaused() = full
    @Synchronized fun observePose(pose: ScanPose?) {
        val h = pose?.heading
        if (pose != null && previousBasis == pose.basisId) {
            if (pose.angularTravel != null && previousTravel != null) turn += (pose.angularTravel - previousTravel!!).coerceAtLeast(0f)
            else if (h != null) previousHeading?.let { turn += abs(angleDelta(h, it)) }
        }
        previousTravel = pose?.angularTravel
        previousHeading = h
        previousBasis = pose?.basisId
    }
    @Synchronized fun frames(): List<ScanFrame> = retained.toList()
    @Synchronized fun add(frame: ScanFrame, duplicateId: String?): ScanFrame? {
        require(frame.scanId == session.id)
        val duplicate = retained.firstOrNull { it.id == duplicateId }
        if (duplicate != null && (duplicate.preferred || frame.quality <= duplicate.quality * 1.05f)) return frame
        val replacement = duplicate ?: if (retained.size >= ScanLimits.MAX_FRAMES)
            retained.filterNot { it.preferred }.minByOrNull { old ->
                val spacing = retained.filter { it.id != old.id }.map { other -> viewDistance(old, other) }.sorted().take(2).sum()
                spacing + old.quality.coerceIn(0f, 20f)
            } else null
        if (retained.size >= ScanLimits.MAX_FRAMES && replacement == null) return frame
        if (duplicate == null && replacement != null) {
            val nearest = retained.minByOrNull { viewDistance(frame, it) }
            if (nearest != null && frame.pose?.heading != null && nearest.pose?.heading != null && frame.pose.basisId == nearest.pose.basisId && viewDistance(frame, nearest) < ScanLimits.VIEW_ANGLE && frame.quality <= nearest.quality * 1.05f) return frame
        }
        val projected = retained.sumOf { it.bytes } - (replacement?.bytes ?: 0) + frame.bytes
        if (projected > ScanLimits.MAX_FILE_BYTES) { full = true; return frame }
        if (replacement != null) retained.remove(replacement)
        retained += frame
        return replacement
    }
    @Synchronized fun preferLatest(nowNs: Long): Boolean {
        val latest = retained.maxByOrNull { it.receivedNs } ?: return false
        if (nowNs - latest.receivedNs > 500_000_000L || retained.count { it.preferred } >= ScanLimits.MAX_PREFERRED) return false
        val index = retained.indexOf(latest)
        retained[index] = latest.copy(preferred = true)
        return true
    }
    @Synchronized fun progress(nowNs: Long) = ScanProgress(retained.size, retained.count { it.preferred },
        ((nowNs - session.startedNs).coerceAtLeast(0) / 1_000_000_000L).toInt(), turn, full)
    @Synchronized fun snapshot(): ScanSnapshot {
        val end = requireNotNull(endedNs)
        return ScanSnapshot(session.copy(endedNs = end), java.util.Collections.unmodifiableList(retained.toList()), turn)
    }
}

private fun viewDistance(a: ScanFrame, b: ScanFrame): Float {
    val x = a.pose; val y = b.pose
    return if (x?.heading != null && y?.heading != null && x.basisId == y.basisId)
        hypot(angleDelta(x.heading, y.heading), x.pitch - y.pitch)
    else (abs(a.receivedNs - b.receivedNs) / 1_000_000_000f).coerceAtMost(180f)
}

fun angleDelta(target: Float, current: Float): Float = (((target - current) % 360f + 540f) % 360f) - 180f
fun sameDirection(a: ScanPose?, b: ScanPose?): Boolean = a != null && b != null && a.basisId == b.basisId &&
    abs(a.pitch - b.pitch) < ScanLimits.VIEW_ANGLE && a.heading != null && b.heading != null &&
    abs(angleDelta(a.heading, b.heading)) < ScanLimits.VIEW_ANGLE

/** 仅云端真实调用前扣减，失败、取消和切换不退还或重置；离线不读取或消耗此预算。 */
class PhotoSessionBudget {
    var calls: Int = 0; private set
    var uploadedBytes: Long = 0; private set
    var selectionCalls: Int = 0; private set
    @Synchronized fun consume(bytes: Long, selection: Boolean = false) {
        require(bytes >= 0)
        check(calls < ScanLimits.MAX_MODEL_CALLS && uploadedBytes + bytes <= ScanLimits.MAX_SESSION_BYTES && (!selection || selectionCalls < ScanLimits.MAX_SELECTION_CALLS)) { "SESSION_BUDGET_EXHAUSTED" }
        calls++
        if (selection) selectionCalls++
        uploadedBytes += bytes
    }
}

object ViewSelectionValidator {
    fun validate(selection: ViewSelection, scanId: String, sentIds: Set<String>) {
        require(selection.version == 2 && selection.scanId == scanId && selection.alternatives.size <= 2) { "INVALID_VIEW_SELECTION" }
        val views = listOf(selection.primary) + selection.alternatives
        require(views.map { it.frameId }.distinct().size == views.size && views.all { it.frameId in sentIds }) { "INVALID_VIEW_FRAME" }
        views.forEach { view ->
            require(view.subject.isNotBlank() && view.reason.isNotBlank() && view.backgroundRegions.size <= 8 && view.subjectRegions.size <= 8)
            (view.backgroundRegions + view.subjectRegions).forEach { r ->
                require(listOf(r.left, r.top, r.right, r.bottom).all { it.isFinite() } &&
                    r.left >= 0 && r.top >= 0 && r.right <= 1 && r.bottom <= 1 && r.right > r.left && r.bottom > r.top) { "INVALID_VIEW_REGION" }
            }
        }
    }
}

data class MatchMetrics(val matches: Int, val inliers: Int, val coverage: Float, val quadrants: Int,
    val cornerError: Float, val rotation: Float, val scale: Float, val valid: Boolean)
object BackgroundMatchPolicy {
    const val STABLE_NS = 700_000_000L
    const val LONG_SIDE = 640
    const val MAX_FEATURES = 800
    const val ANALYSIS_INTERVAL_NS = 200_000_000L
    const val RATIO_TEST = .75f
    const val RANSAC_PIXELS = 3.0
    const val MIN_MATCHES = 30
    const val MIN_INLIERS = 15
    const val MIN_INLIER_RATIO = .5f
    const val MIN_COVERAGE = .20f
    const val MIN_QUADRANTS = 3
    const val MAX_CORNER_ERROR = .03f
    const val MAX_ROTATION = 3f
    const val MAX_SCALE_ERROR = .08f
    fun confident(m: MatchMetrics) = m.valid && m.matches >= MIN_MATCHES && m.inliers >= MIN_INLIERS &&
        m.inliers.toFloat() / m.matches >= MIN_INLIER_RATIO && m.coverage >= MIN_COVERAGE && m.quadrants >= MIN_QUADRANTS
    fun aligned(m: MatchMetrics) = confident(m) && m.cornerError <= MAX_CORNER_ERROR && abs(m.rotation) <= MAX_ROTATION && abs(m.scale - 1f) <= MAX_SCALE_ERROR
}
class BackgroundStability {
    private var since: Long? = null
    fun update(aligned: Boolean, atNs: Long): Boolean {
        if (!aligned) { since = null; return false }
        if (since == null) since = atNs
        return atNs - since!! >= BackgroundMatchPolicy.STABLE_NS
    }
    fun reset() { since = null }
}
