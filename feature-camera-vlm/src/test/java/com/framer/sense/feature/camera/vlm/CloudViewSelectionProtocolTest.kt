package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.CloudViewSelectionProtocol
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CloudViewSelectionProtocolTest {
    private fun selection(frameId: String = "frame-5") = ViewSelection(scanId = "scan",
        primary = ViewCandidate(frameId, "树木和建筑", "背景留白适合人物入镜", emptyList(), emptyList(), emptyList()), alternatives = emptyList())

    @Test fun oneSixAndEightFramesReceiveCompleteSchemaAndExactFrameIds() {
        listOf(1, 2, 3, 6, 8).forEach { count ->
            val images = (0 until count).map { ViewImage("frame-$it", "unused", false, 10, 10) }
            val prompt = CloudViewSelectionProtocol.prompt("scan", images, "", "")
            val example = prompt.substringAfter("complete structure: ").substringBefore(". The example frameId")
            val reply = CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", example), "scan", images.map { it.frameId }.toSet())
            assertNotNull(reply.selection)
            assertEquals(minOf(3, count) - 1, reply.selection!!.alternatives.size)
            assertTrue(prompt.contains("Rank the best ${minOf(3, count)} distinct frames"))
            images.forEach { assertTrue(prompt.contains(it.frameId)) }
            assertTrue(prompt.contains("${count} supplied images"))
        }
    }

    @Test fun rankedAlternativesKeepTheirOwnBackgroundAndOrder() {
        val ranked = selection("frame-2").copy(alternatives = listOf(
            selection("frame-5").primary.copy(backgroundRegions = listOf(CropRect(.2f, .1f, .8f, .9f))),
            selection("frame-0").primary))
        val result = CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", VlmJson.encodeToString(ranked)),
            "scan", setOf("frame-0", "frame-2", "frame-5")).selection!!
        assertEquals(listOf("frame-2", "frame-5", "frame-0"), (listOf(result.primary) + result.alternatives).map { it.frameId })
        assertEquals(ranked.alternatives[0].backgroundRegions, result.alternatives[0].backgroundRegions)
    }

    @Test fun sixFrameReplyWithCodeFenceIsAcceptedButInventedIdsAndInvalidRegionsAreRejected() {
        val sentIds = (0 until 6).map { "frame-$it" }.toSet()
        val raw = VlmJson.encodeToString(selection())
        assertEquals("frame-5", CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", "```json\n$raw\n```"), "scan", sentIds).selection!!.primary.frameId)
        assertEquals("INVALID_VIEW_FRAME", CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", VlmJson.encodeToString(selection("6"))), "scan", sentIds).reason)
        val invalid = selection().let { it.copy(primary = it.primary.copy(backgroundRegions = listOf(CropRect(right = 60f)))) }
        assertEquals("INVALID_VIEW_REGION", CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", VlmJson.encodeToString(invalid)), "scan", sentIds).reason)
    }

    @Test fun missingFieldsAndBlankDescriptionsHaveSpecificSafeReasons() {
        val missing = """{"version":2,"scanId":"scan","primary":{"frameId":"frame-5","subject":"背景","reason":"留白","backgroundRegions":[],"subjectRegions":[]},"alternatives":[]}"""
        assertEquals("MISSING_CANDIDATE_FIELD_uncertainties", CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", missing), "scan", setOf("frame-5")).reason)
        val blank = selection().let { it.copy(primary = it.primary.copy(subject = "")) }
        assertEquals("EMPTY_VIEW_SUBJECT", CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", VlmJson.encodeToString(blank)), "scan", setOf("frame-5")).reason)
        assertEquals("INVALID_JSON_OBJECT", CloudViewSelectionProtocol.inspect(ViewSelectionReply("m", "sk-secret not-json"), "scan", setOf("frame-5")).reason)
    }
}
