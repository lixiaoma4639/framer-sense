package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class ActionOrigin { MODEL, USER }
enum class ActionReadiness { BASIC, REVIEWED, AWAITING_ASSET }
enum class ActionRequirement { NONE, SEAT, WALL, PROP }
enum class AvatarRenderPurpose { COMPOSITION, FACE }

/** 执行数据只由本地审核目录提供，模型不能指定文件、动画或关节矩阵。 */
@Serializable
sealed interface PhotoPoseSource {
    @Serializable data class Controlled(val pose: PoseId, val directive: AvatarPoseDirective? = null) : PhotoPoseSource
    @Serializable data class Snapshot(val path: String) : PhotoPoseSource
    @Serializable data class Animation(val name: String, val sampleSeconds: Float) : PhotoPoseSource
}

/** 完整姿态快照：所有蒙皮关节的绝对局部变换，列主序 4×4 矩阵。 */
@Serializable
data class PhotoPoseSnapshot(val rigId: String, val bones: Map<String, List<Float>>) {
    fun validate(expectedRig: String, requiredBones: Set<String>) {
        require(rigId == expectedRig) { "POSE_RIG_MISMATCH" }
        require(bones.keys == requiredBones && bones.isNotEmpty()) { "INCOMPLETE_POSE_SNAPSHOT" }
        require(bones.values.all { matrix ->
            matrix.size == 16 && matrix.all(Float::isFinite) &&
                kotlin.math.abs(matrix[3]) < .0001f && kotlin.math.abs(matrix[7]) < .0001f &&
                kotlin.math.abs(matrix[11]) < .0001f && kotlin.math.abs(matrix[15] - 1f) < .0001f &&
                kotlin.math.abs(matrix[0] * (matrix[5] * matrix[10] - matrix[9] * matrix[6]) -
                    matrix[4] * (matrix[1] * matrix[10] - matrix[9] * matrix[2]) +
                    matrix[8] * (matrix[1] * matrix[6] - matrix[5] * matrix[2])) > .000001f
        }) { "INVALID_POSE_TRANSFORM" }
    }
}

data class ResolvedPhotoAction(
    val id: String,
    val version: Int,
    val rigId: String,
    val avatarAssetPath: String,
    val avatarAssetVersion: Int,
    val boundsExtentScale: Float,
    val origin: ActionOrigin,
    val source: PhotoPoseSource,
    /** 完整姿态/动画的语义来自审核目录，不从矩阵猜测。 */
    val authoredGuidance: AvatarPoseDirective? = null,
    val labelResource: Int? = null,
    val stepResources: List<Int> = emptyList()
) {
    fun cacheIdentity(): String = listOf(id, version, rigId, avatarAssetPath, avatarAssetVersion, boundsExtentScale, origin,
        VlmJson.encodeToString(source), authoredGuidance, labelResource, stepResources).joinToString("|")
}

/** App 内的最终拍摄事实，不进入现有 v1 网关请求。 */
data class ResolvedPhotoSpec(
    val planId: String,
    val revision: Int,
    val shot: ShotType,
    val crop: CropRect,
    val zoom: Float,
    val avatar: AvatarPlacement,
    val needsRetake: Boolean,
    val action: ResolvedPhotoAction,
    val guidance: CompositionGuidance
) {
    fun matches(plan: CompositionPlan): Boolean = planId == plan.id && revision == plan.revision &&
        shot == plan.shot && crop == plan.crop && zoom == plan.zoom && avatar == plan.avatar && needsRetake == plan.needsRetake &&
        (action.source !is PhotoPoseSource.Controlled ||
            (action.source.pose == avatar.pose && action.source.directive == avatar.poseDirective)) &&
        guidance == expectedGuidance(plan)

    private fun expectedGuidance(plan: CompositionPlan): CompositionGuidance {
        val actual = CompositionGuidance.from(plan.copy(resolvedSpec = null))
        return action.authoredGuidance?.let { actual.copy(directive = it, legacyHandNearFace = false) } ?: actual
    }

    /** 布局回传和 revision 变化不丢失手动选择及动作资源版本。 */
    fun rebase(plan: CompositionPlan): ResolvedPhotoSpec {
        return copy(planId = plan.id, revision = plan.revision, shot = plan.shot, crop = plan.crop,
            zoom = plan.zoom, avatar = plan.avatar, needsRetake = plan.needsRetake,
            guidance = expectedGuidance(plan))
    }
}

fun CompositionPlan.withActualAvatar(value: AvatarPlacement): CompositionPlan {
    val changed = copy(avatar = value)
    return changed.copy(resolvedSpec = resolvedSpec?.rebase(changed))
}

fun CompositionPlan.withRevision(value: Int): CompositionPlan {
    val changed = copy(revision = value)
    return changed.copy(resolvedSpec = resolvedSpec?.rebase(changed))
}
