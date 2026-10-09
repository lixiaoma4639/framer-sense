package com.framer.sense.feature.camera.vlm.avatar

import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.R

data class PhotoActionDefinition(
    val id: String,
    val version: Int,
    val rigId: String,
    val readiness: ActionReadiness,
    val supportedAvatars: Set<AvatarId>,
    val shots: Set<ShotType> = ShotType.entries.toSet(),
    val requirement: ActionRequirement = ActionRequirement.NONE,
    val source: PhotoPoseSource? = null,
    val guidance: AvatarPoseDirective? = null,
    val legacyLabel: PoseId = PoseId.RELAXED,
    val labelResource: Int? = null,
    val stepResources: List<Int> = emptyList(),
    /** 进阶动作可提供逐角色 GLB；版本必须随资产更新。 */
    val avatarAssets: Map<AvatarId, AvatarCatalog.Asset> = emptyMap(),
    val selectable: Boolean = true,
    val expressionProfilePath: String? = null,
    val faceAnchorNode: String = "Bip01 Head",
    val softenColors: Boolean = true,
    val ethnicityAssets: Map<AvatarEthnicity, AvatarCatalog.Asset> = emptyMap()
) {
    fun asset(role: AvatarId, ethnicity: AvatarEthnicity? = null): AvatarCatalog.Asset =
        if (ethnicityAssets.isNotEmpty() && role in supportedAvatars)
            ethnicityAssets.getValue(ethnicity ?: AvatarEthnicity.WHITE)
        else {
            require(ethnicity == null) { "PHOTO_AVATAR_ETHNICITY_UNAVAILABLE" }
            avatarAssets[role] ?: AvatarCatalog.asset(role)
        }
}

/** 完整草稿可手动验证，待素材条目不可执行；正式推荐仅使用 REVIEWED；PREVIEW_MODEL 白名单只供明确标识的初稿试用。 */
class PhotoActionCatalog(val definitions: List<PhotoActionDefinition> = defaults()) {
    init {
        require(definitions.map { it.id }.distinct().size == definitions.size)
        require(definitions.all { it.id.isNotBlank() && it.version > 0 && it.rigId.isNotBlank() })
    }
    fun find(id: String): PhotoActionDefinition = definitions.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("UNKNOWN_PHOTO_ACTION")

    fun candidates(role: AvatarId, shot: ShotType): List<PhotoActionDefinition> = definitions.filter {
        it.readiness != ActionReadiness.AWAITING_ASSET && it.source != null && role in it.supportedAvatars &&
            shot in it.shots && it.requirement == ActionRequirement.NONE && it.asset(role).rigId == it.rigId
    }

