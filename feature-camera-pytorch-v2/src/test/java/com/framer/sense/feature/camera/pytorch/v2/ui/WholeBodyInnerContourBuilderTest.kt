package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeBodyInnerContourBuilderTest {

    @Test
    fun build_keepsPortraitFeatureLinesAndRemovesDebugMeshLines() {
        val lines = WholeBodyInnerContourBuilder.build(fullBodyCloseUpPose())

        assertTrue(lines.containsSegment(point(5), point(7)))
        assertTrue(lines.containsSegment(point(7), point(9)))
        assertTrue(lines.containsSegment(point(11), point(13)))
        assertTrue(lines.containsSegment(facePoint(36), facePoint(37)))
        assertTrue(lines.containsSegment(handPoint(91, 0), handPoint(91, 5)))
        assertTrue(lines.containsSegment(point(15), point(17)))

        assertTrue(!lines.containsSegment(facePoint(0), facePoint(1)))
        assertTrue(!lines.containsSegment(point(5), point(6)))
        assertTrue(!lines.containsSegment(point(11), point(12)))
        assertTrue(!lines.containsSegment(point(5), point(11)))
        assertTrue(!lines.containsSegment(point(0), point(1)))
        assertTrue(!lines.containsSegment(handPoint(91, 1), handPoint(91, 2)))
    }

    @Test
    fun build_forDistantPerson_skipsFaceAndHandDetails() {
        val lines = WholeBodyInnerContourBuilder.build(distantFullBodyPose())

        assertTrue(lines.containsSegment(point(5), point(7)))
        assertTrue(lines.containsSegment(point(11), point(13)))
        assertTrue(!lines.containsSegment(facePoint(36), facePoint(37)))
        assertTrue(!lines.containsSegment(handPoint(91, 0), handPoint(91, 5)))
    }

    @Test
    fun build_forHalfBody_skipsLegsAndFeet() {
        val lines = WholeBodyInnerContourBuilder.build(halfBodyPose())

        assertTrue(lines.containsSegment(point(5), point(7)))
        assertTrue(!lines.containsSegment(point(11), point(13)))
        assertTrue(!lines.containsSegment(point(15), point(17)))
    }

    @Test
    fun build_filtersLowConfidenceAndShortLines() {
        val lines = WholeBodyInnerContourBuilder.build(
            WholeBodyPoseEstimate(
                keypoints = listOf(
                    WholeBodyKeypoint(5, V2Point(0.40f, 0.30f), 0.95f),
                    WholeBodyKeypoint(7, V2Point(0.405f, 0.305f), 0.95f),
                    WholeBodyKeypoint(9, V2Point(0.34f, 0.52f), 0.12f)
                ),
                confidence = 0.9f
            )
        )

        assertTrue(lines.isEmpty())
    }

    @Test
    fun build_clampsAllOutputPoints() {
        val lines = WholeBodyInnerContourBuilder.build(fullBodyCloseUpPose())

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { line ->
            line.start.x in 0f..1f &&
                line.start.y in 0f..1f &&
                line.end.x in 0f..1f &&
                line.end.y in 0f..1f
        })
    }

    private fun fullBodyCloseUpPose(): WholeBodyPoseEstimate =
        wholeBodyPose(
            scale = 1f,
            includeLowerBody = true,
            includeFace = true,
            includeHands = true
        )

    private fun distantFullBodyPose(): WholeBodyPoseEstimate =
        wholeBodyPose(
            scale = 0.45f,
            includeLowerBody = true,
            includeFace = true,
            includeHands = true
        )

    private fun halfBodyPose(): WholeBodyPoseEstimate =
        wholeBodyPose(
            scale = 1f,
            includeLowerBody = false,
            includeFace = true,
            includeHands = true
        )

    private fun wholeBodyPose(
        scale: Float,
        includeLowerBody: Boolean,
        includeFace: Boolean,
        includeHands: Boolean
    ): WholeBodyPoseEstimate {
        val points = mutableListOf<WholeBodyKeypoint>()
        fun add(index: Int, source: V2Point, confidence: Float = 0.95f) {
            points += WholeBodyKeypoint(index, source.scaled(scale), confidence)
        }

        BODY_POINTS.forEach { (index, source) ->
            if (includeLowerBody || index !in setOf(11, 12, 13, 14, 15, 16, 17, 19, 20, 22)) {
                add(index, source)
            }
        }
        if (includeFace) {
            FACE_POINTS.forEach { (index, source) -> add(23 + index, source) }
        }
        if (includeHands) {
            HAND_POINTS.forEach { (index, source) ->
                add(91 + index, source)
                add(112 + index, V2Point(1f - source.x, source.y))
            }
        }

        return WholeBodyPoseEstimate(points, confidence = 0.9f)
    }

    private fun V2Point.scaled(scale: Float): V2Point {
        val center = V2Point(0.50f, 0.50f)
        return V2Point(
            x = center.x + (x - center.x) * scale,
            y = center.y + (y - center.y) * scale
        )
    }

    private fun point(index: Int): V2Point =
        BODY_POINTS.getValue(index)

    private fun facePoint(offset: Int): V2Point =
        FACE_POINTS.getValue(offset)

    private fun handPoint(base: Int, offset: Int): V2Point {
        val point = HAND_POINTS.getValue(offset)
        return if (base == 91) point else V2Point(1f - point.x, point.y)
    }

    private fun List<VirtualHumanLine>.containsSegment(start: V2Point, end: V2Point): Boolean =
        any { line ->
            (line.start.closeTo(start) && line.end.closeTo(end)) ||
                (line.start.closeTo(end) && line.end.closeTo(start))
        }

    private fun V2Point.closeTo(other: V2Point): Boolean =
        kotlin.math.abs(x - other.x) < 0.002f && kotlin.math.abs(y - other.y) < 0.002f

    private companion object {
        val BODY_POINTS = mapOf(
            0 to V2Point(0.50f, 0.16f),
            1 to V2Point(0.47f, 0.15f),
            5 to V2Point(0.38f, 0.30f),
            6 to V2Point(0.62f, 0.30f),
            7 to V2Point(0.32f, 0.44f),
            8 to V2Point(0.68f, 0.44f),
            9 to V2Point(0.28f, 0.58f),
            10 to V2Point(0.72f, 0.58f),
            11 to V2Point(0.42f, 0.58f),
            12 to V2Point(0.58f, 0.58f),
            13 to V2Point(0.38f, 0.74f),
            14 to V2Point(0.62f, 0.74f),
            15 to V2Point(0.36f, 0.90f),
            16 to V2Point(0.64f, 0.90f),
            17 to V2Point(0.34f, 0.94f),
            19 to V2Point(0.39f, 0.94f),
            20 to V2Point(0.66f, 0.94f),
            22 to V2Point(0.61f, 0.94f)
        )

        val FACE_POINTS = buildMap {
            repeat(68) { index ->
                put(index, V2Point(0.42f + (index % 10) * 0.018f, 0.13f + (index / 10) * 0.018f))
            }
        }

        val HAND_POINTS = buildMap {
            repeat(21) { index ->
                put(index, V2Point(0.24f + (index % 5) * 0.020f, 0.56f + (index / 5) * 0.020f))
            }
        }
    }
}
