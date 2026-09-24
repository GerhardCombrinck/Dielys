package za.co.dielys.ui.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import za.co.dielys.R
import za.co.dielys.data.local.ListEntity
import za.co.dielys.domain.TaskActivityKind
import za.co.dielys.ui.SyncStatus

/**
 * What a list's header adds once somebody else can change the list: its sync
 * status, and the bell that chooses what to be notified about (ADR 0012).
 * Nothing on a solo list — sync status has nothing to be behind on, and every
 * change is this account's own, so there is nobody to hear about.
 */
@Composable
fun SharedListActions(
    list: ListEntity?,
    pending: Int,
    stuck: Int,
    onSetNotify: (Set<String>) -> Unit,
) {
    if (list?.isShared != true) return
    var open by rememberSaveable { mutableStateOf(false) }
    SyncStatus(pending = pending, stuck = stuck)
    NotifyButton(subscribed = list.notify.isNotEmpty(), onClick = { open = true })
    if (open) NotifyDialog(chosen = list.notify, onSave = onSetNotify, onDismiss = { open = false })
}

/**
 * The bell in a shared list's header (ADR 0012): filled when this account has
 * asked to hear about anything on the list, outlined when not. Opens
 * [NotifyDialog].
 */
@Composable
fun NotifyButton(
    subscribed: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            if (subscribed) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
            contentDescription = stringResource(R.string.cd_notifications),
        )
    }
}

/**
 * Which changes somebody else makes to this list should reach this phone as a
 * notification — per list, per account, off until chosen (ADR 0012).
 *
 * Saved on Save, not on each tick: it is a set of four chosen together, and the
 * server takes the whole set at once. Android 13 and later ask for the
 * notification permission here, the first time anything is turned on — asking
 * at start-up, before anybody has chosen to be notified about anything, is the
 * prompt most people say no to. A permission already refused, or notifications
 * turned off for the app, is said out loud rather than leaving the choice to
 * silently do nothing.
 */
@Composable
fun NotifyDialog(
    chosen: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf(chosen.toList()) }
    var blocked by remember {
        mutableStateOf(!NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            blocked = !granted
            if (granted) onDismiss()
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notify_dialog_title)) },
        text = {
            Column {
                for (kind in TaskActivityKind.entries) {
                    val on = kind.wire in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = on, role = Role.Checkbox) {
                                    selected =
                                        if (it) selected + kind.wire else selected - kind.wire
                                },
                    ) {
                        // The row takes the tap, so the box itself does not.
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(
                            stringResource(kind.label()),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                if (blocked && selected.isNotEmpty()) {
                    Text(
                        stringResource(R.string.notify_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val events = selected.toSet()
                    onSave(events)
                    if (events.isNotEmpty() && needsPermission(context)) {
                        // Closed by the result, so a refusal can still be shown.
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun TaskActivityKind.label(): Int =
    when (this) {
        TaskActivityKind.ADDED -> R.string.notify_event_added
        TaskActivityKind.CHECKED -> R.string.notify_event_checked
        TaskActivityKind.DELETED -> R.string.notify_event_deleted
        TaskActivityKind.UPDATED -> R.string.notify_event_updated
    }

private fun needsPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
