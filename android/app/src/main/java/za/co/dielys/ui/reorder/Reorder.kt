package za.co.dielys.ui.reorder

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * Long-press drag for a `LazyColumn`, in the only two hundred lines it takes.
 *
 * There is no auto-scroll at the edges yet: a shopping list that runs past one
 * screen is worth handling, but not before the thing works at all.
 */
class ReorderState(
    private val listState: LazyListState,
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

    /**
     * @param y where the long press landed, in pixels from the top of the list.
     * @param count how many rows from the top are reorderable.
     * @return the index picked up, or null if the press was not on a draggable row.
     */
    fun start(
        y: Float,
        count: Int,
    ): Int? {
        val picked =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.index < count && y >= item.offset && y <= item.offset + item.size
            } ?: return null

        draggingKey = picked.key
        initialOffset = picked.offset
        accumulated = 0f
        limit = count
        return picked.index
    }

    /**
     * Moves by [dy] and, when the dragged row's middle has crossed into a
     * neighbour, reports the swap through [onMove]. Middle rather than edge: the
     * row only takes a place once it is more than half into it, which is what
     * stops a slow drag from oscillating between two positions.
     */
    fun drag(
        dy: Float,
        onMove: (Int, Int) -> Unit,
    ) {
        accumulated += dy
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

        onMove(current.index, target.index)
        // draggingKey is left untouched: the row being dragged has not
        // changed, only its index has, and the next read resolves that fresh.
    }

    fun stop() {
        draggingKey = null
        accumulated = 0f
        initialOffset = 0
    }

    private fun itemAt(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
}
