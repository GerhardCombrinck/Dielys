package za.co.dielys.ui.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.R
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.ui.SyncStatus
import za.co.dielys.ui.TextPrompt
import za.co.dielys.ui.lists.displayTitle
import za.co.dielys.ui.reorder.ReorderState
import za.co.dielys.ui.reorder.draftStillWanted
import za.co.dielys.ui.reorder.moved
import za.co.dielys.ui.theme.PillShape
import za.co.dielys.ui.theme.listAccent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    // [list] and [board] live on one shared, retained ViewModel (there is no
    // navigation library to scope a fresh instance per list — see
    // DielysApp.kt), and the switch between lists happens through a
    // LaunchedEffect below rather than during this composition. So the very
    // first frame after opening a different list would otherwise still show
    // the previous list's rows — Done section included — for an instant,
    // before Room answers for this one. Comparing the ids is a cheap way to
    // tell "stale" from "mine" without waiting on that switch.
    val currentList = list?.takeIf { it.id == listId }
    val rawBoard by viewModel.board.collectAsStateWithLifecycle()
    val board = if (currentList != null) rawBoard else TaskBoard()
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
                            currentList?.displayTitle(LocalContext.current) ?: "",
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (currentList?.isShared == true) {
                        SyncStatus(pending = pending, stuck = stuck)
                    }
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
            title = stringResource(R.string.action_edit),
            label = stringResource(R.string.task_label),
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

/** The four callers of [taskDragGestures] out of [Tasks], bundled so the
 *  gesture wiring stays under the parameter-count threshold. */
private class DragCallbacks(
    val onStart: State<(Int) -> Unit>,
    val onMove: State<(Int, Int) -> Unit>,
    val onEnd: State<() -> Unit>,
    val onCancel: State<() -> Unit>,
)

/**
 * The long-press-drag gesture, pulled out of [Tasks] so that function's own
 * branching stays under the complexity threshold — this is wiring, not logic;
 * [ReorderState] and [edgeScrollDelta] are where the actual decisions live.
 */
private fun Modifier.taskDragGestures(
    reorder: ReorderState,
    count: State<Int>,
    callbacks: DragCallbacks,
): Modifier =
    pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                reorder
                    .start(offset.y, count.value) { from, to -> callbacks.onMove.value(from, to) }
                    ?.let { callbacks.onStart.value(it) }
            },
            onDrag = { change, amount ->
                change.consume()
                reorder.drag(amount.y)
            },
            onDragEnd = {
                reorder.stop()
                callbacks.onEnd.value()
            },
            onDragCancel = {
                reorder.stop()
                callbacks.onCancel.value()
            },
        )
    }

private data class GhostRowState(
    val key: Any,
    val visible: Boolean,
)

/**
 * The typed-but-not-yet-saved row: which `LazyColumn` key it renders under,
 * and whether it should show at all — plus the one-time scroll that brings it
 * into view when it first appears. Pulled out of [Tasks] to keep that
 * function's own branching under the complexity threshold.
 *
 * Keyed on `ghostId` once there is one, so the placeholder and the real row
 * Room eventually produces are the same `LazyColumn` item — the id carries
 * across the swap, so there is never a moment with both on screen at once.
 * Before a submit there is no id yet; a constant stands in for it, which
 * costs nothing since a ghost never collides with a real task's key.
 */
