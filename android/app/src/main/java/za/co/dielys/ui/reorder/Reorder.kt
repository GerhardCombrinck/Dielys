package za.co.dielys.ui.reorder

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Takes the item at [from] and puts it at [to], everything else closing up behind
 * it. Pure, and separate from the gesture, because this is the part that has to be
 * right: the drop reads the two neighbours out of the result and hands them to
 * `Position.between`, so an off-by-one here is a task that lands in the wrong place
 * on both phones.
 */
fun <T> List<T>.moved(
    from: Int,
    to: Int,
): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val copy = toMutableList()
    copy.add(to, copy.removeAt(from))
    return copy
}

/**
 * Whether the on-screen order a drag produced should still be shown instead of
 * what Room says.
 *
 * A drag reorders locally before the write completes, so for a moment there are
 * two answers. The draft wins until the database agrees with it — and is dropped
 * the moment the two are about different tasks, because then something else
 * happened (the other phone added a row, a sync landed) and the draft is stale
 * rather than merely ahead.
 */
fun draftStillWanted(
    draft: List<String>,
    stored: List<String>,
): Boolean = draft != stored && draft.toSet() == stored.toSet()

/**
 * How far to auto-scroll this frame so a row dragged past the visible area can
 * still reach the rest of the list (#45). Zero outside [edge] of either end of
 * the viewport; scales linearly with how far past the edge the row has gone,
 * capped at [maxSpeed] — a hair's-width crossing crawls, a finger pinned at
 * the very edge runs at full speed.
 */
fun edgeScrollDelta(
    item: ClosedFloatingPointRange<Float>,
    viewport: IntRange,
    edge: Float,
    maxSpeed: Float,
): Float {
    val intoTop = (viewport.first + edge) - item.start
    if (intoTop > 0f) return -maxSpeed * (intoTop / edge).coerceIn(0f, 1f)
    val intoBottom = item.endInclusive - (viewport.last - edge)
    if (intoBottom > 0f) return maxSpeed * (intoBottom / edge).coerceIn(0f, 1f)
    return 0f
}

/**
 * Long-press drag for a `LazyColumn`, in the only two hundred lines it takes.
 *
 * Auto-scrolls near the top/bottom edges of the viewport (#45) via a loop that
 * runs for as long as a drag is live, independent of `onDrag` — a finger held
 * still right at the edge must keep scrolling, not wait for the next pixel of
 * movement to notice it is there.
 */
class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxScrollPxPerTick: Float,
) {
    /**
     * Key (`items(..., key = ...)`) of the row under the finger, or null when
     * nothing is being dragged. Identity, not a slot: the LazyColumn can still
     * be composed with yesterday's order for a frame after [drag] asks for a
     * swap, since the state change that reorders `active` and the recomposition
     * that lays it back out are two separate steps. Tracking *which row*
     * rather than *which index* means a lookup in that gap finds the row
     * itself wherever it currently sits, instead of whatever row is still
     * sitting at the index the swap was asked for.
     */
    private var draggingKey by mutableStateOf<Any?>(null)

    /** Index of the row under the finger, or null when nothing is being dragged. */
    val draggingIndex: Int?
        get() = draggingItem?.index

    private var accumulated by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)

    /**
     * Rows below this index are not reorderable — the completed section and its
     * heading share the `LazyColumn`, and their indices must never be a drop
     * target.
     */
    private var limit by mutableIntStateOf(0)

    /** Pixels to shift the dragged row by so it stays under the finger. */
    val draggingOffset: Float
        get() =
            draggingItem?.let { item -> (initialOffset + accumulated) - item.offset } ?: 0f

    private val draggingItem: LazyListItemInfo?
        get() = draggingKey?.let { key -> itemAt(key) }

    private var onMove: ((Int, Int) -> Unit)? = null
    private var scrollJob: Job? = null

    /**
     * @param y where the long press landed, in pixels from the top of the list.
     * @param count how many rows from the top are reorderable.
     * @param onMove called with (from, to) whenever the drag — by finger or by
     *   auto-scroll — carries the dragged row's middle into a neighbour.
     * @return the index picked up, or null if the press was not on a draggable row.
     */
    fun start(
        y: Float,
        count: Int,
        onMove: (Int, Int) -> Unit,
    ): Int? {
        val picked =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.index < count && y >= item.offset && y <= item.offset + item.size
            } ?: return null

        draggingKey = picked.key
        initialOffset = picked.offset
        accumulated = 0f
        limit = count
        this.onMove = onMove
        scrollJob = scope.launch { autoScroll() }
        return picked.index
    }

    /**
     * Moves by [dy] and, when the dragged row's middle has crossed into a
     * neighbour, reports the swap. Middle rather than edge: the row only takes
     * a place once it is more than half into it, which is what stops a slow
     * drag from oscillating between two positions.
     */
    fun drag(dy: Float) {
        accumulated += dy
        checkForSwap()
    }

    fun stop() {
        scrollJob?.cancel()
        scrollJob = null
        onMove = null
        draggingKey = null
        accumulated = 0f
        initialOffset = 0
    }

    /**
     * Runs for the whole drag, not just while [drag] is called — a finger
     * parked at the edge with no further movement still has to keep the list
     * coming (#45), which nothing driven off pointer events would do on its
     * own. `delay` rather than a frame clock: this scope has no composition
     * attached to guarantee one is in its context, and a fixed tick is close
     * enough for a scroll a finger is watching, not timing.
     */
    private suspend fun autoScroll() {
        while (true) {
            delay(SCROLL_TICK_MILLIS)
            scrollTick()
        }
    }

    /** One frame of [autoScroll] — its own function so the loop above has a
     *  single exit path rather than a `continue` per early-out. */
    private suspend fun scrollTick() {
        val current = draggingItem ?: return
        val top = current.offset + draggingOffset
        val layout = listState.layoutInfo
        val delta =
            edgeScrollDelta(
                item = top..(top + current.size),
                viewport = layout.viewportStartOffset..layout.viewportEndOffset,
                edge = edgePx,
                maxSpeed = maxScrollPxPerTick,
            )
        if (delta == 0f) return
        listState.scrollBy(delta)
        checkForSwap()
    }

    private fun checkForSwap() {
        val onMove = onMove ?: return
        val current = draggingItem ?: return
        val top = current.offset + draggingOffset
        val middle = top + current.size / 2f

        val target =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key != current.key &&
                    item.index < limit &&
                    middle >= item.offset &&
                    middle <= item.offset + item.size
            } ?: return

        // A LazyColumn pins the *key* of its first visible item to the top
        // across a change to the list, so reordering the row that happens to be
        // that anchor drags the viewport along after it: pick up the first row,
        // move it down two, and the list scrolls down two with it, leaving the
        // row somewhere further down than the finger asked for (#53). Only the
        // first visible row can be the anchor, which is why every other row
        // moves cleanly.
        //
        // Re-pinning by index instead holds the viewport where it is. The
        // request is applied on the next measure, by which time the new order is
        // in place — anchoring to a position rather than to a row that is in the
        // middle of leaving it.
        val anchor = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset

        onMove(current.index, target.index)
        // draggingKey is left untouched: the row being dragged has not
        // changed, only its index has, and the next read resolves that fresh.

        listState.requestScrollToItem(anchor, anchorOffset)
    }

    private fun itemAt(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    private companion object {
        const val SCROLL_TICK_MILLIS = 16L
    }
}
