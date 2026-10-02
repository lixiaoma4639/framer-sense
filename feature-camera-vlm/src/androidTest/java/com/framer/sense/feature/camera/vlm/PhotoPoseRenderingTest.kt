package com.framer.sense.feature.camera.vlm

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 专用蒙皮方块验证执行路径，不属于专业摄影内容。需 ARM64/OpenGL 设备运行。 */
class PhotoPoseRenderingTest {
    @Test fun animationAndCompleteSnapshotRenderThenResetToBasicPose() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getAssets() = instrumentation.context.assets
        }
        val asset = AvatarCatalog.Asset("photo_pose_fixture/pose-test.glb", rigId = "fixture-v1", boundsExtentScale = 1f)
        fun definition(id: String, source: PhotoPoseSource, guidance: AvatarPoseDirective? = null) = PhotoActionDefinition(
            id, 1, "fixture-v1", ActionReadiness.REVIEWED, setOf(AvatarId.ADULT_FEMALE), source = source,
            guidance = guidance, avatarAssets = mapOf(AvatarId.ADULT_FEMALE to asset))
        val basicId = PhotoActionCatalog.basicId(PoseId.RELAXED)
        val catalog = PhotoActionCatalog(listOf(
            definition(basicId, PhotoPoseSource.Controlled(PoseId.RELAXED)),
            definition("fixture.animation", PhotoPoseSource.Animation("fixture-head-turn", .5f), AvatarPoseDirective(head = HeadPose.TURN_RIGHT)),
            definition("fixture.snapshot", PhotoPoseSource.Snapshot("photo_pose_fixture/pose-test.json"), AvatarPoseDirective(head = HeadPose.TURN_RIGHT)),
            definition("fixture.missing", PhotoPoseSource.Animation("not-installed", .5f), AvatarPoseDirective()),
            definition("fixture.missing_snapshot", PhotoPoseSource.Snapshot("not-installed.json"), AvatarPoseDirective())
        ))
        val renderer = FilamentAvatarRenderer(context, catalog)
        assertEquals(setOf(basicId, "fixture.animation", "fixture.snapshot"), renderer.availableActions(AvatarId.ADULT_FEMALE, ShotType.FULL).map { it.id }.toSet())
        val request = CompositionPlan("p1", "测试姿态", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(height = .8f, expression = ExpressionId.NEUTRAL), "测试指导", "测试场景")
        val neutral = renderer.render(request, 384, 512, 0f, .6f)
        val base = request.withActualAvatar(neutral.appliedAvatar!!).copy(resolvedSpec = neutral.appliedSpec)
        fun pixels(preview: AvatarPreview) = IntArray(preview.bitmap.width * preview.bitmap.height).also {
            preview.bitmap.getPixels(it, 0, preview.bitmap.width, 0, 0, preview.bitmap.width, preview.bitmap.height)
        }
        try {
            for (id in listOf("fixture.animation", "fixture.snapshot")) {
                val selected = renderer.resolve(base, id)
                val sample = renderer.render(selected, 384, 512, 0f, .6f)
                val reset = renderer.render(renderer.resolve(base, basicId), 384, 512, 0f, .6f)
                val face = renderer.renderFace(selected, 384)
                try {
                    val a = pixels(neutral)
                    val b = pixels(sample)
                    assertTrue("$id 未执行", a.indices.any { a[it] != b[it] })
                    assertArrayEquals("$id 残留到后续姿态", a, pixels(reset))
                    assertTrue(sample.appliedSpec!!.matches(selected.withActualAvatar(sample.appliedAvatar!!)))
                    assertTrue(pixels(face).any { (it ushr 24) > 0 })
                    assertEquals(selected.resolvedSpec, face.appliedSpec)
                } finally { sample.bitmap.recycle(); reset.bitmap.recycle(); face.bitmap.recycle() }
            }
        } finally { neutral.bitmap.recycle() }
    }
}
