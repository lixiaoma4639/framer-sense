package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionCatalog
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

/** 纯协议/数学回归，不加载权重或 GPU。 */
class PhotographyProtocolTest {
    private fun input() = DirectorInput(SceneSnapshot("frame",600,800,1f,"/unused","/unused"),
        CameraCapabilities(1f,4f,listOf(1f,2f,4f)),"环境优先",photographyEnabled=true)
    private fun decision(): PhotographyDecision = PhotographyDecision(3,"frame","建筑形成引导线，暖色墙面与深色服装形成对比。\n\n注意避开门边高亮。",
        (1..3).map { i ->
            val x=listOf(.3f,.55f,.75f)[i-1]
            val crop=if(i == 1) CropRect(.1f,.1f,.8f,.8f) else CropRect()
            PhotographyIntent("p$i","方案$i",ShotType.FULL,crop,"裁去右侧杂物，保留入口和纵深。",
                Point2(x,.2f),Point2(x,.72f),CropRect(x-.1f,.2f,x+.1f,.72f),
                PhotoActionCatalog.SAMPLE_ID,ExpressionId.SMILE,.8f,0f,"入口线条引向人物。","避开门边，让人物与背景轮廓分开。",
                emptyList(),emptyList(),listOf("地面可站立性未知"))
        })
    private fun decode(d: PhotographyDecision=decision(),source: DirectorInput=input(),fallback: Boolean=false) =
        PhotographyProtocol.decode(VlmJson.encodeToString(d),source,fallback)

    @Test fun threeSameShotPlansKeepModelGeometryCropAndActualCameraZoom() {
        val plans=decode()
        assertTrue(PlanValidator().validate(plans,input()).isEmpty())
        assertEquals(3,plans.map { it.shot }.count { it == ShotType.FULL })
        val p=plans.first()
        assertEquals(1f,p.zoom,0f)
        assertEquals(.52f/.7f,p.avatar.height,.0001f)
        assertEquals((.72f-.1f)/.7f,p.avatar.foot.y,.0001f)
        assertEquals(decision().plans.first().crop,p.crop)
        assertTrue(p.resolvedSpec!!.matches(p))
        assertFalse(p.sceneDescription!!.contains("\n\n"))
        assertTrue(p.sceneDescription!!.contains("。\n"))
        val serialized=VlmJson.encodeToString(p)
        assertFalse(serialized.contains("photography"))
        assertFalse(serialized.contains("scaleEstimate"))
    }

    @Test fun largeCropNeedsReasonAndProtectsNinetyPercentOfBackground() {
        val d=decision()
        assertTrue(runCatching { decode(d.copy(plans=d.plans.map { it.copy(cropReason="") })) }.isFailure)
        val source=input().copy(backgroundRegions=listOf(CropRect(.7f,.1f,1f,.8f)))
        assertTrue(runCatching { decode(d,source) }.isFailure)
        assertTrue(PhotographyProtocol.keeps(CropRect(.1f,.1f,.9f,.9f),CropRect(.2f,.2f,.8f,.8f)))
    }

    @Test fun fourRolesKeepTheirHeightAndOnlyUseExecutableIdentity() {
        for(role in AvatarId.entries) {
            val source=input().copy(avatarId=role,subjectHeightMeters=AvatarHeightPolicy.defaultCm(role)/100f)
            val d=decision().copy(plans=decision().plans.map { it.copy(actionId=PhotographyProtocol.actionIds(role).first()) })
            decode(d,source).forEach { p ->
                assertEquals(role,p.avatar.avatarId)
                assertEquals(source.subjectHeightMeters,p.subjectHeightMeters,0f)
                assertTrue(p.resolvedSpec!!.matches(p))
            }
        }
    }

    @Test fun digitalCropWaitsForScannedBackgroundButSingleImageDoesNotWait() {
        val p=decode().first()
        assertEquals(p.crop,p.photographyDisplayCrop(false))
        val scan=p.copy(viewBinding=PhotoViewBinding("scan","frame",1))
        assertEquals(CropRect(),scan.photographyDisplayCrop(false))
        assertEquals(p.crop,scan.photographyDisplayCrop(true))
    }

