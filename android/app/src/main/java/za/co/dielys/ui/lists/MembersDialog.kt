package za.co.dielys.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import za.co.dielys.R
import za.co.dielys.data.Member

/**
 * Who a list is shared with, and the way to end that (#60).
 *
 * Only ever opened from a shared list's own row, so it never has to explain
 * what sharing is — it answers "who exactly can see this", which is the
 * question somebody has when they tap it.
 *
 * Every destructive action asks first, by name. Removing somebody is not
 * undoable from here — the owner would have to invite them again, and they
 * would have to accept — so a mis-tap on a row in a short list is worth one
 * question.
 */
@Composable
fun MembersDialog(
    state: MembersState,
    onRemove: (listId: String, userId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Which row is being confirmed, if any. Held here rather than in the view
    // model: nothing outside this dialog needs to know a question was asked,
    // and dismissing the dialog is supposed to forget it.
    var confirming by remember(state.listId) { mutableStateOf<Member?>(null) }

    val loaded = state as? MembersState.Loaded
    confirming?.let { member ->
        ConfirmRemoval(
            member = member,
            leaving = member.userId == loaded?.meUserId,
            onConfirm = {
                confirming = null
                onRemove(state.listId, member.userId)
            },
            onDismiss = { confirming = null },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.members_title)) },
        text = {
            when (state) {
                is MembersState.Loading -> Text(stringResource(R.string.members_loading))
                is MembersState.Failed -> Text(state.message)
                is MembersState.Loaded -> Members(state, onAsk = { confirming = it })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun Members(
    state: MembersState.Loaded,
    onAsk: (Member) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A list that says it is shared but names only one person is a list
        // somebody was just removed from, or one whose invite has not been
        // accepted yet. Either way, saying so beats a row on its own.
        if (state.members.size <= 1) {
            Text(
                stringResource(R.string.members_solo),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        for (member in state.members) {
            MemberRow(
                member = member,
                isMe = member.userId == state.meUserId,
                // The owner may remove anybody else; anybody may leave. An owner
                // is never offered either on their own row — they would strand
                // the list, and the server refuses it. Same rule as the server's,
                // so the screen never offers what would come back a 403.
                action =
                    when {
                        state.working != null -> null
                        member.userId == state.meUserId && !member.isOwner -> R.string.action_leave
                        member.userId != state.meUserId && state.iAmOwner -> R.string.action_remove
                        else -> null
                    },
                working = state.working == member.userId,
                onAct = { onAsk(member) },
            )
        }
    }
}

@Composable
private fun MemberRow(
    member: Member,
    isMe: Boolean,
    action: Int?,
    working: Boolean,
    onAct: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                member.email,
                style = MaterialTheme.typography.bodyMedium,
                // An address is long and a dialog is narrow. The tail is the
                // part that repeats between two people in one household, so
                // it is the middle that goes.
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val note = notes(member.isOwner, isMe)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = NOTE_ALPHA),
                )
            }
        }

        if (working) {
            Text(
                stringResource(R.string.members_loading),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else if (action != null) {
            TextButton(onClick = onAct) { Text(stringResource(action)) }
        }
    }
}

/** "owner", "you", or "owner · you" — whichever of the two is true. */
@Composable
private fun notes(
    isOwner: Boolean,
    isMe: Boolean,
): String? {
    val parts =
        listOfNotNull(
            if (isOwner) stringResource(R.string.members_owner) else null,
            if (isMe) stringResource(R.string.members_you) else null,
        )
    return parts.ifEmpty { null }?.joinToString(" · ")
}

@Composable
private fun ConfirmRemoval(
    member: Member,
    leaving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (leaving) {
                    stringResource(R.string.confirm_leave_title)
                } else {
                    stringResource(R.string.confirm_remove_title, member.email)
                },
            )
        },
        text = {
            Text(
                if (leaving) {
                    stringResource(R.string.confirm_leave_body)
                } else {
                    stringResource(R.string.confirm_remove_body)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (leaving) R.string.action_leave else R.string.action_remove,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val NOTE_ALPHA = 0.65f
