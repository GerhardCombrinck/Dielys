package za.co.dielys.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import za.co.dielys.R

/**
 * Naming a list, renaming a task — the same three-part question every time. One
 * copy so the keyboard behaves the same wherever it is asked.
 */
@Composable
fun TextPrompt(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = stringResource(R.string.action_save),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = {
        if (value.isNotBlank()) {
            onConfirm(value)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                // The value itself stays one line (Done submits rather than
                // inserting a newline) — only the field is allowed to wrap, so a
                // long title is all on screen at once instead of scrolling
                // sideways past the edge of a fixed line.
                maxLines = MAX_VISIBLE_LINES,
                // Sentences, not Words: the ask is the first letter, and Words
                // would turn "milk and eggs" into a headline. A keyboard hint
                // rather than something forced on the text, so a deliberate
                // "iPhone charger" survives being typed.
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = value.isNotBlank()) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Enough for a long title to unfold without the dialog growing unbounded. */
private const val MAX_VISIBLE_LINES = 5
