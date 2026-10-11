package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class OfflineSceneDescriptionTest {
    private val description = "背景有树木和远处的建筑，左侧留白较多，明暗层次清楚。"
    private fun input() = DirectorInput(SceneSnapshot("frame", 640, 480, 1f, "frame.jpg", "input.jpg"),
        CameraCapabilities(), "")
    private fun plans() = (1..3).map { CompositionPlan("p$it", "方案$it", ShotType.FULL,
        CropRect(), 1f, AvatarPlacement(), "指导", "理由") }

    @Test fun realTextIsBoundToTheSelectedImageAndThinkTextIsExcluded() {
        assertEquals(SceneObservation("frame", description), OfflineSceneDescription.parse(description, "frame"))
        assertEquals(description, OfflineSceneDescription.parse("<think>内部推理</think>\n$description", "frame")?.text)
        listOf("", "1", "SELECT=1", "SELECT(SELECTING)", "{\"choice\":1}", "<think>未结束", "抱歉，我无法描述这张图片").forEach {
            assertNull(it, OfflineSceneDescription.parse(it, "frame"))
        }
    }

    @Test fun detailedParagraphsKeepObjectsLightingColorAndPhotographyEvidence() {
        val detailed = listOf(
            "前景有石板路，中景为树木和长椅，远处可见建筑，背景主体位于画面右侧。",
            "道路形成引导线，左侧留白较多，树木与建筑分出前后层次，边缘杂物需要避免入镜。",
            "地面可见柔和阴影，建筑一侧较亮，局部反光可能分散注意；人物尚未入镜，脸部光照无法判断。",
            "绿色树木与灰色建筑形成色彩对比，整体偏中性，远处轮廓清楚；不能由照片确定空气质量。",
            "人像可参考左侧留白安排，保留右侧树木和建筑，并避免头部与树干重合。"
        ).joinToString("\n")
        assertEquals(detailed, OfflineSceneDescription.parse(detailed, "frame")?.text)
    }

    @Test fun modelWordingAndOrderArePreservedWithoutBlankRows() {
        val raw = "\n这是一条林间小路。\n\n远处可见建筑。\n\n主体：树木与道路。\n\n光线：明暗对比较柔和。\n\n色彩：偏暖，背景色差便于轮廓分离。\n"
        val expected = "这是一条林间小路。\n远处可见建筑。\n主体：树木与道路。\n光线：明暗对比较柔和。\n色彩：偏暖，背景色差便于轮廓分离。"
        assertEquals(expected, SceneDescriptionFormatting.format(raw))
        assertEquals(expected, OfflineSceneDescription.parse(raw, "frame")?.text)
        assertEquals(expected, SceneDescriptionFormatting.format(expected))
        assertEquals("场景原文\n可见树木", SceneDescriptionFormatting.format("场景原文\n\n可见树木"))
        assertEquals("场景概述\n1. 主体：树木\n2. 光线：柔和", SceneDescriptionFormatting.format("场景概述\n1. 主体：树木\n\n2. 光线：柔和"))
    }

    @Test fun onlyBlankRowsAreRemovedWhilePunctuationAndNormalLineBreaksRemain() {
        val raw = "\n  主体：树木、道路（右侧）。  \n \t\n- 光线：柔和；注意高亮！\n\n色彩：偏暖，饱和度适中；适合人像。\n"
        val expected = "  主体：树木、道路（右侧）。  \n- 光线：柔和；注意高亮！\n色彩：偏暖，饱和度适中；适合人像。"
        assertEquals(expected, SceneDescriptionFormatting.format(raw))
        assertEquals(expected, OfflineSceneDescription.parse(raw, "frame")?.text)
    }

    @Test fun validModelTextIsNotRejectedOrTruncatedByCharacterCount() {
        val short = "光线柔和。"
        val long = "光线柔和，背景有层次；留白有利于人像构图。".repeat(100) + "\n保留这一正常换行，以及末尾的摄影分析。"
        assertEquals(short, OfflineSceneDescription.parse(short, "frame")?.text)
        assertTrue(long.length > 1200)
        assertEquals(long, OfflineSceneDescription.parse(long, "frame")?.text)
    }

    @Test fun allPlansShareTheirBackgroundButRevisionDoesNotChangeOtherPlans() {
        val plans = plans()
        val observation = SceneObservation("frame", description)
        assertTrue(OfflineSceneDescription.attach(plans, input(), observation).all { it.sceneDescription == description })
        val edited = OfflineSceneDescription.attach(plans, input().copy(existing = plans, selectedId = "p2"), observation)
        assertEquals(plans[0], edited[0]); assertEquals(plans[2], edited[2])
        assertEquals(description, edited[1].sceneDescription)
        assertEquals(plans, OfflineSceneDescription.attach(plans, input(), SceneObservation("other", description)))
        assertEquals(plans, OfflineSceneDescription.attach(plans, input(), null))
    }

    @Test fun sceneTextDoesNotChangeGatewaySerialization() {
        val plan = plans().first()
        assertEquals(VlmJson.encodeToString(plan), VlmJson.encodeToString(plan.copy(sceneDescription = description)))
        val reply = StepReply("local", "1")
        assertEquals(VlmJson.encodeToString(reply), VlmJson.encodeToString(reply.copy(sceneObservation = SceneObservation("frame", description))))
    }

    @Test fun cacheCannotCrossFilesImagesOrModelsAndCanBeCleared() {
        val cache = OfflineSceneDescriptionCache()
        val key = OfflineSceneDescriptionCache.Key("frame", "frame.jpg", 100, 42, "model1")
        val observation = SceneObservation("frame", description)
        cache.put(key, observation)
        assertEquals(observation, cache.get(key))
        listOf(key.copy(imageId = "other"), key.copy(path = "other.jpg"), key.copy(bytes = 101),
            key.copy(modified = 43), key.copy(model = "model2"), key.copy(version = key.version + 1)).forEach { assertNull(cache.get(it)) }
        cache.clear(); assertNull(cache.get(key))
    }
}