@Composable
private fun rememberGhostRowState(
    ghostText: String?,
    ghostId: String?,
    ghostAtTop: Boolean,
    active: List<TaskEntity>,
    listState: LazyListState,
): GhostRowState {
    val key = ghostId ?: GHOST_TYPING_KEY
    val visible = ghostText != null && (ghostId == null || active.none { it.id == ghostId })

    // A new item lands wherever the setting says, so that is where the screen
    // goes — as soon as there is something to show there, not once the write
    // comes back. Keyed on whether there is a ghost at all, not the text
    // itself, so typing further characters does not re-trigger the scroll.
    LaunchedEffect(ghostText != null) {
        if (ghostText == null) return@LaunchedEffect
        val target = if (ghostAtTop) 0 else active.size
        listState.animateScrollToItem(target)
    }

    return GhostRowState(key, visible)
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
    val count = rememberUpdatedState(active.size)
    val callbacks =
        DragCallbacks(
            onStart = rememberUpdatedState(onDragStart),
            onMove = rememberUpdatedState(onDragMove),
            onEnd = rememberUpdatedState(onDragEnd),
            onCancel = rememberUpdatedState(onDragCancel),
        )

    val ghost = rememberGhostRowState(ghostText, ghostId, ghostAtTop, active, listState)

    LazyColumn(
        state = listState,
        // A gap between cards, not a line inside one: CARD_GAP is what keeps
        // consecutive rows from reading as a single block now that each one
        // stands on its own surface. The same gap trails the last card, so it
        // does not read as attached to the add-item bar below it.
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        contentPadding = PaddingValues(bottom = CARD_GAP),
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .taskDragGestures(reorder, count, callbacks),
    ) {
        if (ghostAtTop && ghost.visible) {
            item(key = ghost.key) {
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

        if (!ghostAtTop && ghost.visible) {
            item(key = ghost.key) {
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
            stringResource(R.string.done_count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = DONE_ALPHA),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription =
                if (expanded) {
                    stringResource(R.string.cd_collapse_done)
                } else {
                    stringResource(R.string.cd_expand_done)
                },
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
                    // Both states draw the same path at the same stroke width, so
                    // the star is the same size whichever way it is toggled — the
                    // filled one just also fills that path, rather than switching
                    // to a stock star icon's own, differently-sized geometry.
                    StarGlyph(
                        filled = task.starred,
                        tint =
                            if (task.starred) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = STAR_OUTLINE_ALPHA)
                            },
                        contentDescription =
                            if (task.starred) {
                                stringResource(R.string.cd_unstar)
                            } else {
                                stringResource(R.string.cd_star)
                            },
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_task_options),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!task.done) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            onClick = {
                                menuOpen = false
                                onRename()
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
}

/**
 * A five-pointed star drawn directly rather than via the stock Material star
 * icons — see the call site in [TaskRow] for why. Both states stroke the same
 * path at the same width, and only [filled] adds a fill inside it, so
 * toggling a star never changes its size — just whether its centre is solid
 * or see-through.
 */
@Composable
private fun StarGlyph(
    filled: Boolean,
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .size(24.dp)
                .semantics { this.contentDescription = contentDescription },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f * STAR_OUTER_RADIUS_FRACTION
        val innerRadius = outerRadius * STAR_INNER_RADIUS_FRACTION
        val path = starPath(center, outerRadius, innerRadius)
        if (filled) drawPath(path = path, color = tint)
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = STAR_OUTLINE_STROKE_WIDTH.toPx()),
        )
    }
}

private fun starPath(
    center: Offset,
    outerRadius: Float,
    innerRadius: Float,
): Path =
    Path().apply {
        repeat(STAR_POINTS * 2) { i ->
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = -PI / 2 + i * PI / STAR_POINTS
            val x = center.x + radius * cos(angle).toFloat()
            val y = center.y + radius * sin(angle).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
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
                placeholder = { Text(stringResource(R.string.add_item_placeholder)) },
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
                        .size(ADD_BUTTON_SIZE)
                        .clip(CircleShape)
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
                // ic_launcher_foreground draws its "D" small within a padded
                // adaptive-icon canvas — see AuthScreen.kt's badge for the same
                // crop. Scaling well past the button and clipping to its circle
                // reproduces the tightly-cropped mark instead of the padded one.
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.cd_add),
                    tint = Color.White,
                    modifier = Modifier.requiredSize(ADD_BUTTON_SIZE * ADD_BUTTON_LOGO_SCALE),
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
        Text(
            stringResource(R.string.empty_list_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.empty_list_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val DRAG_ELEVATION = 12f

/** #45: how close to the top/bottom of the screen a dragged row has to get
 *  before the list starts auto-scrolling to meet it, and how fast it scrolls
 *  once it is pinned right at the edge. */
private val EDGE_SCROLL_ZONE = 64.dp
private val EDGE_SCROLL_SPEED = 12.dp

/** Breathing room between cards, so neighbours read as separate surfaces. */
private val CARD_GAP = 8.dp

/** How long the star's glow takes to fade — long enough to follow the row up,
 * short enough that it is over before the next thing is tapped. */
private const val STAR_GLOW_MILLIS = 700
private const val STAR_GLOW_ALPHA = 0.38f
private const val STAR_GLOW_ELEVATION = 8f

private val ADD_BUTTON_SIZE = 52.dp

/** Same ratio as the "D" badge on AuthScreen.kt: the launcher icon's padded
 *  canvas needs about 1.8x the button size to crop tightly to the mark. */
private const val ADD_BUTTON_LOGO_SCALE = 1.8125f

/** An unstarred star's outline — dim enough not to read as starred. */
private const val STAR_OUTLINE_ALPHA = 0.5f
private val STAR_OUTLINE_STROKE_WIDTH = 1.5.dp
private const val STAR_POINTS = 5

/** A regular pentagram's ratio of inner vertices to outer points. */
private const val STAR_INNER_RADIUS_FRACTION = 0.382f
private const val STAR_OUTER_RADIUS_FRACTION = 0.9f

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
