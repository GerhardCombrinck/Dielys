package za.co.dielys.ui.tasks

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.R
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.domain.spotUnderStarred
import za.co.dielys.ui.HelpButton
import za.co.dielys.ui.Loading
import za.co.dielys.ui.SettingsButton
import za.co.dielys.ui.lists.displayTitle
import za.co.dielys.ui.reorder.ReorderState
import za.co.dielys.ui.reorder.draftStillWanted
import za.co.dielys.ui.reorder.moved
import za.co.dielys.ui.theme.PillShape
import za.co.dielys.ui.theme.accentColor
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
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskListViewModel = viewModel(),
) {
    LaunchedEffect(listId) { viewModel.open(listId) }
    // Opened, or come back to from the background: either way somebody is now
    // looking at this list, so its notification has nothing left to say.
    LifecycleResumeEffect(listId) {
        viewModel.seen(listId)
        onPauseOrDispose { }
    }

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

    // The row being edited, if any. Editing happens in the add bar rather than
    // in a dialog over the list (#63): it is the same question the bar already
    // asks, so it gets the same controls — and the row itself greys out to a
    // placeholder while its words are down there being changed.
    var editing by remember { mutableStateOf<TaskEntity?>(null) }

    // The field's own text, lifted up here so the list above it can preview
    // what is being typed before it is saved. A TextFieldValue rather than a
    // String because opening an edit has to put the cursor at the end of the
    // title — a plain String keeps the old selection, which is the start.
    var draftText by remember { mutableStateOf(TextFieldValue()) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

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
    // Nothing is being added while something is being edited: the field holds
    // an existing row's words, and a placeholder for a new item would be a
    // second copy of them somewhere else on the screen (#63).
    val ghostText = if (editing != null) null else pendingText ?: draftText.text.ifBlank { null }

    // The order a drag is producing, before the write has come back through Room.
    var draft by remember { mutableStateOf<List<TaskEntity>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val active = draft ?: board.active

    // Straight to the keyboard, so tapping Edit is one gesture rather than two.
    // requestFocus() alone moves Compose focus but does not reliably reopen the
    // IME when it was already dismissed (e.g. Back closed a previous edit) — the
    // system only auto-shows it for a field going from unfocused to focused as
    // part of a direct user tap, not a programmatic focus request. show() makes
    // it unconditional.
    LaunchedEffect(editing?.id) {
        if (editing != null) {
            field.requestFocus()
            keyboard?.show()
        }
    }

    // The other phone ticked it off, or deleted it, while it was open for
    // editing here. There is nothing left to rename, so the bar goes back to
    // adding rather than saving into a row that is gone (#63).
    LaunchedEffect(board.active, editing) {
        val id = editing?.id ?: return@LaunchedEffect
        if (board.active.none { it.id == id }) editing = null
    }

    // Back gets out of an edit before it gets out of the list — the same thing
    // Cancel does, and what the gesture means while a keyboard is up.
    BackHandler(enabled = editing != null) {
        editing = null
        draftText = TextFieldValue()
    }

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
    // so opening a list is visibly the same list you tapped — and so do the
    // stars and tick boxes below it, which is what makes a glance at the screen
    // say *which* list without reading the title (#57).
    //
    // The stored colour once it is this list's; the hashed fallback while the
    // answer on hand is still the previous list's, or while a list that just
    // arrived has not been given one.
    val storedAccent by viewModel.accent.collectAsStateWithLifecycle()
    val accent =
        storedAccent
            ?.takeIf { it.listId == listId }
            ?.index
            ?.let(::accentColor)
            ?: listAccent(listId)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // The same height as the lists screen's bar (#59). The actions
                // sit centred in it, so anything shorter puts the chip and the
                // sync icon 8dp higher here than on the screen you just left.
                expandedHeight = 96.dp,
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
                    SharedListActions(
                        list = currentList,
                        pending = pending,
                        stuck = stuck,
                        onSetNotify = viewModel::setNotify,
                    )
                    // The same control in the same place as the lists screen
                    // (#59), so crossing between the two does not rearrange the
                    // header under the thumb already reaching for it.
                    HelpButton(onClick = onHelp)
                    SettingsButton(onClick = onSettings)
                },
            )
        },
        bottomBar = {
            AddTaskBar(
                value = draftText,
                editing = editing != null,
                field = field,
                onValueChange = { draftText = it },
                onCancel = {
                    editing = null
                    draftText = TextFieldValue()
                },
                onSubmit = {
                    val trimmed = draftText.text.trim()
                    val added = submitDraft(trimmed, editing, viewModel::add, viewModel::rename)
                    draftText = TextFieldValue()
                    editing = null
                    if (added != null) {
                        pendingId = added
                        pendingText = trimmed
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (board.isEmpty && ghostText == null) {
                NoRows(loaded = board.loaded)
            } else {
                Tasks(
                    active = active,
                    done = board.done,
                    accent = accent,
                    draggingId = draggingId,
                    highlightedId = highlighted,
                    ghostText = ghostText,
                    ghostId = pendingId,
                    ghostIndex = ghostSlot(active, newItemsOnTop),
                    editingId = editing?.id,
                    editingText = draftText.text,
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
                    onRename = { task ->
                        editing = task
                        draftText = TextFieldValue(task.title, TextRange(task.title.length))
                    },
                    onDelete = viewModel::delete,
                )
            }
        }
    }
}

/**
 * What the bottom bar's button does, which depends only on whether a row is open
 * for editing (#63). Returns the id of a row that was *added*, which is the one
 * outcome the screen has to follow — there is a placeholder on screen waiting to
 * become it. A rename has nothing to wait for: the row is already there.
 *
 * Out here rather than inline so the screen's own branching stays under the
 * complexity threshold.
 */
private fun submitDraft(
    trimmed: String,
    editing: TaskEntity?,
    add: (String) -> String?,
    rename: (String, String) -> Unit,
): String? {
    if (editing != null) {
        // Emptying the field and saving is not a request for a nameless row: it
        // leaves the title alone, the same as backing out of the edit.
        if (trimmed.isNotEmpty()) rename(editing.id, trimmed)
        return null
    }
    return if (trimmed.isEmpty()) null else add(trimmed)
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
 * Which `LazyColumn` slot the placeholder row occupies among the active rows.
 *
 * The same question [za.co.dielys.domain.spotUnderStarred] answers for the
 * write, asked here so what is being typed appears where it will end up (#62) —
 * under the starred block when new items go on top, and at the end otherwise.
 */
private fun ghostSlot(
    active: List<TaskEntity>,
    onTop: Boolean,
): Int = if (onTop) spotUnderStarred(active) { it.starred } else active.size

/**
 * The typed-but-not-yet-saved row: which `LazyColumn` key it renders under, and
 * whether it should show at all.
 *
 * Always [GHOST_TYPING_KEY], through typing and the pending-submit window
 * alike, so the placeholder is one continuous `LazyColumn` item throughout —
 * switching to `ghostId` the moment it exists used to retire that item and
 * mount a second one under the new key, and LazyColumn would animate that as
 * an exit and an entrance rather than nothing changing, which is what left a
 * fading duplicate of the row behind on a scroll straight after. The id
 * still decides when the ghost goes away: once Room's real row lands, this
 * item simply stops being requested and the row underneath it, already
 * showing under its own key, is what remains.
 */
private fun ghostRowState(
    ghostText: String?,
    ghostId: String?,
    active: List<TaskEntity>,
): GhostRowState =
    GhostRowState(
        key = GHOST_TYPING_KEY,
        visible = ghostText != null && (ghostId == null || active.none { it.id == ghostId }),
    )

/**
 * Puts the row being worked on where it can be worked on: a little under a
 * third of the way down, so there is still a list above it and a list below it
 * rather than a row pinned to the top edge with nothing to place it against.
 *
 * A fraction of the viewport rather than a row count, because rows are not a
 * fixed height — two long ones can be most of the screen, and "third row down"
 * would mean something different on every list. Nearer the top than the middle
 * because the keyboard is about to take the bottom half.
 *
 * Short lists simply clamp: there is no scrolling a list of three rows so that
 * the first of them sits a third of the way down, and nothing here pretends
 * otherwise.
 */
private suspend fun LazyListState.scrollToWorkingRow(index: Int) {
    settle()
    val inset = (layoutInfo.viewportSize.height * WORKING_ROW_FRACTION).toInt()
    // Negative, because a positive offset scrolls the row *up* past the top
    // edge; this is asking for the opposite.
    animateScrollToItem(index, -inset)
}

/**
 * Waits for the list to stop being re-laid-out underneath, so the scroll that
 * follows lands once instead of landing and then correcting itself — which is
 * what read as the screen scrolling twice (#62).
 *
 * Two things move the moment work starts on a row. The keyboard rises, and the
 * viewport shrinks a frame at a time as it does, so a distance measured before
 * it settles is measured against a screen that no longer exists. And the
 * placeholder row goes in, pushing its neighbours along under a scroll already
 * in flight.
 *
 * [SETTLE_FRAME_LIMIT] is a ceiling rather than a target: whatever is still
 * moving after that long is not a keyboard, and is not worth holding the scroll
 * for.
 */
private suspend fun LazyListState.settle() {
    var previous: Pair<Int, Int>? = null
    repeat(SETTLE_FRAME_LIMIT) {
        withFrameNanos { }
        val now = layoutInfo.viewportSize.height to layoutInfo.totalItemsCount
        if (now == previous) return
        previous = now
    }
}

/** What a row's controls do. See [rememberRowActions]. */
private class RowActions(
    val toggle: (String, Boolean) -> Unit,
    val star: (TaskEntity) -> Unit,
    val rename: (TaskEntity) -> Unit,
    val delete: (String) -> Unit,
)

/**
 * Ticking a row off and starring one both move it, and `LazyColumn` pins the
 * first visible row's *key* — so both drag the viewport somewhere nobody asked
 * for. This is what each should do instead. Pulled out of [Tasks] to keep that
 * function's own branching under the complexity threshold.
 *
 * Both wait for the new order rather than acting on the tap: Room answers
 * several frames later, and until it does the list has not moved, so anything
 * sent at the tap acts on the old one.
 *
 * Renaming and deleting pass straight through — they move nothing, and they are
 * bundled here only so a row takes one of these rather than four callbacks.
 */
@Composable
private fun rememberRowActions(
    active: List<TaskEntity>,
    done: List<TaskEntity>,
    listState: LazyListState,
    onToggle: (String, Boolean) -> Unit,
    onStar: (String, Boolean) -> Unit,
    onRename: (TaskEntity) -> Unit,
    onDelete: (String) -> Unit,
): RowActions {
    // #56: ticking the row pinned to the top sends that key down into the Done
    // section and the viewport follows it there. Put it back. Any other row
    // leaving does not move the anchor, which is what makes re-pinning to the
    // same place a no-op there.
    var repin by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(active, done) {
        val (index, offset) = repin ?: return@LaunchedEffect
        repin = null
        listState.requestScrollToItem(index, offset)
    }

    // #58: starring sends the row to the top of the list, which is off the top
    // of the *screen* the moment the list is scrolled at all — and the pinning
    // above means even a list already at the top slides down by exactly one row
    // to keep its old first row in place, hiding the row just starred. Either
    // way it vanishes, and the glow meant to be followed up there plays where
    // nobody can see it. Go up with it.
    var chasing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) {
        val id = chasing ?: return@LaunchedEffect
        // Not at the top yet, so this is some other change and the star is
        // still on its way. Scrolling now would leave what is being looked at
        // for no reason.
        if (active.firstOrNull()?.id != id) return@LaunchedEffect
        chasing = null
        // Instant, not animated, for the same reason the repin above is: the
        // server echoes the star back a moment later, rewriting the row with
        // its own timestamp, and that re-lays the list out. A scroll animation
        // still in flight when that lands gets disturbed mid-travel — the
        // twitch that showed up exactly as the sync icon went green. There is
        // nothing to disturb once the viewport is already where it belongs.
        //
        // Nothing is lost by not animating it: the row itself still travels,
        // because animateItem() is what carries it up to the top, glow and
        // all. The viewport only has to be somewhere that can see it happen.
        listState.requestScrollToItem(0)
    }

    return RowActions(
        rename = onRename,
        delete = onDelete,
        toggle = { id, value ->
            repin = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            onToggle(id, value)
        },
        star = { task ->
            // Nothing to follow on the way out — unstarring leaves the row where
            // it is — nor for a row already at the top, which will not move, so
            // there would be no new order to wait for and the wait would never
            // end.
            if (!task.starred && active.firstOrNull()?.id != task.id) chasing = task.id
            onStar(task.id, !task.starred)
        },
    )
}

@Composable
private fun Tasks(
    active: List<TaskEntity>,
    done: List<TaskEntity>,
    accent: Color,
    draggingId: String?,
    highlightedId: String?,
    ghostText: String?,
    ghostId: String?,
    ghostIndex: Int,
    editingId: String?,
    editingText: String,
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

    val ghost = ghostRowState(ghostText, ghostId, active)

    val actions =
        rememberRowActions(active, done, listState, onToggle, onStar, onRename, onDelete)

    // The one row being worked on: the one being typed into existence, or the
    // one whose words are down in the bar (#63). Both want the same thing —
    // to be somewhere it can be read while the keyboard is up — so both get it
    // from the same place, once.
    //
    // Keyed on which row it is rather than on what it says, so the screen holds
    // still while it is being typed into. The placeholder keeps its stand-in
    // key across the submit as well, so saving does not scroll a second time on
    // the way to the real row.
    val working = editingId ?: GHOST_TYPING_KEY.takeIf { ghost.visible }
    LaunchedEffect(working) {
        if (working == null) return@LaunchedEffect
        val index =
            if (editingId != null) active.indexOfFirst { it.id == editingId } else ghostIndex
        if (index >= 0) listState.scrollToWorkingRow(index)
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
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .taskDragGestures(reorder, count, callbacks),
    ) {
        // Split around the placeholder rather than bracketed by two of them: a
        // new item goes in under the starred block, which can be anywhere from
        // nowhere to the whole list (#62).
        val chrome =
            RowChrome(
                accent = accent,
                draggingId = draggingId,
                highlightedId = highlightedId,
                editing = EditingRow(editingId, editingText),
                actions = actions,
            )
        taskRows(active.take(ghostIndex), chrome, reorder)

        if (ghost.visible) {
            item(key = ghost.key) {
                GhostRow(text = ghostText ?: "", modifier = Modifier.animateItem())
            }
        }

        taskRows(active.drop(ghostIndex), chrome, reorder)

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
                        accent = accent,
                        modifier = Modifier.animateItem(),
                        onToggle = { actions.toggle(task.id, it) },
                        onStar = { actions.star(task) },
                        onRename = { actions.rename(task) },
                        onDelete = { actions.delete(task.id) },
                    )
                }
            }
        }
    }
}

