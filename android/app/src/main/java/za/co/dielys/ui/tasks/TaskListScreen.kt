package za.co.dielys.ui.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.ui.SyncBanner
import za.co.dielys.ui.TextPrompt
import za.co.dielys.ui.lists.displayTitle
import za.co.dielys.ui.theme.PillShape

/**
 * One list. Everything on it comes from Room and every action writes to Room —
 * the network appears nowhere on this screen, which is the whole point (E1.2).
 *
 * A drag starts on a long press anywhere in a row's body. The buttons on the
 * right take their own taps, so the drag is picked up from the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    listId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskListViewModel = viewModel(),
) {
    LaunchedEffect(listId) { viewModel.open(listId) }

    val list by viewModel.list.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val stuck by viewModel.stuck.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<TaskEntity?>(null) }

    // The order a drag is producing, before the write has come back through Room.
    var draft by remember { mutableStateOf<List<TaskEntity>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val active = draft ?: board.active

    LaunchedEffect(board.active, draft) {
        val current = draft ?: return@LaunchedEffect
        val wanted =
            draftStillWanted(current.map { it.id }, board.active.map { it.id })
        if (!wanted) draft = null
    }

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(list?.displayTitle ?: "") },
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
        bottomBar = { AddTaskBar(onAdd = viewModel::add) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SyncBanner(pending = pending, stuck = stuck)

            if (board.isEmpty) {
                Empty()
            } else {
                Tasks(
                    active = active,
                    done = board.done,
                    draggingId = draggingId,
                    onDragStart = { index -> draggingId = active.getOrNull(index)?.id },
                    onDragMove = { from, to -> draft = active.moved(from, to) },
                    onDragEnd = {
                        commitMove(draft, draggingId, viewModel::move)
                        draggingId = null
                    },
                    onDragCancel = {
                        draft = null
                        draggingId = null
                    },
                    onToggle = viewModel::setDone,
                    onStar = viewModel::setStarred,
                    onRename = { renaming = it },
                    onDelete = viewModel::delete,
                )
            }
        }
    }

    renaming?.let { task ->
        TextPrompt(
            title = "Rename",
            label = "Task",
            initial = task.title,
            onDismiss = { renaming = null },
            onConfirm = { viewModel.rename(task.id, it) },
        )
    }
}

/**
 * Reads the two rows the dragged one ended up between and asks for exactly that
 * move. Null on either side is an end of the list, which is what
 * `Position.between` expects.
 */
private fun commitMove(
    order: List<TaskEntity>?,
    movedId: String?,
    move: (String, String?, String?) -> Unit,
) {
    if (order == null || movedId == null) return
    val index = order.indexOfFirst { it.id == movedId }
    if (index < 0) return
    move(movedId, order.getOrNull(index - 1)?.id, order.getOrNull(index + 1)?.id)
}

@Composable
private fun Tasks(
    active: List<TaskEntity>,
    done: List<TaskEntity>,
    draggingId: String?,
    onDragStart: (Int) -> Unit,
    onDragMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onStar: (String, Boolean) -> Unit,
    onRename: (TaskEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder = remember(listState) { ReorderState(listState) }
    val count = rememberUpdatedState(active.size)
    val start = rememberUpdatedState(onDragStart)
    val moveRow = rememberUpdatedState(onDragMove)
    val end = rememberUpdatedState(onDragEnd)
    val cancel = rememberUpdatedState(onDragCancel)
    // Default expanded: a person who just finished something wants to see it
    // land, not go hunting for a collapsed section.
    var doneExpanded by remember { mutableStateOf(true) }

    LazyColumn(
        state = listState,
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        reorder.start(offset.y, count.value)?.let { start.value(it) }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.drag(amount.y) { from, to -> moveRow.value(from, to) }
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
        items(active, key = { it.id }) { task ->
            val dragging = task.id == draggingId
            TaskRow(
                task = task,
                onToggle = { onToggle(task.id, it) },
                onStar = { onStar(task.id, !task.starred) },
                onRename = { onRename(task) },
                onDelete = { onDelete(task.id) },
                modifier =
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) reorder.draggingOffset else 0f
                            shadowElevation = if (dragging) DRAG_ELEVATION else 0f
                        },
            )
        }

        if (done.isNotEmpty()) {
            item(key = "done-heading") {
                DoneHeading(
                    count = done.size,
                    expanded = doneExpanded,
                    onClick = { doneExpanded = !doneExpanded },
                )
            }
            if (doneExpanded) {
                items(done, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { onToggle(task.id, it) },
                        onStar = { onStar(task.id, !task.starred) },
                        onRename = { onRename(task) },
                        onDelete = { onDelete(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneHeading(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "done-chevron",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
    ) {
        Text(
            "DONE ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = DONE_ALPHA),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse done" else "Expand done",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = DONE_ALPHA),
            modifier = Modifier.padding(start = 4.dp).rotate(rotation),
        )
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    onToggle: (Boolean) -> Unit,
    onStar: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TaskCheckbox(
                checked = task.done,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(start = 16.dp),
            )

            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onRename)
                        .padding(start = 14.dp, top = 14.dp, bottom = 14.dp)
                        .alpha(if (task.done) DONE_ALPHA else 1f),
            )

            IconButton(onClick = onStar) {
                Icon(
                    imageVector = if (task.starred) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = if (task.starred) "Unstar" else "Star",
                    tint =
                        if (task.starred) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Task options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
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
}

/**
 * A 20dp square rather than Material's default checkbox — the redesign calls
 * for it, and it doubles as the one place `done` gets its dark-mode amber
 * instead of navy, which the stock [androidx.compose.material3.Checkbox]'s
 * color slots do not cleanly express.
 */
@Composable
private fun TaskCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val fillColor =
        if (dark) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Box(
        modifier =
            modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .then(
                    if (checked) {
                        Modifier.background(fillColor)
                    } else {
                        Modifier.border(2.dp, borderColor, RoundedCornerShape(5.dp))
                    },
                ).clickable(onClick = { onCheckedChange(!checked) }),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = if (dark) CheckGlyphOnAmber else Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun AddTaskBar(onAdd: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val dark = isSystemInDarkTheme()

    val submit = {
        if (draft.isNotBlank()) {
            onAdd(draft)
            draft = ""
        }
    }

    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Add an item") },
                singleLine = true,
                shape = PillShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .size(52.dp)
                        .background(
                            color =
                                if (draft.isBlank()) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                } else if (dark) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            shape = CircleShape,
                        ).clickable(enabled = draft.isNotBlank(), onClick = submit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Add",
                    tint = Color.White,
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
        Text("Nothing on this list", style = MaterialTheme.typography.titleMedium)
        Text(
            "Type below to add the first thing.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val DRAG_ELEVATION = 12f
private const val DONE_ALPHA = 0.6f

/** NavyDeep — the check glyph reads dark against the dark-mode done checkbox's amber fill. */
private val CheckGlyphOnAmber = Color(0xFF0E1728)
