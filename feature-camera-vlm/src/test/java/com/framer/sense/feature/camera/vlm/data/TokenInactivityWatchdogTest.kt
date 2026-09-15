package com.framer.sense.feature.camera.vlm.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenInactivityWatchdogTest {
    /** 冷加载和首 token 超过旧的 120 秒限制，持续输出也不能因总耗时被中止。 */
    @Test fun allowsSlowFirstTokenAndLongHealthyGeneration() = runTest {
        val result = withTokenInactivityWatchdog { onToken ->
            delay(180_000)
            repeat(8) { onToken(); delay(38_000) }
            "complete"
        }
        assertEquals("complete", result)
        assertEquals(484_000L, currentTime)
    }

    /** 新 token 重置计时；最后一个 token 后 39 秒取消等待并通知原生取消标记。 */
    @Test fun timesOut39SecondsAfterLastTokenAndCancelsNativeWait() = runTest {
        var cancelled = false
        try {
            withTokenInactivityWatchdog<Unit> { onToken ->
                onToken()
                delay(38_000)
                onToken()
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { cancelled = true }
                }
            }
            fail("停滞应终止等待")
        } catch (failure: ModelFailure) {
            assertEquals("LOCAL_TIMEOUT", failure.code)
        }
        assertEquals(77_000L, currentTime)
        assertTrue(cancelled)
    }

    /** 明确报错立即返回，不能被误报为停滞或等待到 39 秒。 */
    @Test fun propagatesExplicitFailureImmediately() = runTest {
        val original = IllegalStateException("native failure")
        for (started in listOf(false, true)) {
            val failure = runCatching {
                withTokenInactivityWatchdog<Unit> { onToken ->
                    if (started) onToken()
                    throw original
                }
            }.exceptionOrNull()
            // 挂起边界可能由协程恢复机制复制异常；校验类型和内容即可确认没有被看门狗替换。
            assertTrue(failure is IllegalStateException)
            assertEquals(original.message, failure?.message)
        }
        assertEquals(0L, currentTime)
    }

    /** 用户取消保持取消语义，不能被内部定时器转换成超时错误。 */
    @Test fun preservesCallerCancellationAndStopsWatchdog() = runTest {
        val result = withTimeoutOrNull(1_000) {
            withTokenInactivityWatchdog<Unit> { onToken -> onToken(); awaitCancellation() }
        }
        assertNull(result)
        assertEquals(1_000L, currentTime)
    }

    /** 上次调用的迟到回调不能延长下一次调用的等待时间。 */
    @Test fun ignoresLateTokensFromCompletedCall() = runTest {
        var oldToken: () -> Unit = {}
        withTokenInactivityWatchdog { onToken -> oldToken = onToken }
        try {
            withTokenInactivityWatchdog<Unit> { onToken ->
                onToken()
                delay(38_000)
                oldToken()
                awaitCancellation()
            }
            fail("旧回调不应刷新计时")
        } catch (failure: ModelFailure) {
            assertEquals("LOCAL_TIMEOUT", failure.code)
        }
        assertEquals(39_000L, currentTime)
    }
}
