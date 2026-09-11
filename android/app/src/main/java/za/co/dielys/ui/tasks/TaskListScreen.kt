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
import androidx.compose.foundation.layout.PaddingValues
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
    val newItemsOnTop by viewModel.newItemsOnTop.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<TaskEntity?>(null) }

    // The field's own text, lifted up here so the list above it can preview
    // what is being typed before it is saved.
    var draftText by remember { mutableStateOf("") }

    // The id and text of an add already sent to the view model — reserved at
    // submit time, before Room has a row for it. Separate from [draftText],
    // which clears immediately so the field is ready for the next item.
    //
    // The id is what lets the ghost row and the real row Room eventually
    // produces be *one* LazyColumn item throughout: both are keyed on it, so
    // the swap between them is a slot's content changing, not a second row
    // appearing next to the first (see ghostAtTop's item(key = ...) below).
    var pendingId by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    val ghostText = pendingText ?: draftText.ifBlank { null }

    // The order a drag is producing, before the write has come back through Room.
    var draft by remember { mutableStateOf<List<TaskEntity>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val active = draft ?: board.active

    // Local to this phone — read once per list rather than watched, since
    // nothing else changes it while this screen is open.
    var doneExpanded by remember(listId) { mutableStateOf(viewModel.isDoneExpanded(listId)) }

    LaunchedEffect(board.active, draft) {
        val current = draft ?: return@LaunchedEffect
        val wanted =
            draftStillWanted(current.map { it.id }, board.active.map { it.id })
        if (!wanted) draft = null
    }

    // What was just added on this device: the row lights up once it is really
    // there, because a new item appearing at whichever edge is scrolled out of
    // view otherwise shows no sign it is the one just typed. The scroll itself
    // happens earlier, at the first keystroke (see ghostText above) — by the
    // time this fires, that edge is already in view.
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active, pendingId) {
        val id = pendingId ?: return@LaunchedEffect
        if (active.none { it.id == id }) return@LaunchedEffect
        highlighted = id
        pendingId = null
        pendingText = null
    }

    // The header wears the same colour as this list's dot on the Lists screen,
    // so opening a list is visibly the same list you tapped.
    val accent = listAccent(listId)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                expandedHeight = 80.dp,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(12.dp).background(accent, CircleShape))
                        Text(
                            list?.displayTitle ?: "",
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    SyncStatus(pending = pending, stuck = stuck)
                },
            )
        },
        bottomBar = {
            AddTaskBar(
                value = draftText,
                onValueChange = { draftText = it },
                onSubmit = {
                    val trimmed = draftText.trim()
                    draftText = ""
                    if (trimmed.isNotEmpty()) {
                        val id = viewModel.add(trimmed)
                        if (id != null) {
                            pendingId = id
                            pendingText = trimmed
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (board.isEmpty && ghostText == null) {
                Empty()
            } else {
                Tasks(
                    active = active,
                    done = board.done,
                    draggingId = draggingId,
                    highlightedId = highlighted,
                    ghostText = ghostText,
                    ghostId = pendingId,
                    ghostAtTop = newItemsOnTop,
                    doneExpanded = doneExpanded,
                    onToggleDoneExpanded = {
                        doneExpanded = !doneExpanded
                        viewModel.setDoneExpanded(listId, doneExpanded)
                    },
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
            title = "Edit",
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
    ghostText: String?,
    ghostId: String?,
    ghostAtTop: Boolean,
    doneExpanded: Boolean,
    onToggleDoneExpanded: () -> Unit,
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

    // Keyed on ghostId once there is one, so the placeholder and the real row
    // Room eventually produces are the same LazyColumn item — the id carries
    // across the swap, so there is never a moment with both on screen at once.
    // Before a submit there is no id yet; a constant stands in for it, which
    // costs nothing since a ghost never collides with a real task's key.
    val ghostKey = ghostId ?: GHOST_TYPING_KEY
    val ghostVisible = ghostText != null && (ghostId == null || active.none { it.id == ghostId })

    // A new item lands wherever the setting says, so that is where the screen
    // goes — as soon as there is something to show there, not once the write
    // comes back. Keyed on whether there is a ghost at all, not the text
    // itself, so typing further characters does not re-trigger the scroll.
    LaunchedEffect(ghostText != null) {
        if (ghostText == null) return@LaunchedEffect
        val target = if (ghostAtTop) 0 else active.size
        listState.animateScrollToItem(target)
    }

    LazyColumn(
        state = listState,
        // A gap between cards, not a line inside one: CARD_GAP is what keeps
        // consecutive rows from reading as a single block now that each one
        // stands on its own surface. The same gap trails the last card, so it
        // does not read as attached to the add-item bar below it.
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        contentPadding = PaddingValues(bottom = CARD_GAP),
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
        if (ghostAtTop && ghostVisible) {
            item(key = ghostKey) {
                GhostRow(text = ghostText ?: "", modifier = Modifier.animateItem())
            }
        }

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

        if (!ghostAtTop && ghostVisible) {
            item(key = ghostKey) {
                GhostRow(text = ghostText ?: "", modifier = Modifier.animateItem())
            }
        }

        if (done.isNotEmpty()) {
            item(key = "done-heading") {
                DoneHeading(
                    count = done.size,
                    expanded = doneExpanded,
                    onClick = onToggleDoneExpanded,
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

/**
 * A stand-in for the row that is about to exist: same shape as [TaskRow], so
 * the swap to the real thing once it is saved is that same LazyColumn item's
 * content changing, not a different element appearing in its place. Unchecked
 * and unstarred always — a task that does not exist yet cannot be either.
 */
@Composable
private fun GhostRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = GHOST_SURFACE_ALPHA),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.padding(start = 2.dp).size(CHECKBOX_TOUCH_TARGET),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .border(
                                2.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = GHOST_TEXT_ALPHA),
                                RoundedCornerShape(5.dp),
                            ),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = GHOST_TEXT_ALPHA),
                modifier = Modifier.weight(1f).padding(start = 8.dp, top = 16.dp, bottom = 16.dp),
            )
        }
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

    // Tapping the title does nothing but acknowledge the tap — editing is a
    // deliberate action off the options menu, not a side effect of trying to
    // read a long line. The shared interaction source puts the ripple on the
    // Row while the click target stays the Text, so it does not read as if
    // only the words were hit.
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
                            onClick = {},
                        ).padding(start = 8.dp, top = 16.dp, bottom = 16.dp)
                        .alpha(if (task.done) DONE_ALPHA else 1f),
            )

            // Starring and renaming are both about what is still to do; a done
            // task keeps only the option to remove it.
            if (!task.done) {
                IconButton(onClick = onStar) {
                    Icon(
                        imageVector = if (task.starred) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (task.starred) "Unstar" else "Star",
                        // A solid star at full alpha read as nearly as bright as
                        // a starred row's fill, so every row looked starred at a
                        // glance. The outlined glyph's own empty centre now does
                        // that work instead of a dimmed fill — full alpha, so it
                        // still reads clearly as a star, just not filled.
                        tint =
                            if (task.starred) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Task options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!task.done) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onRename()
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
private fun AddTaskBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val dark = isSystemInDarkTheme()

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
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Add an item") },
                singleLine = true,
                shape = PillShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .size(52.dp)
                        .background(
                            color =
                                if (value.isBlank()) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                } else if (dark) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            shape = CircleShape,
                        ).clickable(enabled = value.isNotBlank(), onClick = onSubmit),
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

/** Faint enough to read as not-yet-real without the row's text going hard to read. */
private const val GHOST_SURFACE_ALPHA = 0.5f
private const val GHOST_TEXT_ALPHA = 0.55f

/** Stands in for the ghost row's key before there is a real task id to use. */
private const val GHOST_TYPING_KEY = "ghost-typing"

/** NavyDeep — the check glyph reads dark against the dark-mode done checkbox's amber fill. */
private val CheckGlyphOnAmber = Color(0xFF0E1728)
