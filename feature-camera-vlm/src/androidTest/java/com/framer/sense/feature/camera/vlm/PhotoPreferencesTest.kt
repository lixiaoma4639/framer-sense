package com.framer.sense.feature.camera.vlm

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.data.SettingsStore
import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PhotoPreferencesTest {
    @Test fun preferencesSurviveNewStoreWhileArRemainsSessionScoped() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            val prefix = UUID.randomUUID().toString()
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val settings = SettingsStore(context)
        val expected = PhotoPreferences(CaptureAspectRatio.NINE_SIXTEEN, AvatarId.ADULT_MALE,
            AvatarEthnicity.ASIAN, true, "保留建筑")
        try {
            settings.savePhotoPreferences(expected)
            assertEquals(expected, SettingsStore(context).readPhotoPreferences())
            settings.saveInstructionHistory(listOf("人物靠右", "保留建筑"))
            val restored = SettingsStore(context)
            assertEquals(listOf("人物靠右", "保留建筑"), restored.readInstructionHistory())
            restored.saveInstructionHistory(restored.readInstructionHistory().filterNot { it == "人物靠右" })
            assertEquals(listOf("保留建筑"), SettingsStore(context).readInstructionHistory())
            settings.savePhotoPreferences(expected.copy(instructionFromRecommendation = true))
            assertTrue(SettingsStore(context).readPhotoPreferences().instructionFromRecommendation)
            assertEquals(listOf("保留建筑"), SettingsStore(context).readInstructionHistory())
            assertEquals(CameraMode.NORMAL, com.framer.sense.feature.camera.vlm.ui.VlmUiState(photoPreferences = expected).cameraMode)
        } finally { context.getSharedPreferences("vlm-settings", 0).edit().clear().commit() }
    }
}
