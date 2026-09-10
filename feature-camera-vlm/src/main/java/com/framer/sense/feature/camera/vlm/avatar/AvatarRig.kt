package com.framer.sense.feature.camera.vlm.avatar

import android.opengl.Matrix
import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.*

/** 人偶节点；geometryScale 为空时节点只作为关节，不绘制网格。 */
data class RigNode(
    val name: String, val parent: String?, val position: FloatArray,
    val rotation: FloatArray = floatArrayOf(0f, 0f, 0f),
    val geometryScale: FloatArray? = null,
    val tint: FloatArray = floatArrayOf(0.16f, 0.48f, 0.53f, 1f),
    val capsule: Boolean = false
)

/** 人偶程序化配置，使用同一套父子关节和球体网格。 */
object AvatarRig {
    const val HEIGHT = 1.94f

    /** 创建指定姿态、表情的人偶节点。
     * @param avatar 姿态编号、表情和强度；整体位置由渲染器设置。
     * @return 父节点在子节点之前的有序列表。
     */
    fun nodes(avatar: AvatarPlacement): List<RigNode> = buildList {
        val skin = floatArrayOf(.85f, .57f, .38f, 1f)
        val dark = floatArrayOf(.045f, .065f, .075f, 1f)
        val pants = floatArrayOf(.10f, .17f, .23f, 1f)
        add(RigNode("root", null, floatArrayOf(0f, 0f, 0f)))
        add(RigNode("torso", "root", floatArrayOf(0f, 1.14f, 0f), geometryScale = floatArrayOf(.26f, .38f, .16f)))
        add(RigNode("hip", "root", floatArrayOf(0f, .85f, 0f), geometryScale = floatArrayOf(.23f, .18f, .15f), tint = pants))
        var headX = 0f
        var headY = 0f
        if (avatar.pose == PoseId.LOOK_UP) headX = -22f
        if (avatar.pose == PoseId.LOOK_DOWN) headX = 20f
        if (avatar.pose == PoseId.LOOK_BACK) headY = -80f
        add(RigNode("headJoint", "root", floatArrayOf(0f, 1.62f, 0f), floatArrayOf(headX, headY, 0f)))
        add(RigNode("head", "headJoint", floatArrayOf(0f, 0f, 0f), geometryScale = floatArrayOf(.24f, .27f, .22f), tint = skin))
        add(RigNode("hair", "headJoint", floatArrayOf(0f, .20f, -.025f), geometryScale = floatArrayOf(.245f, .12f, .22f), tint = dark))
        for (side in listOf(-1f, 1f)) {
            val key = if (side < 0) "left" else "right"
            val shoulder = shoulderAngles(avatar.pose, side)
            val elbow = elbowAngles(avatar.pose, side)
            add(RigNode("${key}Shoulder", "root", floatArrayOf(side * .28f, 1.36f, 0f), shoulder))
            add(RigNode("${key}UpperArm", "${key}Shoulder", floatArrayOf(0f, -.16f, 0f), geometryScale = floatArrayOf(.075f, .205f, .075f), capsule = true))
            add(RigNode("${key}Elbow", "${key}Shoulder", floatArrayOf(0f, -.32f, 0f), elbow))
            add(RigNode("${key}LowerArm", "${key}Elbow", floatArrayOf(0f, -.14f, 0f), geometryScale = floatArrayOf(.062f, .18f, .062f), tint = skin, capsule = true))
            add(RigNode("${key}Hand", "${key}Elbow", floatArrayOf(0f, -.30f, 0f), geometryScale = floatArrayOf(.065f, .085f, .045f), tint = skin))
            add(RigNode("${key}HipJoint", "root", floatArrayOf(side * .13f, .83f, 0f), floatArrayOf(0f, 0f, side * 3f)))
            add(RigNode("${key}Thigh", "${key}HipJoint", floatArrayOf(0f, -.18f, 0f), geometryScale = floatArrayOf(.095f, .23f, .095f), tint = pants, capsule = true))
            add(RigNode("${key}Knee", "${key}HipJoint", floatArrayOf(0f, -.37f, 0f)))
            add(RigNode("${key}Calf", "${key}Knee", floatArrayOf(0f, -.17f, 0f), geometryScale = floatArrayOf(.075f, .22f, .075f), tint = pants, capsule = true))
            add(RigNode("${key}Foot", "${key}Knee", floatArrayOf(0f, -.37f, .055f), geometryScale = floatArrayOf(.09f, .08f, .14f), tint = dark))
            val eyeHeight = if (avatar.expression == ExpressionId.HAPPY) .012f else .027f
            add(RigNode("${key}Eye", "headJoint", floatArrayOf(side * .082f, .025f, .208f), geometryScale = floatArrayOf(.020f, eyeHeight, .014f), tint = dark))
            add(RigNode("${key}Brow", "headJoint", floatArrayOf(side * .085f, .086f, .198f), floatArrayOf(0f, 0f, side * -8f), floatArrayOf(.039f, .008f, .012f), dark))
        }
        for (index in 0..10) {
            val t = index / 10f
            val x: Float
            val y: Float
            if (avatar.expression == ExpressionId.SURPRISED) {
                x = cos(t * 2f * PI.toFloat()) * .030f
                y = -.075f + sin(t * 2f * PI.toFloat()) * .038f
            } else {
                x = (t - .5f) * .11f
                val smile = if (avatar.expression == ExpressionId.NEUTRAL) 0f else avatar.expressionIntensity * .055f
                y = -.065f - smile * sin(t * PI.toFloat())
            }
            add(RigNode("mouth$index", "headJoint", floatArrayOf(x, y, .210f), geometryScale = floatArrayOf(.009f, .009f, .010f), tint = dark))
        }
    }

