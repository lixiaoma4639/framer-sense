package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.net.Uri
import com.framer.sense.feature.camera.vlm.model.VlmJson
import com.framer.sense.feature.camera.vlm.model.newVlmId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@Serializable data class ModelFile(val path: String, val size: Long, val sha256: String)
@Serializable data class ModelManifest(val model: String, val runtime: String, val files: List<ModelFile>)

/** 管理私有模型包；与模型推理的串行卸载顺序由调用方保证。 */
class LocalModelStore(
    private val context: Context,
    private val availableSpace: (File) -> Long = { it.usableSpace }
) {
    private val root = File(context.noBackupFilesDir, "vlm-models").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("vlm-model", Context.MODE_PRIVATE)

    /** 获取已激活模型目录；无参数，未安装或文件丢失返回 null。 */
    fun activeDirectory(): File? = preferences.getString("active", null)?.let { name -> File(root, name).takeIf { it.isDirectory } }

    /** 获取模型展示名称；无参数，未安装返回明确说明。 */
    fun description(): String = activeDirectory()?.let { dir ->
        runCatching { VlmJson.decodeFromString<ModelManifest>(File(dir, "manifest.json").readText()).model }.getOrDefault("模型包损坏")
    } ?: "尚未导入离线模型"

    /** 导入并激活模型 ZIP，失败保留原模型。
     * @param uri 用户在系统文件选择器中选择的 ZIP。
     * @param progress 接收已解包字节数与当前阶段说明。
     */
    suspend fun importPackage(uri: Uri, progress: (Long, String) -> Unit) = withContext(Dispatchers.IO) {
        val staging = File(root, "import-${newVlmId()}").apply { mkdirs() }
        var activated = false
        try {
            var total = 0L
            var count = 0
            val names = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri)?.use { source ->
                ZipInputStream(source.buffered()).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        require(++count <= 4096) { "模型包文件数量过多" }
                        val target = safeChild(staging, entry.name.trimEnd('/'))
                        require(names.add(entry.name)) { "模型包存在重复路径" }
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    require(total <= 12L * 1024 * 1024 * 1024) { "模型包解压后超过 12 GiB" }
                                    require(availableSpace(staging) > n + 64L * 1024 * 1024) { "可用存储空间不足" }
                                    output.write(buffer, 0, n)
                                    progress(total, "正在导入 ${entry.name}")
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("无法读取模型包")
            progress(total, "正在校验模型完整性")
            validateDirectory(staging)
            currentCoroutineContext().ensureActive()
            val old = activeDirectory()
            check(preferences.edit().putString("active", staging.name).commit()) { "无法保存模型激活信息" }
            activated = true
            old?.takeIf { it != staging }?.deleteRecursively()
            progress(total, "模型已导入，可加载验证")
        } finally {
            if (!activated) staging.deleteRecursively()
        }
    }

    /** 验证包中文件哈希和实际视觉配置。
     * @param dir 已解包的私有模型目录。
     * @return 校验通过的清单。
     */
    suspend fun validateDirectory(dir: File): ModelManifest = withContext(Dispatchers.IO) {
        val manifestFile = File(dir, "manifest.json")
        require(manifestFile.isFile && manifestFile.length() <= 1024 * 1024) { "缺少或无效的 manifest.json" }
        val manifest = VlmJson.decodeFromString<ModelManifest>(manifestFile.readText())
        require(manifest.runtime == "MNN-3.6.1" && manifest.model == "Qwen3-VL-2B-Instruct") { "首版仅支持 Qwen3-VL-2B-Instruct / MNN-3.6.1 模型包" }
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size) { "清单路径重复" }
        val expected = manifest.files.map { it.path }.toSet()
        val actual = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet() - "manifest.json"
        require(expected == actual) { "模型包文件与清单不一致" }
        for (entry in manifest.files) {
            currentCoroutineContext().ensureActive()
            val file = safeChild(dir, entry.path)
            require(file.isFile && file.length() == entry.size && entry.size > 0) { "模型文件缺失或大小不符：${entry.path}" }
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) }.equals(entry.sha256, true)) { "模型文件校验失败：${entry.path}" }
        }
        require("config.json" in expected && "llm_config.json" in expected) { "缺少模型配置" }
        require(listOf("config.json", "llm_config.json").all { File(dir, it).length() <= 2 * 1024 * 1024 }) { "模型配置过大" }
        val config = VlmJson.parseToJsonElement(File(dir, "config.json").readText()).jsonObject
        val info = VlmJson.parseToJsonElement(File(dir, "llm_config.json").readText()).jsonObject
        val merged = config + info
        require(merged["is_visual"]?.jsonPrimitive?.booleanOrNull == true) { "模型未启用视觉输入" }
        require(merged["model_type"]?.jsonPrimitive?.content == "qwen3_vl") { "模型架构必须为 qwen3_vl" }
        require(merged["is_single"]?.jsonPrimitive?.booleanOrNull != false && merged["is_audio"]?.jsonPrimitive?.booleanOrNull != true) { "首版仅支持单体图文模型" }
        require((config["llm_config"]?.jsonPrimitive?.content ?: "llm_config.json") == "llm_config.json") { "模型元配置必须为 llm_config.json" }
        validateConfigPaths(config, dir, expected)
        validateConfigPaths(info, dir, expected)
        if (merged["tie_embeddings"] == null) {
            val embedding = merged["embedding_file"]?.jsonPrimitive?.content ?: "embeddings_bf16.bin"
            require(embedding in expected) { "缺少词嵌入文件" }
        }
        mapOf("llm_config" to "llm_config.json", "llm_model" to "llm.mnn", "llm_weight" to "llm.mnn.weight", "tokenizer_file" to "tokenizer.txt", "visual_model" to "visual.mnn").forEach { (key, fallback) ->
            val path = merged[key]?.jsonPrimitive?.content ?: fallback
            require(path in expected && safeChild(dir, path).isFile) { "缺少 $key 对应文件" }
        }
        manifest
    }

    /** 递归检查运行配置，拒绝越界路径和首版未启用的后端。
     * @param config 当前 JSON 配置对象。
     * @param dir 私有解包目录。
     * @param files 清单中可用文件集合。
     */
    private fun validateConfigPaths(config: JsonObject, dir: File, files: Set<String>) {
        config.forEach { (key, value) ->
            if (value is JsonObject) validateConfigPaths(value, dir, files)
            require(key !in setOf("base_dir", "tmp_path", "prefix_cache_path", "draft_model", "npu_model_dir")) { "首版不支持配置 $key" }
            if (key == "backend_type") require(value.jsonPrimitive.content == "cpu") { "首版仅支持 CPU 后端" }
            if (key == "speculative_type") require(value.jsonPrimitive.content == "none") { "首版不支持推测解码" }
            if (key.endsWith("_file") || key.endsWith("_model") || key.endsWith("_weight") || key == "llm_config") {
                val path = value.jsonPrimitive.content
                require(safeChild(dir, path).isFile && path in files) { "配置引用未收录的文件：$key" }
            }
        }
    }

    /** 删除已经卸载的模型；无参数，先清除激活指针再回收文件。 */
    suspend fun delete() = withContext(Dispatchers.IO) {
        val old = activeDirectory()
        check(preferences.edit().remove("active").commit()) { "无法清除模型配置" }
        old?.deleteRecursively()
        Unit
    }

    companion object {
        /** 将包内路径解析到指定根目录，拒绝路径穿越。
         * @param root 可信解包根目录。
         * @param path 包内相对路径。
         * @return 保证位于根目录下的文件。
         */
        fun safeChild(root: File, path: String): File {
            require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').none { it.isEmpty() || it == ".." || it == "." }) { "模型包包含非法路径" }
            val file = File(root, path).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator)) { "模型包路径越界" }
            return file
        }
    }
}
