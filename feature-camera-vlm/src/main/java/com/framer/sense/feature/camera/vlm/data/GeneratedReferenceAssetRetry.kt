package com.framer.sense.feature.camera.vlm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ProtocolException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** 只重试一次资源 GET；绝不将此策略用于重发生图 POST。 */
internal suspend fun <T> retryGeneratedAssetDownload(
    method: String,
    onRetry: (Exception) -> Unit = {},
    fetch: suspend (freshConnection: Boolean) -> T
): T {
    try {
        return fetch(false)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        val transportFailure = causes.any { it is IOException && it !is GeneratedReferenceFailure }
        val permanentFailure = causes.any { it is CertificateException || it is SSLPeerUnverifiedException ||
            it is SSLHandshakeException || it is ProtocolException }
        if (method != "GET" || !transportFailure || permanentFailure) throw failure
        onRetry(failure)
        delay(300)
        // 第二次失败直接上抛，不循环或递归重试。
        return fetch(true)
    }
}
