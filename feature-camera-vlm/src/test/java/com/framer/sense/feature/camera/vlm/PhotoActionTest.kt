package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
            val basics = catalog.candidates(role, ShotType.FULL).filter { it.readiness == ActionReadiness.BASIC }
            assertEquals(12, basics.count { it.selectable })
            assertTrue(basics.all { it.id.startsWith("basic.") })
            assertEquals(if (role == AvatarId.ADULT_FEMALE) 5 else 0,
                catalog.candidates(role, ShotType.FULL).count { it.readiness == ActionReadiness.DRAFT })
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
            PhotoActionCatalog().candidates(role, ShotType.FULL).forEach {
                val actual = AvatarAssetMetadata.read(File("src/main/assets", it.asset(role).fileName).readBytes())
                PhotoActionSupport.validate(it, role, actual)
            }
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

    @Test fun draftSampleIsManualOnlyAndKeepsItsSourceAfterPlacementChanges() {
        val catalog = PhotoActionCatalog()
        val sample = catalog.find(PhotoActionCatalog.SAMPLE_ID)
        val original = plan().copy(avatar = plan().avatar.copy(pose = PoseId.RELAXED,
            poseDirective = sample.guidance))
        val resolver = PhotoActionResolver(catalog)
        assertNull(resolver.automaticActionId(original, listOf(sample)))
        val selected = resolver.resolve(original, sample.id)
        assertEquals(ActionReadiness.DRAFT, selected.resolvedSpec!!.action.readiness)
        assertEquals(ActionOrigin.USER, selected.resolvedSpec.action.origin)
        val moved = selected.withActualAvatar(selected.avatar.copy(foot = Point2(.3f, .9f))).withRevision(2)
        assertEquals(sample.source, resolver.resolve(moved).resolvedSpec!!.action.source)
        assertTrue(moved.resolvedSpec!!.matches(moved))
        assertTrue(runCatching { resolver.resolve(selected.copy(resolvedSpec =
            selected.resolvedSpec.copy(action = selected.resolvedSpec.action.copy(origin = ActionOrigin.MODEL)))) }.isFailure)
    }

    @Test fun packagedSampleHasFullAnimationAndVersionedExecutableExpressions() {
        val sample = PhotoActionCatalog().find(PhotoActionCatalog.SAMPLE_ID)
        val metadata = AvatarAssetMetadata.read(File("src/main/assets", sample.asset(AvatarId.ADULT_FEMALE).fileName).readBytes())
        assertEquals(5, sample.version)
        assertEquals(5, sample.asset(AvatarId.ADULT_FEMALE).version)
        assertEquals(6, metadata.maxFilamentSlots)
        assertTrue(metadata.vertexLayouts.isNotEmpty())
        assertTrue(metadata.vertexLayouts.all { layout -> layout.attributes ==
            setOf("POSITION", "NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0") })
        assertEquals(69, metadata.bones.size)
        assertTrue(metadata.bones.containsAll(setOf("DEF-Head", "DEF-Hand.L", "DEF-Hand.R", "DEF-Foot.L", "DEF-Foot.R")))
        assertEquals(setOf("photo-rain-natural-v1"), metadata.animations.keys)
        assertEquals(1f, metadata.animations.getValue("photo-rain-natural-v1"), .001f)
        PhotoActionSupport.validate(sample, AvatarId.ADULT_FEMALE, metadata)
        val profile = VlmJson.decodeFromString<PhotoExpressionProfile>(File("src/main/assets", sample.expressionProfilePath!!).readText())
        profile.validate(metadata.morphs)
        assertEquals(ActionReadiness.DRAFT, profile.status)
        assertEquals(setOf(ExpressionId.NEUTRAL, ExpressionId.SMILE, ExpressionId.HAPPY), profile.expressions.keys)
        assertEquals(1f, profile.defaultIntensity, 0f)
        assertTrue(profile.weights(ExpressionId.SMILE, .8f).values.any { it > 0f })
        assertTrue(profile.weights(ExpressionId.SMILE, 0f).values.all { it == 0f })
        assertTrue(runCatching { profile.weights(ExpressionId.SURPRISED, 1f) }.isFailure)
        assertTrue(runCatching { profile.weights(ExpressionId.SMILE, Float.NaN) }.isFailure)
        assertTrue(runCatching { profile.validate(emptySet()) }.isFailure)
        val action = PhotoActionResolver().resolve(plan(), sample.id).resolvedSpec!!.action.copy(expressionProfile = profile)
        assertEquals("DEF-Head", action.faceAnchorNode)
        assertFalse(action.softenColors)
        assertNotEquals(action.cacheIdentity(), action.copy(faceAnchorNode = "OtherHead").cacheIdentity())
        assertNotEquals(action.cacheIdentity(), action.copy(softenColors = true).cacheIdentity())
        assertNotEquals(action.cacheIdentity(), action.copy(expressionProfile = profile.copy(version = 2)).cacheIdentity())
        assertNotEquals(action.cacheIdentity(), action.copy(expressionProfile = profile.copy(
            expressions = profile.expressions + (ExpressionId.SMILE to mapOf("PhotoSmile" to .1f)))).cacheIdentity())
    }

    @Test fun ethnicitySelectionChangesActualResourceAndSurvivesLayoutEditsWithoutGatewayChanges() {
        val catalog = PhotoActionCatalog(); val resolver = PhotoActionResolver(catalog)
        val sample = catalog.find(PhotoActionCatalog.SAMPLE_ID)
        val plans = AvatarEthnicity.entries.map { ethnicity ->
            val original = plan().copy(avatar = plan().avatar.copy(ethnicity = ethnicity))
            val chosen = resolver.resolve(original, sample.id)
            assertEquals(ethnicity, chosen.avatar.ethnicity)
            assertEquals(ethnicity, chosen.resolvedSpec!!.action.ethnicity)
            assertEquals(sample.asset(AvatarId.ADULT_FEMALE, ethnicity).fileName, chosen.resolvedSpec.action.avatarAssetPath)
            val moved = chosen.withActualAvatar(chosen.avatar.copy(foot = Point2(.4f, .8f), height = .45f)).withRevision(5)
            val resolved = resolver.resolve(moved)
            assertEquals(ethnicity, resolved.avatar.ethnicity)
            assertEquals(chosen.resolvedSpec.action.avatarAssetPath, resolved.resolvedSpec!!.action.avatarAssetPath)
            assertTrue(resolved.resolvedSpec.matches(resolved))
            chosen
        }
        assertEquals(3, plans.map { it.resolvedSpec!!.action.cacheIdentity() }.distinct().size)
        assertEquals(1, plans.map { VlmJson.encodeToString(it) }.distinct().size)
        assertFalse(VlmJson.encodeToString(plans[1]).contains("ethnicity"))
        val mismatched = plans[0].withActualAvatar(plans[0].avatar.copy(ethnicity = AvatarEthnicity.BLACK))
        assertFalse(mismatched.resolvedSpec!!.matches(mismatched))
        val basic = resolver.resolve(plans[2], PhotoActionCatalog.basicId(PoseId.RELAXED))
        assertNull(basic.avatar.ethnicity)
        assertTrue(basic.resolvedSpec!!.matches(basic))
    }

    @Test fun packagedEthnicityVariantsSupportSamePoseAndExpressions() {
        val definition = PhotoActionCatalog().find(PhotoActionCatalog.SAMPLE_ID)
        val profile = VlmJson.decodeFromString<PhotoExpressionProfile>(File("src/main/assets", definition.expressionProfilePath!!).readText())
        AvatarEthnicity.entries.forEach { ethnicity ->
            val asset = definition.asset(AvatarId.ADULT_FEMALE, ethnicity)
            assertEquals(if (ethnicity == AvatarEthnicity.BLACK) 4 else 5, asset.version)
            val data = AvatarAssetMetadata.read(File("src/main/assets", asset.fileName).readBytes())
            PhotoActionSupport.validate(definition, AvatarId.ADULT_FEMALE, data, ethnicity = ethnicity)
            profile.validate(data.morphs)
            assertEquals(69, data.bones.size)
            assertEquals(6, data.maxFilamentSlots)
            assertTrue(setOf("PhotoSmile", "PhotoHappy").all { it in data.morphs })
            assertEquals(setOf("photo-rain-natural-v1"), data.animations.keys)
            assertEquals(1f, data.animations.getValue("photo-rain-natural-v1"), .001f)
        }
    }

    @Test fun completeRainPoseDraftsBindAllJointsForEveryAppearanceAndCannotBeAutoRecommended() {
        val catalog = PhotoActionCatalog(); val resolver = PhotoActionResolver(catalog)
        val snapshots = PhotoActionCatalog.sampleActionIds.drop(1).map { id ->
            val definition = catalog.find(id)
            assertEquals(ActionReadiness.DRAFT, definition.readiness)
            val source = definition.source as PhotoPoseSource.Snapshot
            val snapshot = VlmJson.decodeFromString<PhotoPoseSnapshot>(File("src/main/assets", source.path).readText())
            AvatarEthnicity.entries.forEach { ethnicity ->
                val asset = definition.asset(AvatarId.ADULT_FEMALE, ethnicity)
                val metadata = AvatarAssetMetadata.read(File("src/main/assets", asset.fileName).readBytes())
                PhotoActionSupport.validate(definition, AvatarId.ADULT_FEMALE, metadata, snapshot, ethnicity)
                assertEquals(69, snapshot.bones.size)
                val selected = resolver.resolve(plan().copy(avatar = plan().avatar.copy(
                    ethnicity = ethnicity, expression = ExpressionId.HAPPY)), id)
                assertEquals(definition.guidance, CompositionGuidance.from(selected).directive)
                assertEquals(ExpressionId.HAPPY, selected.avatar.expression)
                val moved = selected.withActualAvatar(selected.avatar.copy(foot = Point2(.3f, .9f), height = .4f))
                    .withRevision(selected.revision + 1)
                assertEquals(id, resolver.resolve(moved).resolvedSpec!!.action.id)
                assertEquals(ethnicity, resolver.resolve(moved).avatar.ethnicity)
                assertTrue(runCatching { resolver.resolve(plan(), id, ActionOrigin.MODEL) }.isFailure)
            }
            assertNull(resolver.automaticActionId(plan(), listOf(definition)))
            snapshot
        }
        assertEquals(4, snapshots.map { it.bones }.distinct().size)
        assertTrue(snapshots.all { it.bones.keys == snapshots.first().bones.keys })
    }

    @Test fun nativeBufferLimitIsRejectedBeforeAssetLoadingWithoutCountingMorphSlots() {
        val standard = setOf("POSITION", "NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0")
        val boundary = AvatarVertexLayout("boundary", 0, standard + setOf("COLOR_0", "COLOR_1"))
        assertEquals(8, boundary.maxFilamentSlots)
        boundary.validate()
        val crashing = boundary.copy(mesh = "rain-head-v1", attributes = boundary.attributes + "COLOR_2")
        assertEquals(9, crashing.maxFilamentSlots)
        val error = runCatching { crashing.validate() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("AVATAR_VERTEX_BUFFER_LIMIT"))
        assertTrue(error.message!!.contains("rain-head-v1"))
        val data = AvatarAssetMetadata(emptySet(), setOf("PhotoSmile", "PhotoHappy"), emptyMap(), listOf(crashing))
        assertTrue(runCatching { data.validateFilamentVertexBuffers() }.isFailure)
        val unsafe = AvatarAssetMetadata.read(File(fixture, "over-budget.glb").readBytes())
        assertEquals(9, unsafe.maxFilamentSlots)
        assertTrue(runCatching { unsafe.validateFilamentVertexBuffers() }.isFailure)
        assertTrue(runCatching { PhotoActionSupport.validate(definition(PhotoPoseSource.Controlled(PoseId.RELAXED)),
            AvatarId.ADULT_FEMALE, data) }.exceptionOrNull()!!.message!!.startsWith("AVATAR_VERTEX_BUFFER_LIMIT"))
        // 法线重算与 dummy 槽也要保留；源 TANGENT 不额外占槽。
        assertEquals(6, AvatarVertexLayout("no-normal", 0, standard - "NORMAL" + "TANGENT").maxFilamentSlots)
        assertTrue(runCatching { AvatarVertexLayout("no-position", 0, standard - "POSITION").validate() }.isFailure)
        AvatarId.entries.forEach { role ->
            AvatarAssetMetadata.read(File("src/main/assets", AvatarCatalog.asset(role).fileName).readBytes())
                .validateFilamentVertexBuffers()
        }
    }

    @Test fun packagedCartoonHasRealDistinctFaceVertexDeltas() {
        val asset = PhotoActionCatalog().find(PhotoActionCatalog.SAMPLE_ID).asset(AvatarId.ADULT_FEMALE)
        val bytes = File("src/main/assets", asset.fileName).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.getInt(12)
        val root = VlmJson.parseToJsonElement(bytes.copyOfRange(20, 20 + length).toString(Charsets.UTF_8).trim()).jsonObject
        val binary = 20 + length + 8
        fun offset(viewIndex: Int): Int = binary + (root.getValue("bufferViews").jsonArray[viewIndex]
            .jsonObject["byteOffset"]?.jsonPrimitive?.int ?: 0)
        fun vectors(index: Int): FloatArray {
            val accessor = root.getValue("accessors").jsonArray[index].jsonObject
            assertEquals(5126, accessor.getValue("componentType").jsonPrimitive.int)
            assertEquals("VEC3", accessor.getValue("type").jsonPrimitive.content)
            val result = FloatArray(accessor.getValue("count").jsonPrimitive.int * 3)
            accessor["bufferView"]?.jsonPrimitive?.int?.let { view ->
                val stride = root.getValue("bufferViews").jsonArray[view].jsonObject["byteStride"]?.jsonPrimitive?.int ?: 12
                val start = offset(view) + (accessor["byteOffset"]?.jsonPrimitive?.int ?: 0)
                result.indices.forEach { result[it] = buffer.getFloat(start + (it / 3) * stride + (it % 3) * 4) }
            }
            accessor["sparse"]?.jsonObject?.let { sparse ->
                val indices = sparse.getValue("indices").jsonObject
                val values = sparse.getValue("values").jsonObject
                val start = offset(indices.getValue("bufferView").jsonPrimitive.int) + (indices["byteOffset"]?.jsonPrimitive?.int ?: 0)
                val data = offset(values.getValue("bufferView").jsonPrimitive.int) + (values["byteOffset"]?.jsonPrimitive?.int ?: 0)
                repeat(sparse.getValue("count").jsonPrimitive.int) { entry ->
                    val vertex = when (indices.getValue("componentType").jsonPrimitive.int) {
                        5121 -> buffer.get(start + entry).toInt() and 255
                        5123 -> buffer.getShort(start + entry * 2).toInt() and 65535
                        5125 -> buffer.getInt(start + entry * 4)
                        else -> error("Unsupported sparse index")
                    }
                    repeat(3) { result[vertex * 3 + it] = buffer.getFloat(data + entry * 12 + it * 4) }
                }
            }
            assertTrue(result.all(Float::isFinite))
            return result
        }
        val meshIndex = root.getValue("nodes").jsonArray.map { it.jsonObject }
            .first { it["name"]?.jsonPrimitive?.content == "GEO-rain-head-Photo" }.getValue("mesh").jsonPrimitive.int
        val mesh = root.getValue("meshes").jsonArray[meshIndex].jsonObject
        val names = mesh.getValue("extras").jsonObject.getValue("targetNames").jsonArray.map { it.jsonPrimitive.content }
        val primitive = mesh.getValue("primitives").jsonArray.first().jsonObject
        fun deltas(name: String) = vectors(primitive.getValue("targets").jsonArray[names.indexOf(name)]
            .jsonObject.getValue("POSITION").jsonPrimitive.int)
        val smile = deltas("PhotoSmile"); val happy = deltas("PhotoHappy")
        fun changed(values: FloatArray) = values.asList().chunked(3).count { v -> v.sumOf { (it * it).toDouble() } > .0001 * .0001 }
        assertTrue(changed(smile) > 1000)
        assertTrue(changed(happy) > 1000)
        assertTrue(smile.indices.sumOf { kotlin.math.abs(happy[it] - smile[it]).toDouble() } > .5)
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
