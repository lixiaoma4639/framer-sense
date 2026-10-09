package com.framer.sense.feature.camera.vlm.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import com.framer.sense.feature.camera.vlm.model.CameraCapabilities
import com.framer.sense.feature.camera.vlm.model.CameraGestureGate
import com.framer.sense.feature.camera.vlm.model.CameraZoomPolicy
import kotlin.math.hypot

/** 缩放提交合并在 PreviewView 的显示帧；这里仅计算目标倍率。 */
internal class CameraTouchHandler(context: Context, private val zoom: () -> Float,
    private val enabled: () -> Boolean, private val limits: () -> CameraCapabilities,
    private val onZoom: (Float) -> Unit, private val onClick: (Float, Float) -> Unit) {
    private val gate = CameraGestureGate()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var x = 0f; private var y = 0f; private var moved = false
    private var target = 1f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            target = zoom(); return enabled()
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!enabled()) return false
            val range = limits()
            target = CameraZoomPolicy.clamp(target * detector.scaleFactor, range.minZoom, range.maxZoom)
            onZoom(target); return true
        }
    })
    fun onTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gate.begin(); x = event.x; y = event.y; moved = false
        }
        gate.pointers(event.pointerCount)
        if (hypot(event.x - x, event.y - y) > slop) moved = true
        scale.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && gate.canClick() && !moved) onClick(event.x, event.y)
    }
}
