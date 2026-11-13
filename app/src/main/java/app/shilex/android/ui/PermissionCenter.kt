package app.shilex.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.shilex.android.permissions.PermissionCategory
import app.shilex.android.permissions.PermissionId
import app.shilex.android.permissions.PermissionRegistry
import app.shilex.android.permissions.PermissionSnapshot
import app.shilex.android.permissions.PermissionStateItem
import app.shilex.android.permissions.PermissionStatus

/**
 * Reusable Liquid Glass Permission Center used in both Onboarding and Settings.
 * Shows permission name, reason, dependent feature, Android category (Runtime, Special Setting,
 * System Photo/File Picker), live status, revocation warning, and official grant/settings actions.
 */
@Composable
fun PermissionCenterPanel(
    compact: Boolean = false,
    onSnapshotChanged: (PermissionSnapshot) -> Unit = {},
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(PermissionRegistry.snapshot(context)) }

    val refresh = {
        val next = PermissionRegistry.snapshot(context)
        snapshot = next
        onSnapshotChanged(next)
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refresh()
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (snapshot.revokedItems.isNotEmpty()) {
            GlassCard(shape = RoundedCornerShape(18.dp), verticalPadding = 12) {
                Text(
                    "Permission revoked in Android Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = CoralError,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${snapshot.revokedItems.joinToString { it.spec.title }} was turned off. Unrelated features still work normally; grant it again any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (snapshot.missingRuntime.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${snapshot.readyCount}/${snapshot.totalCount} ready",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                )
                TextButton(
                    onClick = {
                        val perms = snapshot.missingRuntime
                            .mapNotNull { it.spec.manifestPermission }
                            .toTypedArray()
                        if (perms.isNotEmpty()) runtimeLauncher.launch(perms)
                    },
                ) {
                    Text("Allow missing runtime permissions", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        GlassCard(shape = RoundedCornerShape(22.dp), verticalPadding = if (compact) 8 else 12) {
            snapshot.items.forEachIndexed { idx, item ->
                if (idx > 0) GlassDivider()
                PermissionItemRow(
                    item = item,
                    onAction = {
                        when (item.spec.category) {
                            PermissionCategory.Runtime -> {
                                val perm = item.spec.manifestPermission
                                if (item.status == PermissionStatus.Revoked || perm == null) {
                                    context.startActivity(PermissionRegistry.settingsIntent(context, item.spec.id))
                                } else {
                                    runtimeLauncher.launch(arrayOf(perm))
                                }
                            }
                            PermissionCategory.SpecialSetting -> {
                                context.startActivity(PermissionRegistry.settingsIntent(context, item.spec.id))
                            }
                            PermissionCategory.SystemPicker -> Unit
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionStateItem,
    onAction: () -> Unit,
) {
    val statusColor = when (item.status) {
        PermissionStatus.Granted, PermissionStatus.OnDemandPicker -> ElectricCyan
        PermissionStatus.Revoked -> CoralError
        PermissionStatus.Denied, PermissionStatus.NeedsSettings -> AmberWarning
    }
    val statusText = when (item.status) {
        PermissionStatus.Granted -> "Granted"
        PermissionStatus.OnDemandPicker -> "Picker Ready"
        PermissionStatus.Revoked -> "Revoked"
        PermissionStatus.Denied -> "Required"
        PermissionStatus.NeedsSettings -> "Setting"
    }
    val typeTag = when (item.spec.category) {
        PermissionCategory.Runtime -> "Runtime permission"
        PermissionCategory.SpecialSetting -> "Android setting"
        PermissionCategory.SystemPicker -> "System Photo/File Picker"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    item.spec.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                )
                Pill(statusText, statusColor)
            }
            Text(
                "$typeTag • For: ${item.spec.dependentFeature}",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                item.spec.reason,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (item.spec.id != PermissionId.FilesAndPhotosPicker) {
            val actionLabel = when {
                item.status == PermissionStatus.Granted -> "Manage"
                item.spec.category == PermissionCategory.SpecialSetting -> "Open"
                item.status == PermissionStatus.Revoked -> "Settings"
                else -> "Allow"
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, color = ElectricCyan, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
