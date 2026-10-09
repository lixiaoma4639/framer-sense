package com.framer.sense.feature.camera.vlm.camera

import java.nio.ByteBuffer

/** 批量读取直接缓冲，兼容平面／交错色度、行填充及非零起始位置。 */
internal object Yuv420Copy {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun toNv21(width: Int, height: Int, planes: List<Plane>): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && planes.size == 3)
        val result = ByteArray(width * height * 3 / 2)
        copy(planes[0], width, height, result, 0, 1)
        copy(planes[2], width / 2, height / 2, result, width * height, 2)
        copy(planes[1], width / 2, height / 2, result, width * height + 1, 2)
        return result
    }

    private fun copy(plane: Plane, columns: Int, rows: Int, output: ByteArray, offset: Int, step: Int) {
        require(plane.pixelStride > 0 && plane.rowStride >= (columns - 1) * plane.pixelStride + 1)
        val source = plane.buffer.duplicate()
        val base = source.position()
        if (plane.pixelStride == 1 && step == 1) {
            if (plane.rowStride == columns) source.get(output, offset, columns * rows)
            else for (y in 0 until rows) {
                source.position(base + y * plane.rowStride)
                source.get(output, offset + y * columns, columns)
            }
            return
        }
        // 最后一行可能没有尾部 padding，只读取到最后一个有效像素。
        val row = ByteArray((columns - 1) * plane.pixelStride + 1)
        for (y in 0 until rows) {
            source.position(base + y * plane.rowStride)
            source.get(row)
            val destination = offset + y * columns * step
            for (x in 0 until columns) output[destination + x * step] = row[x * plane.pixelStride]
        }
    }
}
