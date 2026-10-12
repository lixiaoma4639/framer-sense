package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.*

object ArGeometry {
    const val NEAR = .1f
    const val FAR = 40f
    fun suggestedGround(plan: CompositionPlan): Point2? = PlanCoordinates.toOriginal(plan.avatar.foot, plan.crop)
        .takeIf { it.x in 0f..1f && it.y in 0f..1f }
    /** AR 深度图是正 Z 距离；OpenGL 相机前方是负 Z，Y 方向与图像相反。 */
    fun writeDepthVertex(out: FloatArray, offset: Int, x: Int, y: Int, z: Float,
                         fx: Float, fy: Float, cx: Float, cy: Float) {
        out[offset] = (x-cx)*z/fx
        out[offset+1] = -(y-cy)*z/fy
        out[offset+2] = -z
    }
}
