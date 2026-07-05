package com.framer.sense.feature.camera.pytorch.v2.ui

class PoseTemplateSelector {

    fun select(pose: PoseEstimate, scene: SemanticScene): PoseTemplate {
        val leftShoulder = pose.point(PoseKeypointName.LEFT_SHOULDER)
        val rightShoulder = pose.point(PoseKeypointName.RIGHT_SHOULDER)
        val leftAnkle = pose.point(PoseKeypointName.LEFT_ANKLE)
        val rightAnkle = pose.point(PoseKeypointName.RIGHT_ANKLE)

        if (leftShoulder != null && rightShoulder != null && leftAnkle != null && rightAnkle != null) {
            val shoulderWidth = kotlin.math.abs(rightShoulder.x - leftShoulder.x)
            val ankleWidth = kotlin.math.abs(rightAnkle.x - leftAnkle.x)
            if (ankleWidth > shoulderWidth * 1.15f) return PoseTemplate.WALKING
            if (shoulderWidth < 0.10f && pose.confidence > 0.35f) return PoseTemplate.SIDE_STANCE
        }

        return when (scene.group) {
            SceneGroup.URBAN, SceneGroup.OUTDOOR -> PoseTemplate.WALKING
            SceneGroup.INDOOR -> PoseTemplate.SIDE_STANCE
            SceneGroup.NATURE, SceneGroup.UNKNOWN -> PoseTemplate.RELAXED_STAND
        }
    }
}
