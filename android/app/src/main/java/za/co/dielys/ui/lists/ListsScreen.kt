package za.co.dielys.ui.lists

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.dielys.R
import za.co.dielys.data.local.ListEntity
import za.co.dielys.ui.SyncStatus
import za.co.dielys.ui.TextPrompt
import za.co.dielys.ui.reorder.ReorderState
import za.co.dielys.ui.reorder.draftStillWanted
import za.co.dielys.ui.reorder.moved
import za.co.dielys.ui.theme.PillShape
import za.co.dielys.ui.theme.accentColor
import za.co.dielys.ui.theme.listAccent

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
    val anySharedList by viewModel.anySharedList.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ListEntity?>(null) }
    var colouring by remember { mutableStateOf<String?>(null) }

    // The order a drag is producing, held here until Room agrees with it — the
    // same two-answers-for-a-moment problem the task list has, solved the same way.
    var draft by remember { mutableStateOf<List<ListRow>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val shown = draft ?: rows

    LaunchedEffect(rows, draft) {
        val current = draft ?: return@LaunchedEffect
        if (!draftStillWanted(current.map { it.list.id }, rows.map { it.list.id })) draft = null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                expandedHeight = 96.dp,
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.lists_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.lists_tagline),
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
                    if (anySharedList) {
                        SyncStatus(pending = pending, stuck = stuck)
                    }
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
                Lists(
                    rows = shown,
                    draggingId = draggingId,
                    onDragStart = { index -> draggingId = shown.getOrNull(index)?.list?.id },
                    onDragMove = { from, to -> draft = shown.moved(from, to) },
                    onDragEnd = {
                        commitMove(draft, draggingId, viewModel::move)
                        draggingId = null
                    },
                    onDragCancel = {
                        draft = null
                        draggingId = null
                    },
                    onOpen = onOpen,
                    onRename = { renaming = it },
                    onColour = { colouring = it.id },
                    onShare = viewModel::invite,
                    onDelete = viewModel::delete,
                    modifier = Modifier.weight(1f),
                )
                // Anchored, not the last row: it stays put as the list grows past
                // a screenful, so making a list is always a reach to the same spot.
                NewListRow(
                    onClick = { creating = true },
                    modifier = Modifier.padding(horizontal = 16.dp).navigationBarsPadding(),
                )
            }
        }
    }

    if (creating) {
        TextPrompt(
            title = stringResource(R.string.new_list_label),
            label = stringResource(R.string.label_name),
            confirm = stringResource(R.string.action_create),
            onDismiss = { creating = false },
            onConfirm = viewModel::create,
        )
    }

    renaming?.let { list ->
        TextPrompt(
            title = stringResource(R.string.rename_list_title),
            label = stringResource(R.string.label_name),
            initial = list.title,
            onDismiss = { renaming = null },
            onConfirm = { viewModel.rename(list.id, it) },
        )
    }

    // Read back out of [rows] rather than captured when the menu was tapped, so
    // the ring follows each pick while the dialog is still open.
    colouring?.let { listId ->
        AccentPicker(
            selected = rows.firstOrNull { it.list.id == listId }?.accent,
            onPick = { viewModel.setAccent(listId, it) },
            onDismiss = { colouring = null },
        )
    }

    invite?.let { state ->
        InviteDialog(
            state = state,
            onSend = viewModel::sendInvite,
            onDismiss = viewModel::dismissInvite,
        )
    }
}

/**
 * The rows, and the long press that reorders them. The gesture lives on the
 * `LazyColumn` rather than on each row, so a finger that drifts sideways off the
 * row it grabbed keeps dragging it.
 */
