package za.co.dielys.ui.lists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import za.co.dielys.R

/**
 * The three dialogs sharing needs: asking who to invite and reporting how that
 * went, being asked about an invite that arrived by link, and the wait after
 * accepting one.
 *
 * Split out of `ListsScreen` because they are a self-contained conversation and
 * the screen underneath them is already a screen.
 */
@Composable
fun InviteDialog(
    state: InviteState,
    onSend: (listId: String, listTitle: String, email: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Hoisted here, not inside EmailEntry: the confirm button below needs the
    // typed value too, and a sibling slot of the same AlertDialog cannot read
    // a child composable's own `remember`ed state. It survives EnteringEmail
    // recomposing into Working because this call site itself does not leave
    // composition in between — only dismissing the dialog does, which is
    // always followed by a fresh `invite(list)` starting over regardless.
    var email by remember(state) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state is InviteState.Failed) {
                    stringResource(R.string.share_failed_title)
                } else {
                    stringResource(R.string.share_list_title)
                },
            )
        },
        text = {
            when (state) {
                is InviteState.EnteringEmail ->
                    EmailEntry(state.listTitle, email, onEmailChange = { email = it })
                is InviteState.Working ->
                    Busy(stringResource(R.string.share_sending, state.listTitle))
                is InviteState.Sent -> Sent(state.listTitle, state.email)
                is InviteState.Failed -> Text(state.message)
            }
        },
        confirmButton = {
            if (state is InviteState.EnteringEmail) {
                TextButton(
                    enabled = email.isNotBlank(),
                    onClick = { onSend(state.listId, state.listTitle, email) },
                ) {
                    Text(stringResource(R.string.send_invite))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(
                        if (state is InviteState.Sent) {
                            stringResource(R.string.action_done)
                        } else {
                            stringResource(R.string.action_close)
                        },
                    )
                }
            }
        },
        dismissButton = {
            if (state is InviteState.EnteringEmail) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun EmailEntry(
    listTitle: String,
    email: String,
    onEmailChange: (String) -> Unit,
) {
    Column {
        Text(stringResource(R.string.share_who_for, listTitle))
        Text(
            stringResource(R.string.share_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.label_email)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A line of text with a spinner under it — what every "asking the server"
 *  moment in this file looks like. */
@Composable
private fun Busy(message: String) {
    Column {
        Text(message)
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Sent(
    listTitle: String,
    email: String,
) {
    Text(stringResource(R.string.share_sent, email, listTitle))
}

/**
 * A link was tapped. Asked rather than done: an invite link can come from anyone,
 * so joining stays something the person on the phone decides.
 */
@Composable
fun InvitationDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.invited_title)) },
        // Deleting is not the way out of somebody else's list — that is a
        // tombstone and it syncs to everyone on it (F5.3). Leaving is, since
        // #60, off the shared-with sheet.
        text = { Text(stringResource(R.string.invited_body)) },
        confirmButton = {
            TextButton(
                onClick = onAccept,
            ) { Text(stringResource(R.string.action_join)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.invited_not_now)) }
        },
    )
}

/**
 * The wait between accepting an invite and the list being on screen (#61).
 *
 * Accepting is two steps that look like one: the server writes the membership,
 * and the sync that follows brings the list down. This covers both, and closes
 * itself the moment the list is really here — the list appearing underneath is
 * the confirmation, so asking for a tap to dismiss would only be in the way.
 *
 * Dismissable throughout: the join has already happened by then, so tapping
 * away costs nothing but the spinner.
 */
@Composable
fun JoiningDialog(
    state: JoinState,
    onDismiss: () -> Unit,
) {
    // How long a spinner is worth watching is a question about this dialog, so
    // it is answered here rather than in the view model — which only knows
    // whether the list has landed, and has no deadline of its own.
    var slow by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is JoinState.Fetching) return@LaunchedEffect
        delay(SLOW_AFTER_MILLIS)
        slow = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state is JoinState.Failed) {
                    stringResource(R.string.join_failed_title)
                } else {
                    stringResource(R.string.joining_title)
                },
            )
        },
        text = {
            when (state) {
                JoinState.Checking -> Busy(stringResource(R.string.joining_checking))
                is JoinState.Fetching ->
                    if (slow) {
                        Text(stringResource(R.string.joining_slow))
                    } else {
                        Busy(stringResource(R.string.joining_fetching))
                    }
                is JoinState.Failed -> Text(state.message)
            }
        },
        confirmButton = {
            // Only where the dialog has stopped being a progress report. While
            // it is still working, a button is something to fiddle with rather
            // than something to do.
            val settled = state is JoinState.Failed || (state is JoinState.Fetching && slow)
            if (settled) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

/** Long enough for an ordinary sync to beat it, short enough that a spinner
 *  never becomes the whole answer (#61). */
private const val SLOW_AFTER_MILLIS = 12_000L
