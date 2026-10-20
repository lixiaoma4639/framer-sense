package com.framer.sense.feature.camera.vlm.ui

import com.framer.sense.feature.camera.vlm.model.BackgroundStatus
import com.framer.sense.feature.camera.vlm.model.BackgroundGuidance

/** 保存结果只属于当次背景与已应用方案，不跨流程点亮拍摄节点。 */
data class CameraWorkflowKey(val sceneId: String?, val planId: String?, val planRevision: Int?,
    val batchId: String = INITIAL_COMPOSITION_BATCH)

internal enum class CameraWorkflowStep { SCAN, GENERATE, SELECT, ALIGN, CAPTURE }
internal enum class CameraStepStatus { PENDING, RUNNING, COMPLETE }

internal val VlmUiState.workflowKey: CameraWorkflowKey
    get() = CameraWorkflowKey(scene?.id, referencePlan?.id, referencePlan?.revision, compositionBatchId)

/** 对齐回退后作废已拍摄记录，再次对齐不能复活上一次的拍摄完成状态。 */
internal fun VlmUiState.withBackgroundGuidance(guidance: BackgroundGuidance): VlmUiState =
    copy(background = guidance, capturedWorkflow = capturedWorkflow.takeUnless {
        referencePlan?.viewBinding != null && guidance.status == BackgroundStatus.SEARCHING
    })

/** 由业务状态派生进度，不用计时器推进，也不把默认选中卡片视为应用成功。 */
internal fun VlmUiState.workflowProgress(): List<CameraStepStatus> {
    val collecting = stage == VlmStage.SCANNING || stage == VlmStage.FREEZING ||
        (stage == VlmStage.SCAN_PROCESSING && scanSnapshot?.frames.isNullOrEmpty())
    val backgroundReady = !collecting && (scene != null || !scanSnapshot?.frames.isNullOrEmpty())
    val applying = stage == VlmStage.MODIFYING && directorProgress == null &&
        result != null && !compositionDetailsVisible
    val generating = stage == VlmStage.GENERATING || stage == VlmStage.VIEW_SELECTING ||
        (stage == VlmStage.SCAN_PROCESSING && backgroundReady) ||
        (stage == VlmStage.MODIFYING && !applying)
    val generated = backgroundReady && !generating &&
        stage in listOf(VlmStage.READY, VlmStage.MODIFYING, VlmStage.LIVE) && !result?.plans.isNullOrEmpty()
    val applied = generated && stage == VlmStage.LIVE && referencePlan != null && !compositionDetailsVisible
    val aligned = applied && (referencePlan?.viewBinding == null ||
        background.status != BackgroundStatus.SEARCHING)
    val progress = listOf(
        stepStatus(backgroundReady, collecting),
        stepStatus(generated, generating),
        stepStatus(applied, applying),
        stepStatus(aligned, applied && !aligned),
        stepStatus(capturedWorkflow == workflowKey, saving)
    )
    // 只保留连续完成的前缀与当前节点，旧结果或保存记录不能越过回退节点。
    val current = progress.indexOfFirst { it != CameraStepStatus.COMPLETE }
    return progress.mapIndexed { index, status ->
        if (current >= 0 && index > current) CameraStepStatus.PENDING else status
    }
}

private fun stepStatus(complete: Boolean, running: Boolean) = when {
    running -> CameraStepStatus.RUNNING
    complete -> CameraStepStatus.COMPLETE
    else -> CameraStepStatus.PENDING
}
