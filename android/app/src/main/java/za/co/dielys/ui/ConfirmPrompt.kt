package za.co.dielys.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import za.co.dielys.R

/**
 * "Are you sure?" — the same three-part question every time it is asked, the
 * way [TextPrompt] is the same three-part question every time something is
 * named. One copy so backing out is always in the same corner, whichever
 * destructive thing is being asked about.
 *
 * The question itself belongs to the caller: what goes, whose it is, and
 * whether it can be had back are things only the screen asking knows, and a
 * dialog that says "this cannot be undone" about something recoverable is
 * worse than no dialog at all.
 */
@Composable
fun ConfirmPrompt(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
