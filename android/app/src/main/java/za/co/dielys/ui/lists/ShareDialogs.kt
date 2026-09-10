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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The four dialogs sharing needs: asking who to invite, showing progress and
 * the result, pasting one, and being asked about one that arrived by link.
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
        title = { Text(if (state is InviteState.Failed) "Could not share" else "Share this list") },
        text = {
            when (state) {
                is InviteState.EnteringEmail ->
                    EmailEntry(state.listTitle, email, onEmailChange = { email = it })
                is InviteState.Working -> Working(state.listTitle)
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
                    Text("Send invite")
                }
            } else {
                TextButton(
                    onClick = onDismiss,
                ) { Text(if (state is InviteState.Sent) "Done" else "Close") }
            }
        },
        dismissButton = {
            if (state is InviteState.EnteringEmail) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
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
        Text("Who is $listTitle for?")
        Text(
            "Only that email address will be able to join. It works for seven days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Working(listTitle: String) {
    Column {
        Text("Sending an invite for $listTitle…")
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
    Text(
        "Invite sent to $email. It works for seven days, and only that address can join $listTitle.",
    )
}

@Composable
fun JoinDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var pasted by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a list") },
        text = {
            Column {
                Text("Paste the invite somebody sent you.")
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Invite") },
                    // The message around the link is fine — the token is found
                    // inside whatever is pasted.
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pasted.isNotBlank(),
                onClick = {
                    onJoin(pasted)
                    onDismiss()
                },
            ) {
                Text("Join")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
        title = { Text("You were invited to a list") },
        // Not "you can leave later": deleting a list is a tombstone that syncs
        // to everyone on it (F5.3), so it is not a way out of somebody else's
        // list. There is no leave yet, and saying otherwise would be worse than
        // saying nothing.
        text = { Text("Join it? Whoever shared it will see what you add.") },
        confirmButton = { TextButton(onClick = onAccept) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Not now") } },
    )
}
