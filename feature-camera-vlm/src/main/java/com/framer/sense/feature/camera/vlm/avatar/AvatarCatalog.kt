package com.framer.sense.feature.camera.vlm.avatar

import com.framer.sense.feature.camera.vlm.model.AvatarId
import com.framer.sense.feature.camera.vlm.model.ArmPose
import com.framer.sense.feature.camera.vlm.model.AvatarPoseDirective
import com.framer.sense.feature.camera.vlm.model.BodyPose
import com.framer.sense.feature.camera.vlm.model.ExpressionId
import com.framer.sense.feature.camera.vlm.model.HeadPose
import com.framer.sense.feature.camera.vlm.model.PoseId
import com.framer.sense.feature.camera.vlm.model.StancePose

/** 内置 Rocketbox 人像与受限摄影姿势、表情模板。 */
object AvatarCatalog {
    data class Asset(val fileName: String)

    private val assets = mapOf(
        AvatarId.ADULT_FEMALE to Asset("vlm_avatars/adult_female.glb"),
        AvatarId.ADULT_MALE to Asset("vlm_avatars/adult_male.glb"),
        AvatarId.CHILD_GIRL to Asset("vlm_avatars/girl.glb"),
        AvatarId.CHILD_BOY to Asset("vlm_avatars/boy.glb")
    )

    fun asset(id: AvatarId): Asset = assets.getValue(id)

    /** 每项为骨骼名和局部旋转角度；只在原始绑定姿势附近小幅修正，避免关节失真。 */
    fun poseRotations(pose: PoseId): Map<String, FloatArray> = when (pose) {
        PoseId.RELAXED -> mapOf("Bip01 Spine1" to angles(0f, 0f, -3f))
        PoseId.SIDE -> mapOf("Bip01 Spine" to angles(0f, 28f, 0f), "Bip01 Head" to angles(0f, -12f, 0f))
        PoseId.LOOK_BACK -> mapOf("Bip01 Spine" to angles(0f, 42f, 0f), "Bip01 Head" to angles(0f, 32f, 0f))
        PoseId.HANDS_FRONT -> mapOf(
            "Bip01 L UpperArm" to angles(-18f, 0f, -34f), "Bip01 R UpperArm" to angles(-18f, 0f, 34f),
            "Bip01 L Forearm" to angles(-45f, 0f, -8f), "Bip01 R Forearm" to angles(-45f, 0f, 8f)
        )
        PoseId.HAND_HIP -> mapOf("Bip01 R UpperArm" to angles(8f, 0f, 42f), "Bip01 R Forearm" to angles(-42f, 0f, 50f))
        PoseId.HANDS_HIPS -> mapOf(
            "Bip01 L UpperArm" to angles(8f, 0f, -42f), "Bip01 R UpperArm" to angles(8f, 0f, 42f),
            "Bip01 L Forearm" to angles(-42f, 0f, -50f), "Bip01 R Forearm" to angles(-42f, 0f, 50f)
        )
        PoseId.WAVE -> mapOf("Bip01 R UpperArm" to angles(-12f, 0f, 76f), "Bip01 R Forearm" to angles(-12f, 0f, 68f))
        PoseId.POINT -> mapOf("Bip01 R UpperArm" to angles(-8f, 0f, 58f), "Bip01 R Forearm" to angles(5f, 0f, 26f))
        PoseId.HAT -> mapOf("Bip01 R UpperArm" to angles(-15f, 0f, 72f), "Bip01 R Forearm" to angles(-42f, 0f, 68f))
        PoseId.LOOK_UP -> mapOf("Bip01 Head" to angles(-16f, 0f, 0f))
        PoseId.LOOK_DOWN -> mapOf("Bip01 Head" to angles(13f, 0f, 0f), "Bip01 Spine1" to angles(4f, 0f, 0f))
        PoseId.ARMS_OPEN -> mapOf("Bip01 L UpperArm" to angles(0f, 0f, -58f), "Bip01 R UpperArm" to angles(0f, 0f, 58f))
    }

    /** 所有受控动作涉及的骨骼；缺少某名称的资产会自动跳过对应局部动作。 */
    val controlledBoneNames: Set<String> by lazy {
        PoseId.entries.flatMap { poseRotations(it).keys }.toSet() + setOf(
            "Bip01 Pelvis", "Bip01 L Thigh", "Bip01 R Thigh"
        )
    }

