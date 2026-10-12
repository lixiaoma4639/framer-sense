package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AvatarPreviewTest {
    private fun plan() = CompositionPlan("p1", "全身", ShotType.FULL, CropRect(), 1f,
        AvatarPlacement(height = .8f, poseDirective = AvatarPoseDirective()), "自然站立", "背景保留")

    @Test fun photographyCropAndModelLayoutReachTheSameCompositeAndInspection() = runBlocking {
        val snapshots=SnapshotStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val background=Bitmap.createBitmap(80,120,Bitmap.Config.ARGB_8888)
        for(y in 0 until 120) for(x in 0 until 80) background.setPixel(x,y,
            if(x < 40) android.graphics.Color.RED else android.graphics.Color.BLUE)
        val scene=try { snapshots.save(background,1f) } finally { background.recycle() }
        val request=plan().copy(crop=CropRect(0f,0f,.5f,.5f),
            photography=PhotographyLayout(head=Point2(.25f,.1f),foot=Point2(.25f,.45f),
                personRegion=CropRect(.15f,.1f,.35f,.45f),cropReason="去掉右侧干扰。",references=emptyList()))
        var calls=0
        val previews=PreviewStore(object: AvatarRenderer {
            override suspend fun render(plan: CompositionPlan,width: Int,height: Int,warmth: Float,brightness: Float): AvatarPreview {
                calls++
                assertEquals(.7f,plan.avatar.height,.0001f)
                assertEquals(Point2(.5f,.9f),plan.avatar.foot)
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                bitmap.setPixel(width/2,height/2,android.graphics.Color.GREEN)
                return AvatarPreview(bitmap,CropRect(.4f,.2f,.6f,.9f),plan.avatar,plan.resolvedSpec)
            }
        })
        try {
            val preview=previews.preview(scene,request)
            assertTrue(android.graphics.Color.red(preview.composite.getPixel(60,10)) > 200)
            assertTrue(android.graphics.Color.blue(preview.composite.getPixel(60,10)) < 40)
            val inspection=previews.inspect(preview.appliedPlan,scene)
            assertEquals(preview.appliedPlan,inspection.appliedPlan)
            assertTrue(inspection.appliedSpec!!.matches(inspection.appliedPlan!!))
            assertEquals(request.crop,inspection.appliedPlan!!.crop)
            assertEquals(1,calls)
        } finally { snapshots.delete(scene) }
    }

    @Test fun missingSpatialDataStillRendersEstimatedAvatarAndKeepsCacheAndRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshots = SnapshotStore(context)
        val background = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888)
        val scene = try { snapshots.save(background, 1f) } finally { background.recycle() }
        var calls = 0
        val previews = PreviewStore(object : AvatarRenderer {
            override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview {
                calls++
                return AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }, CropRect(.4f, .5f, .6f, .92f),
                    plan.avatar.copy(height = .42f, projectionCalibrated = true))
            }
        })
        try {
            val first = previews.preview(scene, plan())
            val resolved = previews.preview(scene, first.appliedPlan)
            val inspected = previews.inspect(first.appliedPlan, scene)
            assertSame(first.composite, resolved.composite)
            assertEquals(1, calls)
            assertTrue(resolved.appliedPlan.avatar.height > 0f)
            assertNotNull(resolved.appliedPlan.scaleEstimate)
            assertFalse(resolved.appliedPlan.hasMeasuredScale())
            assertTrue(resolved.appliedPlan.hasRenderableScale())
            assertTrue(resolved.bounds.bottom > resolved.bounds.top)
            assertTrue((resolved.avatar.getPixel(0,0) ushr 24) > 0)
            assertEquals(resolved.appliedPlan.avatar, inspected.appliedAvatar)
            // 新版本重新渲染，同一版本仍命中缓存，估算标记跟随实际参数。
            val revised = previews.preview(scene, first.appliedPlan.copy(revision = 1))
            assertEquals(2, calls)
            assertEquals(1, revised.appliedPlan.revision)
        } finally { snapshots.delete(scene) }
    }

    /** 需 ARM64 真机/OpenGL；验证四角色的表情、动作、独立脸部示范与重置。 */
    @Test fun realGlbSmileChangesPixelsAndNeutralResetsExpression() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = FilamentAvatarRenderer(context)
        for (id in AvatarId.entries) {
            val request = plan().copy(avatar = plan().avatar.copy(avatarId = id, height = .9f, expression = ExpressionId.NEUTRAL))
            val neutral = renderer.render(request, 512, 768, 0f, .6f)
            val applied = request.withActualAvatar(requireNotNull(neutral.appliedAvatar)).copy(resolvedSpec = neutral.appliedSpec)
            val smiling = applied.withActualAvatar(applied.avatar.copy(expression = ExpressionId.SMILE, expressionIntensity = 1f))
            val smile = renderer.render(smiling, 512, 768, 0f, .6f)
            val neutralFace = renderer.renderFace(applied, 384)
            val smileFace = renderer.renderFace(smiling, 384)
            val wave = renderer.render(renderer.resolve(smiling, PhotoActionCatalog.basicId(PoseId.WAVE)), 512, 768, 0f, .6f)
            val reset = renderer.render(applied, 512, 768, 0f, .6f)
            try {
                fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
                val a = pixels(neutral.bitmap)
                val b = pixels(smile.bitmap)
                assertTrue("$id 人偶不可见", a.any { (it ushr 24) > 0 })
                assertTrue("$id 微笑通道没有影响实际渲染", a.indices.any { a[it] != b[it] })
                val w = pixels(wave.bitmap)
                assertTrue("$id 动作切换未改变渲染", b.indices.any { b[it] != w[it] })
                val nf = pixels(neutralFace.bitmap)
                val sf = pixels(smileFace.bitmap)
                assertTrue("$id 脸部示范不可见", sf.any { (it ushr 24) > 0 })
                assertTrue("$id 脸部微笑不可辨认", nf.indices.any { nf[it] != sf[it] })
                assertEquals(smiling.resolvedSpec, smileFace.appliedSpec)
                assertArrayEquals("$id 骨骼或表情未重置", a, pixels(reset.bitmap))
                assertEquals(applied.avatar.height, smile.appliedAvatar!!.height, 0f)
            } finally {
                neutral.bitmap.recycle(); smile.bitmap.recycle(); reset.bitmap.recycle()
                neutralFace.bitmap.recycle(); smileFace.bitmap.recycle(); wave.bitmap.recycle()
            }
        }
    }
}
