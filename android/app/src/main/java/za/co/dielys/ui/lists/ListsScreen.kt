package za.co.dielys.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.data.local.ListEntity
import za.co.dielys.ui.SyncBanner
import za.co.dielys.ui.TextPrompt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onOpen: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel = viewModel(),
) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val stuck by viewModel.stuck.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()
    val invitation by viewModel.invitation.collectAsStateWithLifecycle()
    val joined by viewModel.joined.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ListEntity?>(null) }
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(joined) {
        val message = joined ?: return@LaunchedEffect
        snackbars.showSnackbar(message)
        viewModel.dismissJoined()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text("Dielys") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                actions = {
                    ScreenMenu(onJoin = { joining = true }, onSignOut = onSignOut)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New list")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SyncBanner(pending = pending, stuck = stuck)

            if (lists.isEmpty()) {
                Empty()
            } else {
                LazyColumn {
                    items(lists, key = { it.id }) { list ->
                        ListRow(
                            list = list,
                            onOpen = { onOpen(list.id) },
                            onRename = { renaming = list },
                            onShare = { viewModel.invite(list) },
                            onDelete = { viewModel.delete(list.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (creating) {
        TextPrompt(
            title = "New list",
            label = "Name",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = viewModel::create,
        )
    }

    renaming?.let { list ->
        TextPrompt(
            title = "Rename list",
            label = "Name",
            initial = list.title,
            onDismiss = { renaming = null },
            onConfirm = { viewModel.rename(list.id, it) },
        )
    }

    invite?.let { state ->
        InviteDialog(state = state, onDismiss = viewModel::dismissInvite)
    }

    if (joining) {
        JoinDialog(onDismiss = { joining = false }, onJoin = viewModel::join)
    }

    if (invitation != null) {
        InvitationDialog(
            onAccept = viewModel::acceptInvitation,
            onDecline = viewModel::declineInvitation,
        )
    }
}

/** Sign out is here rather than on its own icon so joining has somewhere to be. */
@Composable
private fun ScreenMenu(
    onJoin: () -> Unit,
    onSignOut: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Join a list") },
                onClick = {
                    open = false
                    onJoin()
                },
            )
            DropdownMenuItem(
                text = { Text("Sign out") },
                onClick = {
                    open = false
                    onSignOut()
                },
            )
        }
    }
}

@Composable
private fun ListRow(
    list: ListEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(list.displayTitle, style = MaterialTheme.typography.titleMedium)
            // Until the first sync lands there is no server timestamp, which is
            // exactly the "made offline, not sent yet" state worth showing.
            if (list.updatedAt == null) {
                Text(
                    "Not synced yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "List options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                // L3: only the owner may invite, so a list somebody else shared
                // does not offer it rather than offering it and being refused.
                if (list.ownedByMe) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun Empty() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No lists yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Make one with the + button, or use the menu to join one you were invited to.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
