package android.template.feature.camera.pytorch.ui

class CompositionGuideEngine {

    fun buildGuide(
        people: List<DetectedObjectFrame>,
        objects: List<DetectedObjectFrame>,
        luminance: Double?
    ): CameraGuideState {
        if (luminance != null && luminance < LOW_LIGHT_THRESHOLD) {
            val guideBounds = CameraGuideState.Initial.guideBounds
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = buildHumanPoseLines(guideBounds),
                message = "画面偏暗，朝光线更好的方向移动手机",
                sceneQuality = CameraSceneQuality.POOR,
                movementDirection = CameraMovementDirection.NONE
            )
        }

        val guideBounds = chooseGuideBounds(objects)
        val poseLines = buildHumanPoseLines(guideBounds)
        val primaryPerson = people.maxByOrNull { it.bounds.area * it.confidence }

        if (objects.any { it.bounds.intersectionArea(guideBounds) / guideBounds.area > OCCLUSION_LIMIT }) {
            val direction = chooseMovementDirection(objects)
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = movementMessage(direction),
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = direction
            )
        }

        if (primaryPerson == null) {
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = "ONNX 实时检测中：请让人物走进虚线区域",
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = CameraMovementDirection.NONE
            )
        }

        if (primaryPerson.bounds.height > guideBounds.height * 1.18f) {
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = "人物太近，请后退一点再拍",
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = CameraMovementDirection.NONE
            )
        }

        if (primaryPerson.bounds.height < guideBounds.height * 0.42f) {
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = "人物太远，请靠近一点再拍",
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = CameraMovementDirection.NONE
            )
        }

        val horizontalOffset = primaryPerson.bounds.centerX - guideBounds.centerX
        if (horizontalOffset < -HORIZONTAL_TOLERANCE) {
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = "人物偏左，向左移动手机",
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = CameraMovementDirection.LEFT
            )
        }
        if (horizontalOffset > HORIZONTAL_TOLERANCE) {
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = "人物偏右，向右移动手机",
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = CameraMovementDirection.RIGHT
            )
        }

        val verticalOffset = primaryPerson.bounds.centerY - guideBounds.centerY
        if (verticalOffset < -VERTICAL_TOLERANCE) {
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = "人物偏上，向上移动手机",
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = CameraMovementDirection.UP
            )
        }
        if (verticalOffset > VERTICAL_TOLERANCE) {
            return CameraGuideState(
                guideBounds = guideBounds,
                poseLines = poseLines,
                message = "人物偏下，向下移动手机",
                sceneQuality = CameraSceneQuality.NEEDS_MOVE,
                movementDirection = CameraMovementDirection.DOWN
            )
        }

        return CameraGuideState(
            guideBounds = guideBounds,
            poseLines = poseLines,
            message = "构图正确，保持相机位置",
            sceneQuality = CameraSceneQuality.GOOD,
            movementDirection = CameraMovementDirection.NONE
        )
    }

    private fun chooseGuideBounds(objects: List<DetectedObjectFrame>): NormalizedRect =
        GUIDE_CANDIDATES
            .maxBy { candidate ->
                val overlapPenalty = objects.sumOf { detectedObject ->
                    candidate.intersectionArea(detectedObject.bounds).toDouble()
                }
                val centerPreference = 1.0 - kotlin.math.abs(candidate.centerX - 0.5f)
                centerPreference - overlapPenalty * OVERLAP_WEIGHT
            }

    private fun chooseMovementDirection(objects: List<DetectedObjectFrame>): CameraMovementDirection {
        val leftArea = objects.sumOf { it.bounds.intersectionArea(LEFT_HALF).toDouble() }
        val rightArea = objects.sumOf { it.bounds.intersectionArea(RIGHT_HALF).toDouble() }
        val topArea = objects.sumOf { it.bounds.intersectionArea(TOP_HALF).toDouble() }
        val bottomArea = objects.sumOf { it.bounds.intersectionArea(BOTTOM_HALF).toDouble() }

        return when {
            leftArea > rightArea * AREA_BIAS -> CameraMovementDirection.RIGHT
            rightArea > leftArea * AREA_BIAS -> CameraMovementDirection.LEFT
            topArea > bottomArea * AREA_BIAS -> CameraMovementDirection.DOWN
            bottomArea > topArea * AREA_BIAS -> CameraMovementDirection.UP
            else -> CameraMovementDirection.RIGHT
        }
    }

    private fun movementMessage(direction: CameraMovementDirection): String =
        when (direction) {
            CameraMovementDirection.LEFT -> "画面右侧有些拥挤，向左移动手机"
            CameraMovementDirection.RIGHT -> "画面左侧有些拥挤，向右移动手机"
            CameraMovementDirection.UP -> "下方空间不足，向上抬一点手机"
            CameraMovementDirection.DOWN -> "上方空间不足，向下压一点手机"
            CameraMovementDirection.NONE -> "当前场景有些杂乱，换个角度试试"
        }

    private fun buildHumanPoseLines(bounds: NormalizedRect): List<NormalizedLine> {
        val headCenter = NormalizedPoint(bounds.centerX, bounds.top + bounds.height * 0.12f)
        val neck = NormalizedPoint(bounds.centerX, bounds.top + bounds.height * 0.25f)
        val hip = NormalizedPoint(bounds.centerX, bounds.top + bounds.height * 0.56f)
        val leftShoulder = NormalizedPoint(bounds.left + bounds.width * 0.26f, bounds.top + bounds.height * 0.30f)
        val rightShoulder = NormalizedPoint(bounds.right - bounds.width * 0.26f, bounds.top + bounds.height * 0.30f)
        val leftHand = NormalizedPoint(bounds.left + bounds.width * 0.12f, bounds.top + bounds.height * 0.48f)
        val rightHand = NormalizedPoint(bounds.right - bounds.width * 0.12f, bounds.top + bounds.height * 0.42f)
        val leftFoot = NormalizedPoint(bounds.left + bounds.width * 0.28f, bounds.bottom)
        val rightFoot = NormalizedPoint(bounds.right - bounds.width * 0.24f, bounds.bottom)

        return listOf(
            NormalizedLine(headCenter, neck),
            NormalizedLine(neck, hip),
            NormalizedLine(leftShoulder, rightShoulder),
            NormalizedLine(leftShoulder, leftHand),
            NormalizedLine(rightShoulder, rightHand),
            NormalizedLine(hip, leftFoot),
            NormalizedLine(hip, rightFoot)
        )
    }

    private companion object {
        const val LOW_LIGHT_THRESHOLD = 45.0
        const val OCCLUSION_LIMIT = 0.28f
        const val OVERLAP_WEIGHT = 3.5
        const val AREA_BIAS = 1.25
        const val HORIZONTAL_TOLERANCE = 0.07f
        const val VERTICAL_TOLERANCE = 0.08f

        val GUIDE_CANDIDATES = listOf(
            NormalizedRect(0.14f, 0.18f, 0.50f, 0.90f),
            NormalizedRect(0.32f, 0.18f, 0.68f, 0.90f),
            NormalizedRect(0.50f, 0.18f, 0.86f, 0.90f)
        )
        val LEFT_HALF = NormalizedRect(0f, 0f, 0.5f, 1f)
        val RIGHT_HALF = NormalizedRect(0.5f, 0f, 1f, 1f)
        val TOP_HALF = NormalizedRect(0f, 0f, 1f, 0.5f)
        val BOTTOM_HALF = NormalizedRect(0f, 0.5f, 1f, 1f)
    }
}
