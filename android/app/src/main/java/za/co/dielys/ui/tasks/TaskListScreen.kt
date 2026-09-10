package za.co.dielys.ui.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
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
import za.co.dielys.ui.SyncStatus
import za.co.dielys.ui.TextPrompt
import za.co.dielys.ui.lists.displayTitle
import za.co.dielys.ui.reorder.ReorderState
import za.co.dielys.ui.reorder.draftStillWanted
import za.co.dielys.ui.reorder.moved
import za.co.dielys.ui.theme.PillShape
import za.co.dielys.ui.theme.listAccent
import za.co.dielys.ui.theme.onListAccent

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

    // What was just added on this device: the list scrolls to it and the row
    // lights up, because a new item at the top of a list that is scrolled down is
    // otherwise added out of sight.
    val added by viewModel.added.collectAsStateWithLifecycle()
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(added) {
        val id = added ?: return@LaunchedEffect
        highlighted = id
        viewModel.addSeen()
    }

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

    // The header wears the same colour as this list's dot on the Lists screen,
    // so opening a list is visibly the same list you tapped.
    val accent = listAccent(listId)
    val onAccent = onListAccent(accent)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(list?.displayTitle ?: "") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = accent,
                        titleContentColor = onAccent,
                        navigationIconContentColor = onAccent,
                    ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    SyncStatus(
                        pending = pending,
                        stuck = stuck,
                        labelColor = onAccent.copy(alpha = 0.85f),
                    )
                },
            )
        },
        bottomBar = { AddTaskBar(onAdd = viewModel::add) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (board.isEmpty) {
                Empty()
            } else {
                Tasks(
                    active = active,
                    done = board.done,
                    draggingId = draggingId,
                    highlightedId = highlighted,
                    scrollTo = added,
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
    highlightedId: String?,
    scrollTo: String?,
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

    // Added tasks go to the top, so that is where the screen goes. Animated, so
    // it is visibly the list moving rather than a different list appearing.
    LaunchedEffect(scrollTo) {
        if (scrollTo != null) listState.animateScrollToItem(0)
    }

    LazyColumn(
        state = listState,
        // A gap between cards, not a line inside one: CARD_GAP is what keeps
        // consecutive rows from reading as a single block now that each one
        // stands on its own surface.
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        modifier =
            Modifier.fillMaxSize().padding(horizontal = 16.dp).pointerInput(Unit) {
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
            // Animated so the row eases back down on drop; the offset itself
            // stays unanimated, because it has to track the finger exactly.
            val lift by animateFloatAsState(if (dragging) 1f else 0f, label = "drag-lift")
            TaskRow(
                task = task,
                dragging = dragging,
                highlighted = task.id == highlightedId,
                onToggle = { onToggle(task.id, it) },
                onStar = { onStar(task.id, !task.starred) },
                onRename = { onRename(task) },
                onDelete = { onDelete(task.id) },
                modifier =
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        // Placement animation on every row but the dragged one:
                        // that one is already being placed by the finger, and two
                        // things moving it at once reads as lag. This is what makes
                        // a starred task visibly travel to the top instead of
                        // teleporting there.
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .graphicsLayer {
                            translationY = if (dragging) reorder.draggingOffset else 0f
                            // The whole row lifts as one card: shadowed and
                            // slightly larger, rather than a ripple boxed around
                            // the title. Square corners throughout — no shape or
                            // clip here, so there is nothing for the lift to round.
                            shadowElevation = lift * DRAG_ELEVATION
                            scaleX = 1f + lift * DRAG_SCALE
                            scaleY = 1f + lift * DRAG_SCALE
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
                        modifier = Modifier.animateItem(),
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
    dragging: Boolean = false,
    highlighted: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue =
            if (dragging) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        label = "drag-background",
    )

    // Starring moves the row to the top, and a row that simply arrives somewhere
    // else is hard to follow. It lights up instead: the glow is what the eye
    // tracks on the way up, and it is gone by the time the row settles.
    //
    // The state is remembered against the item key, so it survives the move —
    // this is the same composition travelling, not a new row appearing.
    val glow = remember { Animatable(0f) }
    var wasStarred by remember { mutableStateOf(task.starred) }
    val glowColor = MaterialTheme.colorScheme.secondary
    LaunchedEffect(task.starred) {
        if (task.starred && !wasStarred) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(STAR_GLOW_MILLIS, easing = LinearOutSlowInEasing))
        }
        wasStarred = task.starred
    }

    // The same light, held longer: a task that was just typed stays lit while the
    // eye finds it, then fades rather than switching off.
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        glow.snapTo(1f)
        glow.animateTo(
            targetValue = 0f,
            animationSpec =
                keyframes {
                    durationMillis = ADDED_GLOW_MILLIS
                    1f at ADDED_GLOW_HOLD_MILLIS using LinearOutSlowInEasing
                    0f at ADDED_GLOW_MILLIS
                },
        )
    }

    // The title alone renames, but the press it belongs to draws across the whole
    // row: the shared interaction source puts the ripple on the Row while the
    // click stays on the Text, so a tap does not read as if only the words were hit.
    val press = remember { MutableInteractionSource() }

    Surface(
        color = background,
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // Lifts with the light, so the row reads as picked up rather
                    // than repainted. Square, like every other card — no shape or
                    // clip, so the corners never round mid-animation.
                    shadowElevation = glow.value * STAR_GLOW_ELEVATION
                }.drawWithContent {
                    drawContent()
                    val strength = glow.value
                    if (strength > 0f) {
                        // Brightest at the leading edge — the side the row is
                        // travelling towards — and fading across the rest.
                        drawRect(
                            brush =
                                Brush.horizontalGradient(
                                    listOf(
                                        glowColor.copy(alpha = STAR_GLOW_ALPHA * strength),
                                        glowColor.copy(alpha = STAR_GLOW_ALPHA * 0.45f * strength),
                                        Color.Transparent,
                                    ),
                                ),
                        )
                    }
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.indication(press, ripple()),
        ) {
            TaskCheckbox(
                checked = task.done,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(start = 2.dp),
            )

            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = press,
                            indication = null,
                            onClick = onRename,
                        ).padding(start = 8.dp, top = 16.dp, bottom = 16.dp)
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

    // The tap target is the 48dp box Material asks for; the 20dp square inside is
    // only what it looks like. Ticking things off is done one-handed in a shop,
    // and a 20dp target is a miss half the time.
    Box(
        modifier =
            modifier
                .size(CHECKBOX_TOUCH_TARGET)
                .clip(CircleShape)
                .clickable(onClick = { onCheckedChange(!checked) }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .then(
                        if (checked) {
                            Modifier.background(fillColor)
                        } else {
                            Modifier.border(2.dp, borderColor, RoundedCornerShape(5.dp))
                        },
                    ),
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
            // union, not two paddings: with the keyboard up the IME inset already
            // covers the nav bar, and adding both leaves a gap under the field.
            // Scaffold does not inset its own bottomBar, so edge-to-edge would
            // otherwise slide this under the system buttons.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(8.dp),
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

/** Breathing room between cards, so neighbours read as separate surfaces. */
private val CARD_GAP = 8.dp

/** How long the star's glow takes to fade — long enough to follow the row up,
 * short enough that it is over before the next thing is tapped. */
private const val STAR_GLOW_MILLIS = 700
private const val STAR_GLOW_ALPHA = 0.38f
private const val STAR_GLOW_ELEVATION = 8f

/** Two seconds for something just typed: long enough to look up from the
 * keyboard and find it, and it fades instead of ending. */
private const val ADDED_GLOW_MILLIS = 2000
private const val ADDED_GLOW_HOLD_MILLIS = 700

/** Material's minimum, and what a thumb in a shop actually needs. */
private val CHECKBOX_TOUCH_TARGET = 48.dp
private const val DRAG_SCALE = 0.02f
private const val DONE_ALPHA = 0.6f

/** NavyDeep — the check glyph reads dark against the dark-mode done checkbox's amber fill. */
private val CheckGlyphOnAmber = Color(0xFF0E1728)
