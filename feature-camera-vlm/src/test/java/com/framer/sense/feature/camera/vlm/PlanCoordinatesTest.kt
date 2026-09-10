package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Assert.*
import org.junit.Test

class PlanCoordinatesTest {
    /** 裁剪坐标可逆且不钳制画面外脚底；无参数。 */
    @Test fun roundTripKeepsOffscreenFeet() {
        val crop = CropRect(.25f, .25f, .75f, .75f)
        val foot = Point2(.3f, 1.4f)
        val original = PlanCoordinates.toOriginal(foot, crop)
        assertEquals(.4f, original.x, .0001f)
        assertEquals(.95f, original.y, .0001f)
        val restored = PlanCoordinates.toPlan(original, crop)
        assertEquals(foot.x, restored.x, .0001f)
        assertEquals(foot.y, restored.y, .0001f)
    }

    /** 空裁剪不能产生无限坐标；无参数。 */
    @Test fun rejectsEmptyCrop() {
        assertTrue(runCatching { PlanCoordinates.toPlan(Point2(0f, 0f), CropRect(0f, 0f, 0f, 1f)) }.isFailure)
    }
}
