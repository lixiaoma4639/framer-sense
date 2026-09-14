package com.framer.sense.feature.camera.vlm.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 使用 OkHttp 拦截器模拟上游，不连接互联网或运行真实模型。 */
class HubDownloadClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val revision = "a".repeat(40)

    /** 构造模拟 HTTP 响应。@param request 请求。@param code 状态码。@param text 正文。@param range 可选续传头。@return 模拟响应。 */
    private fun reply(request: Request, code: Int, text: String, range: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(text.toResponseBody()).apply { range?.let { header("Content-Range", it) } }.build()

    /** 服务器返回 200 时必须重写而不是追加；无参数。 */
    @Test fun ignoredRangeRewritesFile() = runBlocking {
        val file = temporary.newFile().apply { writeText("ab") }
        val client = HubDownloadClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=2-", chain.request().header("Range"))
            reply(chain.request(), 200, "abcdef")
        }.build())
        client.download(HubDownloadClient.DEFAULT_SOURCE, revision, HubFile("weights.mnn", 6), file, {}, {})
        assertEquals("abcdef", file.readText())
    }

    /** 合法 206 只追加缺失部分，错误起点不改文件；无参数。 */
    @Test fun partialResponseRequiresExactRange() = runBlocking {
        val file = temporary.newFile().apply { writeText("ab") }
        var range = "bytes 1-5/6"
        val client = HubDownloadClient(OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request(), 206, "cdef", range) }.build())
        assertTrue(runCatching { client.download(HubDownloadClient.DEFAULT_SOURCE, revision, HubFile("weights.mnn", 6), file, {}, {}) }.isFailure)
        assertEquals("ab", file.readText())
        range = "bytes 2-5/6"
        client.download(HubDownloadClient.DEFAULT_SOURCE, revision, HubFile("weights.mnn", 6), file, {}, {})
        assertEquals("abcdef", file.readText())
    }

    /** 摘要失败删除坏文件以便重试；无参数。 */
    @Test fun badDigestDoesNotBecomeCompletedFile() = runBlocking {
        val file = temporary.newFile()
        val client = HubDownloadClient(OkHttpClient.Builder().addInterceptor { reply(it.request(), 200, "abcdef") }.build())
        assertTrue(runCatching { client.download(HubDownloadClient.DEFAULT_SOURCE, revision, HubFile("weights.mnn", 6, "0".repeat(64)), file, {}, {}) }.isFailure)
        assertFalse(file.exists())
    }

    /** 模型版本解析后所有分页固定提交，并包含末页文件；无参数。 */
    @Test fun snapshotPinsCommitAndReadsEveryPage() = runBlocking {
        val requests = mutableListOf<String>()
        val client = HubDownloadClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.toString()
            when {
                request.url.encodedPath.endsWith("/revision/main") -> reply(request, 200, """{"sha":"$revision"}""")
                request.url.queryParameter("cursor") == "next" -> reply(request, 200, """[{"type":"file","path":"visual.mnn.weight","size":7,"lfs":{"oid":"${"b".repeat(64)}"}}]""")
                else -> reply(request, 200, """[{"type":"file","path":"config.json","size":2}]""").newBuilder()
                    .header("Link", "<${request.url}&cursor=next>; rel=\"next\"").build()
            }
        }.build())
        val snapshot = client.snapshot(HubDownloadClient.DEFAULT_SOURCE)
        assertEquals(revision, snapshot.revision)
        assertEquals(listOf("config.json", "visual.mnn.weight"), snapshot.files.map { it.path })
        assertTrue(requests.drop(1).all { "/tree/$revision" in it })
    }

    /** 协程取消必须调用底层 Call.cancel，且无需等待网络超时；无参数。 */
    @Test fun coroutineCancellationCancelsSocketCall() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val client = HubDownloadClient(OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            cancelled.set(chain.call().isCanceled())
            throw IOException("fixture cancelled")
        }.build())
        val job = launch(Dispatchers.IO) { client.snapshot(HubDownloadClient.DEFAULT_SOURCE) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        withTimeout(3000) { while (!cancelled.get()) delay(10) }
        assertTrue(cancelled.get())
    }
}
