package za.co.dielys.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

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
                title = { Text("Settings") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)

            Detail(label = "Email", value = state.email ?: "Unknown")

            Column(modifier = Modifier.padding(top = 32.dp)) {
                Text("New items go to", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    SegmentedButton(
                        selected = newItemsOnTop,
                        onClick = { viewModel.setNewItemsOnTop(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Top")
                    }
                    SegmentedButton(
                        selected = !newItemsOnTop,
                        onClick = { viewModel.setNewItemsOnTop(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("Bottom")
                    }
                }
            }

            Button(
                onClick = onSignOut,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            ) {
                Text("Sign out")
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
