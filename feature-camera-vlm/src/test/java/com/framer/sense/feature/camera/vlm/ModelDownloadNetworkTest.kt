package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.data.ModelDownloadNetwork
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadNetworkTest {
    @Test fun wifiIsAllowedEvenIfSystemMarksItMetered() {
        assertFalse(ModelDownloadNetwork.requiresConfirmation(wifi = true, cellular = false, metered = false))
        assertFalse(ModelDownloadNetwork.requiresConfirmation(wifi = true, cellular = false, metered = true))
    }

    @Test fun cellularAlwaysNeedsConfirmationIncludingUnmeteredDataPlans() {
        assertTrue(ModelDownloadNetwork.requiresConfirmation(wifi = false, cellular = true, metered = true))
        assertTrue(ModelDownloadNetwork.requiresConfirmation(wifi = false, cellular = true, metered = false))
        assertTrue(ModelDownloadNetwork.requiresConfirmation(wifi = true, cellular = true, metered = false))
    }

    @Test fun otherNetworksKeepCostProtection() {
        assertFalse(ModelDownloadNetwork.requiresConfirmation(wifi = false, cellular = false, metered = false))
        assertTrue(ModelDownloadNetwork.requiresConfirmation(wifi = false, cellular = false, metered = true))
    }
}
