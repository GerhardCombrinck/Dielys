package za.co.dielys.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.dielys.data.local.ListEntity
import za.co.dielys.ui.SyncStatus
import za.co.dielys.ui.TextPrompt
import za.co.dielys.ui.theme.PillShape

/** Cycles across rows in order — Room has no per-list color, so the identity is purely visual. */
private val ListDotColors =
    listOf(
        Color(0xFFE8A33D), // amber
        Color(0xFF7FA893), // sage
        Color(0xFF7C93C4), // dusty blue
        Color(0xFFC97B63), // terracotta
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    accountInitials: String,
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel,
) {
    val rows by viewModel.lists.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val stuck by viewModel.stuck.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ListEntity?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Lists",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Twee mense, één lys.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ALPHA_MUTED),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                actions = {
                    SyncStatus(pending = pending, stuck = stuck)
                    Box(
                        modifier =
                            Modifier
                                .padding(end = 16.dp)
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable(onClick = onSettings),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            accountInitials,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (rows.isEmpty()) {
                Empty(onCreate = { creating = true })
            } else {
                LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.list.id }) { index, row ->
                        ListRow(
                            row = row,
                            dotColor = ListDotColors[index % ListDotColors.size],
                            onOpen = { onOpen(row.list.id) },
                            onRename = { renaming = row.list },
                            onShare = { viewModel.invite(row.list) },
                            onDelete = { viewModel.delete(row.list.id) },
                        )
                    }
                    item(key = "new-list") {
                        NewListRow(onClick = { creating = true })
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
}

@Composable
private fun ListRow(
    row: ListRow,
    dotColor: Color,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val list = row.list

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(vertical = 16.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).background(dotColor, CircleShape))

        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
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

        Text(
            row.itemCount.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ALPHA_MUTED),
            modifier = Modifier.padding(end = 4.dp),
        )

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
private fun NewListRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 16.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Text(
            "New list",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun Empty(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No lists yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Make one for groceries, chores, or anything you're sharing.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        Button(onClick = onCreate, shape = PillShape, modifier = Modifier.height(48.dp)) {
            Text("Create your first list")
        }
    }
}

private const val ALPHA_MUTED = 0.65f