/**
 * Everything a still-to-do row needs that is the same for all of them, bundled
 * so [taskRows] stays inside the parameter-count threshold — the same reason
 * [DragCallbacks] exists.
 */
private class RowChrome(
    val accent: Color,
    val draggingId: String?,
    val highlightedId: String?,
    val editing: EditingRow,
    val actions: RowActions,
)

/** The row whose words are in the add bar, and what they say right now — read
 *  live, so the placeholder standing in for it keeps up with the typing (#63). */
private class EditingRow(
    val id: String?,
    val text: String,
)

/**
 * A run of still-to-do rows. Called twice, for the rows above the placeholder
 * and the rows below it, so the one body serves both (#62).
 */
private fun LazyListScope.taskRows(
    rows: List<TaskEntity>,
    chrome: RowChrome,
    reorder: ReorderState,
) {
    items(rows, key = { it.id }) { task ->
        if (task.id == chrome.editing.id) {
            // Greyed out where it stands, not hidden: the point of editing in
            // the bar rather than a dialog is that the list is still there to
            // read while you do it (#63). Blank falls back to the old title, so
            // clearing the field does not blank the row it came from.
            GhostRow(
                text = chrome.editing.text.ifBlank { task.title },
                modifier = Modifier.animateItem(),
            )
            return@items
        }

        val dragging = task.id == chrome.draggingId
        // Animated so the row eases back down on drop; the offset itself
        // stays unanimated, because it has to track the finger exactly.
        val lift by animateFloatAsState(if (dragging) 1f else 0f, label = "drag-lift")
        TaskRow(
            task = task,
            accent = chrome.accent,
            dragging = dragging,
            highlighted = task.id == chrome.highlightedId,
            onToggle = { chrome.actions.toggle(task.id, it) },
            onStar = { chrome.actions.star(task) },
            onRename = { chrome.actions.rename(task) },
            onDelete = { chrome.actions.delete(task.id) },
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
 * A row standing in for one that is not settled: the item being typed into the
 * add bar, or the one whose words are down there being changed (#63).
 *
 * Same shape as [TaskRow], so the swap to the real thing once it is saved is
 * that same LazyColumn item's content changing rather than a different element
 * appearing in its place. No tick and no star either way: what this shows is a
 * title mid-flight, and the controls that act on a row belong to the settled
 * one.
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
    accent: Color,
    onToggle: (Boolean) -> Unit,
    onStar: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    highlighted: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Done gives up its card entirely and sits straight on the page, so the
    // Done section reads as a list of what is no longer in the way rather than
    // more of the same stack. Animated, so ticking something off fades its card
    // out under the tick instead of cutting.
    val background by animateColorAsState(
        targetValue =
            when {
                dragging -> MaterialTheme.colorScheme.surfaceVariant
                task.done -> Color.Transparent
                else -> MaterialTheme.colorScheme.surface
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
    // The list's colour rather than the app's amber, so the light that follows
    // the row up is the same colour as the star that sent it there (#57).
    val glowColor = accent
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
        if (!highlighted) {
            // A row adopted fast enough after another (only one id is ever "the
            // highlighted one" at a time) retargets this LaunchedEffect before
            // the 2s fade below finishes, cancelling the animateTo mid-flight —
            // which used to leave glow frozen at whatever alpha it had reached,
            // a highlight stuck on an old row for good. Snap it closed instead,
            // so a superseded fade always lands at 0 rather than wherever it
            // was cut off.
            glow.snapTo(0f)
            return@LaunchedEffect
        }
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

    // Tapping the title opens it for editing, the same as Edit on the options
    // menu (#69). Editing happens in the bar now rather than in a dialog, so a
    // stray tap costs an X rather than a wall over the list. A done row has no
    // Edit to offer — the bar only edits active rows — so its tap stays a plain
    // acknowledgement. The shared interaction source puts the ripple on the Row
    // while the click target stays the Text, so it does not read as if only the
    // words were hit.
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
                accent = accent,
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
                            onClickLabel =
                                if (task.done) null else stringResource(R.string.action_edit),
                            onClick = { if (!task.done) onRename() },
                        ).padding(start = 8.dp, top = 16.dp, bottom = 16.dp)
                        .alpha(if (task.done) DONE_ALPHA else 1f),
            )

            // A done row is meant to recede: it keeps the tick and the title and
            // gives up every control on the right, so the eye passes over it on
            // the way to what is still to do. Un-tick it to get them back.
            if (!task.done) {
                IconButton(onClick = onStar) {
                    // Both states draw the same path at the same stroke width, so
                    // the star is the same size whichever way it is toggled — the
                    // filled one just also fills that path, rather than switching
                    // to a stock star icon's own, differently-sized geometry.
                    // In the list's own colour (#57), which also fixes a star
                    // that was white on a white card in the light theme.
                    StarGlyph(
                        filled = task.starred,
                        tint =
                            if (task.starred) {
                                accent
                            } else {
                                accent.copy(alpha = STAR_OUTLINE_ALPHA)
                            },
                        contentDescription =
                            if (task.starred) {
                                stringResource(R.string.cd_unstar)
                            } else {
                                stringResource(R.string.cd_star)
                            },
                    )
                }

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.cd_task_options),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
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
 * for it, and the stock [androidx.compose.material3.Checkbox]'s color slots do
 * not cleanly express a checked state that is quieter than an unchecked one.
 *
 * Checked is drawn like the unstarred star: outline only, in the list's own
 * colour at half alpha (#57). A filled box would be the loudest thing in the
 * Done section, which is the opposite of what being done should look like.
 */
@Composable
private fun TaskCheckbox(
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Both states in the list's colour, and the ticked one quieter — the same
    // hierarchy as before, now saying which list it belongs to as well.
    val borderColor = accent
    val doneColor = accent.copy(alpha = STAR_OUTLINE_ALPHA)

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
                    .border(
                        2.dp,
                        if (checked) doneColor else borderColor,
                        RoundedCornerShape(5.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = doneColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * The one place words are typed on this screen: a new item, or an existing one
 * being changed (#63). [editing] is the whole difference — it puts a way out
 * next to the field and turns the add button into a save.
 */
@Composable
private fun AddTaskBar(
    value: TextFieldValue,
    editing: Boolean,
    field: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onCancel: () -> Unit,
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
            // Only while editing: adding has nothing to back out of, and a
            // button that does nothing most of the time is one more thing to
            // read past on the way to the field.
            if (editing) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                    )
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        if (editing) {
                            stringResource(R.string.edit_item_placeholder)
                        } else {
                            stringResource(R.string.add_item_placeholder)
                        },
                    )
                },
                singleLine = true,
                shape = PillShape,
                // Matches TextPrompt: the first letter, from the keyboard, so a
                // deliberate lowercase entry is still one backspace away.
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.weight(1f).focusRequester(field),
            )
            Box(
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .size(ADD_BUTTON_SIZE)
                        .clip(CircleShape)
                        .background(
                            color =
                                if (value.text.isBlank()) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                } else if (dark) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            shape = CircleShape,
                        ).clickable(enabled = value.text.isNotBlank(), onClick = onSubmit),
                contentAlignment = Alignment.Center,
            ) {
                if (editing) {
                    // A tick, not the mark: this button is agreeing to a change
                    // here, and the logo is what "add to this list" looks like.
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.action_save),
                        tint = Color.White,
                    )
                } else {
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
}

/** No rows to draw — because the list has none, or because Room has not said
 *  yet. Only the first of those gets to say "nothing here" (#65). */
@Composable
private fun NoRows(loaded: Boolean) {
    if (!loaded) {
        Loading()
        return
    }
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

/** Stands in for the ghost row's key before there is a real task id to use.
 *  Also what [Tasks] watches to know a row is being added, since it is the one
 *  name that does not change between the first keystroke and the save. */
private const val GHOST_TYPING_KEY = "ghost-typing"

/** How far down the screen the row being worked on belongs — see
 *  [scrollToWorkingRow]. Under a third, so what is above it is a glance rather
 *  than half the screen. */
private const val WORKING_ROW_FRACTION = 0.3f

/** About half a second at 60fps. Long enough for a keyboard to finish rising,
 *  short enough that a list which never settles is not waited on. */
private const val SETTLE_FRAME_LIMIT = 30
