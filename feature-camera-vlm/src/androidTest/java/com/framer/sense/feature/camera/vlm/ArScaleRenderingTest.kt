package com.framer.sense.feature.camera.vlm

import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.ar.SpatialMatrices
import com.framer.sense.feature.camera.vlm.ar.ArFraming
import com.framer.sense.feature.camera.vlm.ar.ArScaleSolver
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 真实 Filament GPU 的透视/资源测试；真实地面、锚点及手机拍摄通过自测清单验收。 */
class ArScaleRenderingTest {
    @Test fun modelLayoutWithoutGroundRendersSameCropAndExpressionOnGpu() = runBlocking {
        val renderer=FilamentAvatarRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
        val n=.1f; val f=40f
        val camera=ArScaleSnapshot("world",1,1,SpatialMatrices.identity(),
            listOf(2f,0f,0f,0f,0f,1.5f,0f,0f,0f,0f,-(f+n)/(f-n),-1f,0f,0f,-2*f*n/(f-n),0f),SpatialPoint(0f,0f,0f),384,512)
        val base=renderer.resolve(CompositionPlan("p","环境",ShotType.FULL,CropRect(.1f,.1f,.9f,.9f),1f,
            AvatarPlacement(ethnicity=AvatarEthnicity.WHITE),"保留建筑","引导线",
            viewBinding=PhotoViewBinding("scan","frame",1,"world"),arCamera=camera,
            photography=PhotographyLayout(head=Point2(.5f,.2f),foot=Point2(.5f,.8f),personRegion=CropRect(.35f,.2f,.65f,.8f),
                cropReason="去掉边缘杂物。",references=emptyList())),PhotoActionCatalog.SAMPLE_ID)
        val estimated=com.framer.sense.feature.camera.vlm.ar.PhotoScaleEstimator.estimate(base,renderer.scaleMetadata(base.resolvedSpec!!.action))
        assertTrue(estimated.spatialPlacement!!.estimated)
        assertEquals(base.crop,estimated.crop)
        val neutral=renderer.render(estimated,384,512,0f,.6f)
        val smiling=estimated.withActualAvatar(estimated.avatar.copy(expression=ExpressionId.SMILE,expressionIntensity=1f))
        val smile=renderer.render(smiling,384,512,0f,.6f)
        try {
            assertEquals(base.crop,smile.appliedSpec!!.crop)
            assertEquals(estimated.spatialPlacement,smile.appliedSpec!!.spatialPlacement)
            assertTrue(smile.appliedSpec!!.matches(smiling))
            val a=IntArray(384*512); val b=IntArray(384*512)
            neutral.bitmap.getPixels(a,0,384,0,0,384,512); smile.bitmap.getPixels(b,0,384,0,0,384,512)
            assertTrue(b.any { it ushr 24 > 0 })
            assertTrue(a.indices.any { a[it] != b[it] })
        } finally { neutral.bitmap.recycle(); smile.bitmap.recycle() }
    }
    @Test fun differentShotsUseOnePhysicalPersonAndCropTheReferenceCamera() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val renderer=FilamentAvatarRenderer(context)
        val base=renderer.resolve(CompositionPlan("p","",ShotType.FULL,CropRect(),1f,
            AvatarPlacement(ethnicity=AvatarEthnicity.WHITE),"","",
            viewBinding=PhotoViewBinding("scan","frame",1,"world"),subjectHeightMeters=1.65f),PhotoActionCatalog.SAMPLE_ID)
        val metadata=renderer.scaleMetadata(base.resolvedSpec!!.action)!!
        val n=.1f; val f=40f
        val camera=ArScaleSnapshot("world",1,1,
            SpatialMatrices.identity().toMutableList().apply { this[13] = -1.2f },
            listOf(2f,0f,0f,0f, 0f,1.5f,0f,0f, 0f,0f,-(f+n)/(f-n),-1f, 0f,0f,-2*f*n/(f-n),0f),
            SpatialPoint(0f,1.2f,0f),384,512)
        val floor=ArGroundPlane("floor",listOf(SpatialPoint(-4f,0f,-1f),SpatialPoint(4f,0f,-1f),
            SpatialPoint(4f,0f,-8f),SpatialPoint(-4f,0f,-8f)),stableObservations=3)
        val position=SpatialPoint(0f,0f,-2.4f)
        for (shot in ShotType.entries) {
            val plan=base.copy(shot=shot,arCamera=camera)
            val placement=requireNotNull(ArScaleSolver.at(plan,camera,floor,position,metadata,manual=true))
            val calibrated=ArFraming.apply(plan,placement)
            val rendered=renderer.render(calibrated,384,512,0f,.6f)
            try {
                assertEquals(position,rendered.appliedSpec!!.spatialPlacement!!.position)
                assertEquals(1.65f,rendered.appliedSpec!!.subjectHeightMeters,0f)
                assertEquals(placement.framingCrop,rendered.appliedSpec!!.crop)
                assertTrue(rendered.appliedSpec!!.matches(calibrated.withActualAvatar(rendered.appliedAvatar ?: calibrated.avatar)))
                assertTrue(rendered.bounds.bottom > rendered.bounds.top)
            } finally { rendered.bitmap.recycle() }
        }
    }

    @Test fun perspectiveSizeUsesDistanceAndExpressionDoesNotChangePhysicalHeight() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val renderer=FilamentAvatarRenderer(context)
        val base=CompositionPlan("p","全身",ShotType.FULL,CropRect(),1f,
            AvatarPlacement(ethnicity=AvatarEthnicity.WHITE),"站立","环境",
            viewBinding=PhotoViewBinding("scan","frame",1,"world"),subjectHeightMeters=1.65f)
        val resolved=renderer.resolve(base,PhotoActionCatalog.SAMPLE_ID)
        val metadata=renderer.scaleMetadata(resolved.resolvedSpec!!.action)!!
        assertTrue(metadata.valid())
        val n=.1f; val f=40f
        val view=SpatialMatrices.identity().toMutableList().apply { this[13] = -1.2f }
        val camera=ArScaleSnapshot("world",1,1,view,
            listOf(2f,0f,0f,0f, 0f,1.5f,0f,0f, 0f,0f,-(f+n)/(f-n),-1f, 0f,0f,-2*f*n/(f-n),0f),
            SpatialPoint(0f,1.2f,0f),384,512)
        fun request(distance: Float, expression: ExpressionId): CompositionPlan {
            val placement=ResolvedSpatialPlacement(camera,"floor",SpatialPoint(0f,0f,-distance),0f,1.65f,metadata,
                Point2(.5f,.5f+.9f/distance),1.2375f/distance,distance)
            return resolved.copy(arCamera=camera).withSpatial(placement).withActualAvatar(resolved.avatar.copy(
                height=placement.projectedHeight,foot=placement.projectedFoot,expression=expression))
        }
        val near=renderer.render(request(3f,ExpressionId.NEUTRAL),384,512,0f,.6f)
        val far=renderer.render(request(6f,ExpressionId.NEUTRAL),384,512,0f,.6f)
        val smile=renderer.render(request(3f,ExpressionId.SMILE),384,512,0f,.6f)
        try {
            val a=near.bounds.bottom-near.bounds.top; val b=far.bounds.bottom-far.bounds.top
            assertEquals("距离翻倍应使真实像素身高约减半",.5f,b/a,.08f)
            assertEquals(1.65f,smile.appliedSpec!!.subjectHeightMeters,0f)
            assertEquals(near.appliedSpec!!.spatialPlacement!!.metadata,smile.appliedSpec!!.spatialPlacement!!.metadata)
            assertTrue(smile.appliedSpec!!.spatialPlacement!!.camera == camera)
        } finally { listOf(near,far,smile).forEach { it.bitmap.recycle() } }
    }

    @Test fun scaleDirectorySupportsAllCurrentRolesAndRainAppearances() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=AvatarScaleStore(context)
        val resolver=PhotoActionResolver()
        for (role in AvatarId.entries) {
            val plan=resolver.resolve(CompositionPlan("p","",ShotType.FULL,CropRect(),1f,AvatarPlacement(avatarId=role),"",""))
            assertNotNull(store.find(plan.resolvedSpec!!.action))
        }
        for (ethnicity in AvatarEthnicity.entries) {
            val base=CompositionPlan("p","",ShotType.FULL,CropRect(),1f,AvatarPlacement(ethnicity=ethnicity),"","")
            val selected=resolver.resolve(base,PhotoActionCatalog.SAMPLE_ID)
            val metadata=store.find(selected.resolvedSpec!!.action)!!
            assertTrue(metadata.referenceHeight > 1.6f)
            assertNull(store.find(selected.resolvedSpec!!.action.copy(avatarAssetVersion=999)))
        }
    }
}
