package app.shilex.android.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRegistryTest {
    @Test
    fun detectsGrantRevocationSettingsAndSystemPickerStates() {
        val mic = PermissionSpec(
            id = PermissionId.Microphone,
            title = "Microphone",
            category = PermissionCategory.Runtime,
            reason = "Voice",
            dependentFeature = "Wake word",
        )
        val accessibility = PermissionSpec(
            id = PermissionId.AccessibilityControl,
            title = "Accessibility",
            category = PermissionCategory.SpecialSetting,
            reason = "Control",
            dependentFeature = "UI actions",
        )
        val picker = PermissionSpec(
            id = PermissionId.FilesAndPhotosPicker,
            title = "Photos",
            category = PermissionCategory.SystemPicker,
            reason = "Pick",
            dependentFeature = "Images",
        )

        assertEquals(PermissionStatus.Denied, PermissionRegistry.evaluateItem(mic, isCurrentlyGranted = false, wasPreviouslyGranted = false).status)
        assertEquals(PermissionStatus.Granted, PermissionRegistry.evaluateItem(mic, isCurrentlyGranted = true, wasPreviouslyGranted = false).status)
        assertEquals(PermissionStatus.Revoked, PermissionRegistry.evaluateItem(mic, isCurrentlyGranted = false, wasPreviouslyGranted = true).status)

        assertEquals(PermissionStatus.NeedsSettings, PermissionRegistry.evaluateItem(accessibility, isCurrentlyGranted = false, wasPreviouslyGranted = false).status)
        assertEquals(PermissionStatus.Revoked, PermissionRegistry.evaluateItem(accessibility, isCurrentlyGranted = false, wasPreviouslyGranted = true).status)

        assertEquals(PermissionStatus.OnDemandPicker, PermissionRegistry.evaluateItem(picker, isCurrentlyGranted = true, wasPreviouslyGranted = false).status)
        assertTrue(PermissionRegistry.specs(33).any { it.id == PermissionId.Notifications })
    }
}
