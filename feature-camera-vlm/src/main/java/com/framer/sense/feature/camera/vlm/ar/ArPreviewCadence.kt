package com.framer.sense.feature.camera.vlm.ar

/** 帧耗时计入周期，避免处理时间之后再完整等待一帧。 */
internal object ArPreviewCadence {
    fun delayMillis(workNanos: Long, rendering: Boolean): Long =
        ((if (rendering) 33L else 100L) - workNanos.coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(1L)
}
