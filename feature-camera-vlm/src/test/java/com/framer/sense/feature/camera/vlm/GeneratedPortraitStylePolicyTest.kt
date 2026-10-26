package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.GeneratedPortraitStylePolicy
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GeneratedPortraitStylePolicyTest {
    @Test fun randomDrawsExcludeTheWholeHistoryAndExhaustEveryCombinationWithoutRepeats() {
        val count = GeneratedPortraitStylePolicy.combinationCount
        val used = mutableSetOf<Int>()
        val styles = mutableSetOf<GeneratedPortraitStylePolicy.Style>()
        val random = Random(20261026)
        assertTrue(GeneratedPortraitStylePolicy.wardrobeCount >= 30)
        assertTrue(GeneratedPortraitStylePolicy.artCount >= 30)
        repeat(count) {
            val index = GeneratedPortraitStylePolicy.nextIndex(used, random)!!
            assertTrue(index in 0 until count)
            assertTrue(used.add(index))
            assertTrue(styles.add(GeneratedPortraitStylePolicy.select(index)))
        }
        assertEquals(count, styles.size)
        assertNull(GeneratedPortraitStylePolicy.nextIndex(used, random))
        assertEquals(GeneratedPortraitStylePolicy.wardrobeCount, styles.map { it.wardrobe }.toSet().size)
        assertEquals(GeneratedPortraitStylePolicy.artCount, styles.map { it.art }.toSet().size)
        // 新流程不继承上一流程已用集合。
        assertNotNull(GeneratedPortraitStylePolicy.nextIndex(emptySet(), random))
    }

    @Test fun oneRemainingCombinationIsReturnedInsteadOfRepeatingAnEarlierOne() {
        val remaining = 17
        val used = (0 until GeneratedPortraitStylePolicy.combinationCount).filterNot { it == remaining }.toSet()
        assertEquals(remaining, GeneratedPortraitStylePolicy.nextIndex(used, Random(1)))
    }
}
