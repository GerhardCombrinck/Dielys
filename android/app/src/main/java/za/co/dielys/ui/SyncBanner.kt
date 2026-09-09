package za.co.dielys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * What the outbox is holding, in one line.
 *
 * Pending is not a warning: writing offline is the normal case here, not an
 * error, so it reads as a count and nothing more. A dead row is different — the
 * server refused it and no retry will fix it, and the whole reason those rows are
 * kept instead of deleted is so somebody eventually sees them.
 */
@Composable
fun SyncBanner(
    pending: Int,
    stuck: Int,
    modifier: Modifier = Modifier,
) {
    if (stuck > 0) {
        Banner(
            text = "$stuck ${plural(stuck, "edit")} the server refused. They are still here.",
            background = MaterialTheme.colorScheme.errorContainer,
            foreground = MaterialTheme.colorScheme.onErrorContainer,
            modifier = modifier,
        )
    } else if (pending > 0) {
        Banner(
            text = "$pending ${plural(pending, "change")} waiting to sync",
            background = MaterialTheme.colorScheme.surfaceVariant,
            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

@Composable
private fun Banner(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        modifier =
            modifier
                .fillMaxWidth()
                .background(background)
                .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private fun plural(
    count: Int,
    noun: String,
): String = if (count == 1) noun else "${noun}s"
