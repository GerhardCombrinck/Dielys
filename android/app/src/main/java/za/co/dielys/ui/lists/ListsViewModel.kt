package za.co.dielys.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.dielys.data.DielysRepository
import za.co.dielys.data.local.ListEntity
import javax.inject.Inject

/**
 * The lists screen, fed entirely by Room (E1.2). Every action below writes
 * locally and returns; the outbox and `WorkManager` deal with the server, so
 * nothing on this screen can be blocked by a bad signal.
 */
@HiltViewModel
class ListsViewModel
    @Inject
    constructor(
        private val repo: DielysRepository,
    ) : ViewModel() {
        val lists: StateFlow<List<ListEntity>> = repo.observeLists().asState(emptyList())

        /** Edits made on this device that the server has not acknowledged yet. */
        val pending: StateFlow<Int> = repo.observePendingCount().asState(0)

        /** Edits the server refused for good. Rare, and never silent. */
        val stuck: StateFlow<Int> = repo.observeStuckCount().asState(0)

        fun create(title: String) {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch { repo.createList(trimmed) }
        }

        fun rename(
            listId: String,
            title: String,
        ) {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch { repo.renameList(listId, trimmed) }
        }

        fun delete(listId: String) {
            viewModelScope.launch { repo.deleteList(listId) }
        }

        /**
         * Kept subscribed for a few seconds past the last collector so a rotation
         * re-reads the cached value instead of re-querying and flashing empty.
         */
        private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
            stateIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_ALIVE_MILLIS), initial)

        private companion object {
            const val KEEP_ALIVE_MILLIS = 5_000L
        }
    }

/** A list discovered through `/auth/memberships` has no title until its
 * changelog arrives. Say so rather than inventing one. */
val ListEntity.displayTitle: String
    get() = title.ifBlank { "Untitled list" }
