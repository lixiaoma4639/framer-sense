package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.CompositionPlan

/** 空间参考状态；背景对齐不代表真人姿势合格。 */
enum class ArSource { CHECKING, SHARED_CAMERA, SCREEN }
enum class ArTrackingState { STARTING, TRACKING, NEEDS_GROUND, PLACED, LOST, PAUSED, UNSUPPORTED }
data class ArUiState(val source: ArSource = ArSource.CHECKING, val tracking: ArTrackingState = ArTrackingState.STARTING,
    val sessionId: String? = null, val depth: Boolean = false, val manualAnchor: Boolean = false,
    val placedReferenceKey: String? = null)

/** 不允许旧背景或旧 revision 的已放置回调关闭当前方案的屏幕参考。 */
internal fun CompositionPlan.arReferenceKey(): String =
    "${viewBinding?.scanId}:${viewBinding?.frameId}:$id:$revision:${resolvedSpec?.action?.cacheIdentity()}"

/** 地面尚未识别时限频重试；新方案、失效或重新对齐后重新计时。 */
internal class ArPlacementRetry(private val intervalNs: Long = 1_000_000_000L) {
    private var lastAttemptNs: Long? = null
    var attempts = 0; private set
    fun reset() { lastAttemptNs = null; attempts = 0 }
    fun shouldAttempt(nowNs: Long): Boolean {
        val last = lastAttemptNs
        if (last != null && nowNs - last < intervalNs) return false
        lastAttemptNs = nowNs; attempts++
        return true
    }
}

/** 回调和锚点必须属于同一会话及方案版本。 */
class ArReferenceGate {
    var sessionId: String? = null; private set
    var planKey: String? = null; private set
    var placed = false; private set
    fun session(id: String) { if (id != sessionId) { sessionId = id; planKey = null; placed = false } }
    fun plan(key: String?) { if (key != planKey) { planKey = key; placed = false } }
    fun place(session: String, key: String): Boolean =
        (session == sessionId && key == planKey).also { if (it) placed = true }
    fun invalidate() { placed = false }
}
