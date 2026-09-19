package za.co.dielys.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.R
import za.co.dielys.data.local.SyncPrefs
import za.co.dielys.ui.ConfirmPrompt

/**
 * Account details, signing out and deleting the account — everything that is
 * about the account rather than about any one list (E1.2: the screen reads
 * through the view model, never a repository directly).
 *
 * [onAccountDeleted] runs once the account is gone from the server and this
 * phone; it is the caller's sign-out, which takes Settings off the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onAccountDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state = viewModel.state
    val newItemsOnTop by viewModel.newItemsOnTop.collectAsStateWithLifecycle()
    val deletion by viewModel.deletion.collectAsStateWithLifecycle()

    LaunchedEffect(deletion) {
        if (deletion == AccountDeletion.Done) onAccountDeleted()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                expandedHeight = 80.dp,
                title = { Text(stringResource(R.string.settings_title)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        ) {
            SettingsCard {
                Text(
                    stringResource(R.string.settings_account).uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Text(
                    stringResource(R.string.label_email),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Text(
                    state.email ?: stringResource(R.string.settings_email_unknown),
                    fontSize = 14.5.sp,
                )
            }

            SettingsCard {
                CardTitle(
                    stringResource(R.string.settings_new_items_go_to),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PillSegmented(
                    options =
                        listOf(
                            stringResource(R.string.settings_top),
                            stringResource(R.string.settings_bottom),
                        ),
                    selectedIndex = if (newItemsOnTop) 0 else 1,
                    onSelect = { viewModel.setNewItemsOnTop(it == 0) },
                )
            }

            LanguageSection(viewModel)

            BackgroundSyncSection(viewModel)

            PrivacyPolicySection(modifier = Modifier.align(Alignment.CenterHorizontally))

            // Outlined, not red: signing out is ordinary and undoable.
            OutlinedButton(
                onClick = onSignOut,
                border =
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f),
                    ),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp),
            ) {
                Text(
                    stringResource(R.string.settings_sign_out),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            DeleteAccountSection(
                deletion = deletion,
                onDelete = viewModel::deleteAccount,
            )
        }
    }
}

/** One group of settings: the redesign's rounded navy (white, in light) card. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            content = content,
        )
    }
}

@Composable
private fun CardTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/**
 * A pill-shaped either/or, matching web's: the choice fills amber on a
 * recessed track. Material's `SegmentedButton` draws outlined boxes with a
 * check mark instead, which read as two separate buttons.
 */
@Composable
private fun PillSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .background(MaterialTheme.colorScheme.background, CircleShape)
                .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill = if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(index) },
                            role = Role.RadioButton,
                        ),
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.onSecondary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                )
            }
        }
    }
}

/** Looks like a dropdown field; tapping it opens the dialog that does the
 * actual choosing (a long language list, or a freeform frequency). */
@Composable
private fun PickerField(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), shape)
                .background(MaterialTheme.colorScheme.background)
                .clickable(role = Role.DropdownList, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Play's User Data policy requires this link both in the store listing and
 * somewhere reachable inside the app itself — this is that second place. */
@Composable
private fun PrivacyPolicySection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://dielys.com/privacy")),
            )
        },
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.settings_privacy_policy),
            fontSize = 13.sp,
            textDecoration = TextDecoration.Underline,
        )
    }
}

/**
 * Below a divider and quieter than everything else on the screen, so the
 * permanent action is never the one a thumb lands on by habit — the prompt is
 * where it gets its red. The prompt
 * says what goes and what stays, because "delete account" alone does not tell
 * somebody that the lists they share will carry on without them (ADR 0007).
 */
