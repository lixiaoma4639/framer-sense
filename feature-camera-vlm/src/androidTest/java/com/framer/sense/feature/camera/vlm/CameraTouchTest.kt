package com.framer.sense.feature.camera.vlm

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.model.CameraCapabilities
import com.framer.sense.feature.camera.vlm.ui.CameraTouchHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CameraTouchTest {
    @Test fun pinchChangesZoomButDoesNotFocusAfterFingerLiftAndCanBeLocked() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var target = 1f; var clicks = 0; var enabled = true
            val handler = CameraTouchHandler(context, { target }, { enabled }, { CameraCapabilities(1f, 10f) },
                { target = it }, { _, _ -> clicks++ })
            val start = SystemClock.uptimeMillis()
            var tick = 0
            fun send(action: Int, vararg xs: Float) {
                val properties = Array(xs.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coordinates = Array(xs.size) { index -> MotionEvent.PointerCoords().apply { x = xs[index]; y = 100f; pressure = 1f; size = 1f } }
                val event = MotionEvent.obtain(start, start + ++tick * 16L, action, xs.size,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { handler.onTouch(event) } finally { event.recycle() }
            }
            fun pinch() {
                send(MotionEvent.ACTION_DOWN, 100f)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 400f)
                send(MotionEvent.ACTION_MOVE, 100f, 650f)
                send(MotionEvent.ACTION_MOVE, 100f, 900f)
                send(MotionEvent.ACTION_MOVE, 100f, 1150f)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 1150f)
                send(MotionEvent.ACTION_UP, 100f)
            }
            pinch(); assertTrue(target > 1f); assertEquals(0, clicks)
            val last = target
            enabled = false; pinch(); assertEquals(last, target, 0f); assertEquals(0, clicks)
            send(MotionEvent.ACTION_DOWN, 100f); send(MotionEvent.ACTION_UP, 100f)
            assertEquals(1, clicks)
        }
    }
}
