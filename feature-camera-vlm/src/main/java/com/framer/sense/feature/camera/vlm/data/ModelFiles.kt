package com.framer.sense.feature.camera.vlm.data

import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 文件操作失败；界面根据资源编号本地化，不直接展示网络响应或底层异常。
 * @param resource 中文错误文案资源。
 * @param detail 可公开的文件名或状态码，不包含认证信息。
 */
class ModelStorageException(val resource: Int, val detail: String = "") : IOException(detail)

/** 官方模型目录与下载文件共用的检查工具，不解释为模型推理验收。 */
object ModelFiles {
    const val RESERVE_BYTES = 64L * 1024 * 1024
    const val MAX_BYTES = 12L * 1024 * 1024 * 1024

    /** 解析安全相对路径。@param root 私有根目录。@param path 相对文件路径。@return 根目录内部文件。 */
    fun child(root: File, path: String): File {
        if (path.isBlank() || path.startsWith('/') || '\\' in path || ':' in path || path.split('/').any { it.isEmpty() || it == "." || it == ".." })
            throw ModelStorageException(R.string.vlm_model_path)
        return File(root, path).canonicalFile.also {
            if (!it.path.startsWith(root.canonicalPath + File.separator)) throw ModelStorageException(R.string.vlm_model_path)
        }
    }

    /** 检查剩余空间。@param available 可用字节数。@param needed 即将写入的字节数。 */
    fun checkSpace(available: Long, needed: Long) {
        if (needed < 0 || available - RESERVE_BYTES < needed) throw ModelStorageException(R.string.vlm_model_space)
    }

    /** 校验下载内容和可验证摘要；所有流在返回前关闭。
     * @param file 实际文件。
     * @param size 预期字节数。
     * @param sha256 官方 LFS SHA-256；未知时传 null，不用 ETag 或 Xet 标识冒充摘要。
     * @param gitSha1 普通 Git 文件的 blob SHA-1；LFS 指针的 SHA-1 不能用于校验实际权重。
     */
    suspend fun verify(file: File, size: Long, sha256: String? = null, gitSha1: String? = null) {
        if (!file.isFile || size < 0 || file.length() != size) throw ModelStorageException(R.string.vlm_model_file, file.name)
        val header = file.inputStream().use { it.readNBytesCompat(256).toString(Charsets.UTF_8).trimStart().lowercase() }
        if (header.startsWith("version https://git-lfs.github.com/spec/") || header.startsWith("<!doctype html") || header.startsWith("<html"))
            throw ModelStorageException(R.string.vlm_model_file, file.name)
        if (sha256 != null || gitSha1 != null) {
            val expected = sha256 ?: gitSha1!!
            if (!expected.matches(Regex(if (sha256 != null) "[a-fA-F0-9]{64}" else "[a-fA-F0-9]{40}"))) throw ModelStorageException(R.string.vlm_model_metadata)
            val hash = MessageDigest.getInstance(if (sha256 != null) "SHA-256" else "SHA-1")
            if (sha256 == null) hash.update("blob $size\u0000".toByteArray(Charsets.UTF_8))
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            if (!hash.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) throw ModelStorageException(R.string.vlm_model_hash, file.name)
        }
    }

    /** 读取少量前缀，兼容 API 24。@param length 最大字节数。@return 实际读取内容；调用方关闭输入流。 */
    private fun java.io.InputStream.readNBytesCompat(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(buffer, offset, length - offset)
            if (count <= 0) break
            offset += count
        }
        return buffer.copyOf(offset)
    }

    /** 按 MNN 3.6.1 检查目录，支持共享嵌入；不强制额外架构字段。
     * @param dir 含 config.json 的私有模型目录。
     */
    suspend fun validate(dir: File) {
        val config = readConfig(child(dir, "config.json"))
        val info = readConfig(child(dir, config["llm_config"]?.jsonPrimitive?.content ?: "llm_config.json"))
        val merged = JsonObject(config + info)
        if (merged["is_visual"]?.jsonPrimitive?.booleanOrNull != true || merged["is_audio"]?.jsonPrimitive?.booleanOrNull == true || merged["is_single"]?.jsonPrimitive?.booleanOrNull == false)
            throw ModelStorageException(R.string.vlm_model_visual)
        checkPaths(config, dir)
        checkPaths(info, dir)
        val defaults = mapOf("llm_model" to "llm.mnn", "llm_weight" to "llm.mnn.weight", "tokenizer_file" to "tokenizer.txt", "visual_model" to "visual.mnn")
        defaults.forEach { (key, fallback) -> requireFile(child(dir, merged[key]?.jsonPrimitive?.content ?: fallback)) }
        // DiskEmbedding::parseTieEmbeddings：有效列表首项或对象 weight_offset 非零时复用 llm_weight。
        val tie = merged["tie_embeddings"]
        val offset = when (tie) {
            is JsonArray -> if (tie.size >= 5) tie[0].jsonPrimitive.longOrNull else null
            is JsonObject -> tie["weight_offset"]?.jsonPrimitive?.longOrNull ?: 0L
            else -> null
        }
        if (offset == null || offset == 0L) requireFile(child(dir, merged["embedding_file"]?.jsonPrimitive?.content ?: "embeddings_bf16.bin"))
        // 外置视觉权重由 MNN 图决定；官方 Qwen 包带此文件，清单下载时可发现缺失。
        val visual = child(dir, merged["visual_model"]?.jsonPrimitive?.content ?: "visual.mnn")
        if (File(visual.path + ".weight").exists()) requireFile(File(visual.path + ".weight"))
    }

    /** 检查配置路径，未使用的 embedding_file 只检查边界，不误判为缺文件。
     * @param config 配置节点。@param dir 模型根目录。
     */
    private fun checkPaths(config: JsonObject, dir: File) {
        config.forEach { (key, value) ->
            if (key in setOf("base_dir", "tmp_path", "prefix_cache_path", "draft_model", "npu_model_dir")) throw ModelStorageException(R.string.vlm_model_config, key)
            if (key == "speculative_type" && value.jsonPrimitive.content != "none") throw ModelStorageException(R.string.vlm_model_config, key)
            if (value is JsonObject) checkPaths(value, dir)
            if (value is JsonPrimitive && (key.endsWith("_file") || key.endsWith("_model") || key.endsWith("_weight") || key == "llm_config")) child(dir, value.content)
        }
    }

    /** 读取有大小限制的 JSON 对象。@param file 配置文件。@return JSON 配置；格式错误统一转为本地化错误。 */
    private fun readConfig(file: File): JsonObject {
        if (!file.isFile || file.length() !in 1..2L * 1024 * 1024) throw ModelStorageException(R.string.vlm_model_config, file.name)
        return try { VlmJson.parseToJsonElement(file.readText()).jsonObject }
        catch (_: Exception) { throw ModelStorageException(R.string.vlm_model_config, file.name) }
    }

    /** 检查实际运行文件存在且非空。@param file 运行所需文件。 */
    private suspend fun requireFile(file: File) {
        if (!file.isFile || file.length() == 0L) throw ModelStorageException(R.string.vlm_model_file, file.name)
        verify(file, file.length())
    }
}
