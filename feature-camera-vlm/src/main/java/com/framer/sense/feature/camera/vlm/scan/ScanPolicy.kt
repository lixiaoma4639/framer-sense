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
        val duplicate = retained.firstOrNull { it.id == duplicateId && it.config.orientation.deviceRotation == frame.config.orientation.deviceRotation }
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
    val orientationDistance = if (a.config.orientation.deviceRotation == b.config.orientation.deviceRotation) 0f else 90f
    return orientationDistance + if (x?.heading != null && y?.heading != null && x.basisId == y.basisId)
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
        require(selection.version == 2) { "INVALID_VIEW_VERSION" }
        require(selection.scanId == scanId) { "INVALID_VIEW_SCAN_ID" }
        require(selection.alternatives.size <= 2) { "INVALID_VIEW_ALTERNATIVES" }
        val views = listOf(selection.primary) + selection.alternatives
        require(views.map { it.frameId }.distinct().size == views.size && views.all { it.frameId in sentIds }) { "INVALID_VIEW_FRAME" }
        views.forEach { view ->
            require(view.subject.isNotBlank()) { "EMPTY_VIEW_SUBJECT" }
            require(view.reason.isNotBlank()) { "EMPTY_VIEW_REASON" }
            require(view.backgroundRegions.size <= 8 && view.subjectRegions.size <= 8) { "INVALID_VIEW_REGION_COUNT" }
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
    const val STABLE_NS = 500_000_000L
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
    // 扫描去重仍要求画面足够接近，避免合并有价值的不同视角。
    const val MAX_CORNER_ERROR = .03f
    const val MAX_ROTATION = 3f
    const val MAX_SCALE_ERROR = .08f
    // 实时找回背景容许手持的小幅位移、旋转与距离偏差。
    const val MAX_GUIDANCE_CORNER_ERROR = .06f
    const val MAX_GUIDANCE_ROTATION = 6f
    const val MAX_GUIDANCE_SCALE_ERROR = .15f
    // 构图裁剪及人像排除会减少背景分布，局部分布须由更强的匹配证据补足。
    const val MIN_PARTIAL_MATCHES = 45
    const val MIN_PARTIAL_INLIERS = 30
    const val MIN_PARTIAL_INLIER_RATIO = .65f
    const val MIN_PARTIAL_COVERAGE = .15f
    const val MIN_PARTIAL_QUADRANTS = 2
    const val MAX_BRIEF_CORNER_ERROR = .08f
    const val MAX_BRIEF_ROTATION = 8f
    const val MAX_BRIEF_SCALE_ERROR = .18f
    const val MAX_CANDIDATE_GAP_NS = 500_000_000L
    const val MIN_STABLE_FRAMES = 3
    fun confident(m: MatchMetrics) = m.valid && m.matches >= MIN_MATCHES && m.inliers >= MIN_INLIERS &&
        m.inliers.toFloat() / m.matches >= MIN_INLIER_RATIO && m.coverage >= MIN_COVERAGE && m.quadrants >= MIN_QUADRANTS
    fun aligned(m: MatchMetrics) = confident(m) && m.cornerError <= MAX_CORNER_ERROR && abs(m.rotation) <= MAX_ROTATION && abs(m.scale - 1f) <= MAX_SCALE_ERROR
    fun confidentForGuidance(m: MatchMetrics) = confident(m) || m.valid && m.matches >= MIN_PARTIAL_MATCHES &&
        m.inliers >= MIN_PARTIAL_INLIERS && m.inliers.toFloat() / m.matches >= MIN_PARTIAL_INLIER_RATIO &&
        m.coverage >= MIN_PARTIAL_COVERAGE && m.quadrants >= MIN_PARTIAL_QUADRANTS
    fun alignedForGuidance(m: MatchMetrics) = confidentForGuidance(m) && m.cornerError <= MAX_GUIDANCE_CORNER_ERROR &&
        abs(m.rotation) <= MAX_GUIDANCE_ROTATION && abs(m.scale - 1f) <= MAX_GUIDANCE_SCALE_ERROR
    /** 只容忍候选过程中的单帧小幅抖动，本帧不能据此触发确认。 */
    fun canBridgeGuidance(m: MatchMetrics) = confidentForGuidance(m) && m.cornerError <= MAX_BRIEF_CORNER_ERROR &&
        abs(m.rotation) <= MAX_BRIEF_ROTATION && abs(m.scale - 1f) <= MAX_BRIEF_SCALE_ERROR

    /** 仅用于诊断，不参与修改判定结果或放宽任何门限。 */
    fun guidanceRejection(m: MatchMetrics): String? = when {
        !m.valid -> "INVALID_GEOMETRY"
        m.matches < MIN_MATCHES -> "MATCH_COUNT"
        m.inliers < MIN_INLIERS -> "INLIER_COUNT"
        m.inliers.toFloat() / m.matches < MIN_INLIER_RATIO -> "INLIER_RATIO"
        m.coverage < MIN_PARTIAL_COVERAGE -> "COVERAGE"
        m.quadrants < MIN_PARTIAL_QUADRANTS -> "QUADRANTS"
        !confidentForGuidance(m) -> "PARTIAL_EVIDENCE"
        !m.cornerError.isFinite() || !m.rotation.isFinite() || !m.scale.isFinite() -> "NON_FINITE_GEOMETRY"
        m.cornerError > MAX_GUIDANCE_CORNER_ERROR -> "CORNER_OFFSET"
        abs(m.rotation) > MAX_GUIDANCE_ROTATION -> "ROTATION"
        abs(m.scale - 1f) > MAX_GUIDANCE_SCALE_ERROR -> "SCALE"
        else -> null
    }
}
class BackgroundStability {
    private var since: Long? = null
    private var lastGoodNs: Long? = null
    private var pendingBriefFrame = false
    var candidateFrames: Int = 0; private set
    var stableNs: Long = 0; private set
    var resetCount: Int = 0; private set
    var briefGaps: Int = 0; private set
    fun update(aligned: Boolean, atNs: Long, allowBriefGap: Boolean = false): Boolean {
        val previousGood = lastGoodNs
        val recentGood = previousGood != null && atNs >= previousGood &&
            atNs - previousGood <= BackgroundMatchPolicy.MAX_CANDIDATE_GAP_NS
        if (!aligned) {
            if (allowBriefGap && recentGood && !pendingBriefFrame) {
                pendingBriefFrame = true
                briefGaps++
                return false
            }
            reset()
            return false
        }
        // 相隔太久的两帧不能拼成一次稳定确认。
        if (since != null && !recentGood) reset()
        if (since == null) since = atNs
        lastGoodNs = atNs
        pendingBriefFrame = false
        candidateFrames++
        stableNs = atNs - since!!
        return stableNs >= BackgroundMatchPolicy.STABLE_NS && candidateFrames >= BackgroundMatchPolicy.MIN_STABLE_FRAMES
    }
    fun reset() {
        if (since != null) resetCount++
        since = null
        lastGoodNs = null
        pendingBriefFrame = false
        candidateFrames = 0
        stableNs = 0
        briefGaps = 0
    }
}
