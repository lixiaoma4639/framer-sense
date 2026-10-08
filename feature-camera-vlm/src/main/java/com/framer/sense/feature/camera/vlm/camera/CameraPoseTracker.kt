package com.framer.sense.feature.camera.vlm.camera

import android.content.Context
import android.hardware.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.angleDelta
import kotlin.math.*

/** 相机光轴是设备 -Z，而非手机顶边；传感器重启后更换基准，旧方向不可再比较。 */
class CameraPoseTracker(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var registered = false
    private var angularTravel = 0f
    private var previousHeading: Float? = null
    private var basis = newVlmId()
    private val samples = ArrayDeque<ScanPose>()
    @Synchronized fun start() {
        if (registered || sensor == null) return
        basis = newVlmId(); samples.clear(); angularTravel = 0f; previousHeading = null
        registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }
    @Synchronized fun stop() {
        manager.unregisterListener(this); registered = false; samples.clear(); basis = newVlmId()
    }
    @Synchronized override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        val x = -r[2]; val y = -r[5]; val z = -r[8]
        val heading = if (hypot(x, y) > .2f) Math.toDegrees(atan2(x, y).toDouble()).toFloat() else null
        val pitch = Math.toDegrees(asin(z.coerceIn(-1f, 1f)).toDouble()).toFloat()
        if (!pitch.isFinite() || heading?.isFinite() == false) return
        if (heading != null) previousHeading?.let { angularTravel += abs(angleDelta(heading, it)) }
        previousHeading = heading
        samples.addLast(ScanPose(basis, event.timestamp, heading, pitch, angularTravel))
        while (samples.size > 200) samples.removeFirst()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    @Synchronized fun at(timestampNs: Long): ScanPose? {
        val before = samples.lastOrNull { it.timestampNs <= timestampNs }
        val after = samples.firstOrNull { it.timestampNs >= timestampNs }
        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.timestampNs - timestampNs) } ?: return null
        if (abs(nearest.timestampNs - timestampNs) > 100_000_000L) return null
        if (before == null || after == null || before.timestampNs == after.timestampNs) return nearest.copy(timestampNs = timestampNs)
        val weight = (timestampNs - before.timestampNs).toFloat() / (after.timestampNs - before.timestampNs)
        return ScanPose(basis, timestampNs, if (before.heading != null && after.heading != null)
            before.heading + angleDelta(after.heading, before.heading) * weight else null,
            before.pitch + (after.pitch - before.pitch) * weight,
            before.angularTravel!! + (after.angularTravel!! - before.angularTravel) * weight)
    }
}
