package za.co.dielys.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import za.co.dielys.R

/**
 * The way into Settings, in the top right of both screens (#59).
 *
 * A gear rather than the initials chip this used to be: the initials were
 * decoration — there is no display name to show, only whatever was typed at
 * sign-in — and a circle with a letter in it does not tell anybody that
 * Settings is behind it.
 *
 * On both screens rather than only the lists one, because the header is the
 * same header; a control that is there on one screen and gone on the next makes
 * crossing between them feel like crossing between two apps.
 */
@Composable
fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.settings_title),
        )
    }
}
