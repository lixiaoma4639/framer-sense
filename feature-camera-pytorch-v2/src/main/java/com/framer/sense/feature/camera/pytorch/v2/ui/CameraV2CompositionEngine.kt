package com.framer.sense.feature.camera.pytorch.v2.ui

class CameraV2CompositionEngine(
    private val projector: VirtualHumanProjector = VirtualHumanProjector(),
    private val poseTemplateSelector: PoseTemplateSelector = PoseTemplateSelector()
) {

    fun buildGuide(
        analysis: CameraV2Analysis,
        profile: BodyProfile
    ): CameraV2Guide {
        val targetBounds = chooseTargetBounds(analysis)
        val template = poseTemplateSelector.select(analysis.pose, analysis.semanticScene)
        val primaryPerson = analysis.people.maxByOrNull { it.bounds.area * it.confidence }
        val primarySegment = analysis.personSegments.maxByOrNull { it.bounds.area * it.confidence }
        val humanOverlayBounds = primarySegment?.bounds
            ?: primaryPerson?.bounds?.expandedWithPose(analysis.pose)
            ?: targetBounds
        val figure = projector.project(
            targetBounds = humanOverlayBounds,
            profile = profile,
            template = template,
            pose = analysis.pose,
            contourPathPoints = primarySegment?.contour.orEmpty(),
            matchTargetBounds = primarySegment != null || primaryPerson != null
        )

        if (!analysis.modelAvailability.allRequiredReady) {
            return guide(
                targetBounds = targetBounds,
                quality = CameraV2Quality.NEEDS_MOVE,
                movement = CameraV2Movement.NONE,
                hint = CameraV2Hint.MODEL_ASSETS_MISSING,
                analysis = analysis,
                figure = figure
            )
        }

        if (analysis.luminance < LOW_LIGHT_THRESHOLD) {
            return guide(
                targetBounds = targetBounds,
                quality = CameraV2Quality.POOR,
                movement = CameraV2Movement.NONE,
                hint = CameraV2Hint.LOW_LIGHT,
                analysis = analysis,
                figure = figure
            )
        }

        val blockedRatio = analysis.objects.sumOf {
            it.bounds.intersectionArea(targetBounds).toDouble()
        }.toFloat() / targetBounds.area.coerceAtLeast(0.001f)
        if (blockedRatio > BLOCKED_RATIO_LIMIT) {
            val movement = movementAwayFromObjects(analysis.objects)
            return guide(
                targetBounds = targetBounds,
                quality = CameraV2Quality.NEEDS_MOVE,
                movement = movement,
                hint = movement.toBusyHint(),
                analysis = analysis,
                figure = figure
            )
        }

        primaryPerson ?: return guide(
                targetBounds = targetBounds,
                quality = CameraV2Quality.NEEDS_MOVE,
                movement = CameraV2Movement.NONE,
                hint = CameraV2Hint.ENTER_GUIDE,
                analysis = analysis,
                figure = figure
            )

        val sizeRatio = primaryPerson.bounds.height / targetBounds.height.coerceAtLeast(0.001f)
        if (sizeRatio > 1.18f) {
            return guide(
                targetBounds = targetBounds,
                quality = CameraV2Quality.NEEDS_MOVE,
                movement = CameraV2Movement.BACKWARD,
                hint = CameraV2Hint.STEP_BACK,
                analysis = analysis,
                figure = figure
            )
        }
        if (sizeRatio < 0.45f) {
            return guide(
                targetBounds = targetBounds,
                quality = CameraV2Quality.NEEDS_MOVE,
                movement = CameraV2Movement.FORWARD,
                hint = CameraV2Hint.STEP_FORWARD,
                analysis = analysis,
                figure = figure
            )
        }

        val horizontalOffset = primaryPerson.bounds.centerX - targetBounds.centerX
        if (horizontalOffset < -CENTER_TOLERANCE_X) {
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.LEFT, CameraV2Hint.MOVE_LEFT, analysis, figure)
        }
        if (horizontalOffset > CENTER_TOLERANCE_X) {
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.RIGHT, CameraV2Hint.MOVE_RIGHT, analysis, figure)
        }

        val verticalOffset = primaryPerson.bounds.centerY - targetBounds.centerY
        if (verticalOffset < -CENTER_TOLERANCE_Y) {
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.UP, CameraV2Hint.MOVE_UP, analysis, figure)
        }
        if (verticalOffset > CENTER_TOLERANCE_Y) {
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.DOWN, CameraV2Hint.MOVE_DOWN, analysis, figure)
        }

        return guide(
            targetBounds = targetBounds,
            quality = CameraV2Quality.GOOD,
            movement = CameraV2Movement.NONE,
            hint = CameraV2Hint.GOOD,
            analysis = analysis,
            figure = figure
        )
    }

    private fun chooseTargetBounds(analysis: CameraV2Analysis): V2Rect =
        candidatesFor(analysis.semanticScene.group).maxBy { candidate ->
            val objectPenalty = analysis.objects.sumOf {
                candidate.intersectionArea(it.bounds).toDouble() * (1.0 + it.confidence)
            }
            val personPenalty = analysis.people.sumOf {
                candidate.centerDistanceTo(it.bounds).toDouble() * PERSON_REPOSITION_WEIGHT
            }
            val thirdsPreference = 1.0 - kotlin.math.abs(candidate.centerX - preferredCenterX(analysis.semanticScene.group))
            val verticalSpace = 1.0 - kotlin.math.abs(candidate.top - 0.16f)
            thirdsPreference + verticalSpace * 0.25 - objectPenalty * OBJECT_OVERLAP_WEIGHT - personPenalty
        }

    private fun candidatesFor(group: SceneGroup): List<V2Rect> {
        val top = if (group == SceneGroup.INDOOR) 0.18f else 0.14f
        val bottom = if (group == SceneGroup.NATURE) 0.93f else 0.90f
        return listOf(
            V2Rect(0.10f, top, 0.46f, bottom),
            V2Rect(0.32f, top, 0.68f, bottom),
            V2Rect(0.54f, top, 0.90f, bottom)
        )
    }

    private fun preferredCenterX(group: SceneGroup): Float =
        when (group) {
            SceneGroup.URBAN -> 0.62f
            SceneGroup.NATURE -> 0.38f
            else -> 0.50f
        }

    private fun movementAwayFromObjects(objects: List<SceneObject>): CameraV2Movement {
        val left = objects.sumOf { it.bounds.intersectionArea(LEFT_ZONE).toDouble() }
        val right = objects.sumOf { it.bounds.intersectionArea(RIGHT_ZONE).toDouble() }
        val top = objects.sumOf { it.bounds.intersectionArea(TOP_ZONE).toDouble() }
        val bottom = objects.sumOf { it.bounds.intersectionArea(BOTTOM_ZONE).toDouble() }
        return when {
            left > right * AREA_BIAS -> CameraV2Movement.RIGHT
            right > left * AREA_BIAS -> CameraV2Movement.LEFT
            top > bottom * AREA_BIAS -> CameraV2Movement.DOWN
            bottom > top * AREA_BIAS -> CameraV2Movement.UP
            else -> CameraV2Movement.RIGHT
        }
    }

    private fun CameraV2Movement.toBusyHint(): CameraV2Hint =
        when (this) {
            CameraV2Movement.LEFT -> CameraV2Hint.MOVE_LEFT
            CameraV2Movement.RIGHT -> CameraV2Hint.MOVE_RIGHT
            CameraV2Movement.UP -> CameraV2Hint.MOVE_UP
            CameraV2Movement.DOWN -> CameraV2Hint.MOVE_DOWN
            else -> CameraV2Hint.SCENE_BUSY
        }

    private fun V2Rect.expandedWithPose(pose: PoseEstimate): V2Rect {
        val posePoints = pose.keypoints
            .filter { it.confidence >= POSE_BOX_KEYPOINT_THRESHOLD }
            .map { it.point }
        if (posePoints.isEmpty()) return expand(PERSON_BOX_PADDING)
        val poseBounds = V2Rect(
            left = minOf(left, posePoints.minOf { it.x }),
            top = minOf(top, posePoints.minOf { it.y }),
            right = maxOf(right, posePoints.maxOf { it.x }),
            bottom = maxOf(bottom, posePoints.maxOf { it.y })
        )
        return poseBounds.expand(PERSON_BOX_PADDING)
    }

    private fun V2Rect.expand(padding: Float): V2Rect =
        V2Rect(left - padding, top - padding, right + padding, bottom + padding).clamped()

    private fun guide(
        targetBounds: V2Rect,
        quality: CameraV2Quality,
        movement: CameraV2Movement,
        hint: CameraV2Hint,
        analysis: CameraV2Analysis,
        figure: VirtualHumanFigure
    ): CameraV2Guide =
        CameraV2Guide(
            targetBounds = targetBounds,
            quality = quality,
            movement = movement,
            hint = hint,
            semanticScene = analysis.semanticScene,
            virtualHuman = figure,
            modelAvailability = analysis.modelAvailability
        )

    private companion object {
        const val LOW_LIGHT_THRESHOLD = 42.0
        const val BLOCKED_RATIO_LIMIT = 0.26f
        const val OBJECT_OVERLAP_WEIGHT = 3.1
        const val PERSON_REPOSITION_WEIGHT = 0.16
        const val CENTER_TOLERANCE_X = 0.065f
        const val CENTER_TOLERANCE_Y = 0.075f
        const val AREA_BIAS = 1.2
        const val PERSON_BOX_PADDING = 0.025f
        const val POSE_BOX_KEYPOINT_THRESHOLD = 0.2f

        val LEFT_ZONE = V2Rect(0f, 0f, 0.5f, 1f)
        val RIGHT_ZONE = V2Rect(0.5f, 0f, 1f, 1f)
        val TOP_ZONE = V2Rect(0f, 0f, 1f, 0.5f)
        val BOTTOM_ZONE = V2Rect(0f, 0.5f, 1f, 1f)
    }
}
