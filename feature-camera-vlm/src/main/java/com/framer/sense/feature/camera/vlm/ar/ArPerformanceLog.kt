package com.framer.sense.feature.camera.vlm.ar

import android.os.SystemClock
import android.util.Log

/** 每次启动／关键请求变化后仅采集三个五秒窗口，不无限输出，不记录图像内容。 */
internal class ArPerformanceLog {
    private class Timing(var calls: Int = 0, var total: Long = 0L, var maximum: Long = 0L)
    private val timings = linkedMapOf<String, Timing>()
    private val counts = linkedMapOf<String, Int>()
    private var since = SystemClock.elapsedRealtimeNanos()
    private var reports = 0
    val collecting: Boolean
        get() = com.framer.sense.feature.camera.vlm.BuildConfig.DEBUG && reports < 3
    private fun clearWindow(now: Long) { timings.clear(); counts.clear(); since = now }
    fun reset() { reports = 0; clearWindow(SystemClock.elapsedRealtimeNanos()) }
    fun count(name: String) { if (collecting) counts[name] = (counts[name] ?: 0) + 1 }
    fun time(name: String, nanos: Long) {
        if (!collecting) return
        val value = timings.getOrPut(name) { Timing() }
        value.calls++; value.total += nanos; value.maximum = maxOf(value.maximum, nanos)
    }
    fun report(context: String) {
        if (!collecting) return
        val now = SystemClock.elapsedRealtimeNanos()
        val window = now - since
        if (window < 5_000_000_000L) return
        val fps = String.format(java.util.Locale.ROOT,"%.1f",(counts["newFrame"] ?: 0) * 1_000_000_000.0 / window)
        val timing = timings.entries.joinToString(" ") { (key, value) ->
            "$key=avg${value.total / value.calls / 1_000_000}ms/max${value.maximum / 1_000_000}ms"
        }
        reports++
        Log.i("VlmArPerf", "预览汇总 $context windowMs=${window / 1_000_000} newFrameFps=$fps counts=$counts $timing diagnosticsComplete=${reports == 3}")
        clearWindow(now)
    }
}
