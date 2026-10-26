package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.GeneratedPortraitStylePolicy
import org.junit.Assert.*
import org.junit.Test

class GeneratedPortraitStylePolicyTest {
    @Test fun successiveCandidatesChangeBothDimensionsAndCoverTwentyCombinationsBeforeRepeating() {
        val styles = (0 until 20).map(GeneratedPortraitStylePolicy::select)
        assertEquals(20, styles.toSet().size)
        assertEquals(5, styles.map { it.wardrobe }.toSet().size)
        assertEquals(4, styles.map { it.art }.toSet().size)
        styles.zipWithNext().forEach { (before, after) ->
            assertNotEquals(before.wardrobe, after.wardrobe)
            assertNotEquals(before.art, after.art)
        }
        assertEquals(styles.first(), GeneratedPortraitStylePolicy.select(20))
        assertEquals(styles.last(), GeneratedPortraitStylePolicy.select(-1))
    }
}
