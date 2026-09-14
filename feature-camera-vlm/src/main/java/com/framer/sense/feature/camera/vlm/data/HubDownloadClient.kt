package com.framer.sense.feature.camera.vlm.data

import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class HubFile(val path: String, val size: Long, val sha256: String? = null, val gitSha1: String? = null)
@Serializable data class HubSnapshot(val revision: String, val files: List<HubFile>)

/** 使用官方 Hub HTTP 接口读取固定版本快照和流式权重；不依赖 Python 或 Retrofit。
 * @param client 下载专用 OkHttp 客户端；测试可注入模拟拦截器。
 */
class HubDownloadClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .followSslRedirects(false).build()) {
    companion object { const val MODEL = "taobao-mnn/Qwen3-VL-2B-Instruct-MNN"; const val DEFAULT_SOURCE = "https://huggingface.co" }

    /** 规范化下载源。@param source 用户提供的 HTTPS Hub 兼容地址。@return 无结尾斜杠的地址。 */
    fun normalizeSource(source: String): String {
        val url = try { source.trim().toHttpUrl() } catch (_: Exception) { throw ModelStorageException(R.string.vlm_download_source_invalid) }
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
            throw ModelStorageException(R.string.vlm_download_source_invalid)
        return url.toString().trimEnd('/')
    }

    /** 获取提交版本及全部分页文件清单。@param source 已配置下载源。@return 固定提交及文件大小、可验证的 LFS 摘要。 */
    suspend fun snapshot(source: String): HubSnapshot {
        val base = normalizeSource(source)
        val info = json("$base/api/models/$MODEL/revision/main").first.jsonObject
        val revision = info["sha"]?.jsonPrimitive?.content ?: throw ModelStorageException(R.string.vlm_model_metadata)
        if (!revision.matches(Regex("[a-fA-F0-9]{40,64}"))) throw ModelStorageException(R.string.vlm_model_metadata)
        var next: String? = "$base/api/models/$MODEL/tree/$revision?recursive=true&expand=false"
        val visited = mutableSetOf<String>()
        val entries = mutableListOf<HubFile>()
        while (next != null) {
            if (!visited.add(next) || visited.size > 100) throw ModelStorageException(R.string.vlm_model_metadata)
            val (body, link) = json(next)
            body.jsonArray.forEach { node ->
                val entry = node.jsonObject
                if (entry["type"]?.jsonPrimitive?.content == "file") {
                    val lfs = entry["lfs"] as? JsonObject
                    val path = entry["path"]!!.jsonPrimitive.content
                    // 仅保留官方模型目录文件；元信息、许可文件也保留，避免遗漏隐式依赖。
                    if (path.length > 1024 || entries.size >= 4096) throw ModelStorageException(R.string.vlm_model_metadata)
                    val size = entry["size"]!!.jsonPrimitive.long
                    val digest = lfs?.get("oid")?.jsonPrimitive?.content
                    if (size < 0 || size > ModelFiles.MAX_BYTES) throw ModelStorageException(R.string.vlm_model_metadata)
                    entries += HubFile(path, size, digest, if (lfs == null) entry["oid"]?.jsonPrimitive?.content else null)
                }
            }
            next = nextPage(link, next)
            if (next != null) {
                val parsed = next!!.toHttpUrl()
                val origin = base.toHttpUrl()
                if (parsed.scheme != origin.scheme || parsed.host != origin.host || parsed.port != origin.port || !parsed.encodedPath.startsWith(origin.encodedPath.trimEnd('/') + "/api/models/$MODEL/tree/$revision"))
                    throw ModelStorageException(R.string.vlm_model_metadata)
            }
        }
        if (entries.isEmpty() || entries.size > 4096 || entries.map { it.path }.toSet().size != entries.size || entries.sumOf { it.size } > ModelFiles.MAX_BYTES)
            throw ModelStorageException(R.string.vlm_model_metadata)
        return HubSnapshot(revision, entries)
    }

    /** 提取下一页 Link，解析相对地址。@param link HTTP Link 头。@param current 当前请求地址。@return 下一页或 null。 */
    internal fun nextPage(link: String?, current: String): String? {
        val match = Regex("<([^>]+)>\\s*;\\s*rel=\"?next\"?").find(link.orEmpty()) ?: return null
        return current.toHttpUrl().resolve(match.groupValues[1])?.toString() ?: throw ModelStorageException(R.string.vlm_model_metadata)
    }

