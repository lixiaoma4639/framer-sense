package com.framer.sense.feature.camera.vlm.scan

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.framer.sense.feature.camera.vlm.model.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class SceneFeatures(val points: List<Point2>, val descriptors: ByteArray, val quality: Float, val unusable: Boolean, val width: Int, val height: Int)

/** 所有 Mat 在本次操作退出时释放；保留的特征只有小型 JVM 数据。 */
class OpenCvSceneTools {
    var lastMatchRejection: String? = null
        private set
    private val available by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }
    fun extract(bitmap: Bitmap, excluded: CropRect? = null): SceneFeatures? {
        if (!available) return null
        val ratio = minOf(1f, BackgroundMatchPolicy.LONG_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val small = bitmap.scale(max(1, (bitmap.width * ratio).toInt()), max(1, (bitmap.height * ratio).toInt()))
        val rgba = Mat(); val gray = Mat(); val mask = Mat(); val laplacian = Mat()
        val keys = MatOfKeyPoint(); val descriptors = Mat(); val mean = MatOfDouble(); val deviation = MatOfDouble()
        val orb = ORB.create(BackgroundMatchPolicy.MAX_FEATURES)
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            mask.create(gray.rows(), gray.cols(), CvType.CV_8UC1); mask.setTo(Scalar(255.0))
            excluded?.let { r ->
                Imgproc.rectangle(mask, Point((r.left * gray.cols()).toDouble(), (r.top * gray.rows()).toDouble()),
                    Point((r.right * gray.cols()).toDouble(), (r.bottom * gray.rows()).toDouble()), Scalar(0.0), -1)
            }
            orb.detectAndCompute(gray, mask, keys, descriptors)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, deviation)
            val sharpness = deviation.toArray().firstOrNull()?.let { it * it } ?: 0.0
            val data = ByteArray(gray.rows() * gray.cols()); gray.get(0, 0, data)
            val clipped = data.count { (it.toInt() and 255) <= 3 || (it.toInt() and 255) >= 252 }.toFloat() / data.size
            val keyPoints = keys.toArray().map { Point2((it.pt.x / gray.cols()).toFloat(), (it.pt.y / gray.rows()).toFloat()) }
            val descriptorBytes = ByteArray(descriptors.rows() * descriptors.cols()); if (descriptorBytes.isNotEmpty()) descriptors.get(0, 0, descriptorBytes)
            // 没有纹理的天空/水面不能仅凭低拉普拉斯方差判为模糊。
            return SceneFeatures(keyPoints, descriptorBytes, (ln(1.0 + sharpness) * (1 - clipped)).toFloat(),
                clipped > .98f || (keyPoints.size >= 30 && sharpness < 10.0), gray.cols(), gray.rows())
        } finally {
            listOf(rgba, gray, mask, laplacian, keys, descriptors, mean, deviation).forEach { it.release() }
            orb.clear()
            if (small !== bitmap) small.recycle()
        }
    }

    fun match(reference: SceneFeatures?, current: SceneFeatures?): MatchMetrics? {
        lastMatchRejection = null
        if (!available) return rejected("OPENCV_UNAVAILABLE")
        if (reference == null || current == null) return rejected("NO_FEATURES")
        if (reference.points.size < BackgroundMatchPolicy.MIN_MATCHES || current.points.size < BackgroundMatchPolicy.MIN_MATCHES)
            return rejected("INSUFFICIENT_FEATURES")
        val a = Mat(reference.points.size, 32, CvType.CV_8UC1); val b = Mat(current.points.size, 32, CvType.CV_8UC1)
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val matches = mutableListOf<MatOfDMatch>(); val src = MatOfPoint2f(); val dst = MatOfPoint2f(); val inliers = Mat()
        var homography: Mat? = null
        val corners = MatOfPoint2f(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0)); val mapped = MatOfPoint2f()
        try {
            a.put(0, 0, reference.descriptors); b.put(0, 0, current.descriptors)
            matcher.knnMatch(a, b, matches, 2)
            val pairs = matches.mapNotNull { row -> row.toArray().takeIf { it.size >= 2 && it[0].distance < BackgroundMatchPolicy.RATIO_TEST * it[1].distance }?.first() }
                .sortedBy { it.distance }.distinctBy { it.trainIdx }
            if (pairs.size < BackgroundMatchPolicy.MIN_MATCHES) return rejected("DESCRIPTOR_MATCHES_${pairs.size}")
            src.fromList(pairs.map { reference.points[it.queryIdx].let { p -> Point((p.x * reference.width).toDouble(), (p.y * reference.height).toDouble()) } })
            dst.fromList(pairs.map { current.points[it.trainIdx].let { p -> Point((p.x * current.width).toDouble(), (p.y * current.height).toDouble()) } })
            val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, BackgroundMatchPolicy.RANSAC_PIXELS, inliers)
            homography = h
            if (h.empty()) return rejected("HOMOGRAPHY_FAILED")
            corners.fromArray(Point(0.0, 0.0), Point(reference.width.toDouble(), 0.0), Point(reference.width.toDouble(), reference.height.toDouble()), Point(0.0, reference.height.toDouble()))
            Core.perspectiveTransform(corners, mapped, h)
            val transformed = mapped.toArray()
            val mask = ByteArray(pairs.size); inliers.get(0, 0, mask)
            val retained = pairs.indices.filter { mask[it].toInt() != 0 }.map { reference.points[pairs[it].queryIdx] }
            if (retained.isEmpty()) return rejected("NO_INLIERS")
            val coverage = (retained.maxOf { it.x } - retained.minOf { it.x }) * (retained.maxOf { it.y } - retained.minOf { it.y })
            val quadrants = retained.map { (if (it.x > .5f) 1 else 0) + (if (it.y > .5f) 2 else 0) }.distinct().size
            val originals = arrayOf(Point(0.0, 0.0), Point(current.width.toDouble(), 0.0), Point(current.width.toDouble(), current.height.toDouble()), Point(0.0, current.height.toDouble()))
            val error = transformed.indices.maxOf { i -> hypot(transformed[i].x - originals[i].x, transformed[i].y - originals[i].y) / hypot(current.width.toDouble(), current.height.toDouble()) }
            val rotation = Math.toDegrees(atan2(h.get(1, 0)[0], h.get(0, 0)[0]))
            val scale = hypot(transformed[1].x - transformed[0].x, transformed[1].y - transformed[0].y) / current.width
            val area = transformed.indices.sumOf { i -> val j = (i + 1) % 4; transformed[i].x * transformed[j].y - transformed[j].x * transformed[i].y } / (2 * current.width.toDouble() * current.height)
            val valid = area in .1..10.0 && transformed.all { it.x.isFinite() && it.y.isFinite() && abs(it.x) < current.width * 4 && abs(it.y) < current.height * 4 }
            return MatchMetrics(pairs.size, retained.size, coverage, quadrants, error.toFloat(), rotation.toFloat(), scale.toFloat(), valid)
        } finally {
            listOf(a, b, src, dst, inliers, corners, mapped).forEach { it.release() }
            homography?.release(); matches.forEach { it.release() }; matcher.clear()
        }
    }

    private fun rejected(reason: String): MatchMetrics? {
        lastMatchRejection = reason
        return null
    }
}