    companion object {
        const val DIRECTIVE_ID = "basic.directive"
        const val SAMPLE_ID = "draft.rain_natural_stand"
        const val SIDE_ID = "draft.rain_side_stand"
        const val LOOK_BACK_ID = "draft.rain_look_back"
        const val HAND_HIP_ID = "draft.rain_hand_hip"
        const val WALK_PAUSE_ID = "draft.rain_walk_pause"
        val sampleActionIds = listOf(SAMPLE_ID, SIDE_ID, LOOK_BACK_ID, HAND_HIP_ID, WALK_PAUSE_ID)
        val previewModelActionIds = listOf(SAMPLE_ID, SIDE_ID, LOOK_BACK_ID)
        fun isPreviewModelAction(id: String) = id in previewModelActionIds
        fun isRainAction(id: String?) = id != null && id in sampleActionIds
        fun basicId(pose: PoseId) = "basic.${pose.name.lowercase(java.util.Locale.ROOT)}"
        private fun defaults(): List<PhotoActionDefinition> {
            val roles = AvatarId.entries.toSet()
            val basics = PoseId.entries.map { pose -> PhotoActionDefinition(basicId(pose), 1,
                AvatarCatalog.RIG_ID, ActionReadiness.BASIC, roles, source = PhotoPoseSource.Controlled(pose), legacyLabel = pose) }
            val directive = PhotoActionDefinition(DIRECTIVE_ID, 1, AvatarCatalog.RIG_ID, ActionReadiness.BASIC,
                roles, source = PhotoPoseSource.Controlled(PoseId.RELAXED), selectable = false)
            val pending = listOf("natural_stand", "side_stand", "look_back", "hand_hip", "walk_pause").map { name ->
                PhotoActionDefinition("professional.$name", 1, AvatarCatalog.RIG_ID, ActionReadiness.AWAITING_ASSET, roles)
            }
            val sample = PhotoActionDefinition(SAMPLE_ID, 5, "rain-photo-sample-v1", ActionReadiness.DRAFT,
                setOf(AvatarId.ADULT_FEMALE), source = PhotoPoseSource.Animation("photo-rain-natural-v1", 0f),
                guidance = AvatarPoseDirective(),
                labelResource = R.string.vlm_photo_sample_action,
                stepResources = listOf(R.string.vlm_photo_sample_step_body, R.string.vlm_photo_sample_step_arms,
                    R.string.vlm_photo_sample_step_stance),
                avatarAssets = mapOf(AvatarId.ADULT_FEMALE to AvatarCatalog.Asset(
                    "photo_samples/rain-natural-v1/white.glb", version = 5, rigId = "rain-photo-sample-v1", boundsExtentScale = 1f)),
                expressionProfilePath = "photo_samples/rain-natural-v1/expressions.json",
                faceAnchorNode = "DEF-Head", softenColors = false,
                ethnicityAssets = mapOf(
                    AvatarEthnicity.WHITE to AvatarCatalog.Asset("photo_samples/rain-natural-v1/white.glb", 5, "rain-photo-sample-v1", 1f),
                    AvatarEthnicity.ASIAN to AvatarCatalog.Asset("photo_samples/rain-natural-v1/asian.glb", 5, "rain-photo-sample-v1", 1f),
                    AvatarEthnicity.BLACK to AvatarCatalog.Asset("photo_samples/rain-natural-v1/black.glb", 4, "rain-photo-sample-v1", 1f)))
            fun complete(id: String, file: String, label: Int, steps: List<Int>,
                         guidance: AvatarPoseDirective, legacy: PoseId) = sample.copy(
                id = id, version = 1, source = PhotoPoseSource.Snapshot("photo_samples/rain-poses-v1/$file.json"),
                labelResource = label, stepResources = steps, guidance = guidance, legacyLabel = legacy)
            val completeDrafts = listOf(
                complete(SIDE_ID, "side_stand", R.string.vlm_photo_pose_side,
                    listOf(R.string.vlm_photo_pose_side_body, R.string.vlm_photo_pose_side_head, R.string.vlm_photo_pose_side_stance),
                    AvatarPoseDirective(body = BodyPose.TURN_RIGHT, head = HeadPose.TURN_LEFT, stance = StancePose.WEIGHT_RIGHT), PoseId.SIDE),
                complete(LOOK_BACK_ID, "look_back", R.string.vlm_photo_pose_look_back,
                    listOf(R.string.vlm_photo_pose_back_body, R.string.vlm_photo_pose_back_head, R.string.vlm_photo_pose_back_stance),
                    AvatarPoseDirective(body = BodyPose.TURN_RIGHT, head = HeadPose.LOOK_BACK), PoseId.LOOK_BACK),
                complete(HAND_HIP_ID, "hand_hip", R.string.vlm_photo_pose_hand_hip,
                    listOf(R.string.vlm_photo_pose_hip_body, R.string.vlm_photo_pose_hip_hand, R.string.vlm_photo_pose_hip_stance),
                    AvatarPoseDirective(body = BodyPose.TURN_RIGHT, arms = ArmPose.HAND_HIP, stance = StancePose.WEIGHT_RIGHT), PoseId.HAND_HIP),
                complete(WALK_PAUSE_ID, "walk_pause", R.string.vlm_photo_pose_walk_pause,
                    listOf(R.string.vlm_photo_pose_walk_feet, R.string.vlm_photo_pose_walk_arms, R.string.vlm_photo_pose_walk_head),
                    AvatarPoseDirective(body = BodyPose.TURN_RIGHT, stance = StancePose.STEP_FORWARD), PoseId.RELAXED)
            )
            return basics + directive + pending + sample + completeDrafts
        }
    }
}