@Composable
private fun Lists(
    rows: List<ListRow>,
    draggingId: String?,
    onDragStart: (Int) -> Unit,
    onDragMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (ListEntity) -> Unit,
    onColour: (ListEntity) -> Unit,
    onShare: (ListEntity) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reorder =
        remember(listState) {
            ReorderState(
                listState,
                scope,
                edgePx = with(density) { EDGE_SCROLL_ZONE.toPx() },
                maxScrollPxPerTick = with(density) { EDGE_SCROLL_SPEED.toPx() },
            )
        }
    val count = rememberUpdatedState(rows.size)
    val start = rememberUpdatedState(onDragStart)
    val move = rememberUpdatedState(onDragMove)
    val end = rememberUpdatedState(onDragEnd)
    val cancel = rememberUpdatedState(onDragCancel)

    LazyColumn(
        state = listState,
        // A gap between cards, not a line inside one: CARD_GAP is what keeps
        // consecutive rows from reading as a single block now that each one
        // stands on its own surface.
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        modifier =
            modifier.padding(horizontal = 16.dp).pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        reorder
                            .start(offset.y, count.value) { from, to -> move.value(from, to) }
                            ?.let { start.value(it) }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.drag(amount.y)
                    },
                    onDragEnd = {
                        reorder.stop()
                        end.value()
                    },
                    onDragCancel = {
                        reorder.stop()
                        cancel.value()
                    },
                )
            },
    ) {
        items(rows, key = { row -> row.list.id }) { row ->
            val dragging = row.list.id == draggingId
            val lift by animateFloatAsState(if (dragging) 1f else 0f, label = "list-drag-lift")
            ListRow(
                row = row,
                // The stored colour when there is one; a list that arrived
                // moments ago and has not been given one yet wears the hashed
                // fallback rather than nothing (#57).
                dotColor =
                    row.accent?.let(::accentColor) ?: listAccent(row.list.id),
                onOpen = { onOpen(row.list.id) },
                onRename = { onRename(row.list) },
                onColour = { onColour(row.list) },
                onShare = { onShare(row.list) },
                onDelete = { onDelete(row.list.id) },
                dragging = dragging,
                modifier =
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        // Placement animation on every row but the dragged one:
                        // that one is already being placed by the finger, and two
                        // things moving it at once reads as lag.
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .graphicsLayer {
                            translationY = if (dragging) reorder.draggingOffset else 0f
                            // Square corners throughout — no shape or clip, so
                            // there is nothing for the lift to round.
                            shadowElevation = lift * DRAG_ELEVATION
                            scaleX = 1f + lift * DRAG_SCALE
                            scaleY = 1f + lift * DRAG_SCALE
                        },
            )
        }
    }
}

/**
 * Reads the two rows the dragged one ended up between and asks for exactly that
 * move. Null on either side is an end of the screen, which is what
 * `Position.between` expects.
 */
private fun commitMove(
    order: List<ListRow>?,
    movedId: String?,
    move: (String, String?, String?) -> Unit,
) {
    if (order == null || movedId == null) return
    val index = order.indexOfFirst { it.list.id == movedId }
    if (index < 0) return
    move(movedId, order.getOrNull(index - 1)?.list?.id, order.getOrNull(index + 1)?.list?.id)
}

@Composable
private fun ListRow(
    row: ListRow,
    dotColor: Color,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onColour: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val list = row.list
    // A card of its own, not a transparent row over the page background — the
    // dot and title used to sit directly on the scaffold; now every row has a
    // surface, so cards separated by CARD_GAP actually read as separate cards.
    val background by animateColorAsState(
        targetValue =
            if (dragging) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        label = "list-drag-background",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .background(background)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).background(dotColor, CircleShape))

        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                list.displayTitle(LocalContext.current),
                style = MaterialTheme.typography.titleMedium,
            )
            // Until the first sync lands there is no server timestamp, which is
            // exactly the "made offline, not sent yet" state worth showing.
            if (list.updatedAt == null) {
                Text(
                    stringResource(R.string.not_synced_yet),
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
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_list_options),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                // Offered on every list, owned or not: the colour is this
                // phone's own way of telling its lists apart (#57), so it is
                // not a thing the owner of a shared list gets to decide.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_colour)) },
                    onClick = {
                        menuOpen = false
                        onColour()
                    },
                )
                // L3: only the owner may invite, so a list somebody else shared
                // does not offer it rather than offering it and being refused.
                if (list.ownedByMe) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
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
private fun NewListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
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
            stringResource(R.string.new_list_label),
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
        Text(
            stringResource(R.string.empty_lists_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.empty_lists_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        Button(onClick = onCreate, shape = PillShape, modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.create_first_list))
        }
    }
}

private const val ALPHA_MUTED = 0.65f

/** Breathing room between cards, so neighbours read as separate surfaces. */
private val CARD_GAP = 8.dp

/** Matches the task list's lift, so a dragged row looks the same on both screens. */
private const val DRAG_ELEVATION = 12f
private const val DRAG_SCALE = 0.02f

/** #45: same auto-scroll edge and speed as the task list, for the same feel. */
private val EDGE_SCROLL_ZONE = 64.dp
private val EDGE_SCROLL_SPEED = 12.dp
