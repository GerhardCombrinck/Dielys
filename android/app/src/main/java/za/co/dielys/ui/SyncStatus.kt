package za.co.dielys.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.dielys.R

/**
 * A group icon that changes colour, in place of the dot-plus-word banner this
 * used to be. Only shown for a shared list at all (see call sites) — sync
 * status is only interesting once someone else can make the local copy go
 * stale, so a solo list has nothing here to show.
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
) {
    val context = LocalContext.current
    val (color, detail) =
        when {
            stuck > 0 ->
                MaterialTheme.colorScheme.error to
                    pluralStringResource(R.plurals.sync_stuck_detail, stuck, stuck)

            pending > 0 ->
                MaterialTheme.colorScheme.secondary to
                    pluralStringResource(R.plurals.sync_pending_detail, pending, pending)

            else -> SyncedGreen to stringResource(R.string.sync_up_to_date)
        }

    Icon(
        imageVector = Icons.Filled.Group,
        contentDescription = detail,
        tint = color,
        modifier =
            modifier
                .clickable { Toast.makeText(context, detail, Toast.LENGTH_SHORT).show() }
                .padding(8.dp),
    )
}

private val SyncedGreen = Color(0xFF4CAF50)