    /** 返回肩关节欧拉角。
     * @param pose 姿态模板编号。
     * @param side 左侧为 -1，右侧为 1。
     */
    private fun shoulderAngles(pose: PoseId, side: Float): FloatArray = when {
        pose == PoseId.ARMS_OPEN -> floatArrayOf(0f, 0f, side * 85f)
        pose == PoseId.HANDS_FRONT -> floatArrayOf(-28f, 0f, -side * 12f)
        pose == PoseId.HANDS_HIPS || (pose == PoseId.HAND_HIP && side > 0) -> floatArrayOf(0f, 0f, side * 38f)
        pose == PoseId.WAVE && side > 0 -> floatArrayOf(-15f, 0f, 115f)
        pose == PoseId.POINT && side > 0 -> floatArrayOf(-12f, 0f, 85f)
        pose == PoseId.HAT && side > 0 -> floatArrayOf(-10f, 0f, 135f)
        else -> floatArrayOf(0f, 0f, side * 8f)
    }

    /** 返回肘关节欧拉角。
     * @param pose 姿态模板编号。
     * @param side 左右方向符号。
     */
    private fun elbowAngles(pose: PoseId, side: Float): FloatArray = when {
        pose == PoseId.HANDS_FRONT -> floatArrayOf(-60f, 0f, -side * 30f)
        pose == PoseId.HANDS_HIPS || (pose == PoseId.HAND_HIP && side > 0) -> floatArrayOf(-20f, 0f, -side * 78f)
        pose == PoseId.WAVE && side > 0 -> floatArrayOf(0f, 0f, 55f)
        pose == PoseId.HAT && side > 0 -> floatArrayOf(-45f, 0f, 75f)
        else -> floatArrayOf(-8f, 0f, 0f)
    }

    /** 生成人偶节点的局部矩阵。
     * @param node 含位置、旋转和可选几何缩放的节点。
     * @return OpenGL 列主序 4x4 矩阵。
     */
    fun localMatrix(node: RigNode): FloatArray = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
        Matrix.translateM(this, 0, node.position[0], node.position[1], node.position[2])
        Matrix.rotateM(this, 0, node.rotation[1], 0f, 1f, 0f)
        Matrix.rotateM(this, 0, node.rotation[0], 1f, 0f, 0f)
        Matrix.rotateM(this, 0, node.rotation[2], 0f, 0f, 1f)
        node.geometryScale?.let { Matrix.scaleM(this, 0, it[0], it[1], it[2]) }
    }
}