    /** 有大小上限地读取 Hub 元数据。@param url HTTPS 地址。@return JSON 及分页头，不记录响应正文。 */
    private suspend fun json(url: String): Pair<JsonElement, String?> = exchange(Request.Builder().url(url).build()) { response ->
        if (!response.isSuccessful) throw ModelStorageException(R.string.vlm_download_http, response.code.toString())
        val body = response.body ?: throw ModelStorageException(R.string.vlm_model_metadata)
        val buffer = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(chunk)
                if (n < 0) break
                if (buffer.size() + n > 8 * 1024 * 1024) throw ModelStorageException(R.string.vlm_model_metadata)
                buffer.write(chunk, 0, n)
            }
        }
        VlmJson.parseToJsonElement(buffer.toString("UTF-8")) to response.header("Link")
    }

    /** 下载一个文件；Range 被忽略时从零写入，流在退出时关闭。
     * @param source 下载源。@param revision 固定提交。@param entry 文件清单项。
     * @param file 私有临时文件。@param checkNetwork 每块写入前检查网络约束。
     * @param progress 当前文件已写入字节数，服务端重置时可能回退。
     */
    suspend fun download(source: String, revision: String, entry: HubFile, file: File, checkNetwork: () -> Unit, progress: (Long) -> Unit) {
        checkNetwork()
        file.parentFile?.mkdirs()
        if (file.length() > entry.size) file.delete()
        if (file.exists() && file.length() == entry.size) {
            try { ModelFiles.verify(file, entry.size, entry.sha256, entry.gitSha1); progress(entry.size); return }
            catch (_: ModelStorageException) { file.delete() }
        }
        val offset = file.length()
        val url = (normalizeSource(source) + "/$MODEL/resolve/$revision/").toHttpUrl().newBuilder().addPathSegments(entry.path).build()
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        exchange(request) { response ->
            val append = response.code == 206
            validateRange(response.code, response.header("Content-Range"), offset, entry.size)
            var written = if (append) offset else 0L
            ModelFiles.checkSpace(file.parentFile!!.usableSpace, entry.size - written)
            val body = response.body ?: throw ModelStorageException(R.string.vlm_model_file, entry.path)
            FileOutputStream(file, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    progress(written)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkNetwork()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (written + n > entry.size) throw ModelStorageException(R.string.vlm_model_file, entry.path)
                        ModelFiles.checkSpace(file.parentFile!!.usableSpace, n.toLong())
                        output.write(buffer, 0, n)
                        written += n
                        progress(written)
                    }
                    output.fd.sync()
                }
            }
            try { ModelFiles.verify(file, entry.size, entry.sha256, entry.gitSha1) }
            catch (error: ModelStorageException) { file.delete(); throw error }
        }
    }

    /** 验证续传响应。@param code HTTP 状态。@param range Content-Range。@param offset 请求起点。@param size 官方文件大小。 */
    internal fun validateRange(code: Int, range: String?, offset: Long, size: Long) {
        if (code == 200) return
        if (code != 206) throw ModelStorageException(R.string.vlm_download_http, code.toString())
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.orEmpty())
        if (match == null || match.groupValues[1].toLongOrNull() != offset || match.groupValues[2].toLongOrNull() != size - 1 || match.groupValues[3].toLongOrNull() != size)
            throw ModelStorageException(R.string.vlm_download_range)
    }

    /** 将 OkHttp 回调桥接到协程；读取响应期间取消也会关闭 socket。
     * @param request HTTP 请求。@param consume 在 IO 上消费响应的动作。
     * @return 动作结果；响应和取消监听在退出时释放。
     */
    private suspend fun <T> exchange(request: Request, consume: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val call = client.newCall(request)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                val response = suspendCancellableCoroutine<Response> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        /** 回传网络失败。@param call 当前请求。@param e 网络异常，不展示响应正文。 */
                        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                        /** 转交响应所有权。@param call 当前请求。@param response 网络响应，取消时关闭。 */
                        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
                    })
                }
                response.use { consume(it) }
            } finally { cancellation.cancel() }
        }
    }
}
