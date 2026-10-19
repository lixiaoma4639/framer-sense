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

    /** 需 ARM64 真机/OpenGL；横向输出中的站立人像仍沿照片纵轴，不能侧倒 90°。 */
    @Test fun realGlbStaysUprightInPortraitAndLandscapePhotoSizes() = runBlocking {
        val renderer = FilamentAvatarRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
        val resolved = renderer.resolve(plan())
        listOf(384 to 512, 512 to 384, 288 to 512, 512 to 288).forEach { (width, height) ->
            val rendered = renderer.render(resolved, width, height, 0f, .6f)
            try {
                assertEquals(width, rendered.bitmap.width); assertEquals(height, rendered.bitmap.height)
                val b = rendered.bounds
                assertTrue("${width}x$height 人像应直立于照片纵轴",
                    (b.bottom - b.top) * height > (b.right - b.left) * width)
                assertTrue(b.top >= -.02f && b.bottom <= 1.02f)
            } finally { rendered.bitmap.recycle() }
        }
    }

    @Test fun calibratedPreviewIsReusedAndInspectorReturnsAppliedPlacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshots = SnapshotStore(context)
        val background = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888)
        val scene = try { snapshots.save(background, 1f) } finally { background.recycle() }
        var calls = 0
        val previews = PreviewStore(object : AvatarRenderer {
            override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview {
                calls++
                return AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(.4f, .5f, .6f, .92f),
                    plan.avatar.copy(height = .42f, projectionCalibrated = true))
            }
        })
        try {
            val first = previews.preview(scene, plan())
            val resolved = previews.preview(scene, first.appliedPlan)
            val inspected = previews.inspect(first.appliedPlan, scene)
            assertSame(first, resolved)
            assertEquals(1, calls)
            assertEquals(.42f, resolved.appliedPlan.avatar.height, 0f)
            assertEquals(resolved.appliedPlan.avatar, inspected.appliedAvatar)
            // 版本变化需要新预览，但已校准标记仍进入渲染请求。
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
