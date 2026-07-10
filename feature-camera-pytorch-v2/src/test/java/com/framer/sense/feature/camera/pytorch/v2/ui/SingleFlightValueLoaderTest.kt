package com.framer.sense.feature.camera.pytorch.v2.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SingleFlightValueLoaderTest {

    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val callersExecutor = Executors.newFixedThreadPool(8)

    @After
    fun tearDown() {
        loadExecutor.shutdownNow()
        callersExecutor.shutdownNow()
    }

    @Test
    fun concurrentAwait_loadsValueOnlyOnce() {
        val loadCount = AtomicInteger(0)
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val callersReady = CountDownLatch(1)
        val loader = SingleFlightValueLoader(loadExecutor) {
            loadCount.incrementAndGet()
            loadStarted.countDown()
            releaseLoad.await()
            "ready"
        }

        loader.preload()
        assertTrue(loadStarted.await(2, TimeUnit.SECONDS))
        loader.preload()

        val results = List(8) {
            callersExecutor.submit<String> {
                callersReady.await()
                loader.await()
            }
        }
        callersReady.countDown()

        assertEquals(1, loadCount.get())
        releaseLoad.countDown()
        results.forEach { assertEquals("ready", it.get(2, TimeUnit.SECONDS)) }
        assertEquals(1, loadCount.get())
    }

    @Test
    fun failedLoad_isCachedAndNotRetried() {
        val loadCount = AtomicInteger(0)
        val loader = SingleFlightValueLoader<String>(loadExecutor) {
            loadCount.incrementAndGet()
            error("model load failed")
        }

        expectExecutionFailure { loader.await() }
        expectExecutionFailure { loader.await() }

        assertEquals(1, loadCount.get())
    }

    private fun expectExecutionFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected model loading to fail")
        } catch (_: ExecutionException) {
            // The cached Future rethrows the same failure for every caller.
        }
    }
}
