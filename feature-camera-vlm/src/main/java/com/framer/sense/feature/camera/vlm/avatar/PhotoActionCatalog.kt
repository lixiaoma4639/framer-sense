package com.framer.sense.feature.camera.vlm.avatar

import com.framer.sense.feature.camera.vlm.model.*

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
    val selectable: Boolean = true
) {
    fun asset(role: AvatarId): AvatarCatalog.Asset = avatarAssets[role] ?: AvatarCatalog.asset(role)
}

/** 仅注册本地动作，待审核五样片不会进入推荐或用户选择。 */
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
            return basics + directive + pending
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
        val source = requireNotNull(definition.source)
        var actual = plan
        if (selectedActionId != null) {
            val directive = (source as? PhotoPoseSource.Controlled)?.directive ?: definition.guidance
            actual = plan.copy(avatar = plan.avatar.copy(pose = (source as? PhotoPoseSource.Controlled)?.pose ?: definition.legacyLabel,
                poseDirective = directive, projectionCalibrated = false))
        }
        val execution = if (id == PhotoActionCatalog.DIRECTIVE_ID)
            PhotoPoseSource.Controlled(actual.avatar.pose, actual.avatar.poseDirective) else source
        val asset = definition.asset(actual.avatar.avatarId)
        val action = ResolvedPhotoAction(id, definition.version, definition.rigId, asset.fileName, asset.version,
            asset.boundsExtentScale,
            origin ?: if (selectedActionId != null) ActionOrigin.USER else plan.resolvedSpec?.action?.origin ?: ActionOrigin.MODEL,
            execution, if (source is PhotoPoseSource.Controlled) null else requireNotNull(definition.guidance),
            definition.labelResource, definition.stepResources)
        val base = CompositionGuidance.from(actual.copy(resolvedSpec = null))
        val guidance = action.authoredGuidance?.let { base.copy(directive = it, legacyHandNearFace = false) } ?: base
        val spec = ResolvedPhotoSpec(actual.id, actual.revision, actual.shot, actual.crop, actual.zoom, actual.avatar,
            actual.needsRetake, action, guidance, actual.viewBinding)
        return actual.copy(resolvedSpec = spec)
    }
}
