package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.avatar.AvatarCatalog
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 从实际打包的 GLB 元数据验证能力，防止 FBX 名称误用于运行时资产。 */
class AvatarCatalogTest {
    private fun metadata(id: AvatarId): JsonObject {
        val file = File("src/main/assets", AvatarCatalog.asset(id).fileName)
        return RandomAccessFile(file, "r").use { source ->
            val header = ByteArray(20).also(source::readFully)
            val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x46546C67, bytes.int)
            assertEquals(2, bytes.int)
            assertEquals(source.length(), bytes.int.toLong())
            val length = bytes.int
            assertEquals(0x4E4F534A, bytes.int)
            val json = ByteArray(length).also(source::readFully).toString(Charsets.UTF_8).trim()
            VlmJson.parseToJsonElement(json).jsonObject
        }
    }

    @Test fun allPackagedAvatarsSupportEveryControlledPoseAndExpression() {
        AvatarId.entries.forEach { id ->
            val root = metadata(id)
            val nodes = root.getValue("nodes").jsonArray
            val bones = root.getValue("skins").jsonArray.flatMap { skin ->
                skin.jsonObject.getValue("joints").jsonArray.map { index ->
                    nodes[index.jsonPrimitive.int].jsonObject.getValue("name").jsonPrimitive.content
                }
            }.toSet()
            val morphs = root.getValue("meshes").jsonArray.flatMap { mesh ->
                mesh.jsonObject["extras"]?.jsonObject?.get("targetNames")?.jsonArray
                    ?.map { it.jsonPrimitive.content }.orEmpty()
            }.toSet()
            assertTrue("$id 没有蒙皮骨骼", bones.isNotEmpty())
            assertTrue("$id 没有表情通道", morphs.isNotEmpty())
            PoseId.entries.forEach { pose -> ExpressionId.entries.forEach { expression ->
                val missing = AvatarCatalog.missingCapabilities(AvatarPlacement(pose = pose, expression = expression), bones, morphs)
                assertTrue("$id/$pose/$expression 缺少 $missing", missing.isEmpty())
            } }
            BodyPose.entries.forEach { body -> ArmPose.entries.forEach { arms ->
                HeadPose.entries.forEach { head -> StancePose.entries.forEach { stance ->
                    val avatar = AvatarPlacement(poseDirective = AvatarPoseDirective(body, arms, head, stance))
                    assertTrue("$id/${avatar.poseDirective}", AvatarCatalog.missingCapabilities(avatar, bones, morphs).isEmpty())
                } }
            } }
        }
    }

    @Test fun smileWritesBothRealChannelsAndZeroIntensityRequiresNoMorphs() {
        val weights = AvatarCatalog.expressionWeights(ExpressionId.SMILE, 1f)
        assertEquals(.42f, weights.getValue("AK_44_MouthSmileLeft"), 0f)
        assertEquals(weights["AK_44_MouthSmileLeft"], weights["AK_45_MouthSmileRight"])
        val noBonesNeeded = AvatarPlacement(poseDirective = AvatarPoseDirective(), expression = ExpressionId.SMILE, expressionIntensity = 0f)
        assertTrue(AvatarCatalog.missingCapabilities(noBonesNeeded, emptySet(), emptySet()).isEmpty())
        assertTrue(AvatarCatalog.missingCapabilities(noBonesNeeded.copy(expressionIntensity = 1f), emptySet(), emptySet()).contains("AK_45_MouthSmileRight"))
        assertTrue(AvatarCatalog.missingCapabilities(noBonesNeeded.copy(poseDirective = AvatarPoseDirective(arms = ArmPose.WAVE)), emptySet(), emptySet()).contains("Bip01 R Forearm"))
    }
}
