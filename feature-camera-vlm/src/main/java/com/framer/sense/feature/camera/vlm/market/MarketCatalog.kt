// Derived from Alibaba MNN Chat; Apache-2.0. See assets/model-market/SOURCE.txt.
package com.framer.sense.feature.camera.vlm.market

import com.framer.sense.feature.camera.vlm.model.VlmJson
import com.framer.sense.feature.camera.vlm.data.InstalledModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale

@Serializable enum class MarketSource(val baseUrl: String, val catalogKey: String) {
    MODELSCOPE("https://modelscope.cn", "ModelScope"),
    HUGGING_FACE("https://huggingface.co", "HuggingFace");
    companion object {
        fun defaultFor(language: String) = if (language.equals("zh", true)) MODELSCOPE else HUGGING_FACE
    }
}

@Serializable data class MarketModel(
    val id: String,
    val vendor: String,
    val tags: List<String>,
    val sources: Map<String, String>,
    val parameters: Double = 0.0,
    val bytes: Long = 0,
    val description: String = "",
    val local: Boolean = false
) {
    val compatible: Boolean get() = local || id in MarketCatalog.compatibleIds
    val verified: Boolean get() = id in MarketCatalog.verifiedIds
    fun repository(source: MarketSource): String? = sources[source.catalogKey]
}

data class MarketFilter(
    val search: String = "",
    val tags: Set<String> = emptySet(),
    val vendor: String? = null,
    val size: Int? = null,
    val installedOnly: Boolean = false,
    val downloadPhase: com.framer.sense.feature.camera.vlm.data.DownloadPhase? = null
) {
    val hasOptions: Boolean get() = search.isNotBlank() || tags.isNotEmpty() || vendor != null || size != null || downloadPhase != null
}

/** 官方 Vision 标签仅决定展示范围；摄影兼容名单由本项目独立维护。 */
object MarketCatalog {
    const val DEFAULT_MODEL = "Qwen3-VL-2B-Instruct-MNN"
    val verifiedIds = setOf(DEFAULT_MODEL, "Qwen3-VL-4B-Instruct-MNN", "gemma-4-E2B-it-MNN")
    // 固定测试名单：远程新增 Gemma 不自动开放；超出 12 GiB 的条目仍不可下载。
    val gemmaCandidateIds = setOf("gemma-4-E2B-it-MNN", "gemma-4-E4B-it-MNN")
    val qwen35CandidateIds = setOf("Qwen3.5-2B-MNN", "Qwen3.5-4B-MNN")
    val compatibleIds = verifiedIds + "Qwen3-VL-8B-Instruct-MNN" + gemmaCandidateIds + qwen35CandidateIds
    const val REMOTE_URL = "https://meta.alicdn.com/data/mnn/apis/model_market.json"

    /** 远程目录移除条目或离线刷新时，仍能管理已经校验成功的本地安装。 */
    fun withInstalled(models: List<MarketModel>, installed: List<InstalledModel>): List<MarketModel> {
        val known = models.map { it.id }.toSet()
        return models + installed.filter { it.id !in known }.distinctBy { it.id }.map { record ->
            val source = MarketSource.entries.firstOrNull { it.baseUrl == record.source }
            val repositories = if (source != null && record.repository.isNotBlank()) mapOf(source.catalogKey to record.repository) else emptyMap()
            MarketModel(record.id, "", listOf("Vision"), repositories, local = record.id.startsWith("local-"))
        }
    }

    fun vendors(models: List<MarketModel>): List<String> {
        val priority = listOf("Qwen", "DeepSeek", "Gemma", "GPT")
        val vendors = models.map { it.vendor }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
        return priority.map { name -> vendors.firstOrNull { it.equals(name, true) } ?: name } +
            vendors.filter { vendor -> priority.none { it.equals(vendor, true) } }
    }
    fun decode(text: String): Pair<Long, List<MarketModel>> {
        require(text.toByteArray().size <= 8 * 1024 * 1024)
        val root = VlmJson.parseToJsonElement(text).jsonObject
        val rows = root["models"]!!.jsonArray
        require(rows.size <= 4096)
        val models = rows.mapNotNull { value ->
            val row = value.jsonObject
            val tags = row["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if ("Vision" !in tags) return@mapNotNull null
            val id = row["modelName"]!!.jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9_.-]{1,200}")))
            val sources = row["sources"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                .filterKeys { it in MarketSource.entries.map { source -> source.catalogKey } }
            require(sources.values.all { it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) })
            MarketModel(id, row["vendor"]?.jsonPrimitive?.content.orEmpty(), tags, sources,
                row["size_gb"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                row["file_size"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0,
                row["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        require(models.isNotEmpty() && models.map { it.id }.distinct().size == models.size)
        return (root["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) to models
    }

    fun filter(models: List<MarketModel>, filter: MarketFilter, installed: Set<String>,
        phases: Map<String, com.framer.sense.feature.camera.vlm.data.DownloadPhase> = emptyMap()): List<MarketModel> {
        val search = filter.search.trim().lowercase(Locale.ROOT)
        return models.filter { model ->
            if (filter.installedOnly) return@filter model.id in installed
            (search.isEmpty() || listOf(model.id, model.description, model.vendor).plus(model.tags)
                .any { it.lowercase(Locale.ROOT).contains(search) }) &&
                filter.tags.all { it in model.tags } && (filter.vendor == null || model.vendor.equals(filter.vendor, true)) &&
                (filter.downloadPhase == null || (if (filter.downloadPhase == com.framer.sense.feature.camera.vlm.data.DownloadPhase.DOWNLOADED && model.id in installed)
                    com.framer.sense.feature.camera.vlm.data.DownloadPhase.DOWNLOADED else phases[model.id] ?: com.framer.sense.feature.camera.vlm.data.DownloadPhase.IDLE) == filter.downloadPhase) &&
                when (filter.size) {
                    0 -> model.parameters < 1
                    1 -> model.parameters >= 1 && model.parameters < 5
                    2 -> model.parameters >= 5 && model.parameters < 15
                    3 -> model.parameters >= 15
                    else -> true
                }
        }.sortedWith(compareByDescending<MarketModel> { it.verified }.thenByDescending { it.compatible }.thenBy { it.vendor }.thenBy { it.parameters })
    }
}
