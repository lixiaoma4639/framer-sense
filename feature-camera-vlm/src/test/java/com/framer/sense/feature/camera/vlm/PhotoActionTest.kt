package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PhotoActionTest {
    private fun plan() = CompositionPlan("p1", "全身", ShotType.FULL, CropRect(), 1f,
        AvatarPlacement(height = .6f, pose = PoseId.SIDE), "模型原文", "场景依据")
    private val fixture = File("src/androidTest/assets/photo_pose_fixture")
    private fun definition(source: PhotoPoseSource) = PhotoActionDefinition("test.action", 1, "fixture-v1",
        ActionReadiness.REVIEWED, setOf(AvatarId.ADULT_FEMALE), source = source, guidance = AvatarPoseDirective(head = HeadPose.TURN_RIGHT),
        avatarAssets = mapOf(AvatarId.ADULT_FEMALE to AvatarCatalog.Asset("photo_pose_fixture/pose-test.glb", rigId = "fixture-v1", boundsExtentScale = 1f)))

    @Test fun basicLibraryExcludesPendingProfessionalSamplesAndConditionalActions() {
        val catalog = PhotoActionCatalog()
        assertEquals(5, catalog.definitions.count { it.readiness == ActionReadiness.AWAITING_ASSET })
        AvatarId.entries.forEach { role ->
            assertEquals(12, catalog.candidates(role, ShotType.FULL).count { it.selectable })
            assertTrue(catalog.candidates(role, ShotType.FULL).all { it.id.startsWith("basic.") })
        }
        val action = definition(PhotoPoseSource.Controlled(PoseId.RELAXED))
        assertTrue(PhotoActionCatalog(listOf(action.copy(requirement = ActionRequirement.WALL))).candidates(AvatarId.ADULT_FEMALE, ShotType.FULL).isEmpty())
        assertTrue(PhotoActionCatalog(listOf(action.copy(shots = setOf(ShotType.HALF)))).candidates(AvatarId.ADULT_FEMALE, ShotType.FULL).isEmpty())
        assertTrue(PhotoActionCatalog(listOf(action.copy(rigId = "other"))).candidates(AvatarId.ADULT_FEMALE, ShotType.FULL).isEmpty())
    }

    @Test fun oldAndModelDirectivePlansResolveWithoutChangingGatewayPayload() {
        val old = plan()
        val resolved = PhotoActionResolver().resolve(old)
        assertEquals(PhotoActionCatalog.basicId(PoseId.SIDE), resolved.resolvedSpec!!.action.id)
        assertEquals(old.avatar, resolved.avatar)
        assertEquals(VlmJson.encodeToString(old), VlmJson.encodeToString(resolved))
        assertFalse(VlmJson.encodeToString(resolved).contains("resolvedSpec"))
        val directive = AvatarPoseDirective(arms = ArmPose.WAVE, head = HeadPose.LOOK_UP)
        val model = PhotoActionResolver().resolve(old.copy(avatar = old.avatar.copy(poseDirective = directive)))
        assertEquals(PhotoActionCatalog.DIRECTIVE_ID, model.resolvedSpec!!.action.id)
        assertEquals(directive, CompositionGuidance.from(model).directive)
        assertEquals(PhotoPoseSource.Controlled(model.avatar.pose, directive), model.resolvedSpec.action.source)
    }

    @Test fun manualActionSurvivesPlacementRevisionAndCalibrationChanges() {
        val resolver = PhotoActionResolver()
        val chosen = resolver.resolve(plan(), PhotoActionCatalog.basicId(PoseId.HAT))
        val edited = chosen.withActualAvatar(chosen.avatar.copy(foot = Point2(.7f, .9f), height = .4f, expression = ExpressionId.SMILE)).withRevision(3)
        val result = resolver.resolve(edited).shrinkProjection(.8f)
        assertTrue(result.resolvedSpec!!.matches(result))
        assertEquals(ActionOrigin.USER, result.resolvedSpec.action.origin)
        assertEquals(PhotoActionCatalog.basicId(PoseId.HAT), result.resolvedSpec.action.id)
        assertTrue(CompositionGuidance.from(result).legacyHandNearFace)
        assertEquals(CompositionZone.RIGHT, CompositionGuidance.from(result).zone)
        assertEquals(ExpressionId.SMILE, CompositionGuidance.from(result).expression)
        val regenerated = resolver.resolve(plan().copy(avatar = plan().avatar.copy(pose = PoseId.WAVE)))
        assertEquals(ActionOrigin.MODEL, regenerated.resolvedSpec!!.action.origin)
        assertNotEquals(result.resolvedSpec.action.id, regenerated.resolvedSpec.action.id)
    }

    @Test fun versionsAndSamplingTimeChangeCacheIdentity() {
        val a = definition(PhotoPoseSource.Animation("fixture-head-turn", .5f))
        fun resolve(action: PhotoActionDefinition) = PhotoActionResolver(PhotoActionCatalog(listOf(action))).resolve(plan(), action.id).resolvedSpec!!.action.cacheIdentity()
        assertNotEquals(resolve(a), resolve(a.copy(version = 2)))
        assertNotEquals(resolve(a), resolve(a.copy(source = PhotoPoseSource.Animation("fixture-head-turn", .8f))))
        assertNotEquals(resolve(a), resolve(a.copy(avatarAssets = a.avatarAssets.mapValues { it.value.copy(version = 2) })))
    }

    @Test fun actualPackagedMetadataSupportsBasicLibrary() {
        AvatarId.entries.forEach { role ->
            val data = AvatarAssetMetadata.read(File("src/main/assets", AvatarCatalog.asset(role).fileName).readBytes())
            PhotoActionCatalog().candidates(role, ShotType.FULL).forEach { PhotoActionSupport.validate(it, role, data) }
            ExpressionId.entries.forEach { expression -> assertTrue(data.morphs.containsAll(AvatarCatalog.expressionWeights(expression, 1f).keys)) }
        }
    }

    @Test fun animationFixtureRejectsMissingClipAndInvalidSamplingTime() {
        val data = AvatarAssetMetadata.read(File(fixture, "pose-test.glb").readBytes())
        assertEquals(setOf("Bip01 Spine1", "Bip01 Head"), data.bones)
        assertEquals(1f, data.animations.getValue("fixture-head-turn"), 0f)
        PhotoActionSupport.validate(definition(PhotoPoseSource.Animation("fixture-head-turn", .5f)), AvatarId.ADULT_FEMALE, data)
        for (source in listOf(PhotoPoseSource.Animation("missing", .5f), PhotoPoseSource.Animation("fixture-head-turn", 2f), PhotoPoseSource.Animation("fixture-head-turn", Float.NaN))) {
            assertTrue(runCatching { PhotoActionSupport.validate(definition(source), AvatarId.ADULT_FEMALE, data) }.isFailure)
        }
        assertTrue(runCatching { AvatarAssetMetadata.read(File(fixture, "pose-test.glb").readBytes().dropLast(1).toByteArray()) }.isFailure)
    }

    @Test fun fullSnapshotRejectsWrongRigMissingOrExtraBonesAndSingularMatrices() {
        val snapshot = VlmJson.decodeFromString<PhotoPoseSnapshot>(File(fixture, "pose-test.json").readText())
        val data = AvatarAssetMetadata.read(File(fixture, "pose-test.glb").readBytes())
        PhotoActionSupport.validate(definition(PhotoPoseSource.Snapshot("fixture.json")), AvatarId.ADULT_FEMALE, data, snapshot)
        val broken = listOf(snapshot.copy(rigId = "wrong"), snapshot.copy(bones = snapshot.bones - "Bip01 Head"),
            snapshot.copy(bones = snapshot.bones + ("extra" to snapshot.bones.values.first())),
            snapshot.copy(bones = snapshot.bones + ("Bip01 Head" to List(16) { 0f })))
        broken.forEach { assertTrue(runCatching { it.validate("fixture-v1", data.bones) }.isFailure) }
        assertTrue(runCatching { PhotoActionSupport.validate(definition(PhotoPoseSource.Snapshot("missing.json")), AvatarId.ADULT_FEMALE, data) }.isFailure)
    }

    @Test fun authoredInstructionsAndZeroExpressionFollowResolvedActualValues() {
        val action = definition(PhotoPoseSource.Animation("fixture-head-turn", .5f))
        val resolved = PhotoActionResolver(PhotoActionCatalog(listOf(action))).resolve(plan(), action.id)
        val actual = resolved.withActualAvatar(resolved.avatar.copy(height = .3f, expression = ExpressionId.HAPPY, expressionIntensity = 0f))
        assertEquals(action.guidance, CompositionGuidance.from(actual).directive)
        assertEquals(ExpressionId.NEUTRAL, CompositionGuidance.from(actual).expression)
        assertEquals(30, CompositionGuidance.from(actual).heightPercent)
        assertTrue(actual.resolvedSpec!!.matches(actual))
        assertFalse(actual.resolvedSpec.matches(actual.copy(revision = 10)))
    }

    @Test fun automaticSelectionOnlyUsesAvailableReviewedExactSemanticMatch() {
        val old = plan()
        val desired = CompositionGuidance.from(old).directive
        val match = definition(PhotoPoseSource.Animation("fixture-head-turn", .5f)).copy(guidance = desired)
        val pending = match.copy(id = "pending", readiness = ActionReadiness.AWAITING_ASSET)
        val different = match.copy(id = "different", guidance = AvatarPoseDirective(arms = ArmPose.WAVE))
        val resolver = PhotoActionResolver(PhotoActionCatalog(listOf(match, pending, different)))
        assertNull(resolver.automaticActionId(old, emptyList()))
        assertNull(resolver.automaticActionId(old, listOf(pending, different)))
        assertEquals(match.id, resolver.automaticActionId(old, listOf(match)))
        val result = resolver.resolve(old, match.id, ActionOrigin.MODEL)
        assertEquals(ActionOrigin.MODEL, result.resolvedSpec!!.action.origin)
    }

    @Test fun mismatchedExecutionOrGuidanceCannotPassCompatibilityCheck() {
        val resolved = PhotoActionResolver().resolve(plan())
        val spec = resolved.resolvedSpec!!
        assertTrue(spec.matches(resolved))
        val wrongPose = resolved.withActualAvatar(resolved.avatar.copy(pose = PoseId.WAVE))
        assertFalse(wrongPose.resolvedSpec!!.matches(wrongPose))
        val wrongGuidance = spec.copy(guidance = spec.guidance.copy(expression = ExpressionId.HAPPY))
        assertFalse(wrongGuidance.matches(resolved.copy(resolvedSpec = wrongGuidance)))
    }

    @Test fun animationTimesCannotReadPastTheirOwnBufferView() {
        val bytes = File(fixture, "pose-test.glb").readBytes()
        val length = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)
        val original = bytes.copyOfRange(20, 20 + length).toString(Charsets.UTF_8)
        val broken = original.replace("\"byteLength\":8", "\"byteLength\":4")
        assertNotEquals(original, broken)
        assertEquals(original.length, broken.length)
        broken.toByteArray().copyInto(bytes, 20)
        assertTrue(runCatching { AvatarAssetMetadata.read(bytes) }.isFailure)
    }
}
