package za.co.dielys.ui.lists

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import za.co.dielys.R
import za.co.dielys.domain.ACCENT_COUNT
import za.co.dielys.ui.theme.accentColor

/**
 * The list's colour, picked off its options menu (#57).
 *
 * Applied on the tap, not on a Save button: the only thing the dialog changes
 * is a colour the person is looking at, so confirming it a second time adds a
 * step and tells them nothing. Cancel closes it; there is nothing to undo that
 * re-picking the old swatch does not.
 *
 * [selected] is null on a list that has no stored colour yet — nothing is ringed
 * then, which is honest: the dot is wearing the hashed fallback, not one of
 * these.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentPicker(
    selected: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.colour_list_title)) },
        text = {
            // Two rows of four rather than one row of eight: eight swatches at a
            // thumb-sized target do not fit across a narrow phone, and a wrap
            // left to itself lands unevenly. FlowRow rather than a fixed grid so
            // it still wraps further at a large font scale instead of clipping.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = ACCENT_COUNT / 2,
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(ACCENT_COUNT) { index ->
                    Swatch(
                        index = index,
                        chosen = index == selected,
                        onClick = { onPick(index) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * One colour. The ring is drawn outside the circle rather than over it, so the
 * swatch is exactly the colour it promises — a border laid on top would tint
 * the edge of every one of them.
 */
@Composable
private fun Swatch(
    index: Int,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    // Animated, so picking a colour moves the ring across rather than blinking
    // it from one swatch to the next.
    val ring by animateDpAsState(if (chosen) RING_WIDTH else 0.dp, label = "accent-ring")
    val description = stringResource(R.string.colour_option, index + 1)

    Box(
        modifier =
            Modifier
                .size(TOUCH_TARGET)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = description
                    selected = chosen
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(SWATCH_SIZE)
                    .border(ring, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .padding(ring + RING_GAP)
                    .background(accentColor(index), CircleShape),
        )
    }
}

/** Material's minimum: these are tapped with a thumb like everything else. */
private val TOUCH_TARGET = 48.dp
private val SWATCH_SIZE = 40.dp
private val RING_WIDTH = 2.dp

/** Keeps the ring clear of the colour, so it reads as a ring and not a rim. */
private val RING_GAP = 3.dp
