package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.GeneratedPortraitStylePolicy
import org.junit.Assert.*
import org.junit.Test

class GeneratedPortraitStylePolicyTest {
    @Test fun fallbackCursorCoversTheExpandedPoolsWithoutSkippingCombinations() {
        val period = GeneratedPortraitStylePolicy.cycleLength
        val styles = (0 until period).map(GeneratedPortraitStylePolicy::select)
        assertTrue(GeneratedPortraitStylePolicy.wardrobeCount >= 30)
        assertTrue(GeneratedPortraitStylePolicy.artCount >= 30)
        assertEquals(period, styles.toSet().size)
        assertEquals(GeneratedPortraitStylePolicy.wardrobeCount, styles.map { it.wardrobe }.toSet().size)
        assertEquals(GeneratedPortraitStylePolicy.artCount, styles.map { it.art }.toSet().size)
        styles.zipWithNext().forEach { (before, after) ->
            assertNotEquals(before.wardrobe, after.wardrobe)
            assertNotEquals(before.art, after.art)
        }
        assertEquals(styles.first(), GeneratedPortraitStylePolicy.select(period))
        assertEquals(styles.last(), GeneratedPortraitStylePolicy.select(-1))
    }
}
