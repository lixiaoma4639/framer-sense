package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualHumanProjectorTest {

    private val projector = VirtualHumanProjector()

    @Test
    fun projectDefaultHanfuGuide_containsReferencePoseDetailsInsidePreview() {
        val figure = projector.projectDefaultHanfuGuide(
            targetBounds = V2Rect(0.32f, 0.14f, 0.68f, 0.92f),
            profile = BodyProfile(170, 60)
        )

        assertEquals(VirtualHumanVisualStyle.HANFU_GUIDE, figure.visualStyle)
        assertTrue(figure.lines.isEmpty())
        assertTrue(figure.decorativePaths.any { it.role == VirtualHumanPathRole.HAIR })
        assertTrue(figure.decorativePaths.any { it.role == VirtualHumanPathRole.HANDS })
        assertTrue(figure.decorativePaths.any { it.role == VirtualHumanPathRole.SKIRT_FOLD })
        assertTrue(figure.decorativePaths.flatMap { it.points }.all { point ->
            point.x in 0f..1f && point.y in 0f..1f
        })
    }

    @Test
    fun projectDefaultHanfuGuide_scalesWithBodyProfile() {
        val target = V2Rect(0.32f, 0.14f, 0.68f, 0.92f)
        val slim = projector.projectDefaultHanfuGuide(target, BodyProfile(185, 58))
        val heavy = projector.projectDefaultHanfuGuide(target, BodyProfile(160, 110))

        assertTrue(heavy.bounds.width > slim.bounds.width)
    }

    @Test
    fun projectDefaultHanfuGuide_isSlightlyWiderThanPoseDrivenFigure() {
        val target = V2Rect(0.32f, 0.14f, 0.68f, 0.92f)
        val hanfuGuide = projector.projectDefaultHanfuGuide(target, BodyProfile(170, 60))
        val fallbackTemplate = projector.project(
            targetBounds = target,
            profile = BodyProfile(170, 60),
            template = PoseTemplate.RELAXED_STAND
        )
        val poseDriven = projector.project(
            targetBounds = target,
            profile = BodyProfile(170, 60),
            template = PoseTemplate.RELAXED_STAND,
            wholeBodyPose = completePose()
        )

        assertTrue(hanfuGuide.bounds.width > poseDriven.bounds.width)
        assertTrue(hanfuGuide.bounds.width > fallbackTemplate.bounds.width)
    }

    @Test
    fun initialGuide_usesHanfuVisualStyle() {
        assertEquals(
            VirtualHumanVisualStyle.HANFU_GUIDE,
            CameraV2Guide.initial().virtualHuman.visualStyle
        )
    }

    @Test
    fun project_forDifferentBodyProfiles_keepsHumanInsidePreview() {
        val target = V2Rect(0.32f, 0.14f, 0.68f, 0.92f)
        val slim = projector.project(target, BodyProfile(185, 58), PoseTemplate.RELAXED_STAND)
        val heavy = projector.project(target, BodyProfile(160, 110), PoseTemplate.RELAXED_STAND)

        assertTrue(slim.bounds.top >= 0f)
        assertTrue(slim.bounds.bottom <= 1f)
        assertTrue(heavy.bounds.left >= 0f)
        assertTrue(heavy.bounds.right <= 1f)
        assertTrue(heavy.bounds.width > slim.bounds.width)
    }

    @Test
    fun project_walkingPose_containsProjectedSkeletonLines() {
        val figure = projector.project(
            targetBounds = V2Rect(0.32f, 0.14f, 0.68f, 0.92f),
            profile = BodyProfile(170, 60),
            template = PoseTemplate.WALKING,
            wholeBodyPose = completePose()
        )

        assertTrue(figure.lines.size >= 10)
        assertTrue(figure.lines.all { line ->
            line.start.x in 0f..1f &&
                line.start.y in 0f..1f &&
                line.end.x in 0f..1f &&
                line.end.y in 0f..1f
        })
    }

    @Test
    fun project_withCompletePose_changesSkeletonFromTemplate() {
        val target = V2Rect(0.32f, 0.14f, 0.68f, 0.92f)
        val template = projector.project(
            targetBounds = target,
            profile = BodyProfile(170, 60),
            template = PoseTemplate.RELAXED_STAND
        )
        val poseAware = projector.project(
            targetBounds = target,
            profile = BodyProfile(170, 60),
            template = PoseTemplate.RELAXED_STAND,
            wholeBodyPose = completePose()
        )

        assertTrue(poseAware.lines.isNotEmpty())
        assertTrue(lineSignature(template) != lineSignature(poseAware))
        assertTrue(poseAware.lines.any { it.depth > 0.02f })
        assertTrue(poseAware.lines.any { it.depth < -0.02f })
        assertTrue(poseAware.poseDriven)
        assertTrue(!poseAware.drawHead)
        assertEquals(VirtualHumanVisualStyle.SKELETON, poseAware.visualStyle)
    }

    @Test
    fun project_withInsufficientPose_fallsBackToTemplate() {
        val target = V2Rect(0.32f, 0.14f, 0.68f, 0.92f)
        val template = projector.project(target, BodyProfile(170, 60), PoseTemplate.SIDE_STANCE)
        val fallback = projector.project(
            targetBounds = target,
            profile = BodyProfile(170, 60),
            template = PoseTemplate.SIDE_STANCE,
            wholeBodyPose = WholeBodyPoseEstimate(
                keypoints = listOf(
                    keypoint(PoseKeypointName.LEFT_SHOULDER, 0.44f, 0.30f),
                    keypoint(PoseKeypointName.RIGHT_SHOULDER, 0.56f, 0.30f)
                ),
                confidence = 0.9f
            )
        )

        assertEquals(template, fallback)
        assertEquals(VirtualHumanVisualStyle.HANFU_GUIDE, fallback.visualStyle)
    }

    @Test
    fun project_withHalfBodyPose_drawsUpperBodyWithoutTemplateLegs() {
        val target = V2Rect(0.30f, 0.12f, 0.70f, 0.68f)
        val figure = projector.project(
            targetBounds = target,
            profile = BodyProfile(170, 60),
            template = PoseTemplate.RELAXED_STAND,
            wholeBodyPose = halfBodyPose(),
            matchTargetBounds = true
        )

        assertTrue(figure.poseDriven)
        assertTrue(!figure.drawHead)
        assertTrue(figure.lines.size in 5..8)
        assertTrue(figure.lines.none { line ->
            line.start.y > target.top + target.height * 0.82f ||
                line.end.y > target.top + target.height * 0.82f
        })
    }

    @Test
    fun project_withPose_keepsAllPointsInsidePreview() {
        val figure = projector.project(
            targetBounds = V2Rect(0.10f, 0.14f, 0.46f, 0.90f),
            profile = BodyProfile(190, 90),
            template = PoseTemplate.WALKING,
            wholeBodyPose = completePose()
        )

        assertTrue(figure.headCenter.x in 0f..1f)
        assertTrue(figure.headCenter.y in 0f..1f)
        assertTrue(figure.lines.all { line ->
            line.start.x in 0f..1f &&
                line.start.y in 0f..1f &&
                line.end.x in 0f..1f &&
            line.end.y in 0f..1f
        })
    }

    @Test
    fun project_withWholeBodyPose_addsInnerContourLines() {
        val figure = projector.project(
            targetBounds = V2Rect(0.18f, 0.12f, 0.72f, 0.92f),
            profile = BodyProfile(170, 60),
            template = PoseTemplate.RELAXED_STAND,
            wholeBodyPose = wholeBodyPose()
        )

        assertTrue(figure.innerContourLines.size > figure.lines.size)
        assertEquals(133, figure.innerContourPoints.size)
        assertTrue(figure.innerContourLines.all { line ->
            line.start.x in 0f..1f &&
                line.start.y in 0f..1f &&
                line.end.x in 0f..1f &&
                line.end.y in 0f..1f
        })
    }

    @Test
    fun project_withLowConfidenceWholeBodyPose_doesNotAddInnerContourLines() {
        val figure = projector.project(
            targetBounds = V2Rect(0.18f, 0.12f, 0.72f, 0.92f),
            profile = BodyProfile(170, 60),
            template = PoseTemplate.RELAXED_STAND,
            wholeBodyPose = WholeBodyPoseEstimate(
                keypoints = listOf(WholeBodyKeypoint(23, V2Point(0.42f, 0.20f), 0.1f)),
                confidence = 0.1f
            )
        )

        assertTrue(figure.innerContourLines.isEmpty())
        assertTrue(figure.innerContourPoints.isEmpty())
    }

    private fun completePose(): WholeBodyPoseEstimate =
        WholeBodyPoseEstimate(
            keypoints = listOf(
                keypoint(PoseKeypointName.NOSE, 0.50f, 0.18f),
                keypoint(PoseKeypointName.LEFT_SHOULDER, 0.40f, 0.32f),
                keypoint(PoseKeypointName.RIGHT_SHOULDER, 0.59f, 0.30f),
                keypoint(PoseKeypointName.LEFT_ELBOW, 0.34f, 0.46f),
                keypoint(PoseKeypointName.RIGHT_ELBOW, 0.67f, 0.42f),
                keypoint(PoseKeypointName.LEFT_WRIST, 0.31f, 0.60f),
                keypoint(PoseKeypointName.RIGHT_WRIST, 0.70f, 0.54f),
                keypoint(PoseKeypointName.LEFT_HIP, 0.44f, 0.58f),
                keypoint(PoseKeypointName.RIGHT_HIP, 0.57f, 0.57f),
                keypoint(PoseKeypointName.LEFT_KNEE, 0.38f, 0.76f),
                keypoint(PoseKeypointName.RIGHT_KNEE, 0.61f, 0.72f),
                keypoint(PoseKeypointName.LEFT_ANKLE, 0.34f, 0.94f),
                keypoint(PoseKeypointName.RIGHT_ANKLE, 0.65f, 0.89f)
            ),
            confidence = 0.88f
        )

    private fun halfBodyPose(): WholeBodyPoseEstimate =
        WholeBodyPoseEstimate(
            keypoints = listOf(
                keypoint(PoseKeypointName.NOSE, 0.50f, 0.18f),
                keypoint(PoseKeypointName.LEFT_SHOULDER, 0.39f, 0.34f),
                keypoint(PoseKeypointName.RIGHT_SHOULDER, 0.61f, 0.33f),
                keypoint(PoseKeypointName.LEFT_ELBOW, 0.34f, 0.48f),
                keypoint(PoseKeypointName.RIGHT_ELBOW, 0.66f, 0.46f),
                keypoint(PoseKeypointName.LEFT_WRIST, 0.33f, 0.62f),
                keypoint(PoseKeypointName.RIGHT_WRIST, 0.68f, 0.58f)
            ),
            confidence = 0.84f
        )

    private fun keypoint(name: PoseKeypointName, x: Float, y: Float): WholeBodyKeypoint =
        WholeBodyKeypoint(index = name.ordinal, point = V2Point(x, y), confidence = 0.92f)

    private fun wholeBodyPose(): WholeBodyPoseEstimate {
        val points = mutableListOf<WholeBodyKeypoint>()
        repeat(133) { index ->
            points += WholeBodyKeypoint(
                index = index,
                point = V2Point(
                    x = (0.30f + (index % 17) * 0.018f).coerceIn(0f, 1f),
                    y = (0.16f + (index / 17) * 0.055f).coerceIn(0f, 1f)
                ),
                confidence = 0.9f
            )
        }
        return WholeBodyPoseEstimate(points, confidence = 0.86f)
    }

    private fun lineSignature(figure: VirtualHumanFigure): List<Pair<Float, Float>> =
        figure.lines.map { line ->
            (line.start.x + line.end.x) to (line.start.y + line.end.y)
        }
}