@Composable
private fun DeleteAccountSection(
    deletion: AccountDeletion,
    onDelete: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val busy = deletion == AccountDeletion.Busy || deletion == AccountDeletion.Done
    val label = if (busy) R.string.settings_deleting_account else R.string.settings_delete_account

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
        TextButton(
            onClick = { confirming = true },
            enabled = !busy,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                ),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(label), fontSize = 12.sp)
        }

        if (deletion is AccountDeletion.Failed) {
            Text(
                deletion.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (confirming) {
        ConfirmPrompt(
            title = stringResource(R.string.confirm_delete_account_title),
            body = stringResource(R.string.confirm_delete_account_body),
            confirm = stringResource(R.string.action_delete_account),
            onConfirm = onDelete,
            onDismiss = { confirming = false },
        )
    }
}

/**
 * #42: one of eleven official South African languages, or "System default"
 * (null) to just follow the phone's own language. [SettingsViewModel] reads
 * the current choice fresh rather than as a `StateFlow` because picking one
 * recreates every Activity (via `AppCompatDelegate`) — this screen is gone by
 * the time the value could change out from under it.
 */
@Composable
private fun LanguageSection(viewModel: SettingsViewModel) {
    var pickerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentTag = viewModel.languageTag
    val currentName =
        AppLanguage.entries.firstOrNull { it.tag == currentTag }?.nativeName
            ?: stringResource(R.string.settings_language_system_default)

    SettingsCard {
        CardTitle(
            stringResource(R.string.settings_language),
            modifier = Modifier.padding(bottom = 10.dp),
        )
        PickerField(value = currentName, onClick = { pickerOpen = true })
    }

    if (pickerOpen) {
        LanguagePickerDialog(
            currentTag = currentTag,
            onSelect = {
                viewModel.setLanguage(it)
                pickerOpen = false
                if (viewModel.restartIsOurs) context.findActivity()?.recreate()
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    currentTag: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LanguageOption(
                    label = stringResource(R.string.settings_language_system_default),
                    selected = currentTag == null,
                    onClick = { onSelect(null) },
                )
                AppLanguage.entries.forEach { language ->
                    LanguageOption(
                        label = language.nativeName,
                        selected = currentTag == language.tag,
                        onClick = { onSelect(language.tag) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** The Activity behind a Compose `LocalContext`, which is it or wraps it. */
private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The whole row is the tap target, not just the radio circle — a language
    // name is a much bigger, easier target than a small dot next to it.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * The half-hourly floor (H3.12), as something a person can turn off or
 * stretch out. Push and the socket both still work either way — this only
 * trades away the guarantee that a change shows up even when both of those
 * miss.
 */
@Composable
private fun BackgroundSyncSection(viewModel: SettingsViewModel) {
    var pickerOpen by remember { mutableStateOf(false) }
    val enabled by viewModel.syncEnabled.collectAsStateWithLifecycle()
    val intervalMinutes by viewModel.syncIntervalMinutes.collectAsStateWithLifecycle()

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(
                stringResource(R.string.settings_background_sync),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = viewModel::setSyncEnabled,
                colors =
                    SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.secondary,
                        checkedThumbColor = Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        uncheckedThumbColor = Color.White,
                        uncheckedBorderColor = Color.Transparent,
                    ),
            )
        }
        if (enabled) {
            PickerField(
                value = frequencyLabel(intervalMinutes),
                onClick = { pickerOpen = true },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    if (pickerOpen) {
        SyncFrequencyDialog(
            currentMinutes = intervalMinutes,
            onConfirm = {
                viewModel.setSyncIntervalMinutes(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun frequencyLabel(minutes: Long): String =
    if (minutes % MINUTES_PER_HOUR == 0L) {
        val hours = (minutes / MINUTES_PER_HOUR).toInt()
        pluralStringResource(R.plurals.settings_sync_frequency_every_hours, hours, hours)
    } else {
        pluralStringResource(
            R.plurals.settings_sync_frequency_every_minutes,
            minutes.toInt(),
            minutes,
        )
    }

/**
 * A number and a unit rather than a fixed list of presets — "a custom time
 * span" means whatever number someone actually wants, not a pick from ours.
 * Clamped no lower than [SyncPrefs.MIN_INTERVAL_MINUTES]: `PeriodicWorkRequest`
 * refuses anything shorter itself, so a smaller value here would just be a
 * promise the platform will not keep. Anything over
 * [SyncPrefs.MAX_INTERVAL_MINUTES] cannot be saved at all: the server rejects
 * it, so it could never reach `web/` or another phone.
 */
@Composable
private fun SyncFrequencyDialog(
    currentMinutes: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val startInHours = currentMinutes % MINUTES_PER_HOUR == 0L
    var hours by remember { mutableStateOf(startInHours) }
    var text by remember {
        mutableStateOf(
            if (startInHours) {
                (currentMinutes / MINUTES_PER_HOUR).toString()
            } else {
                currentMinutes.toString()
            },
        )
    }
    // Capped before multiplying so a long run of digits cannot overflow into
    // something that looks valid.
    val minutes =
        text.toLongOrNull()?.takeIf { it <= SyncPrefs.MAX_INTERVAL_MINUTES }?.let {
            if (hours) it * MINUTES_PER_HOUR else it
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sync_frequency_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> if (new.all(Char::isDigit)) text = new },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    SegmentedButton(
                        selected = !hours,
                        onClick = { hours = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.settings_sync_frequency_unit_minutes))
                    }
                    SegmentedButton(
                        selected = hours,
                        onClick = { hours = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.settings_sync_frequency_unit_hours))
                    }
                }
                Text(
                    stringResource(R.string.settings_sync_frequency_minimum),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(minutes!!.coerceAtLeast(SyncPrefs.MIN_INTERVAL_MINUTES))
                },
                enabled = minutes != null && minutes in 1..SyncPrefs.MAX_INTERVAL_MINUTES,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val MINUTES_PER_HOUR = 60L
