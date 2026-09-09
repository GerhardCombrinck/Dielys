package za.co.dielys.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.dielys.data.DielysRepository
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.TaskEntity
import javax.inject.Inject

/**
 * The list as it is shown: still to do on top, in the order the household chose,
 * and the completed ones underneath.
 *
 * Starred tasks are *not* floated to the top. Wunderlist did that, but it fights
 * dragging: a row that jumps somewhere else the moment you star it makes the
 * position you dragged it to meaningless, and position is the thing both phones
 * have to agree on (F5.5). A star is a mark, not a sort.
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
    ) : ViewModel() {
        private val listId = MutableStateFlow<String?>(null)

        val list: StateFlow<ListEntity?> =
            listId.filterNotNull().flatMapLatest { repo.observeList(it) }.asState(null)

        val board: StateFlow<TaskBoard> =
            listId
                .filterNotNull()
                .flatMapLatest { repo.observeTasks(it) }
                .map { tasks -> TaskBoard(tasks.filterNot { it.done }, tasks.filter { it.done }) }
                .asState(TaskBoard())

        val pending: StateFlow<Int> = repo.observePendingCount().asState(0)
        val stuck: StateFlow<Int> = repo.observeStuckCount().asState(0)

        /** Called by the screen when it opens, and again on rotation. Idempotent. */
        fun open(id: String) {
            if (listId.value != id) listId.value = id
        }

        fun add(title: String) {
            val trimmed = title.trim()
            val id = listId.value
            if (trimmed.isEmpty() || id == null) return
            viewModelScope.launch { repo.addTask(id, trimmed) }
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
