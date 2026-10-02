package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.avatar.shrinkProjection
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CompositionGuidanceTest {
    private val input = DirectorInput(SceneSnapshot("frame", 600, 800, 1f, "", ""), CameraCapabilities(1f, 3f), "自然")

    @Test fun fallbackGuidanceUsesEachFinalActionRatherThanSharedModelText() {
        val decision = CompositionIntentMapper.sceneFallback("花园里阳光温暖，人物自然站立微笑", input)
        val plans = CompositionIntentMapper.map(decision, input).plans
        assertEquals(1, plans.map { it.guidance }.distinct().size)
        assertEquals(3, plans.map { CompositionGuidance.from(it).directive }.distinct().size)
        plans.forEach { assertEquals(it.avatar.poseDirective, CompositionGuidance.from(it).directive) }
    }

    @Test fun deduplicationAndManualPlacementAreReflectedInResolvedGuidance() {
        val intent = CompositionIntentMapper.sceneFallback("湖边开阔，人物自然站立微笑", input).intents.first()
        val plans = CompositionIntentMapper.map(CompositionIntentDecision(ActionKind.FINAL, List(3) { intent }, imageId = "frame"), input).plans
        assertEquals(3, plans.map { CompositionGuidance.from(it).zone }.distinct().size)
        val adjusted = plans[0].copy(avatar = plans[0].avatar.copy(foot = Point2(.75f, .9f), height = .4f, yaw = -24f, expressionIntensity = 0f))
        val guide = CompositionGuidance.from(adjusted)
        assertEquals(CompositionZone.RIGHT, guide.zone)
        assertEquals(FacingDirection.THREE_QUARTER_LEFT, guide.facing)
        assertEquals(75, guide.horizontalPercent)
        assertEquals(40, guide.heightPercent)
        assertEquals(ExpressionId.NEUTRAL, guide.expression)
    }

    @Test fun everyShotAndDistanceProducesValidGeometryAndVisibleHeight() {
        val template = CompositionIntentMapper.sceneFallback("湖边开阔，人物自然站立微笑", input)
        ShotType.entries.forEach { shot -> SubjectDistance.entries.forEach { distance ->
            val plans = CompositionIntentMapper.map(template.copy(intents = template.intents.map { it.copy(shot = shot, subjectDistance = distance) }), input).plans
            val errors = PlanValidator().validate(plans, input)
            assertTrue("$shot/$distance: $errors", errors.isEmpty())
            plans.forEach { assertTrue(CompositionGuidance.from(it).heightPercent in 1..100) }
        } }
    }

    @Test fun legacyRenderingDoesNotClaimNewStanceOrHeadRotation() {
        val plan = CompositionIntentMapper.map(CompositionIntentMapper.sceneFallback("湖边开阔，人物自然站立微笑", input), input).plans.first()
        val guide = CompositionGuidance.from(plan.copy(avatar = AvatarPlacement(pose = PoseId.HAT)))
        assertTrue(guide.legacyHandNearFace)
        assertEquals(HeadPose.FRONT, guide.directive.head)
        assertEquals(StancePose.NEUTRAL, guide.directive.stance)
    }

    @Test fun projectionCalibrationStaysOutOfGatewayProtocol() {
        val encoded = VlmJson.encodeToString(AvatarPlacement(projectionCalibrated = true))
        assertFalse(encoded.contains("projectionCalibrated"))
        assertFalse(VlmJson.decodeFromString<AvatarPlacement>(encoded).projectionCalibrated)
    }

    @Test fun projectionShrinkPreservesCroppedShotAndUpdatesVisibleHeight() {
        val template = CompositionIntentMapper.sceneFallback("湖边开阔，人物自然站立微笑", input)
        listOf(ShotType.HALF, ShotType.CLOSE_UP).forEach { shot ->
            val plans = CompositionIntentMapper.map(template.copy(intents = template.intents.map { it.copy(shot = shot) }), input).plans
            val calibrated = plans.map { it.shrinkProjection(.45f) }
            assertTrue(PlanValidator().validate(calibrated, input).isEmpty())
            plans.zip(calibrated).forEach { (before, after) ->
                assertEquals((before.avatar.foot.y - 1f) / before.avatar.height, (after.avatar.foot.y - 1f) / after.avatar.height, .0001f)
                assertTrue(CompositionGuidance.from(after).heightPercent < CompositionGuidance.from(before).heightPercent)
                assertEquals(before.avatar.poseDirective, after.avatar.poseDirective)
            }
        }
    }
}