    @Test fun invalidLayoutCanDegradeButForeignSourceAndMissingActionCannot() {
        val d=decision().copy(plans=decision().plans.map { it.copy(head=it.foot) })
        assertTrue(runCatching { decode(d) }.isFailure)
        decode(d,fallback=true).forEach { p ->
            assertTrue(p.photography!!.layoutFallback)
            val actual=PhotoScaleEstimator.estimate(p.withSpatial(null))
            assertTrue(actual.hasRenderableScale())
            assertEquals(p.crop,actual.crop)
            assertEquals(ScaleEstimateMethod.LAYOUT_FALLBACK,actual.scaleEstimate!!.method)
            assertTrue(actual.resolvedSpec!!.matches(actual))
        }
        assertTrue(runCatching { decode(d.copy(imageId="old-frame"),fallback=true) }.isFailure)
        assertTrue(runCatching { decode(d.copy(plans=d.plans.map { it.copy(actionId=PhotoActionCatalog.HAND_HIP_ID) }),fallback=true) }.isFailure)
    }

    @Test fun halfBodyUsesOffscreenFeetWithoutChangingCrop() {
        val d=decision(); val first=d.plans.first().copy(shot=ShotType.HALF,crop=CropRect(),
            head=Point2(.3f,.1f),foot=Point2(.3f,1.5f),personRegion=CropRect(.1f,.1f,.5f,1.5f))
        val p=decode(d.copy(plans=listOf(first)+d.plans.drop(1))).first()
        val actual=PhotoScaleEstimator.estimate(p.withSpatial(null))
        assertEquals(1.4f,actual.avatar.height,.0001f)
        assertEquals(1.5f,actual.avatar.foot.y,.0001f)
        assertEquals(CropRect(),actual.crop)
    }

    @Test fun localPhotographyCallsOnceAndPreservesUnselectedPlans() = runTest {
        var calls=0
        val result=DirectorAgent().run(input(),ModelSettings(mode=ModelMode.OFFLINE), { object: VisionModelProvider {
            override suspend fun step(call: ModelCall): StepReply {
                calls++
                return StepReply("fake",VlmJson.encodeToString(decision()),photographyProtocol=true)
            }
        } },PreviewInspector { p,_ ->
            val actual=PhotoScaleEstimator.estimate(p.withSpatial(null))
            PreviewInspection(appliedPlan=actual)
        })
        assertEquals(1,calls)
        assertTrue(result.plans.all { it.hasRenderableScale() && it.resolvedSpec!!.matches(it) })
        val edit=input().copy(existing=result.plans,selectedId="p2")
        val one=decision().copy(plans=listOf(decision().plans[1].copy(reason="保留纵深，避开高亮。")))
        val revised=DirectorAgent().run(edit,ModelSettings(mode=ModelMode.OFFLINE), { object: VisionModelProvider {
            override suspend fun step(call: ModelCall)=StepReply("fake",VlmJson.encodeToString(one),photographyProtocol=true)
        } },PreviewInspector { p,_ -> assertEquals("p2",p.id); PreviewInspection(appliedPlan=PhotoScaleEstimator.estimate(p.withSpatial(null))) })
        assertEquals(result.plans[0],revised.plans[0]); assertEquals(result.plans[2],revised.plans[2])
        assertEquals(result.plans[1].revision+1,revised.plans[1].revision)
    }

    @Test fun cloudPhotographyKeepsOneCorrection() = runTest {
        var calls = 0
        val result = DirectorAgent().run(input(), ModelSettings(mode = ModelMode.CLOUD), { object : VisionModelProvider {
            override suspend fun step(call: ModelCall): StepReply {
                calls++
                val d = if (calls == 1) decision().copy(plans = decision().plans.map { it.copy(cropReason = "") }) else decision()
                return StepReply("fake-cloud", VlmJson.encodeToString(d), photographyProtocol = true)
            }
        } }, PreviewInspector { p, _ -> PreviewInspection(appliedPlan = p) })
        assertEquals(2, calls)
        assertEquals(3, result.plans.size)
    }

