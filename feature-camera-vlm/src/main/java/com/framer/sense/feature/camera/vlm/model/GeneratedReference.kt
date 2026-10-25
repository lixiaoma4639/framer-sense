package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable

@Serializable enum class ReferenceSource { LEGACY_GLB, GENERATED_IMAGE }
@Serializable enum class SeedreamModel {
    FLASH, PRO;
    val apiId: String get() = when (this) {
        FLASH -> CloudModelConfig.seedreamFlash
        PRO -> CloudModelConfig.seedreamPro
    }
}

/** 背景找回只依赖照片来源和取景，不依赖 GLB 动作、骨骼或虚构人物锚点。 */
data class BackgroundReferenceTarget(
    val id: String,
    val revision: Int,
    val crop: CropRect,
    val zoom: Float,
    val viewBinding: PhotoViewBinding?
)

fun CompositionPlan.backgroundTarget() = BackgroundReferenceTarget(id, revision, crop, zoom, viewBinding)

@Serializable
data class GeneratedReferenceAssets(
    val compositePath: String,
    val portraitPath: String,
    val width: Int,
    val height: Int,
    val actualModel: String,
    val outputImages: Int,
    val sourceLayerCount: Int = 0
) {
    /** 无人像时保留完整构图；空路径不代表可用的透明人像。 */
    val hasPortrait: Boolean get() = portraitPath.isNotBlank()
}

enum class GeneratedCandidateStatus { QUEUED, RUNNING, READY, FAILED, CANCELLED }

data class GeneratedCandidate(
    val id: String,
    val batchId: String,
    val scene: SceneSnapshot,
    val model: SeedreamModel,
    val instruction: String,
    val variation: Int,
    val viewBinding: PhotoViewBinding?,
    val revision: Int = 0,
    val status: GeneratedCandidateStatus = GeneratedCandidateStatus.QUEUED,
    val assets: GeneratedReferenceAssets? = null,
    val errorCode: String? = null,
    val completedOrder: Int? = null,
    val replacesId: String? = null
) {
    val ready: Boolean get() = status == GeneratedCandidateStatus.READY && assets != null
    val canApply: Boolean get() = ready && assets?.hasPortrait == true
    fun backgroundTarget() = BackgroundReferenceTarget(id, revision, CropRect(), scene.currentZoom, viewBinding)
}

/** 所有回传按候选身份合并，后台任务完成不能改变用户当前选择或已应用参考。 */
data class GeneratedReferenceState(
    val epoch: String = "initial",
    val candidates: List<GeneratedCandidate> = emptyList(),
    val selectedId: String? = null,
    val applied: GeneratedCandidate? = null,
    val zoomVisible: Boolean = false
) {
    val selected: GeneratedCandidate? get() = candidates.firstOrNull { it.id == selectedId }
    val running: Boolean get() = candidates.any { it.status in listOf(GeneratedCandidateStatus.QUEUED, GeneratedCandidateStatus.RUNNING) }
    val hasReady: Boolean get() = candidates.any { it.ready }
    val ordered: List<GeneratedCandidate> get() = candidates.sortedWith(
        compareBy<GeneratedCandidate> { it.completedOrder ?: Int.MAX_VALUE }.thenBy { candidates.indexOf(it) })

    fun update(epoch: String, candidate: GeneratedCandidate): GeneratedReferenceState {
        if (epoch != this.epoch || candidates.none { it.id == candidate.id && it.batchId == candidate.batchId &&
                it.revision == candidate.revision && it.scene.id == candidate.scene.id && it.model == candidate.model }) return this
        val old = candidates.first { it.id == candidate.id }
        if (old.status in listOf(GeneratedCandidateStatus.READY, GeneratedCandidateStatus.FAILED, GeneratedCandidateStatus.CANCELLED)) return this
        val nextOrder = (candidates.mapNotNull { it.completedOrder }.maxOrNull() ?: -1) + 1
        val next = if (candidate.ready) candidate.copy(completedOrder = nextOrder) else candidate
        return copy(candidates = candidates.map { if (it.id == next.id) next else it },
            selectedId = selectedId ?: next.id.takeIf { next.ready })
    }

    fun cancelPending() = copy(candidates = candidates.map {
        if (it.status in listOf(GeneratedCandidateStatus.QUEUED, GeneratedCandidateStatus.RUNNING))
            it.copy(status = GeneratedCandidateStatus.CANCELLED) else it
    })
}
