package za.co.dielys.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.dielys.data.DielysRepository
import za.co.dielys.data.local.DoneSectionPrefs
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.NewTaskPlacement
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.domain.Clock
import za.co.dielys.domain.Uuid7
import javax.inject.Inject

/**
 * The list as it is shown: still to do on top, in the order the household chose,
 * and the completed ones underneath.
 *
 * Nothing here sorts by starred. A star *writes a position* instead — it moves
 * the row to the top once, as an edit both phones agree on (F5.5) — so a row that
 * is later dragged somewhere else stays where it was dragged. Sorting by the flag
 * would make dragging a starred row pointless.
 */
data class TaskBoard(
    val active: List<TaskEntity> = emptyList(),
    val done: List<TaskEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = active.isEmpty() && done.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskListViewModel
    @Inject
    constructor(
        private val repo: DielysRepository,
        private val clock: Clock,
        private val doneSection: DoneSectionPrefs,
        placement: NewTaskPlacement,
    ) : ViewModel() {
        private val listId = MutableStateFlow<String?>(null)

        /** Where a new task lands — a device setting, read live rather than
         *  snapshotted, so flipping it in Settings takes effect immediately on a
         *  screen already open. */
        val newItemsOnTop: StateFlow<Boolean> = placement.newItemsOnTop

        val list: StateFlow<ListEntity?> =
            listId.filterNotNull().flatMapLatest { repo.observeList(it) }.asState(null)

        val board: StateFlow<TaskBoard> =
            listId
                .filterNotNull()
                .flatMapLatest { id ->
                    // A fresh empty frame on every switch, not just the very first
                    // subscription — otherwise opening list B renders list A's
                    // stale board (including its Done section) until Room answers,
                    // and animateItem() then animates that stale-to-real jump.
                    flow {
                        emit(TaskBoard())
                        emitAll(
                            repo.observeTasks(id).map { tasks ->
                                TaskBoard(tasks.filterNot { it.done }, tasks.filter { it.done })
                            },
                        )
                    }
                }.asState(TaskBoard())

        val pending: StateFlow<Int> = repo.observePendingCount().asState(0)
        val stuck: StateFlow<Int> = repo.observeStuckCount().asState(0)

        /** Called by the screen when it opens, and again on rotation. Idempotent. */
        fun open(id: String) {
            if (listId.value != id) listId.value = id
        }

        /** Local to this phone, not part of [board] — a Room read would also
         *  make it something the sync engine has to carry. */
        fun isDoneExpanded(listId: String): Boolean = doneSection.isExpanded(listId)

        fun setDoneExpanded(
            listId: String,
            expanded: Boolean,
        ) = doneSection.setExpanded(listId, expanded)

        /**
         * Mints the id up front and hands it back before the write lands, so the
         * screen can key a placeholder row under it — the same id Room's flow
         * will eventually carry, so what the screen renders is one continuous
         * item, not a placeholder later joined by a second, separate row.
         *
         * Null only if no list is open, which the screen cannot reach in
         * practice ([open] runs before the add bar does).
         */
        fun add(title: String): String? {
            val trimmed = title.trim()
            val listId = this.listId.value
            if (trimmed.isEmpty() || listId == null) return null
            val taskId = Uuid7.generate(clock.nowMillis())
            val atTop = newItemsOnTop.value
            viewModelScope.launch { repo.addTask(listId, trimmed, atTop, taskId) }
            return taskId
        }

        fun setDone(
            taskId: String,
            done: Boolean,
        ) {
            viewModelScope.launch { repo.setDone(taskId, done) }
        }

        fun setStarred(
            taskId: String,
            starred: Boolean,
        ) {
            viewModelScope.launch { repo.setStarred(taskId, starred) }
        }

        fun rename(
            taskId: String,
            title: String,
        ) {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch { repo.renameTask(taskId, trimmed) }
        }

        fun delete(taskId: String) {
            viewModelScope.launch { repo.deleteTask(taskId) }
        }

        /**
         * One row is written and nothing else (F5.5). [afterId] and [beforeId] are
         * the rows the moved one landed between, read off the order the drag
         * produced; null on either side means an end of the list.
         */
        fun move(
            taskId: String,
            afterId: String?,
            beforeId: String?,
        ) {
            viewModelScope.launch { repo.moveTask(taskId, afterId, beforeId) }
        }

        private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
            stateIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_ALIVE_MILLIS), initial)

        private companion object {
            const val KEEP_ALIVE_MILLIS = 5_000L
        }
    }
