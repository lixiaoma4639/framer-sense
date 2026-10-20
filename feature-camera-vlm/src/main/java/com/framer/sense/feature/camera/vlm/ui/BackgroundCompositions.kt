package com.framer.sense.feature.camera.vlm.ui

import com.framer.sense.feature.camera.vlm.avatar.PlanPreview
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionDefinition
import com.framer.sense.feature.camera.vlm.model.*

internal const val INITIAL_COMPOSITION_BATCH = "initial"

/** 每轮仍独立保留三方案协议，界面按批次顺序连续编号。 */
data class CompositionBatch(
    val id: String,
    val result: CompositionResult,
    val diagnostics: CompositionResult?,
    val previews: Map<String, PlanPreview>,
    val actions: Map<String, List<PhotoActionDefinition>>,
    val expressions: Map<String, List<ExpressionId>>,
    val selectedId: String?
)

/** 当前三方案及同背景的历次生成批次，不改变模型内部 p1/p2/p3 编号。 */
data class BackgroundComposition(
    val scene: SceneSnapshot,
    val frameId: String?,
    val result: CompositionResult,
    val diagnostics: CompositionResult?,
    val previews: Map<String, PlanPreview>,
    val actions: Map<String, List<PhotoActionDefinition>>,
    val expressions: Map<String, List<ExpressionId>>,
    val selectedId: String?,
    val batchId: String = INITIAL_COMPOSITION_BATCH,
    val batches: List<CompositionBatch> = emptyList()
)

internal val BackgroundComposition.allBatches: List<CompositionBatch>
    get() {
        val current = CompositionBatch(batchId, result, diagnostics, previews, actions, expressions, selectedId)
        return if (batches.none { it.id == batchId }) batches + current
        else batches.map { if (it.id == batchId) current else it }
    }

internal fun BackgroundComposition.withBatch(id: String): BackgroundComposition? {
    val all = allBatches
    val batch = all.firstOrNull { it.id == id } ?: return null
    return copy(result = batch.result, diagnostics = batch.diagnostics, previews = batch.previews,
        actions = batch.actions, expressions = batch.expressions, selectedId = batch.selectedId,
        batchId = batch.id, batches = all)
}

internal fun VlmUiState.currentBackgroundComposition(): BackgroundComposition? {
    val image = scene ?: return null
    val plans = result?.takeIf { it.plans.isNotEmpty() } ?: return null
    return BackgroundComposition(image, selectedFrameId, plans, diagnostics, previews,
        availableActions, availableExpressions, selectedId, compositionBatchId,
        compositionCache[image.id]?.allBatches.orEmpty())
}

internal fun VlmUiState.rememberedBackgrounds(): Map<String, BackgroundComposition> =
    currentBackgroundComposition()?.let { compositionCache + (it.scene.id to it) } ?: compositionCache

internal val VlmUiState.compositionBackgrounds: List<BackgroundComposition>
    get() = rememberedBackgrounds().values.sortedBy { group ->
        viewCandidates.indexOfFirst { it.frameId == group.frameId }.takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

internal val VlmUiState.hasCompositionChoices: Boolean
    get() = !result?.plans.isNullOrEmpty() || compositionCache.values.any { it.result.plans.isNotEmpty() }

internal val VlmUiState.rankedBackgroundFrames: List<ScanFrame>
    get() = viewCandidates.take(ScanLimits.MAX_COMPOSITION_BACKGROUNDS).mapNotNull { candidate ->
        scanSnapshot?.frames?.firstOrNull { it.id == candidate.frameId }
    }

/** 推荐背景在同一行按名次占位，生成后在原位置展开，不跳过尚未生成的更高名次。 */
internal sealed interface CompositionChoice {
    val key: String
    val rank: Int

    data class Plan(val group: BackgroundComposition, val batch: CompositionBatch, val plan: CompositionPlan, val index: Int,
                    override val rank: Int) : CompositionChoice {
        override val key: String get() = "plan:${group.scene.id}:${batch.id}:${plan.id}"
    }

    data class PendingBackground(val frame: ScanFrame, override val rank: Int) : CompositionChoice {
        override val key: String get() = "background:${frame.id}"
    }
}

internal val VlmUiState.compositionChoices: List<CompositionChoice>
    get() {
        val groups = compositionBackgrounds
        val frames = rankedBackgroundFrames
        val rankedIds = frames.map { it.scene.id }.toSet()
        val generated = groups.associateBy { it.scene.id }
        return buildList {
            frames.forEachIndexed { index, frame ->
                val group = generated[frame.scene.id]
                if (group == null) add(CompositionChoice.PendingBackground(frame, index + 1))
                else group.allBatches.flatMap { batch -> batch.result.plans.map { batch to it } }.forEachIndexed { planIndex, (batch, plan) ->
                    add(CompositionChoice.Plan(group, batch, plan, planIndex, index + 1))
                }
            }
            // 单张构图和手动备用背景也保留，不因不在本次推荐列表中而丢失。
            groups.filter { it.scene.id !in rankedIds }.forEachIndexed { index, group ->
                group.allBatches.flatMap { batch -> batch.result.plans.map { batch to it } }.forEachIndexed { planIndex, (batch, plan) ->
                    add(CompositionChoice.Plan(group, batch, plan, planIndex, frames.size + index + 1))
                }
            }
        }
    }

internal fun VlmUiState.restoreBackground(group: BackgroundComposition, planId: String? = group.selectedId): VlmUiState =
    copy(stage = VlmStage.READY, scene = group.scene, selectedFrameId = group.frameId,
        compositionCache = rememberedBackgrounds() + (group.scene.id to group), compositionBatchId = group.batchId,
        result = group.result, diagnostics = group.diagnostics,
        previews = group.previews, availableActions = group.actions, availableExpressions = group.expressions,
        selectedId = planId?.takeIf { id -> group.result.plans.any { it.id == id } } ?: group.result.plans.first().id,
        reference = null, referencePlan = null, background = BackgroundGuidance(), capturedWorkflow = null,
        facePreview = null, faceLoading = false, compositionDetailsVisible = true,
        manualScanSelectionVisible = false, directorProgress = null, error = null, notice = null)

/** 只选中原图供预览，先保存已有方案；没有当前方案时详情隐藏，横向列表继续可选。 */
internal fun VlmUiState.previewBackground(frame: ScanFrame): VlmUiState =
    copy(stage = VlmStage.FROZEN, scene = frame.scene, selectedFrameId = frame.id,
        compositionBatchId = INITIAL_COMPOSITION_BATCH,
        compositionDetailsExpanded = false,
        compositionCache = rememberedBackgrounds(), compositionDetailsVisible = true,
        result = null, diagnostics = null, previews = emptyMap(), selectedId = null,
        availableActions = emptyMap(), availableExpressions = emptyMap(),
        reference = null, referencePlan = null, background = BackgroundGuidance(), capturedWorkflow = null,
        facePreview = null, faceLoading = false, manualScanSelectionVisible = false,
        fromImportedImage = false, directorProgress = null, error = null, notice = null)
