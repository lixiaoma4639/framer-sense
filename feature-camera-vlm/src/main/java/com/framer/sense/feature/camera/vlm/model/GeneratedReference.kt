package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable

@Serializable enum class ReferenceSource { LEGACY_GLB, GENERATED_IMAGE }
sealed interface ImageGenerationModel {
    val apiId: String
    val provider: ProviderId
}
/** 旧模型即使已从菜单下架，历史仍可本地展示；新请求使用当前选择。 */
data class HistoricalImageModel(override val provider: ProviderId, override val apiId: String) : ImageGenerationModel

@Serializable
data class GeneratedStyleChoice(val wardrobe: String, val art: String) {
    val key: String get() = "${wardrobe.trim()}\u0000${art.trim()}"
}

@Serializable
data class GeneratedRequestSnapshot(val modelId: String, val templateVersion: String, val prompt: String,
    val parameters: Map<String, String> = emptyMap())

@Serializable enum class QwenImageModel : ImageGenerationModel {
    V21_TURBO, V21_PRO, V30, V30_PRO;
    override val provider: ProviderId get() = ProviderId.QWEN
    override val apiId: String get() = when (this) {
        V21_TURBO -> CloudModelConfig.qwenImage21Turbo
        V21_PRO -> CloudModelConfig.qwenImage21Pro
        V30 -> CloudModelConfig.qwenImage30
        V30_PRO -> CloudModelConfig.qwenImage30Pro
    }
}

@Serializable enum class SeedreamModel : ImageGenerationModel {
    FLASH, PRO;
    override val provider: ProviderId get() = ProviderId.SEED
    override val apiId: String get() = when (this) {
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

/** 只保存真实取得的说明；缺失字段留空，不将用户输入充当结果分析。 */
@Serializable
data class GeneratedReferenceDescriptions(
    val actualComposition: String = "",
    val shootingGuidance: String = "",
    val environment: String = ""
) {
    fun withEnvironmentFallback(sceneDescription: String) = copy(
        environment = environment.trim().ifBlank { sceneDescription.trim() }
    )
}

@Serializable enum class GeneratedPortraitIssue { NO_TRANSPARENCY, ASPECT_MISMATCH }

@Serializable
data class GeneratedReferenceAssets(
    val compositePath: String,
    val portraitPath: String,
    val width: Int,
    val height: Int,
    val actualModel: String,
    val outputImages: Int,
    val sourceLayerCount: Int = 0,
    val descriptions: GeneratedReferenceDescriptions = GeneratedReferenceDescriptions(),
    val portraitIssue: GeneratedPortraitIssue? = null,
    val requestSnapshot: GeneratedRequestSnapshot? = null
) {
    /** 无人像时保留完整构图；空路径不代表可用的透明人像。 */
    val hasPortrait: Boolean get() = portraitPath.isNotBlank()
}

enum class GeneratedCandidateStatus { QUEUED, RUNNING, READY, FAILED, CANCELLED }

/** 提交时快照，后续修改设置不影响在途请求或历史构图。 */
@Serializable
data class GeneratedPortraitPreferences(
    val avatar: AvatarId = AvatarId.ADULT_FEMALE,
    val ethnicity: AvatarEthnicity = AvatarEthnicity.ASIAN
)

data class GeneratedCandidate(
    val id: String,
    val batchId: String,
    val scene: SceneSnapshot,
    val model: ImageGenerationModel,
    val instruction: String,
    val variation: Int,
    val viewBinding: PhotoViewBinding?,
    val revision: Int = 0,
    val status: GeneratedCandidateStatus = GeneratedCandidateStatus.QUEUED,
    val assets: GeneratedReferenceAssets? = null,
    val errorCode: String? = null,
    val completedOrder: Int? = null,
    val replacesId: String? = null,
    val portraitPreferences: GeneratedPortraitPreferences = GeneratedPortraitPreferences(),
    val sceneDescription: String = "",
    val styleIndex: Int = variation,
    val styleChoice: GeneratedStyleChoice? = null,
    val completedAt: Long? = null
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
    val historyId: String = newVlmId(),
    val usedStyleKeys: Set<String> = emptySet()
) {
    val selected: GeneratedCandidate? get() = candidates.firstOrNull { it.id == selectedId }
    val running: Boolean get() = candidates.any { it.status in listOf(GeneratedCandidateStatus.QUEUED, GeneratedCandidateStatus.RUNNING) }
    val hasReady: Boolean get() = candidates.any { it.ready }
    /** 本流程提交过的组合都保留，包含失败、取消及被替换的候选。 */
    val usedStyleIndices: Set<Int> get() = candidates.map { it.styleIndex }.toSet()
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
