package com.framer.sense.feature.camera.vlm.camera

import androidx.camera.core.ImageCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 为一次快门请求锁定方向，成功、失败或取消后都恢复，不修改 Preview／Analysis 或重新绑定。 */
internal suspend fun <T> ImageCapture.withPhotoTargetRotation(rotation: Int, capture: suspend () -> T): T =
    withContext(Dispatchers.Main.immediate) {
        val previousRotation = targetRotation
        try {
            targetRotation = rotation
            capture()
        } finally {
            targetRotation = previousRotation
        }
    }