    /**
     * 将 VLM 的细分摄影动作收敛为有限的骨骼局部旋转。所有角度均刻意小于常规动画
     * 幅度，目的只是给用户一个舒适、可模仿的构图参考，而不是生成高风险动作。
     */
    fun directiveRotations(directive: AvatarPoseDirective): Map<String, FloatArray> {
        val result = linkedMapOf<String, FloatArray>()
        fun add(values: Map<String, FloatArray>) = values.forEach { (bone, rotation) ->
            val current = result[bone] ?: floatArrayOf(0f, 0f, 0f)
            result[bone] = floatArrayOf(current[0] + rotation[0], current[1] + rotation[1], current[2] + rotation[2])
        }
        add(when (directive.body) {
            BodyPose.FRONT -> emptyMap()
            BodyPose.TURN_LEFT -> mapOf("Bip01 Spine" to angles(0f, -22f, 0f))
            BodyPose.TURN_RIGHT -> mapOf("Bip01 Spine" to angles(0f, 22f, 0f))
            BodyPose.LEAN_FORWARD -> mapOf("Bip01 Spine1" to angles(6f, 0f, 0f))
        })
        add(when (directive.arms) {
            ArmPose.RELAXED -> emptyMap()
            ArmPose.HAND_HIP -> poseRotations(PoseId.HAND_HIP)
            ArmPose.HANDS_HIPS -> poseRotations(PoseId.HANDS_HIPS)
            ArmPose.HANDS_FRONT -> poseRotations(PoseId.HANDS_FRONT)
            ArmPose.WAVE -> poseRotations(PoseId.WAVE)
            ArmPose.POINT -> poseRotations(PoseId.POINT)
            ArmPose.ARMS_OPEN -> poseRotations(PoseId.ARMS_OPEN)
        })
        add(when (directive.head) {
            HeadPose.FRONT -> emptyMap()
            HeadPose.TURN_LEFT -> mapOf("Bip01 Head" to angles(0f, -16f, 0f))
            HeadPose.TURN_RIGHT -> mapOf("Bip01 Head" to angles(0f, 16f, 0f))
            HeadPose.LOOK_UP -> mapOf("Bip01 Head" to angles(-14f, 0f, 0f))
            HeadPose.LOOK_DOWN -> mapOf("Bip01 Head" to angles(11f, 0f, 0f))
            HeadPose.LOOK_BACK -> mapOf("Bip01 Head" to angles(0f, 30f, 0f))
        })
        add(when (directive.stance) {
            StancePose.NEUTRAL -> emptyMap()
            StancePose.WEIGHT_LEFT -> mapOf(
                "Bip01 Pelvis" to angles(0f, 0f, -2f),
                "Bip01 L Thigh" to angles(0f, 0f, -3f),
                "Bip01 R Thigh" to angles(0f, 0f, 2f)
            )
            StancePose.WEIGHT_RIGHT -> mapOf(
                "Bip01 Pelvis" to angles(0f, 0f, 2f),
                "Bip01 L Thigh" to angles(0f, 0f, -2f),
                "Bip01 R Thigh" to angles(0f, 0f, 3f)
            )
            StancePose.STEP_FORWARD -> mapOf(
                "Bip01 L Thigh" to angles(-4f, 0f, 0f),
                "Bip01 R Thigh" to angles(3f, 0f, 0f)
            )
        })
        return result
    }

    /** Rocketbox 导出的 ARKit 形变名称与权重。 */
    fun expressionWeights(expression: ExpressionId, intensity: Float): Map<String, Float> {
        val amount = intensity.coerceIn(0f, 1f)
        fun both(name: String, weight: Float) = mapOf("blendShape1.AK_${name}Left" to weight, "blendShape1.AK_${name}Right" to weight)
        return when (expression) {
            ExpressionId.NEUTRAL -> emptyMap()
            ExpressionId.SMILE -> both("44_MouthSmile", .42f * amount) + both("07_CheekSquint", .12f * amount)
            ExpressionId.HAPPY -> both("44_MouthSmile", .78f * amount) + both("07_CheekSquint", .28f * amount) + both("19_EyeSquint", .12f * amount)
            ExpressionId.SURPRISED -> both("21_EyeWide", .48f * amount) + mapOf("blendShape1.AK_25_JawOpen" to .30f * amount)
            ExpressionId.THOUGHTFUL -> mapOf("blendShape1.AK_03_BrowInnerUp" to .18f * amount, "blendShape1.AK_38_MouthPucker" to .10f * amount)
            ExpressionId.CONFIDENT -> both("44_MouthSmile", .30f * amount) + mapOf("blendShape1.AK_03_BrowInnerUp" to .08f * amount)
        }
    }

    private fun angles(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
}
