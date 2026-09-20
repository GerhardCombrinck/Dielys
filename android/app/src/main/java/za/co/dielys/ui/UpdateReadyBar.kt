package za.co.dielys.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.co.dielys.R

/**
 * Shown once a flexible update has finished downloading, to ask for the
 * restart that installs it.
 *
 * A bar rather than a dialog: the update is already on the device and will
 * install itself on the next natural restart regardless, so interrupting
 * whatever the user is in the middle of would be asking for a decision that
 * does not need making now.
 */
@Composable
fun UpdateReadyBar(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        ) {
            Text(stringResource(R.string.update_ready), fontSize = 13.5.sp)
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.update_restart), fontSize = 13.5.sp)
            }
        }
    }
}
