package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.market.MarketUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketModelStatusTest {
    private val id = "Qwen3-VL-4B-Instruct-MNN"
    private val path = "/models/4b-version-a"
    private fun state() = MarketUiState(
        installed = listOf(InstalledModel(id, path)), activeModel = id,
        downloads = mapOf(id to ModelDownloadState(modelId = id, phase = DownloadPhase.DOWNLOADED))
    )

    @Test fun downloadedAndActivePointerDoNotMeanLoaded() {
        assertEquals(DownloadPhase.DOWNLOADED, state().modelPhase(id))
    }

    @Test fun actualRuntimeUpdatesAllMarketModelsWithoutChangingDownloadRecords() {
        listOf("Qwen3-VL-2B-Instruct-MNN", id, "Qwen3-VL-8B-Instruct-MNN").forEach { model ->
            val state = MarketUiState(installed = listOf(InstalledModel(model, path)),
                downloads = mapOf(model to ModelDownloadState(modelId = model, phase = DownloadPhase.DOWNLOADED)))
            assertEquals(DownloadPhase.LOADING, state.copy(runtime = LocalModelLoadState(LocalModelPhase.LOADING, path)).modelPhase(model))
            assertEquals(DownloadPhase.READY, state.copy(runtime = LocalModelLoadState(LocalModelPhase.READY, path)).modelPhase(model))
            assertEquals(DownloadPhase.LOAD_FAILED, state.copy(runtime = LocalModelLoadState(LocalModelPhase.FAILED, path)).modelPhase(model))
            assertEquals(DownloadPhase.DOWNLOADED, state.downloads[model]?.phase)
        }
    }

    @Test fun unloadAndOtherDirectoryCannotKeepStaleReadyLabel() {
        val stale = state().copy(downloads = mapOf(id to ModelDownloadState(modelId = id, phase = DownloadPhase.READY)))
        assertEquals(DownloadPhase.DOWNLOADED, stale.modelPhase(id))
        assertEquals(DownloadPhase.DOWNLOADED,
            stale.copy(runtime = LocalModelLoadState(LocalModelPhase.UNLOADED, path)).modelPhase(id))
        assertEquals(DownloadPhase.DOWNLOADED,
            stale.copy(runtime = LocalModelLoadState(LocalModelPhase.READY, "/models/other")).modelPhase(id))
    }

    @Test fun importedAndOlderInstalledVersionsFollowTheirActualDirectory() {
        val imported = MarketUiState(installed = listOf(InstalledModel("local-import", path)),
            runtime = LocalModelLoadState(LocalModelPhase.READY, path))
        assertEquals(DownloadPhase.READY, imported.modelPhase("local-import"))
        val versions = state().copy(installed = listOf(InstalledModel(id, path), InstalledModel(id, "/models/4b-version-b")),
            runtime = LocalModelLoadState(LocalModelPhase.READY, path))
        assertEquals(DownloadPhase.READY, versions.modelPhase(id))
    }

    @Test fun downloadingAnotherVersionKeepsTransferStatus() {
        val state = state().copy(runtime = LocalModelLoadState(LocalModelPhase.READY, path),
            downloads = mapOf(id to ModelDownloadState(modelId = id, phase = DownloadPhase.DOWNLOADING)))
        assertEquals(DownloadPhase.DOWNLOADING, state.modelPhase(id))
    }
}
