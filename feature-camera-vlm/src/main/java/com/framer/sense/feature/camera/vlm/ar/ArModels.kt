package com.framer.sense.feature.camera.vlm.ar

/** 空间参考状态；背景对齐不代表真人姿势合格。 */
enum class ArSource { CHECKING, SHARED_CAMERA, SCREEN }
enum class ArTrackingState { STARTING, TRACKING, NEEDS_GROUND, PLACED, LOST, PAUSED, UNSUPPORTED }
data class ArUiState(val source: ArSource = ArSource.CHECKING, val tracking: ArTrackingState = ArTrackingState.STARTING,
    val sessionId: String? = null, val depth: Boolean = false, val manualAnchor: Boolean = false)

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
