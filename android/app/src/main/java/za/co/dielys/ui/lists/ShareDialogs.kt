package za.co.dielys.ui.lists

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The three dialogs sharing needs: making an invite, pasting one, and being
 * asked about one that arrived by link.
 *
 * Split out of `ListsScreen` because they are a self-contained conversation and
 * the screen underneath them is already a screen.
 */
@Composable
fun InviteDialog(
    state: InviteState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state is InviteState.Failed) "Could not share" else "Share this list") },
        text = {
            when (state) {
                is InviteState.Working -> Working(state.listTitle)
                is InviteState.Ready -> Ready(state.listTitle)
                is InviteState.Failed -> Text(state.message)
            }
        },
        confirmButton = {
            if (state is InviteState.Ready) {
                TextButton(onClick = {
                    context.shareInvite(state.link)
                    onDismiss()
                }) {
                    Text("Send invite")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (state is InviteState.Ready) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Working(listTitle: String) {
    Column {
        Text("Making an invite for $listTitle…")
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Ready(listTitle: String) {
    Column {
        Text("Anyone who opens this link can join $listTitle.")
        Text(
            "It works for seven days. Send it to one person, not to a group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Deliberately not showing the link itself. It is a bearer credential — a
 * screenshot of this dialog would be enough to join the list — and there is
 * nothing a person can usefully do with the text that the share sheet does not
 * do better.
 *
 * The shared text is the bare link, nothing around it: the custom `dielys://`
 * scheme does not get auto-linked by most chat apps (see `InviteLink`), so the
 * recipient has to copy the text and paste it into `JoinDialog`. Any words
 * around the link would have to be trimmed off first.
 */
private fun Context.shareInvite(link: String) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
    startActivity(Intent.createChooser(intent, "Send invite"))
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
