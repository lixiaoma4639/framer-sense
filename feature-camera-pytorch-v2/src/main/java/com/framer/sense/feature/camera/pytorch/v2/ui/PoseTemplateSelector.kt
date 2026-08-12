// 声明当前文件属于 Camera PyTorch V2 拍照功能的 UI 包。
package com.framer.sense.feature.camera.pytorch.v2.ui

// 姿态模板选择器：根据真实人体关键点和场景类型选择虚拟人的基础姿态。
class PoseTemplateSelector {

    // 选择当前帧应该使用的虚拟人姿态模板。
    fun select(pose: WholeBodyPoseEstimate, scene: SemanticScene): PoseTemplate {
        // 读取左肩关键点；为空表示模型没有识别到或置信度不足。
        val leftShoulder = pose.point(WholeBodyKeypointIndex.LEFT_SHOULDER)
        // 读取右肩关键点。
        val rightShoulder = pose.point(WholeBodyKeypointIndex.RIGHT_SHOULDER)
        // 读取左脚踝关键点。
        val leftAnkle = pose.point(WholeBodyKeypointIndex.LEFT_ANKLE)
        // 读取右脚踝关键点。
        val rightAnkle = pose.point(WholeBodyKeypointIndex.RIGHT_ANKLE)

        // 只有肩膀和脚踝四个点都存在时，才用姿态几何关系判断站姿或走姿。
        if (leftShoulder != null && rightShoulder != null && leftAnkle != null && rightAnkle != null) {
            // 肩宽使用左右肩 x 坐标差的绝对值，归一化坐标下越大表示横向展开越明显。
            val shoulderWidth = kotlin.math.abs(rightShoulder.x - leftShoulder.x)
            // 脚踝宽度使用左右脚踝 x 坐标差的绝对值，用来判断步幅是否打开。
            val ankleWidth = kotlin.math.abs(rightAnkle.x - leftAnkle.x)
            // 如果脚踝宽度明显大于肩宽，说明双脚打开更像行走或迈步姿态。
            if (ankleWidth > shoulderWidth * 1.15f) return PoseTemplate.WALKING
            // 如果肩宽很窄且整体姿态置信度足够，可能是侧身站立。
            if (shoulderWidth < 0.10f && pose.confidence > 0.35f) return PoseTemplate.SIDE_STANCE
        }

        // 姿态关键点不足或几何判断不明显时，根据语义场景选择默认模板。
        return when (scene.group) {
            // 城市和普通户外更适合动态走姿模板。
            SceneGroup.URBAN, SceneGroup.OUTDOOR -> PoseTemplate.WALKING
            // 室内场景更适合侧身站姿，减少人物和空间的冲突。
            SceneGroup.INDOOR -> PoseTemplate.SIDE_STANCE
            // 自然和未知场景使用放松站立模板作为稳定兜底。
            SceneGroup.NATURE, SceneGroup.UNKNOWN -> PoseTemplate.RELAXED_STAND
        }
    }
}