    @Test fun worldPlacementMatchesModelAndUnknownDepthIsNotMeasured() {
        val near=.1f; val far=40f
        val camera=ArScaleSnapshot("world",1,1,SpatialMatrices.identity(),
            listOf(2f,0f,0f,0f,0f,1.5f,0f,0f,0f,0f,-(far+near)/(far-near),-1f,0f,0f,-2*far*near/(far-near),0f),
            SpatialPoint(0f,0f,0f),600,800)
        val source=input().copy(arMode=true,scene=input().scene.copy(arCamera=camera),viewBinding=PhotoViewBinding("scan","frame",1,"world"))
        val plan=decode(source=source).first()
        val action=plan.resolvedSpec!!.action
        val metadata=AvatarScaleMetadata(action.avatarAssetPath,action.avatarAssetVersion,1,SpatialPoint(0f,0f,0f),SpatialPoint(0f,1.6f,0f))
        val actual=PhotoScaleEstimator.estimate(plan,metadata)
        assertEquals(plan.crop,actual.crop)
        assertEquals(plan.subjectHeightMeters,actual.spatialPlacement!!.heightMeters,0f)
        assertTrue(actual.spatialPlacement!!.estimated)
        assertTrue(PhotographyPlacementSolver.targetError(actual,camera,actual.spatialPlacement!!.position) <= .05f)
        assertFalse(actual.hasMeasuredScale())
        val mismatch=plan.copy(photography=plan.photography!!.copy(head=Point2(.4f,plan.photography!!.head!!.y)))
            .withActualAvatar(plan.avatar)
        assertNull(PhotographyPlacementSolver.estimate(mismatch,metadata))
        val screen=PhotoScaleEstimator.estimate(mismatch,metadata)
        assertTrue(screen.hasRenderableScale())
        assertNull(screen.spatialPlacement)
        assertEquals(plan.crop,screen.crop)
        val edited=actual.withEditedAvatar(actual.avatar.copy(height=actual.avatar.height*.9f))
        assertNull(edited.spatialPlacement)
        assertFalse(edited.photography!!.guidanceCurrent)
        assertTrue(edited.resolvedSpec!!.matches(edited))
        val reference=PhotographyReference("门",CropRect(.1f,.1f,.4f,.8f),1.9f,2.2f,ReferenceDepthRelation.UNKNOWN,"可见门框")
        assertTrue(PhotographyProtocol.referenceObservations(plan.copy(photography=plan.photography!!.copy(references=listOf(reference)))).single().startsWith("REFERENCE_SCALE_UNKNOWN"))
        val same=plan.copy(subjectHeightMeters=1.7f,photography=plan.photography!!.copy(head=Point2(.3f,0f),foot=Point2(.3f,.85f),
            references=listOf(reference.copy(region=CropRect(0f,0f,.4f,1f),heightMinMeters=2f,heightMaxMeters=2f,depthRelation=ReferenceDepthRelation.SAME))))
        assertTrue(PhotographyProtocol.referenceObservations(same).single().endsWith("consistent=true"))
    }

    @Test fun localInvalidRegionsFailWithoutRetryOrInventedPlansAndCancelNeverRetries() = runTest {
        var calls=0
        val invalid=decision().copy(plans=decision().plans.map { it.copy(head=it.foot) })
        try {
            DirectorAgent().run(input(),ModelSettings(mode=ModelMode.OFFLINE), { object: VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++; return StepReply("fake",VlmJson.encodeToString(invalid),photographyProtocol=true)
                }
            } },PreviewInspector { _,_ -> error("无效结果不得生成临时人像") })
            fail("无效结果必须失败")
        } catch (failure: ModelFailure) {
            assertEquals("LOCAL_PHOTO_INVALID_OUTPUT",failure.code)
            assertTrue(failure.diagnostics!!.plans.isEmpty())
            assertEquals(1,calls)
        }
        calls=0
        try {
            DirectorAgent().run(input(),ModelSettings(mode=ModelMode.OFFLINE), { object: VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply { calls++; throw CancellationException("cancel") }
            } },PreviewInspector { _,_ -> error("取消后不能渲染") })
            fail("取消必须继续传播")
        } catch(_: CancellationException) { assertEquals(1,calls) }
    }
}
