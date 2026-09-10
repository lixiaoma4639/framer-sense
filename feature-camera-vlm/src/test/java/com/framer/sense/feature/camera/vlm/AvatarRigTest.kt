package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.avatar.AvatarRig
import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Assert.*
import org.junit.Test

class AvatarRigTest {
    /** 所有模板的父节点先于子节点且胶囊四肢均存在；无参数，不调用 Android 矩阵或 GPU。 */
    @Test fun allTemplatesHaveValidJointHierarchy() {
        assertEquals(12, PoseId.entries.size)
        for (pose in PoseId.entries) {
            val nodes = AvatarRig.nodes(AvatarPlacement(pose = pose))
            val seen = mutableSetOf<String>()
            for (node in nodes) {
                assertTrue(node.parent == null || node.parent in seen)
                assertTrue(seen.add(node.name))
                assertTrue(node.position.all { it.isFinite() })
            }
            assertEquals(8, nodes.count { it.capsule })
            assertTrue(nodes.any { it.name == "head" })
        }
    }

    /** 四种表情生成不同口型或眼部形状；无参数。 */
    @Test fun expressionsHaveDistinctFaceGeometry() {
        val signatures = ExpressionId.entries.map { expression ->
            AvatarRig.nodes(AvatarPlacement(expression = expression)).filter { it.name.startsWith("mouth") || it.name.endsWith("Eye") }
                .joinToString { it.position.contentToString() + it.geometryScale.contentToString() }
        }
        assertEquals(4, signatures.distinct().size)
    }
}
