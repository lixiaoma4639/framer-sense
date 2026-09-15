package com.framer.sense.feature.camera.vlm.data

import android.util.Log
import com.framer.sense.feature.camera.vlm.BuildConfig
import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 加载和首 token 不计时；开始输出后，每个真实 token 都重新给予 39 秒等待时间。 */
internal suspend fun <T> withTokenInactivityWatchdog(block: suspend (onToken: () -> Unit) -> T): T = coroutineScope {
    val tokens = Channel<Unit>(Channel.CONFLATED)
    val watchdog = launch {
        tokens.receive()
        while (true) {
            if (withTimeoutOrNull(39_000L) { tokens.receive() } == null) {
                // 上层通过错误码读取 strings.xml，避免业务层持有 Android Context。
                throw ModelFailure("LOCAL_TIMEOUT", "", true)
            }
        }
    }
    try {
        block { tokens.trySend(Unit); Unit }
    } finally {
        // 等待子协程真正结束，避免它在调用方已经抛出原生错误后又以超时覆盖该错误。
        watchdog.cancelAndJoin()
        tokens.close()
    }
}

/** JNI 仅在实际写出 token 时回调；方法名由 consumer-rules.pro 保留。 */
class MnnTokenProgress(private val onProgress: () -> Unit) {
    fun onToken() = onProgress()

    /** 保留原生异常之前已经产生的文本，避免输出上限掩盖真正的生成问题。 */
    fun onIncompleteOutput(bytes: ByteArray) {
        if (!BuildConfig.DEBUG) return
        val parts = VlmJson.encodeToString(bytes.toString(Charsets.UTF_8)).chunked(800)
        parts.forEachIndexed { index, text ->
            Log.d("VlmOffline", "未完成原始输出 part=${index + 1}/${parts.size} raw=$text")
        }
    }
}
