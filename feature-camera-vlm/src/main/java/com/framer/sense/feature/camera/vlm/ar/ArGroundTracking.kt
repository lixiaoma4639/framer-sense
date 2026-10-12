package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.ArGroundSnapshot
import com.framer.sense.feature.camera.vlm.model.newVlmId
import kotlin.math.abs
import kotlin.math.round

/** 使用逻辑相等性：ARCore 可以为同一平面返回不同的 Java 包装对象。 */
internal class ArGroundTracking<K>(private val nextId: () -> String = ::newVlmId) {
    private val ids = mutableMapOf<K, String>()
    private val evidence = mutableMapOf<String, Pair<Float, Int>>()
    fun findId(key: K): String? = ids[key]
    fun id(key: K): String = ids.getOrPut(key, nextId)
    fun count(id: String): Int = evidence[id]?.second ?: 0
    fun observe(key: K, height: Float): String {
        val id = id(key)
        val old = evidence[id]
        evidence[id] = height to if (old != null && abs(old.first - height) <= .05f)
            (old.second + 1).coerceAtMost(3) else 1
        return id
    }
    fun retain(active: Set<String>) { evidence.keys.retainAll(active) }
    fun lost() { evidence.clear() }
    fun reset() { ids.clear(); evidence.clear() }
    val stable: Boolean get() = evidence.values.any { it.second >= 3 }
}

internal fun ArGroundSnapshot.fingerprint(): String = "$sessionId:$configVersion:" +
    planes.sortedBy { it.id }.joinToString { plane ->
        plane.id + ":" + plane.stableObservations.coerceAtMost(3) + plane.boundary.joinToString { p ->
            "${round(p.x * 20)}:${round(p.y * 20)}:${round(p.z * 20)}"
        }
    }

/** 除几何变化外，有限频率重发，防止一次迟到/恢复期间被丢弃后永远没有输入。 */
internal class ArGroundDelivery {
    private var signature: String? = null
    private var publishedNs: Long? = null
    fun reset() { signature = null; publishedNs = null }
    fun shouldPublish(next: String, nowNs: Long): Boolean {
        if (next == signature && publishedNs?.let { nowNs - it < 2_000_000_000L } == true) return false
        signature = next; publishedNs = nowNs
        return true
    }
}
