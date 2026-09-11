package za.co.dielys.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.R

/**
 * Account details and signing out — everything that is about the account
 * rather than about any one list. Read-only aside from that one action (E1.2:
 * the screen reads through the view model, never a repository directly).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state = viewModel.state
    val newItemsOnTop by viewModel.newItemsOnTop.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                expandedHeight = 80.dp,
                title = { Text(stringResource(R.string.settings_title)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
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
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                stringResource(R.string.settings_account),
                style = MaterialTheme.typography.titleMedium,
            )

            Detail(
                label = stringResource(R.string.label_email),
                value = state.email ?: stringResource(R.string.settings_email_unknown),
            )

            Column(modifier = Modifier.padding(top = 32.dp)) {
                Text(
                    stringResource(R.string.settings_new_items_go_to),
                    style = MaterialTheme.typography.titleMedium,
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    SegmentedButton(
                        selected = newItemsOnTop,
                        onClick = { viewModel.setNewItemsOnTop(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.settings_top))
                    }
                    SegmentedButton(
                        selected = !newItemsOnTop,
                        onClick = { viewModel.setNewItemsOnTop(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.settings_bottom))
                    }
                }
            }

            LanguageSection(viewModel)

            Button(
                onClick = onSignOut,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            ) {
                Text(stringResource(R.string.settings_sign_out))
            }
        }
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
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

    Column(modifier = Modifier.padding(top = 32.dp)) {
        Text(
            stringResource(R.string.settings_language),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(
            onClick = { pickerOpen = true },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(currentName)
        }
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
