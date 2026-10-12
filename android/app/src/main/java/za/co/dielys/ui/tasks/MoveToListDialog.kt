package za.co.dielys.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.dielys.R
import za.co.dielys.ui.theme.accentColor
import za.co.dielys.ui.theme.listAccent

/**
 * Which list a task should go to (ADR 0015): every other list in use, each
 * wearing its own colour so it reads the same as on the Lists screen. One tap
 * moves it and closes the dialog — there is nothing to confirm, since the item
 * is not lost, only somewhere else.
 */
@Composable
internal fun MoveToListDialog(
    viewModel: TaskListViewModel,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets by viewModel.moveTargets.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_title)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.move_to_none))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { target ->
                        val list = target.list
                        val dot = target.accent?.let(::accentColor) ?: listAccent(list.id)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPick(list.id)
                                        onDismiss()
                                    }.padding(vertical = 12.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(12.dp)
                                        .background(dot, CircleShape),
                            )
                            Text(
                                list.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 14.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
