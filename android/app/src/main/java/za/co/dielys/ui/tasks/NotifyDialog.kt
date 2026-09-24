package za.co.dielys.ui.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import za.co.dielys.R
import za.co.dielys.data.local.ListEntity
import za.co.dielys.domain.TaskActivityKind
import za.co.dielys.ui.SyncStatus
import za.co.dielys.ui.theme.PillShape

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
        // The app's own navy panel, not Material's default grey container, so
        // the dialog reads as part of the list it was opened from.
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { BellBadge() },
        title = {
            Text(
                stringResource(R.string.notify_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in TaskActivityKind.entries) {
                    val on = kind.wire in selected
                    NotifyOption(kind = kind, on = on) {
                        selected = if (it) selected + kind.wire else selected - kind.wire
                    }
                }
                if (blocked && selected.isNotEmpty()) {
                    Text(
                        stringResource(R.string.notify_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
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
                shape = PillShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The bell, on a soft amber disc: the same amber as Save and as web's dialog. */
@Composable
private fun BellBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(BADGE_SIZE)
                .background(
                    MaterialTheme.colorScheme.secondary.copy(alpha = BADGE_ALPHA),
                    CircleShape,
                ),
    ) {
        Icon(
            Icons.Filled.Notifications,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * One kind of change, as a whole-width row: its icon, its name, and a switch.
 * The row takes the tap, so the switch itself does not, and a chosen row is
 * tinted so the set reads at a glance without looking at four switches.
 */
@Composable
private fun NotifyOption(
    kind: TaskActivityKind,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (on) colors.primaryContainer else colors.onSurface.copy(alpha = OFF_ROW_ALPHA),
        label = "notify-row",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .clip(ROW_SHAPE)
                .background(fill)
                .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(
            kind.icon(),
            contentDescription = null,
            tint = if (on) colors.onPrimaryContainer else colors.onSurface.copy(alpha = ICON_ALPHA),
        )
        Text(
            stringResource(kind.label()),
            style = MaterialTheme.typography.bodyLarge,
            color = if (on) colors.onPrimaryContainer else colors.onSurface,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
        )
        Switch(
            checked = on,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = colors.secondary,
                    checkedThumbColor = colors.onSecondary,
                    checkedBorderColor = colors.secondary,
                ),
        )
    }
}

private fun TaskActivityKind.icon(): ImageVector =
    when (this) {
        TaskActivityKind.ADDED -> Icons.Outlined.AddCircleOutline
        TaskActivityKind.CHECKED -> Icons.Outlined.CheckCircle
        TaskActivityKind.DELETED -> Icons.Outlined.Delete
        TaskActivityKind.UPDATED -> Icons.Outlined.Edit
    }

private val BADGE_SIZE = 56.dp
private const val BADGE_ALPHA = 0.18f
private val ROW_HEIGHT = 56.dp
private val ROW_SHAPE = RoundedCornerShape(14.dp)

/** Just enough to show an off row's edge against the panel. */
private const val OFF_ROW_ALPHA = 0.06f
private const val ICON_ALPHA = 0.7f

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
