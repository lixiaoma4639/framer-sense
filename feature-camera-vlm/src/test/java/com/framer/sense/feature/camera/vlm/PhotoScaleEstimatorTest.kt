package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionResolver
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PhotoScaleEstimatorTest {
    private fun camera(): ArScaleSnapshot {
        val near=.1f; val far=40f
        val view=SpatialMatrices.identity().toMutableList().apply { this[13] = -1.3f }
        val projection=listOf(2f,0f,0f,0f, 0f,1.5f,0f,0f, 0f,0f,-(far+near)/(far-near),-1f, 0f,0f,-2*far*near/(far-near),0f)
        return ArScaleSnapshot("world",1,10,view,projection,SpatialPoint(0f,1.3f,0f),480,640)
    }
    private fun plan(shot: ShotType=ShotType.FULL, camera: ArScaleSnapshot?=null) = PhotoActionResolver().resolve(
        CompositionPlan("p","方案",shot,CropRect(),1f,AvatarPlacement(height=0f),"站立","环境",
            arCamera=camera, viewBinding=camera?.let { PhotoViewBinding("scan","frame",1,"world") }))
    private fun metadata() = AvatarScaleMetadata("vlm_avatars/adult_female.glb",1,1,
        SpatialPoint(0f,0f,0f),SpatialPoint(0f,1.6f,0f))

    @Test fun everyShotAndRoleProducesVisibleEstimateWithoutGroundOrAr() {
        for (role in AvatarId.entries) for (shot in ShotType.entries) {
            val input=CompositionPlan("p","方案",shot,CropRect(),1f,AvatarPlacement(height=0f,avatarId=role),"站立","环境",
                subjectHeightMeters=AvatarHeightPolicy.defaultCm(role)/100f)
            val value=PhotoScaleEstimator.estimate(input)
            assertTrue("$role/$shot",value.hasRenderableScale())
            assertFalse(value.hasMeasuredScale())
            val cut=(value.avatar.foot.y-1f)/value.avatar.height
            when (shot) {
                ShotType.FULL,ShotType.ENVIRONMENT -> {
                    assertTrue(value.avatar.foot.y <= 1f)
                    assertTrue(value.avatar.foot.y-value.avatar.height >= 0f)
                }
                ShotType.HALF -> assertTrue(cut in .20f.. .58f)
                ShotType.CLOSE_UP -> assertTrue(cut in .58f.. .84f)
            }
        }
    }

    @Test fun arWithoutAnyPlaneCanProduceEstimatedWorldPlacement() {
        val value=PhotoScaleEstimator.estimate(plan(camera=camera()),metadata())
        assertTrue(value.hasRenderableScale())
        assertFalse(value.hasMeasuredScale())
        assertTrue(value.spatialPlacement!!.estimated)
        assertTrue(value.spatialPlacement!!.matches(value))
        assertTrue(value.resolvedSpec!!.matches(value))
    }

    @Test fun environmentDepthAndFeaturesChangeProjectionAndRemainEstimates() {
        fun withDepth(z: Float): ArScaleSnapshot = camera().copy(depth=(0..3).map {
            ArDepthPoint(Point2(.5f,.8f),SpatialPoint(it*.01f,0f,-z),240)
        })
        val close=PhotoScaleEstimator.estimate(plan(ShotType.ENVIRONMENT,withDepth(3f)),metadata())
        val far=PhotoScaleEstimator.estimate(plan(ShotType.ENVIRONMENT,withDepth(6f)),metadata())
        assertEquals(ScaleEstimateMethod.AR_DEPTH,close.scaleEstimate!!.method)
        assertEquals(.5f,far.avatar.height/close.avatar.height,.02f)
        assertFalse(close.hasMeasuredScale())
        val features=camera().copy(features=(0..3).map { ArFeaturePoint(SpatialPoint(it*.01f,0f,-4f),.9f) })
        val value=PhotoScaleEstimator.estimate(plan(ShotType.ENVIRONMENT,features),metadata())
        assertEquals(ScaleEstimateMethod.AR_FEATURES,value.scaleEstimate!!.method)
        assertTrue(value.hasRenderableScale())
    }

    @Test fun invalidOrMissingEvidenceAndAssetScaleStillAllowScreenRendering() {
        val input=plan(camera=camera().copy(depth=listOf(ArDepthPoint(Point2(Float.NaN,0f),SpatialPoint(0f,0f,Float.NaN),255))))
        val missing=PhotoScaleEstimator.estimate(input)
        assertTrue(missing.hasRenderableScale())
        assertNull(missing.spatialPlacement)
        assertEquals(ScaleEstimateMethod.COMPOSITION_PRIOR,missing.scaleEstimate!!.method)
        val wrongAsset=PhotoScaleEstimator.estimate(input,metadata().copy(assetVersion=99))
        assertTrue(wrongAsset.hasRenderableScale())
        assertNull(wrongAsset.spatialPlacement)
    }

    @Test fun modelRegionAndRealMeasurementOverridePriorWithoutChangingAction() {
        val input=plan(camera=camera()).copy(arPersonRegions=listOf(CropRect(.35f,.2f,.65f,.9f)))
        val estimate=PhotoScaleEstimator.estimate(input,metadata())
        assertEquals(ScaleEstimateMethod.MODEL_REGION,estimate.scaleEstimate!!.method)
        val plane=ArGroundPlane("floor",listOf(SpatialPoint(-4f,0f,-1f),SpatialPoint(4f,0f,-1f),
            SpatialPoint(4f,0f,-8f),SpatialPoint(-4f,0f,-8f)),3)
        val measured=ArScaleSolver.at(input,camera(),plane,SpatialPoint(0f,0f,-3.5f),metadata())!!
        val upgraded=ArFraming.apply(estimate,measured)
        assertTrue(upgraded.hasMeasuredScale())
        assertNull(upgraded.scaleEstimate)
        assertSame(upgraded,PhotoScaleEstimator.estimate(upgraded,metadata()))
        assertEquals(estimate.resolvedSpec!!.action,upgraded.resolvedSpec!!.action)
        assertFalse(VlmJson.encodeToString(estimate).contains("scaleEstimate"))
        assertFalse(VlmJson.encodeToString(estimate).contains("spatialPlacement"))
    }
}
