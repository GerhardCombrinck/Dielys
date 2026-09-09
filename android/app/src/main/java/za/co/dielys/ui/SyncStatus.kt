package za.co.dielys.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A dot and a short word, in place of the banner that used to push the list
 * down whenever the outbox changed. The label has a fixed width regardless of
 * which of the three states is showing, so switching between them never
 * reflows anything to its left (the avatar on Lists, the overflow menu on the
 * task list) — that was the whole complaint about the old banner.
 *
 * Pending is not a warning: writing offline is the normal case here, not an
 * error, so it reads as amber and nothing more urgent. Stuck is different —
 * the server refused the edit and no retry will fix it — so it gets red and
 * the full explanation on tap, since the whole reason those rows are kept
 * instead of deleted is so somebody eventually sees them.
 */
@Composable
fun SyncStatus(
    pending: Int,
    stuck: Int,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
) {
    val context = LocalContext.current
    val (color, label, detail) =
        when {
            stuck > 0 ->
                Triple(
                    MaterialTheme.colorScheme.error,
                    "Error",
                    "$stuck ${plural(stuck, "edit")} the server refused. They are still here.",
                )

            pending > 0 ->
                Triple(
                    MaterialTheme.colorScheme.secondary,
                    "Syncing…",
                    "$pending ${plural(pending, "change")} waiting to sync.",
                )

            else -> Triple(SyncedGreen, "Synced", "Everything is up to date.")
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clickable { Toast.makeText(context, detail, Toast.LENGTH_SHORT).show() }
                .padding(horizontal = 8.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp).width(LABEL_WIDTH),
        )
    }
}

/** Fits "Syncing…", the longest of the three labels, without wrapping or truncating. */
private val LABEL_WIDTH = 56.dp

private val SyncedGreen = Color(0xFF4CAF50)

private fun plural(
    count: Int,
    noun: String,
): String = if (count == 1) noun else "${noun}s"
