package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.market.*
import com.framer.sense.feature.camera.vlm.data.DownloadPhase
import org.junit.Assert.*
import org.junit.Test

class MarketCatalogTest {
    private fun entry(name: String, tags: String = "\"Vision\"", vendor: String = "Qwen", parameters: Int = 2) =
        """{"modelName":"$name","vendor":"$vendor","size_gb":$parameters,"file_size":1234,"tags":[$tags],"sources":{"ModelScope":"MNN/$name","HuggingFace":"taobao-mnn/$name"}}"""

    @Test fun officialVisionCatalogDoesNotExpandPhotographyCompatibility() {
        val rows = listOf(entry(MarketCatalog.DEFAULT_MODEL), entry("unreviewed-VL-MNN", "\"Vision\",\"Think\""), entry("text-MNN", "\"Chat\""))
        val (version, models) = MarketCatalog.decode("""{"version":"23","models":[${rows.joinToString(",")}]}""")
        assertEquals(23L, version)
        assertEquals(2, models.size)
        assertTrue(models[0].verified)
        assertFalse(models[1].compatible)
        assertEquals("MNN/${MarketCatalog.DEFAULT_MODEL}", models[0].repository(MarketSource.MODELSCOPE))
        assertEquals(7, MarketCatalog.compatibleIds.size)
    }

    @Test fun combinedSearchFiltersKeepInstalledAndParameterSemantics() {
        val rows = listOf(entry(MarketCatalog.DEFAULT_MODEL), entry("Qwen3-VL-8B-Instruct-MNN", "\"Vision\",\"Think\"", parameters = 8), entry("other-VL-MNN", vendor = "Other", parameters = 1))
        val models = MarketCatalog.decode("""{"models":[${rows.joinToString(",")}]}""").second
        val installed = setOf("Qwen3-VL-8B-Instruct-MNN")
        val filter = MarketFilter(search = " QWEN3 ", tags = setOf("Think"), vendor = "Qwen", size = 2, installedOnly = true,
            downloadPhase = DownloadPhase.DOWNLOADED)
        assertEquals(installed.toList(), MarketCatalog.filter(models, filter, installed, mapOf(installed.single() to DownloadPhase.READY)).map { it.id })
        assertEquals(installed.toList(), MarketCatalog.filter(models, filter.copy(size = 1), installed).map { it.id })
        assertTrue(MarketCatalog.filter(models, filter.copy(size = 1, installedOnly = false), installed).isEmpty())
    }

    @Test fun verifiedQwenAndExplicitGemmaCandidatesStaySeparate() {
        val gemma = listOf("gemma-4-E2B-it-MNN", "gemma-4-E4B-it-MNN")
        val rows = listOf(entry(MarketCatalog.DEFAULT_MODEL), entry("Qwen3-VL-4B-Instruct-MNN"),
            entry("Qwen3-VL-8B-Instruct-MNN")) + gemma.map { entry(it, "\"Vision\",\"Audio\"", "Gemma") } +
            listOf(entry("gemma-4-26B-A4B-it-MNN", vendor = "Gemma"),
                entry("gemma-4-31B-it-MNN", vendor = "Gemma"), entry("gemma-future-VL-MNN", vendor = "Gemma"))
        val models = MarketCatalog.decode("""{"models":[${rows.joinToString(",")}]}""").second.associateBy { it.id }
        assertTrue(models.getValue(MarketCatalog.DEFAULT_MODEL).verified)
        assertTrue(models.getValue("Qwen3-VL-4B-Instruct-MNN").verified)
        assertFalse(models.getValue("Qwen3-VL-8B-Instruct-MNN").verified)
        gemma.forEach { id ->
            val model = models.getValue(id)
            assertTrue(model.compatible)
            assertEquals(id == "gemma-4-E2B-it-MNN", model.verified)
            assertEquals("MNN/$id", model.repository(MarketSource.MODELSCOPE))
            assertEquals("taobao-mnn/$id", model.repository(MarketSource.HUGGING_FACE))
        }
        listOf("gemma-4-26B-A4B-it-MNN", "gemma-4-31B-it-MNN", "gemma-future-VL-MNN").forEach {
            assertFalse(models.getValue(it).compatible)
        }
    }

    @Test fun sourceDefaultsAndUnsafeRepositoriesAreExplicit() {
        assertEquals(MarketSource.MODELSCOPE, MarketSource.defaultFor("zh"))
        assertEquals(MarketSource.HUGGING_FACE, MarketSource.defaultFor("en"))
        assertTrue(runCatching { MarketCatalog.decode("""{"models":[{"modelName":"bad","tags":["Vision"],"sources":{"ModelScope":"../../private"}}]}""") }.isFailure)
    }

