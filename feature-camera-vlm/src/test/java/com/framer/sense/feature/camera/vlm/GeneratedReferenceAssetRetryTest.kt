package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.data.GeneratedReferenceFailure
import com.framer.sense.feature.camera.vlm.data.retryGeneratedAssetDownload
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class GeneratedReferenceAssetRetryTest {
    @Test fun transientSslDownloadFailureRetriesSameOperationOnceWithFreshConnection() = runBlocking {
        val connections = mutableListOf<Boolean>()
        var retries = 0
        val result = retryGeneratedAssetDownload("GET", onRetry = { retries++ }) { fresh ->
            connections += fresh
            if (!fresh) throw GeneratedReferenceFailure("NETWORK", SSLException("fixture"))
            "downloaded"
        }
        assertEquals("downloaded", result)
        assertEquals(listOf(false, true), connections)
        assertEquals(1, retries)
    }

    @Test fun failingDownloadStopsAfterTwoAttempts() = runBlocking {
        var calls = 0
        try {
            retryGeneratedAssetDownload<String>("GET") {
                calls++
                throw GeneratedReferenceFailure("NETWORK", IOException("fixture"))
            }
            fail("不能吞掉第二次下载异常")
        } catch (expected: GeneratedReferenceFailure) { assertEquals("NETWORK", expected.code) }
        assertEquals(2, calls)
    }

    @Test fun postCertificateHandshakeAndProtocolFailuresAreNeverRetried() = runBlocking {
        val failures = listOf(
            "POST" to GeneratedReferenceFailure("NETWORK", SSLException("fixture")),
            "GET" to GeneratedReferenceFailure("NETWORK", SSLHandshakeException("fixture")),
            "GET" to GeneratedReferenceFailure("NETWORK", SSLPeerUnverifiedException("fixture")),
            "GET" to GeneratedReferenceFailure("NETWORK", SSLException("fixture", CertificateException("fixture"))),
            "GET" to GeneratedReferenceFailure("OUTPUT", ProtocolException("fixture")),
            "GET" to GeneratedReferenceFailure("OUTPUT")
        )
        failures.forEach { (method, failure) ->
            var calls = 0
            try {
                retryGeneratedAssetDownload<String>(method) { calls++; throw failure }
                fail("永久失败或生图 POST 不应重试")
            } catch (expected: GeneratedReferenceFailure) { assertSame(failure, expected) }
            assertEquals(1, calls)
        }
    }

    @Test fun httpErrorResponseDoesNotTriggerNetworkRetry() = runBlocking {
        var calls = 0
        val status = retryGeneratedAssetDownload("GET") { calls++; 403 }
        assertEquals(403, status)
        assertEquals(1, calls)
    }

    @Test fun cancellationDuringRetryDelayDoesNotStartAnotherDownload() = runBlocking {
        var calls = 0
        val retrying = CompletableDeferred<Unit>()
        val job = launch {
            retryGeneratedAssetDownload<String>("GET", onRetry = { retrying.complete(Unit) }) {
                calls++
                throw GeneratedReferenceFailure("NETWORK", SSLException("fixture"))
            }
        }
        retrying.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }
}
