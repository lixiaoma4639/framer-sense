package com.framer.sense.feature.camera.vlm

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.MediaStore
import com.framer.sense.feature.camera.vlm.ui.directGalleryActivity
import com.framer.sense.feature.camera.vlm.ui.directGalleryIntent
import org.junit.Assert.*
import org.junit.Test

class CameraGalleryPlatformTest {
    @Test fun launchIntentAlwaysNamesOneActivityAndDoesNotLaunchAChooserOrAnotherTask() {
        val target = ComponentName("test.gallery", "test.gallery.Main")
        val main = directGalleryIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY), target)
        assertEquals(target, main.component)
        assertEquals(Intent.ACTION_MAIN, main.action)
        assertTrue(main.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertFalse(main.hasCategory(Intent.CATEGORY_APP_GALLERY))
        assertNull(main.selector)
        assertEquals(0, main.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        val directory = directGalleryIntent(Intent(Intent.ACTION_VIEW).setDataAndType(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/image"), target)
        assertEquals(target, directory.component)
        assertEquals(Intent.ACTION_VIEW, directory.action)
        assertEquals("vnd.android.cursor.dir/image", directory.type)
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, directory.data)
        assertNull(directory.selector)
        assertEquals(0, directory.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun realDefaultIsUsedButResolverCannotBeChosenAsGallery() {
        val thirdParty = gallery("test.gallery.other", priority = 100)
        val system = gallery("test.gallery.system", flags = ApplicationInfo.FLAG_SYSTEM)
        assertEquals(component(thirdParty), directGalleryActivity(listOf(system, thirdParty), thirdParty))
        val resolver = gallery("android").apply { activityInfo.name = "com.android.internal.app.ResolverActivity" }
        assertEquals(component(system), directGalleryActivity(listOf(thirdParty, system), resolver))
    }

    @Test fun updatedSystemGalleryWinsAndCandidateOrderCannotCauseAChooser() {
        val thirdParty = gallery("test.gallery.other", priority = 100)
        val updated = gallery("test.gallery.updated", flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        assertEquals(component(updated), directGalleryActivity(listOf(thirdParty, updated), null))
        assertEquals(component(updated), directGalleryActivity(listOf(updated, thirdParty), null))
        val another = gallery("test.gallery.another", priority = 100)
        assertEquals(directGalleryActivity(listOf(thirdParty, another), null),
            directGalleryActivity(listOf(another, thirdParty), null))
    }

    @Test fun disabledOrUnexportedGalleryIsSkippedAndMissingGalleryReturnsNoTarget() {
        val disabled = gallery("test.gallery.disabled").apply { activityInfo.enabled = false }
        val hidden = gallery("test.gallery.hidden").apply { activityInfo.exported = false }
        val disabledApp = gallery("test.gallery.disabledapp").apply { activityInfo.applicationInfo.enabled = false }
        val available = gallery("test.gallery.available")
        assertEquals(component(available), directGalleryActivity(listOf(disabled, hidden, disabledApp, available), disabled))
        assertNull(directGalleryActivity(listOf(disabled, hidden, disabledApp), null))
        assertNull(directGalleryActivity(emptyList(), null))
    }

    private fun gallery(packageName: String, flags: Int = 0, priority: Int = 0) = ResolveInfo().apply {
        this.priority = priority
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.GalleryActivity"
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply { this.flags = flags; enabled = true }
        }
    }

    private fun component(info: ResolveInfo) = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
}
