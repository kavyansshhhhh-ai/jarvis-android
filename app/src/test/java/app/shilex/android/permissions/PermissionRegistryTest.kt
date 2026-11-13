package app.shilex.android.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRegistryTest {

    private val micSpec = PermissionSpec(
        id = PermissionId.Microphone,
        title = "Microphone",
        category = PermissionCategory.Runtime,
        reason = "Voice input",
        dependentFeature = "Voice conversation & wake-word detection",
        manifestPermission = Manifest.permission.RECORD_AUDIO,
        essential = true,
    )

    private val contactsSpec = PermissionSpec(
        id = PermissionId.Contacts,
        title = "Contacts",
        category = PermissionCategory.Runtime,
        reason = "Lookup contacts",
        dependentFeature = "Calling & messaging",
        manifestPermission = Manifest.permission.READ_CONTACTS,
        essential = false,
    )

    private val accessibilitySpec = PermissionSpec(
        id = PermissionId.AccessibilityControl,
        title = "Phone Control (Accessibility)",
        category = PermissionCategory.SpecialSetting,
        reason = "Screen control",
        dependentFeature = "On-screen assistance",
    )

    private val overlaySpec = PermissionSpec(
        id = PermissionId.Overlay,
        title = "Display Over Other Apps",
        category = PermissionCategory.SpecialSetting,
        reason = "Wake dot & orb",
        dependentFeature = "Wake indicator dot & Floating Orb",
    )

    private val pickerSpec = PermissionSpec(
        id = PermissionId.FilesAndPhotosPicker,
        title = "Photos & Files",
        category = PermissionCategory.SystemPicker,
        reason = "Android System Picker",
        dependentFeature = "Inspecting selected images",
    )

    @Test
    fun evaluatesAllFiveClearPermissionStatusesAndActionButtons() {
        val notGranted = PermissionRegistry.evaluateItem(
            spec = micSpec,
            isCurrentlyGranted = false,
        )
        assertEquals(PermissionStatus.NotGranted, notGranted.status)
        assertEquals(PermissionActionKind.AllowRuntime, notGranted.actionKind)
        assertEquals("Allow", notGranted.actionLabel)

        val granted = PermissionRegistry.evaluateItem(
            spec = micSpec,
            isCurrentlyGranted = true,
        )
        assertEquals(PermissionStatus.Granted, granted.status)
        assertEquals(PermissionActionKind.ManageSettings, granted.actionKind)
        assertEquals("Manage", granted.actionLabel)

        val specialNeedsSetup = PermissionRegistry.evaluateItem(
            spec = accessibilitySpec,
            isCurrentlyGranted = false,
        )
        assertEquals(PermissionStatus.NeedsSetup, specialNeedsSetup.status)
        assertEquals(PermissionActionKind.OpenSettings, specialNeedsSetup.actionKind)
        assertEquals("Open Settings", specialNeedsSetup.actionLabel)

        val restricted = PermissionRegistry.evaluateItem(
            spec = micSpec,
            isCurrentlyGranted = false,
            isRestrictedByPolicy = true,
        )
        assertEquals(PermissionStatus.RestrictedByAndroid, restricted.status)
        assertEquals(PermissionActionKind.OpenSettings, restricted.actionKind)
        assertTrue(restricted.restrictedByAndroid)

        val picker = PermissionRegistry.evaluateItem(
            spec = pickerSpec,
            isCurrentlyGranted = true,
        )
        assertEquals(PermissionStatus.NotRequired, picker.status)
        assertEquals(PermissionActionKind.None, picker.actionKind)
        assertTrue(picker.isReady)

        val preAndroid12Alarm = PermissionRegistry.evaluateItem(
            spec = overlaySpec,
            isCurrentlyGranted = false,
            isRequiredOnDevice = false,
        )
        assertEquals(PermissionStatus.NotRequired, preAndroid12Alarm.status)
    }

    @Test
    fun handlesDeniedOnceVsPermanentlyDeniedAndRevokedRuntimePermissions() {
        // First denial: canShowRationale = true -> still shows Allow runtime action + explanation
        val deniedOnce = PermissionRegistry.evaluateItem(
            spec = contactsSpec,
            isCurrentlyGranted = false,
            wasPreviouslyGranted = false,
            wasRequestedBefore = true,
            canShowRationale = true,
        )
        assertEquals(PermissionStatus.NotGranted, deniedOnce.status)
        assertTrue(deniedOnce.deniedBefore)
        assertFalse(deniedOnce.permanentlyDenied)
        assertEquals(PermissionActionKind.AllowRuntime, deniedOnce.actionKind)
        assertEquals("Allow", deniedOnce.actionLabel)
        assertTrue(deniedOnce.statusDetail.contains(contactsSpec.dependentFeature))

        // Permanently denied: requested before + canShowRationale = false -> Needs setup + Open Settings shortcut
        val permanentlyDenied = PermissionRegistry.evaluateItem(
            spec = contactsSpec,
            isCurrentlyGranted = false,
            wasPreviouslyGranted = false,
            wasRequestedBefore = true,
            canShowRationale = false,
        )
        assertEquals(PermissionStatus.NeedsSetup, permanentlyDenied.status)
        assertTrue(permanentlyDenied.permanentlyDenied)
        assertEquals(PermissionActionKind.OpenSettings, permanentlyDenied.actionKind)
        assertEquals("Open Settings", permanentlyDenied.actionLabel)

        // Revoked later in Android Settings
        val revoked = PermissionRegistry.evaluateItem(
            spec = micSpec,
            isCurrentlyGranted = false,
            wasPreviouslyGranted = true,
            wasRequestedBefore = true,
            canShowRationale = false,
        )
        assertEquals(PermissionStatus.NeedsSetup, revoked.status)
        assertTrue(revoked.revoked)
        assertEquals(PermissionActionKind.OpenSettings, revoked.actionKind)
    }

    @Test
    fun simulatesRefreshAfterReturningFromAndroidSettings() {
        val beforeReturn = PermissionSnapshot.from(
            listOf(
                PermissionRegistry.evaluateItem(micSpec, isCurrentlyGranted = true),
                PermissionRegistry.evaluateItem(accessibilitySpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(overlaySpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(pickerSpec, isCurrentlyGranted = true),
            ),
        )
        assertEquals(1, beforeReturn.grantedCount)
        assertEquals(3, beforeReturn.actionableTotalCount)
        assertEquals(PermissionStatus.NeedsSetup, beforeReturn.deduplicatedItems[1].status)

        // User enabled Accessibility in Android Settings and returned to JARVIS -> snapshot refreshes
        val afterReturn = PermissionSnapshot.from(
            listOf(
                PermissionRegistry.evaluateItem(micSpec, isCurrentlyGranted = true),
                PermissionRegistry.evaluateItem(accessibilitySpec, isCurrentlyGranted = true),
                PermissionRegistry.evaluateItem(overlaySpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(pickerSpec, isCurrentlyGranted = true),
            ),
        )
        assertEquals(2, afterReturn.grantedCount)
        assertEquals(PermissionStatus.Granted, afterReturn.deduplicatedItems[1].status)
        assertEquals(PermissionActionKind.ManageSettings, afterReturn.deduplicatedItems[1].actionKind)
    }

    @Test
    fun preventsDuplicatePermissionEntriesAcrossAllAndroidLevels() {
        listOf(29, 31, 33, 35).forEach { api ->
            val specs = PermissionRegistry.specs(api)
            assertEquals("Duplicate PermissionId at API $api", specs.size, specs.distinctBy { it.id }.size)
            assertTrue(specs.any { it.id == PermissionId.Microphone })
            assertTrue(specs.any { it.id == PermissionId.Notifications })
            assertTrue(specs.any { it.id == PermissionId.BatteryOptimization })
            assertTrue(specs.any { it.id == PermissionId.ExactAlarms })
            assertTrue(specs.any { it.id == PermissionId.AccessibilityControl })
            assertTrue(specs.any { it.id == PermissionId.NotificationAccess })
            assertTrue(specs.any { it.id == PermissionId.Overlay })
            assertTrue(specs.any { it.id == PermissionId.FilesAndPhotosPicker })
        }

        val duplicatedSnapshot = PermissionSnapshot.from(
            listOf(
                PermissionRegistry.evaluateItem(micSpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(micSpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(overlaySpec, isCurrentlyGranted = false),
            ),
        )
        assertEquals(2, duplicatedSnapshot.deduplicatedItems.size)
        assertEquals(2, duplicatedSnapshot.totalCount)
    }

    @Test
    fun onboardingChecklistGuidesRuntimeThenSpecialSettingsAndNeverBlocksOnDenial() {
        val initial = PermissionSnapshot.from(
            listOf(
                PermissionRegistry.evaluateItem(micSpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(contactsSpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(accessibilitySpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(pickerSpec, isCurrentlyGranted = true),
            ),
        )
        val initialGuide = OnboardingPermissionGuide.analyze(initial)
        assertEquals(0, initialGuide.essentialGranted)
        assertEquals(1, initialGuide.essentialTotal)
        assertEquals(0, initialGuide.optionalGranted)
        assertEquals(2, initialGuide.optionalTotal)
        assertEquals(listOf(PermissionId.Microphone, PermissionId.Contacts), initialGuide.nextRuntimeBatch.map { it.spec.id })
        assertTrue(initialGuide.canContinue)

        // Microphone permanently denied, Contacts granted -> onboarding does NOT re-loop Microphone dialog,
        // reports blocked feature, highlights next special setting, and still lets user continue.
        val afterDenied = PermissionSnapshot.from(
            listOf(
                PermissionRegistry.evaluateItem(
                    micSpec,
                    isCurrentlyGranted = false,
                    wasRequestedBefore = true,
                    canShowRationale = false,
                ),
                PermissionRegistry.evaluateItem(contactsSpec, isCurrentlyGranted = true),
                PermissionRegistry.evaluateItem(accessibilitySpec, isCurrentlyGranted = false),
                PermissionRegistry.evaluateItem(pickerSpec, isCurrentlyGranted = true),
            ),
        )
        val deniedGuide = OnboardingPermissionGuide.analyze(afterDenied)
        assertTrue(deniedGuide.nextRuntimeBatch.isEmpty())
        assertEquals(1, deniedGuide.blockedOrDeniedItems.size)
        assertEquals(PermissionId.Microphone, deniedGuide.blockedOrDeniedItems.first().spec.id)
        assertNotNull(deniedGuide.nextSpecialSetting)
        assertEquals(PermissionId.AccessibilityControl, deniedGuide.nextSpecialSetting?.spec?.id)
        assertTrue(deniedGuide.canContinue)
    }
}