    @Test fun onlyRequestedQwen35ModelsAreOpenedAsUnverifiedCandidates() {
        val candidates = listOf("Qwen3.5-2B-MNN", "Qwen3.5-4B-MNN")
        val blocked = listOf("Qwen3.5-0.8B-MNN", "Qwen3.5-9B-MNN", "Qwen3.5-2B-Claude-4.6-Opus-Reasoning-Distilled-MNN")
        val rows = (candidates + blocked).map { entry(it, "\"Vision\",\"Think\"") }
        val models = MarketCatalog.decode("""{"models":[${rows.joinToString(",")}]}""").second.associateBy { it.id }
        candidates.forEach { id ->
            val model = models.getValue(id)
            assertTrue(model.compatible)
            assertFalse(model.verified)
            assertEquals("MNN/$id", model.repository(MarketSource.MODELSCOPE))
            assertEquals("taobao-mnn/$id", model.repository(MarketSource.HUGGING_FACE))
        }
        blocked.forEach { assertFalse(models.getValue(it).compatible) }
    }

    @Test fun downloadedViewIgnoresOtherFiltersAndRuntimeFailures() {
        val rows = listOf(entry(MarketCatalog.DEFAULT_MODEL), entry("gemma-4-E2B-it-MNN", vendor = "Gemma"), entry("unfinished"))
        val models = MarketCatalog.decode("""{"models":[${rows.joinToString(",")}]}""").second
        val installed = setOf(MarketCatalog.DEFAULT_MODEL, "gemma-4-E2B-it-MNN")
        val filter = MarketFilter(search = "no match", tags = setOf("Video"), vendor = "Other", size = 3,
            downloadPhase = DownloadPhase.FAILED, installedOnly = true)
        for (phase in listOf(DownloadPhase.DOWNLOADED, DownloadPhase.LOADING, DownloadPhase.READY, DownloadPhase.LOAD_FAILED, DownloadPhase.IDLE)) {
            assertEquals(installed, MarketCatalog.filter(models, filter, installed,
                mapOf(MarketCatalog.DEFAULT_MODEL to phase, "unfinished" to DownloadPhase.FAILED)).map { it.id }.toSet())
        }
    }

    @Test fun installedModelsRemainVisibleWhenCatalogOmitsThem() {
        val record = com.framer.sense.feature.camera.vlm.data.InstalledModel("gemma-4-E2B-it-MNN", "download-gemma",
            "https://modelscope.cn", "MNN/gemma-4-E2B-it-MNN")
        val all = MarketCatalog.withInstalled(emptyList(), listOf(record, record.copy(directory = "download-older")))
        assertEquals(1, all.size)
        assertEquals("MNN/gemma-4-E2B-it-MNN", all.single().repository(MarketSource.MODELSCOPE))
        assertEquals(listOf(record.id), MarketCatalog.filter(all, MarketFilter(installedOnly = true), setOf(record.id)).map { it.id })
    }

    @Test fun advancedIndicatorTracksEveryOptionExceptDownloadedView() {
        assertFalse(MarketFilter().hasOptions)
        assertFalse(MarketFilter(installedOnly = true).hasOptions)
        listOf(MarketFilter(search = "qwen"), MarketFilter(tags = setOf("Vision")), MarketFilter(vendor = "Gemma"),
            MarketFilter(size = 0), MarketFilter(downloadPhase = DownloadPhase.PAUSED)).forEach { assertTrue(it.hasOptions) }
    }

    @Test fun vendorFiltersPutRequestedSuppliersFirstWithoutInventingModels() {
        val rows = listOf(entry("other-VL-MNN", vendor = "Other"), entry("gemma-VL-MNN", vendor = "Gemma"),
            entry(MarketCatalog.DEFAULT_MODEL, vendor = "qwen"), entry("deepseek-VL-MNN", vendor = "DeepSeek"))
        val models = MarketCatalog.decode("""{"models":[${rows.joinToString(",")}]}""").second
        assertEquals(listOf("qwen", "DeepSeek", "Gemma", "GPT", "Other"), MarketCatalog.vendors(models))
        assertEquals(listOf(MarketCatalog.DEFAULT_MODEL), MarketCatalog.filter(models, MarketFilter(vendor = "Qwen"), emptySet()).map { it.id })
        assertTrue(MarketCatalog.filter(models, MarketFilter(vendor = "GPT"), emptySet()).isEmpty())
    }
}
