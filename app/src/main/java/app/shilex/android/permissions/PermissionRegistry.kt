package app.shilex.android.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.shilex.android.control.ControlService
import app.shilex.android.notify.NotificationWatch

enum class PermissionCategory {
    Runtime,
    SpecialSetting,
    SystemPicker,
}

enum class PermissionStatus {
    Granted,
    Denied,
    Revoked,
    NeedsSettings,
    OnDemandPicker,
}

enum class PermissionId {
    Microphone,
    Notifications,
    Contacts,
    Calendar,
    AccessibilityControl,
    NotificationAccess,
    Overlay,
    FilesAndPhotosPicker,
}

data class PermissionSpec(
    val id: PermissionId,
    val title: String,
    val category: PermissionCategory,
    val reason: String,
    val dependentFeature: String,
    val manifestPermission: String? = null,
    val minApi: Int = Build.VERSION_CODES.Q,
    val essential: Boolean = false,
)

data class PermissionStateItem(
    val spec: PermissionSpec,
    val status: PermissionStatus,
    val previouslyGranted: Boolean = false,
) {
    val isReady: Boolean get() = status == PermissionStatus.Granted || status == PermissionStatus.OnDemandPicker
}

data class PermissionSnapshot(
    val items: List<PermissionStateItem>,
) {
    val revokedItems: List<PermissionStateItem> get() = items.filter { it.status == PermissionStatus.Revoked }
    val missingRuntime: List<PermissionStateItem>
        get() = items.filter { it.spec.category == PermissionCategory.Runtime && !it.isReady }
    val readyCount: Int get() = items.count { it.isReady }
    val totalCount: Int get() = items.size
}

/**
 * Transparent permission manager tracking runtime permissions, special Android settings,
 * and on-demand Android Photo Picker / Storage Access Framework pickers, including revocation detection.
 */
object PermissionRegistry {
    fun specs(sdkInt: Int = Build.VERSION.SDK_INT): List<PermissionSpec> = buildList {
        add(
            PermissionSpec(
                id = PermissionId.Microphone,
                title = "Microphone",
                category = PermissionCategory.Runtime,
                reason = "Needed when you tap Speak or enable local \"Hey Jarvis\" wake listening.",
                dependentFeature = "Voice conversation & wake-word detection",
                manifestPermission = Manifest.permission.RECORD_AUDIO,
                essential = true,
            ),
        )
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(
                PermissionSpec(
                    id = PermissionId.Notifications,
                    title = "Notifications",
                    category = PermissionCategory.Runtime,
                    reason = "Shows Android's required visible mic indicator, reminders, and app-open alerts.",
                    dependentFeature = "Always-on wake notification & reminders",
                    manifestPermission = Manifest.permission.POST_NOTIFICATIONS,
                    minApi = Build.VERSION_CODES.TIRAMISU,
                    essential = true,
                ),
            )
        }
        add(
            PermissionSpec(
                id = PermissionId.Contacts,
                title = "Contacts",
                category = PermissionCategory.Runtime,
                reason = "Looks up the person you ask to call, SMS, or message on WhatsApp.",
                dependentFeature = "Calling & messaging by contact name",
                manifestPermission = Manifest.permission.READ_CONTACTS,
            ),
        )
        add(
            PermissionSpec(
                id = PermissionId.Calendar,
                title = "Calendar",
                category = PermissionCategory.Runtime,
                reason = "Checks upcoming events and opens pre-filled Calendar drafts.",
                dependentFeature = "Schedule checks & creating events",
                manifestPermission = Manifest.permission.READ_CALENDAR,
            ),
        )
        add(
            PermissionSpec(
                id = PermissionId.AccessibilityControl,
                title = "Phone Control (Accessibility)",
                category = PermissionCategory.SpecialSetting,
                reason = "Lets JARVIS read non-sensitive screens, open Quick Settings/Notification panels, press Send after your confirmation, and run multi-step app flows.",
                dependentFeature = "On-screen automation, WhatsApp Send & system panels",
            ),
        )
        add(
            PermissionSpec(
                id = PermissionId.NotificationAccess,
                title = "Notification Listener",
                category = PermissionCategory.SpecialSetting,
                reason = "Reads incoming notifications so JARVIS can summarize unread messages and reply only after you confirm.",
                dependentFeature = "\"What did I miss\" & hands-free replies",
            ),
        )
        add(
            PermissionSpec(
                id = PermissionId.Overlay,
                title = "Display Over Other Apps",
                category = PermissionCategory.SpecialSetting,
                reason = "Shows the subtle wake-word dot, floating orb, and on-screen Stop / Allow cards over other apps.",
                dependentFeature = "Wake indicator dot & Floating Orb",
            ),
        )
        add(
            PermissionSpec(
                id = PermissionId.FilesAndPhotosPicker,
                title = "Photos & Files (System Picker / SAF)",
                category = PermissionCategory.SystemPicker,
                reason = "Uses Android's built-in Photo Picker and Document Picker only for items you pick—no broad storage access is ever requested.",
                dependentFeature = "Understanding selected screenshots, photos & documents",
            ),
        )
    }

    /** Pure status evaluator for unit tests without Android Context. */
    fun evaluateItem(
        spec: PermissionSpec,
        isCurrentlyGranted: Boolean,
        wasPreviouslyGranted: Boolean,
    ): PermissionStateItem {
        val status = when (spec.category) {
            PermissionCategory.SystemPicker -> PermissionStatus.OnDemandPicker
            PermissionCategory.Runtime -> when {
                isCurrentlyGranted -> PermissionStatus.Granted
                wasPreviouslyGranted -> PermissionStatus.Revoked
                else -> PermissionStatus.Denied
            }
            PermissionCategory.SpecialSetting -> when {
                isCurrentlyGranted -> PermissionStatus.Granted
                wasPreviouslyGranted -> PermissionStatus.Revoked
                else -> PermissionStatus.NeedsSettings
            }
        }
        return PermissionStateItem(spec = spec, status = status, previouslyGranted = wasPreviouslyGranted)
    }

    /** Evaluates live permission states and updates private grant history to catch revocations. */
    fun snapshot(context: Context): PermissionSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val items = specs().map { spec ->
            val current = isGrantedNow(context, spec.id)
            val prevKey = "seen_${spec.id.name}"
            val prev = prefs.getBoolean(prevKey, false)
            if (current && !prev) editor.putBoolean(prevKey, true)
            evaluateItem(spec, current, prev)
        }
        editor.apply()
        return PermissionSnapshot(items)
    }

    fun isGrantedNow(context: Context, id: PermissionId): Boolean = when (id) {
        PermissionId.Microphone ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        PermissionId.Notifications ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        PermissionId.Contacts ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        PermissionId.Calendar ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        PermissionId.AccessibilityControl -> ControlService.instance != null
        PermissionId.NotificationAccess -> NotificationWatch.allowed(context)
        PermissionId.Overlay -> Settings.canDrawOverlays(context)
        PermissionId.FilesAndPhotosPicker -> true
    }

    /** Official Android Settings intent for a special permission or revoked runtime permission. */
    fun settingsIntent(context: Context, id: PermissionId): Intent = when (id) {
        PermissionId.AccessibilityControl -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionId.NotificationAccess -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        PermissionId.Overlay -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        PermissionId.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val PREFS_NAME = "permission_history"
}
