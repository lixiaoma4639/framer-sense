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
    @Test fun completeRainPoseDraftsRenderAndResetWithoutChangingAppearance() = runBlocking {
        val renderer = FilamentAvatarRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
        val base = CompositionPlan("poses", "", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(height = .7f, expression = ExpressionId.NEUTRAL, ethnicity = AvatarEthnicity.WHITE), "", "")
        val natural = renderer.resolve(base, PhotoActionCatalog.SAMPLE_ID)
        val baseline = renderer.render(natural, 384, 512, 0f, .6f)
        fun pixels(image: AvatarPreview) = IntArray(image.bitmap.width * image.bitmap.height).also {
            image.bitmap.getPixels(it, 0, image.bitmap.width, 0, 0, image.bitmap.width, image.bitmap.height)
        }
        try {
            val before = pixels(baseline)
            for (id in PhotoActionCatalog.sampleActionIds.drop(1)) {
                val selected = renderer.resolve(natural, id)
                val pose = renderer.render(selected, 384, 512, 0f, .6f)
                val smile = selected.withActualAvatar(selected.avatar.copy(expression = ExpressionId.SMILE, expressionIntensity = 1f))
                val face = renderer.renderFace(smile, 384)
                val reset = renderer.render(natural, 384, 512, 0f, .6f)
                try {
                    val current = pixels(pose)
                    assertTrue("$id 姿态投影为空", current.any { (it ushr 24) > 128 })
                    assertTrue("$id 与自然站立无可见差异", before.indices.count { before[it] != current[it] } > 1000)
                    assertArrayEquals("$id 骨骼或表情残留", before, pixels(reset))
                    assertTrue("$id 脸部示范为空", pixels(face).any { (it ushr 24) > 128 })
                    assertEquals(id, pose.appliedSpec!!.action.id)
                    assertEquals(id, face.appliedSpec!!.action.id)
                    assertEquals(AvatarEthnicity.WHITE, face.appliedSpec!!.action.ethnicity)
                    assertEquals(ExpressionId.SMILE, face.appliedAvatar!!.expression)
                } finally { listOf(pose, face, reset).forEach { it.bitmap.recycle() } }
            }
        } finally { baseline.bitmap.recycle() }
    }

    @Test fun oversizedVertexLayoutIsRejectedDuringResolutionBeforeNativeLoad() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getAssets() = instrumentation.context.assets
        }
        val unsafe = PhotoActionDefinition("fixture.over-budget", 1, "fixture-v1", ActionReadiness.REVIEWED,
            setOf(AvatarId.ADULT_FEMALE), source = PhotoPoseSource.Animation("fixture-head-turn", .5f),
            guidance = AvatarPoseDirective(), avatarAssets = mapOf(AvatarId.ADULT_FEMALE to
                AvatarCatalog.Asset("photo_pose_fixture/over-budget.glb", rigId = "fixture-v1", boundsExtentScale = 1f)))
        val renderer = FilamentAvatarRenderer(context, PhotoActionCatalog(listOf(unsafe)))
        assertTrue(renderer.availableActions(AvatarId.ADULT_FEMALE, ShotType.FULL).isEmpty())
        val plan = CompositionPlan("unsafe", "", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(expression = ExpressionId.NEUTRAL), "", "")
        val failure = runCatching { renderer.resolve(plan, unsafe.id) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.cause!!.message!!.startsWith("AVATAR_VERTEX_BUFFER_LIMIT"))
    }

    @Test fun realDraftSampleExecutesFullPoseAndFaceWeightsWithoutModel() = runBlocking {
        val renderer = FilamentAvatarRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
        val base = CompositionPlan("sample", "", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(height = .7f, pose = PoseId.RELAXED, expression = ExpressionId.NEUTRAL), "", "")
        val selected = renderer.resolve(base, PhotoActionCatalog.SAMPLE_ID)
        assertEquals(listOf(ExpressionId.NEUTRAL, ExpressionId.SMILE, ExpressionId.HAPPY), renderer.availableExpressions(selected))
        val neutral = renderer.renderFace(selected, 384)
        val smiling = selected.withActualAvatar(selected.avatar.copy(expression = ExpressionId.SMILE, expressionIntensity = 1f))
        val smile = renderer.renderFace(smiling, 384)
        val happy = renderer.renderFace(selected.withActualAvatar(selected.avatar.copy(expression = ExpressionId.HAPPY, expressionIntensity = 1f)), 384)
        val reset = renderer.renderFace(selected, 384)
        val body = renderer.render(selected, 384, 512, 0f, .6f)
        fun pixels(image: AvatarPreview) = IntArray(image.bitmap.width * image.bitmap.height).also {
            image.bitmap.getPixels(it, 0, image.bitmap.width, 0, 0, image.bitmap.width, image.bitmap.height)
        }
        try {
            val before = pixels(neutral); val after = pixels(smile)
            fun visibleChanges(a: IntArray, b: IntArray) = a.indices.count { index ->
                (a[index] ushr 24) > 128 && (b[index] ushr 24) > 128 &&
                    listOf(0, 8, 16).maxOf { shift -> kotlin.math.abs((a[index] ushr shift and 255) - (b[index] ushr shift and 255)) } > 12
            }
            assertTrue("实际脸部微笑没有产生足够清晰的像素变化", visibleChanges(before, after) > 800)
            assertTrue("闭嘴微笑和露齿笑无法区分", visibleChanges(after, pixels(happy)) > 800)
            assertArrayEquals("微笑残留到自然表情", before, pixels(reset))
            assertTrue(pixels(body).any { (it ushr 24) > 0 })
            val spec = requireNotNull(body.appliedSpec)
            assertEquals(ActionReadiness.DRAFT, spec.action.readiness)
            assertEquals("photo-rain-natural-v1", (spec.action.source as PhotoPoseSource.Animation).name)
            assertEquals("DEF-Head", spec.action.faceAnchorNode)
            assertTrue(spec.matches(selected.withActualAvatar(body.appliedAvatar!!)))
            assertTrue(runCatching { renderer.renderFace(selected.withActualAvatar(selected.avatar.copy(expression = ExpressionId.SURPRISED)), 384) }.isFailure)
        } finally {
            listOf(neutral, smile, happy, reset, body).forEach { it.bitmap.recycle() }
        }
    }

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