/** 将旧协议、模型细分动作和手动目录选择收敛为同一最终事实。 */
class PhotoActionResolver(val catalog: PhotoActionCatalog = PhotoActionCatalog()) {
    /** 仅从实际可用且语义完全匹配的审核动作中选择；没有匹配就保留基础路径。 */
    fun automaticActionId(plan: CompositionPlan, available: List<PhotoActionDefinition>): String? {
        if (plan.resolvedSpec != null) return null
        val intent = CompositionGuidance.from(plan).directive
        return available.firstOrNull { it.readiness == ActionReadiness.REVIEWED && it.guidance == intent &&
            it.source !is PhotoPoseSource.Controlled && it in catalog.candidates(plan.avatar.avatarId, plan.shot) }?.id
    }

    fun resolve(plan: CompositionPlan, selectedActionId: String? = null, origin: ActionOrigin? = null): CompositionPlan {
        val id = selectedActionId ?: plan.resolvedSpec?.action?.id ?:
            if (plan.avatar.poseDirective != null) PhotoActionCatalog.DIRECTIVE_ID else PhotoActionCatalog.basicId(plan.avatar.pose)
        val definition = catalog.find(id)
        require(definition in catalog.candidates(plan.avatar.avatarId, plan.shot)) { "PHOTO_ACTION_UNAVAILABLE" }
        require(definition.readiness != ActionReadiness.DRAFT || selectedActionId != null ||
            plan.resolvedSpec?.action?.origin == ActionOrigin.USER ||
            plan.resolvedSpec?.action?.let { it.origin == ActionOrigin.PREVIEW_MODEL && PhotoActionCatalog.isPreviewModelAction(it.id) } == true) { "DRAFT_ACTION_REQUIRES_MANUAL_SELECTION" }
        require(definition.readiness != ActionReadiness.DRAFT || origin != ActionOrigin.MODEL) { "DRAFT_ACTION_NOT_REVIEWED" }
        require(origin != ActionOrigin.PREVIEW_MODEL || PhotoActionCatalog.isPreviewModelAction(id)) { "PREVIEW_ACTION_UNAVAILABLE" }
        val source = requireNotNull(definition.source)
        var actual = plan
        if (selectedActionId != null) {
            val directive = (source as? PhotoPoseSource.Controlled)?.directive ?: definition.guidance
            actual = plan.copy(avatar = plan.avatar.copy(pose = (source as? PhotoPoseSource.Controlled)?.pose ?: definition.legacyLabel,
                poseDirective = directive, projectionCalibrated = false,
                ethnicity = if (definition.ethnicityAssets.isNotEmpty()) plan.avatar.ethnicity ?: AvatarEthnicity.WHITE else null))
        }
        if (definition.ethnicityAssets.isNotEmpty() && actual.avatar.ethnicity == null)
            actual = actual.copy(avatar = actual.avatar.copy(ethnicity = AvatarEthnicity.WHITE))
        val execution = if (id == PhotoActionCatalog.DIRECTIVE_ID)
            PhotoPoseSource.Controlled(actual.avatar.pose, actual.avatar.poseDirective) else source
        val asset = definition.asset(actual.avatar.avatarId, actual.avatar.ethnicity)
        val action = ResolvedPhotoAction(id, definition.version, definition.rigId, asset.fileName, asset.version,
            asset.boundsExtentScale,
            origin ?: if (selectedActionId != null) ActionOrigin.USER else plan.resolvedSpec?.action?.origin ?: ActionOrigin.MODEL,
            execution, if (source is PhotoPoseSource.Controlled) null else requireNotNull(definition.guidance),
            definition.labelResource, definition.stepResources, definition.readiness, definition.expressionProfilePath,
            faceAnchorNode = definition.faceAnchorNode, softenColors = definition.softenColors, ethnicity = actual.avatar.ethnicity)
        val base = CompositionGuidance.from(actual.copy(resolvedSpec = null))
        val guidance = action.authoredGuidance?.let { base.copy(directive = it, legacyHandNearFace = false) } ?: base
        val spec = ResolvedPhotoSpec(actual.id, actual.revision, actual.shot, actual.crop, actual.zoom, actual.avatar,
            actual.needsRetake, action, guidance, actual.viewBinding, actual.backgroundKeepRegions)
        return actual.copy(resolvedSpec = spec)
    }
}
