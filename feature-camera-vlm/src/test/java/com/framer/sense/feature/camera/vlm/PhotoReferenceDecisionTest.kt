package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PhotoReferenceDecisionTest {
    private fun input() = DirectorInput(SceneSnapshot("scene", 640, 480, 1f, "scene.jpg", "model.jpg"),
        CameraCapabilities(), "", photoReferences = true, ethnicity = AvatarEthnicity.ASIAN,
        viewBinding = PhotoViewBinding("scan", "scene", 2, "world"))

    @Test fun allSixChoicesHaveExecutableReferencesAndPreserveAppearanceAndSource() {
        for (n in 1..6) {
            val plans = PhotoReferenceDecisionPolicy.decode("$n", input(), true)
            val primary = plans.first()
            assertEquals(n, primary.localCombination)
            assertFalse(primary.localAlternative)
            assertTrue(plans.drop(1).all { it.localAlternative })
            assertEquals(AvatarEthnicity.ASIAN, primary.avatar.ethnicity)
            assertEquals(input().viewBinding, primary.viewBinding)
            assertEquals(ActionOrigin.PREVIEW_MODEL, primary.resolvedSpec!!.action.origin)
            assertEquals(ActionReadiness.DRAFT, primary.resolvedSpec.action.readiness)
            assertTrue(primary.resolvedSpec.matches(primary))
            assertTrue(plans.all { it.resolvedSpec!!.action.id in PhotoActionCatalog.previewModelActionIds })
            assertTrue(PlanValidator().validate(plans, input()).isEmpty())
            val edited = primary.withActualAvatar(primary.avatar.copy(foot = Point2(.4f,.9f))).withRevision(7)
            assertTrue(PhotoActionResolver().resolve(edited).resolvedSpec!!.matches(edited))
        }
    }
    @Test fun localInvalidNumbersAndFreeTextFailWithoutDefaultPose() {
        for (raw in listOf("0", "7", "SELECT=1", "SELECT(SELECTING)", "1 2", ""))
            assertTrue(runCatching { PhotoReferenceDecisionPolicy.decode(raw, input(), true) }.isFailure)
    }
    @Test fun modifyingOnePlanDoesNotChangeTheOtherPlans() {
        val originals = PhotoReferenceDecisionPolicy.decode("2", input(), true)
        val editedInput = input().copy(existing = originals, selectedId = "p2")
        val revised = PhotoReferenceDecisionPolicy.decode("5", editedInput, true)
        assertEquals(originals[0], revised[0]); assertEquals(originals[2], revised[2])
        assertEquals(PhotoActionCatalog.LOOK_BACK_ID, revised[1].resolvedSpec!!.action.id)
        assertEquals("p2", revised[1].id)
    }
    @Test fun cloudRejectsUnacceptedActionsInvalidWeightsAndForeignScene() {
        val item = PhotoReferenceIntent("p1", "环境参考", ShotType.ENVIRONMENT, CompositionZone.LEFT,
            PhotoActionCatalog.SAMPLE_ID, ExpressionId.SMILE, 1f, 0f, "背景留白", emptyList(), emptyList())
        val decision = PhotoReferenceDecision(imageId = "scene", intents = listOf(item, item.copy(id="p2",zone=CompositionZone.CENTER), item.copy(id="p3",zone=CompositionZone.RIGHT)))
        fun rejects(d: PhotoReferenceDecision) = assertTrue(runCatching { PhotoReferenceDecisionPolicy.decode(VlmJson.encodeToString(d),input(),false) }.isFailure)
        rejects(decision.copy(imageId="other"))
        rejects(decision.copy(intents=decision.intents.map { it.copy(actionId=PhotoActionCatalog.HAND_HIP_ID) }))
        rejects(decision.copy(intents=decision.intents.map { it.copy(actionId=PhotoActionCatalog.WALK_PAUSE_ID) }))
        rejects(decision.copy(intents=decision.intents.map { it.copy(expressionIntensity=1.1f) }))
        rejects(decision.copy(intents=decision.intents.map { it.copy(id="p1") }))
        val region = CropRect(.45f,.05f,.95f,.7f)
        val actual = PhotoReferenceDecisionPolicy.decode(VlmJson.encodeToString(decision.copy(intents=decision.intents.map { it.copy(backgroundKeepRegions=listOf(region)) })),input(),false)
        assertEquals(listOf(region),actual.first().resolvedSpec!!.backgroundKeepRegions)
        assertFalse(VlmJson.encodeToString(actual.first()).contains("backgroundKeepRegions"))
        assertFalse(VlmJson.encodeToString(actual.first()).contains("resolvedSpec"))
    }
    @Test fun staleAnchorsCannotCrossSessionsOrPlanRevisions() {
        val gate = ArReferenceGate()
        gate.session("world1"); gate.plan("p1:0")
        assertTrue(gate.place("world1","p1:0")); assertTrue(gate.placed)
        gate.plan("p1:1"); assertFalse(gate.placed)
        assertFalse(gate.place("world1","p1:0"))
        assertTrue(gate.place("world1","p1:1"))
        gate.invalidate(); assertFalse(gate.placed)
        gate.session("world2"); gate.plan("p1:1")
        assertFalse(gate.place("world1","p1:1")); assertFalse(gate.placed)
    }
    @Test fun groundRayUsesOriginalImageCoordinatesAndDoesNotClampOutOfFrameFeet() {
        val plan = PhotoReferenceDecisionPolicy.decode("3",input(),true).first()
        assertEquals(PlanCoordinates.toOriginal(plan.avatar.foot,plan.crop),ArGeometry.suggestedGround(plan))
        assertNull(ArGeometry.suggestedGround(plan.copy(avatar=plan.avatar.copy(foot=Point2(.5f,3f)))))
        val vertex = FloatArray(6)
        ArGeometry.writeDepthVertex(vertex,0,80,60,2f,100f,100f,80f,60f)
        assertArrayEquals(floatArrayOf(0f,0f,-2f),vertex.take(3).toFloatArray(),.0001f)
        ArGeometry.writeDepthVertex(vertex,3,100,70,2f,100f,100f,80f,60f)
        assertArrayEquals(floatArrayOf(.4f,-.2f,-2f),vertex.drop(3).toFloatArray(),.0001f)
    }
    @Test fun offlineAlternativesFollowActualNativeModelRanking() {
        val plans=PhotoReferenceDecisionPolicy.decode("RANK=4,6,2,5,1,3",input(),true)
        assertEquals(listOf(4,6,2),plans.map { it.localCombination })
        assertTrue(plans.all { it.localRanked })
        assertTrue(plans.all { it.avatar.height == 0f && !it.hasMeasuredScale() })
        assertFalse(plans.first().localAlternative)
        assertTrue(plans.drop(1).all { it.localAlternative })
        assertTrue(PlanValidator().validate(plans,input()).isEmpty())
        for (raw in listOf("RANK=1,1,3,4,5,6","RANK=1,2,3","RANK=1,2,3,4,5,7","RANK=one"))
            assertTrue(runCatching { PhotoReferenceDecisionPolicy.decode(raw,input(),true) }.isFailure)
    }
    @Test fun rankedRevisionKeepsUnselectedPlans() {
        val existing=PhotoReferenceDecisionPolicy.decode("RANK=2,4,6,1,3,5",input(),true)
        val changed=PhotoReferenceDecisionPolicy.decode("RANK=5,6,4,3,2,1",input().copy(existing=existing,selectedId="p2"),true)
        assertEquals(existing[0],changed[0]); assertEquals(existing[2],changed[2])
        assertEquals(5,changed[1].localCombination)
    }

}
