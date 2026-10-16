package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.InstructionHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionHistoryTest {
    @Test fun latestUserTextMovesToFrontWithoutDuplicatesOrBlankEntries() {
        val history = listOf("保留建筑", "居中拍摄")
        assertEquals(listOf("居中拍摄", "保留建筑"), InstructionHistory.record(history, " 居中拍摄 ", false))
        assertEquals(listOf("人物靠右", "保留建筑", "居中拍摄"), InstructionHistory.record(history, "人物靠右", false))
        assertEquals(history, InstructionHistory.record(history, "  ", false))
        assertEquals(listOf("保留建筑"), InstructionHistory.normalize(listOf(" 保留建筑 ", "", "保留建筑")))
    }

    @Test fun unchangedRecommendationDoesNotBecomeOrPromoteHistory() {
        val history = listOf("保留建筑", "居中拍摄")
        assertEquals(history, InstructionHistory.record(history, "推荐指导", true))
        assertEquals(history, InstructionHistory.record(history, "居中拍摄", true))
    }
}
