package com.framer.sense.feature.camera.vlm.scan

import android.graphics.*
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

/** 扫描和背景匹配直接转 RGBA，避免每帧 JPEG 编码／解码；原生库不可用时保留旧路径。 */
internal object YuvBitmapDecoder {
    private val available by lazy {
        runCatching { OpenCVLoader.initLocal() }.getOrDefault(false).also {
            Log.i("VlmScan", "分析图像转换 directRgba=$it jpegFallback=${!it}")
        }
    }

    fun decode(nv21: ByteArray, width: Int, height: Int): Bitmap {
        if (!available) {
            val encoded = ByteArrayOutputStream()
            check(YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 95, encoded))
            val bytes = encoded.toByteArray()
            return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        val input = Mat(height * 3 / 2, width, CvType.CV_8UC1)
        val rgba = Mat()
        var bitmap: Bitmap? = null
        try {
            input.put(0, 0, nv21)
            Imgproc.cvtColor(input, rgba, Imgproc.COLOR_YUV2RGBA_NV21)
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = result
            Utils.matToBitmap(rgba, result)
            return result
        } catch (failure: Exception) {
            bitmap?.recycle()
            throw failure
        } finally { input.release(); rgba.release() }
    }
}
