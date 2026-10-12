package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.ArFailure
import com.framer.sense.feature.camera.vlm.ar.ArServiceState
import com.google.ar.core.exceptions.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ArFailureTest {
    @Test fun unknownAvailabilityReportsServiceStateWithoutClaimingDeviceSupport() {
        assertEquals(ArFailure.SERVICE_MISSING, ArFailure.forUnknownAvailability(ArServiceState.NOT_INSTALLED))
        assertEquals(ArFailure.SERVICE_DISABLED, ArFailure.forUnknownAvailability(ArServiceState.DISABLED))
        assertEquals(ArFailure.CHECK_UNAVAILABLE, ArFailure.forUnknownAvailability(ArServiceState.AVAILABLE))
        assertEquals(ArFailure.CHECK_UNAVAILABLE, ArFailure.forUnknownAvailability(ArServiceState.QUERY_FAILED))
    }

    @Test fun serviceFailuresKeepTheirCauseThroughWrapping() {
        val failures = listOf(
            UnavailableDeviceNotCompatibleException() to ArFailure.DEVICE_UNSUPPORTED,
            UnavailableArcoreNotInstalledException() to ArFailure.SERVICE_MISSING,
            UnavailableApkTooOldException() to ArFailure.SERVICE_OUTDATED,
            UnavailableSdkTooOldException() to ArFailure.SDK_OUTDATED,
            UnavailableUserDeclinedInstallationException() to ArFailure.INSTALL_DECLINED,
            CameraNotAvailableException() to ArFailure.CAMERA_UNAVAILABLE,
            UnsupportedConfigurationException() to ArFailure.CAMERA_CONFIG
        )
        failures.forEach { (error, expected) ->
            assertEquals(expected, ArFailure.from(error))
            assertEquals(expected, ArFailure.from(IllegalStateException("wrapped", error)))
        }
    }

    @Test fun cameraAndNativeFailuresRemainDistinctFromUnknownFailures() {
        assertEquals(ArFailure.CAMERA_PERMISSION, ArFailure.from(SecurityException()))
        assertEquals(ArFailure.CAMERA_UNAVAILABLE, ArFailure.from(IllegalStateException("AR_CAMERA_ERROR:2")))
        assertEquals(ArFailure.CAMERA_CONFIG, ArFailure.from(IllegalStateException("AR_CAMERA_CONFIG_UNAVAILABLE")))
        assertEquals(ArFailure.NATIVE_INITIALIZATION,
            ArFailure.from(IllegalStateException("AR_NATIVE_INITIALIZATION_FAILED", UnsatisfiedLinkError())))
        assertEquals(ArFailure.SESSION_START, ArFailure.from(IllegalStateException("unknown")))
    }
}
