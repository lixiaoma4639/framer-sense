package com.framer.sense.feature.camera.vlm.camera

/** 两种相机路径共用递增配置，回退后不丢弃新的 CameraX 帧。 */
object CameraConfigVersions {
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    fun next(): Int = counter.incrementAndGet()
}
