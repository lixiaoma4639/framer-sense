package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.*
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException

/** 随扫描候选采集稀疏高置信度深度，不逐帧打印日志，不持有 Image 或 AR 对象。 */
internal object ArDepthSampler {
    const val MIN_CONFIDENCE = 160
    data class Result(val points: List<ArDepthPoint>, val status: String)
    fun capture(frame: Frame, crop: CropRect, orientation: Int): Result {
        return try {
            frame.acquireRawDepthImage16Bits().use { depth ->
                frame.acquireRawDepthConfidenceImage().use { confidence ->
                    if (confidence.width != depth.width || confidence.height != depth.height ||
                        confidence.timestamp != depth.timestamp) return Result(emptyList(),"PAIR_MISMATCH")
                    // ARCore 会把旧深度重投影到当前视角，旧时间戳不代表图像已失效。
                    val status=if (frame.timestamp == depth.timestamp) "RAW_NEW" else "RAW_REPROJECTED"
                    val intrinsics=frame.camera.imageIntrinsics
                    val focal=intrinsics.focalLength; val principal=intrinsics.principalPoint
                    val dimensions=intrinsics.imageDimensions
                    val sx=depth.width.toFloat()/dimensions[0]; val sy=depth.height.toFloat()/dimensions[1]
                    val fx=focal[0]*sx; val fy=focal[1]*sy; val cx=principal[0]*sx; val cy=principal[1]*sy
                    if (fx <= 0f || fy <= 0f || !fx.isFinite() || !fy.isFinite()) return Result(emptyList(),"INVALID_INTRINSICS")
                    val d=depth.planes[0]; val c=confidence.planes[0]
                    val db=d.buffer.duplicate(); val cb=c.buffer.duplicate()
                    val ds=db.position(); val cs=cb.position()
                    val step=maxOf(1,(depth.width+31)/32,(depth.height+23)/24)
                    val points=mutableListOf<ArDepthPoint>()
                    // 每格选置信度最高的有效像素，避免规则网格恰好采到稀疏深度的空洞。
                    for (gy in 0 until depth.height step step) for (gx in 0 until depth.width step step) {
                        var px=-1; var py=-1; var best=MIN_CONFIDENCE-1; var z=0f
                        for (y in gy until minOf(gy+step,depth.height)) for (x in gx until minOf(gx+step,depth.width)) {
                            val reliability=cb.get(cs+y*c.rowStride+x*c.pixelStride).toInt() and 255
                            if (reliability <= best) continue
                            val offset=ds+y*d.rowStride+x*d.pixelStride
                            val meters=((db.get(offset).toInt() and 255) or ((db.get(offset+1).toInt() and 255) shl 8))*.001f
                            if (meters !in ArScaleSolver.MIN_DISTANCE..ArScaleSolver.MAX_DISTANCE) continue
                            px=x; py=y; best=reliability; z=meters
                        }
                        if (px < 0) continue
                        val x=((px+.5f)/depth.width-crop.left)/(crop.right-crop.left)
                        val y=((py+.5f)/depth.height-crop.top)/(crop.bottom-crop.top)
                        val image=when (orientation) {
                            90 -> Point2(1f-y,x); 180 -> Point2(1f-x,1f-y); 270 -> Point2(y,1f-x); else -> Point2(x,y)
                        }
                        if (image.x !in 0f..1f || image.y !in 0f..1f) continue
                        val world=frame.camera.pose.transformPoint(floatArrayOf((px+.5f-cx)*z/fx,-(py+.5f-cy)*z/fy,-z))
                        points+=ArDepthPoint(image,SpatialPoint(world[0],world[1],world[2]),best)
                    }
                    Result(points.toList(),if (points.isEmpty()) "NO_CONFIDENT_PIXELS" else status)
                }
            }
        } catch (_: NotYetAvailableException) { Result(emptyList(),"NOT_YET_AVAILABLE") }
        catch (_: com.google.ar.core.exceptions.NotTrackingException) { Result(emptyList(),"NOT_TRACKING") }
        catch (failure: Exception) { Result(emptyList(),"UNAVAILABLE:${failure.javaClass.simpleName}") }
    }
}
