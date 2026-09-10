package za.co.dielys.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.dielys.data.DielysRepository
import za.co.dielys.data.InviteResult
import za.co.dielys.data.JoinResult
import za.co.dielys.data.PendingInvite
import za.co.dielys.data.SharingRepository
import za.co.dielys.data.local.ListEntity
import za.co.dielys.domain.InviteLink
import javax.inject.Inject

/** A list row, with the item count the screen shows — joined from the tasks table
 * rather than carried on [ListEntity] itself, since nothing else needs it. */
data class ListRow(
    val list: ListEntity,
    val itemCount: Int,
)

/** The invite dialog, from the moment it opens to the moment it has a link. */
sealed interface InviteState {
    data class Working(
        val listTitle: String,
    ) : InviteState

    data class Ready(
        val listTitle: String,
        /**
         * The whole invite, as a link. A bearer credential good for seven days —
         * anybody holding it joins the list — so it goes into a share sheet aimed
         * at one person and nowhere else.
         */
        val link: String,
    ) : InviteState

    data class Failed(
        val message: String,
    ) : InviteState
}

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
        private val sharing: SharingRepository,
        private val invites: PendingInvite,
    ) : ViewModel() {
        val lists: StateFlow<List<ListRow>> =
            combine(repo.observeLists(), repo.observeItemCounts()) { lists, counts ->
                val byListId = counts.associate { it.listId to it.count }
                lists.map { ListRow(it, byListId[it.id] ?: 0) }
            }.asState(emptyList())

        /** Edits made on this device that the server has not acknowledged yet. */
        val pending: StateFlow<Int> = repo.observePendingCount().asState(0)

        /** Edits the server refused for good. Rare, and never silent. */
        val stuck: StateFlow<Int> = repo.observeStuckCount().asState(0)

        /**
         * The invite being made or shown. Null when the dialog is closed — and it
         * is not restored across process death on purpose: a token that has been
         * sitting in saved state since yesterday is worth re-asking for rather
         * than re-sharing.
         */
        private val _invite = MutableStateFlow<InviteState?>(null)
        val invite: StateFlow<InviteState?> = _invite.asStateFlow()

        /**
         * An invite that arrived by tapping a link. Offered, never acted on: a
         * link is something a stranger can send, so joining stays a thing the
         * person on the phone chooses to do.
         */
        val invitation: StateFlow<String?> = invites.pending

        /** What the last join attempt said, for a one-line answer on screen. */
        private val _joined = MutableStateFlow<String?>(null)
        val joined: StateFlow<String?> = _joined.asStateFlow()

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

        /**
         * Where the dragged list landed: [afterId] is the row it should sit
         * below, [beforeId] the row above. Null on either side is an end of the
         * screen. The order is this account's own — see PROTOCOL.md "Ordering the
         * lists".
         */
        fun move(
            listId: String,
            afterId: String?,
            beforeId: String?,
        ) {
            viewModelScope.launch { repo.moveList(listId, afterId, beforeId) }
        }

        fun delete(listId: String) {
            viewModelScope.launch { repo.deleteList(listId) }
        }

        /**
         * L3: only the owner may invite, so the screen only offers this on lists
         * this account owns — and the server refuses anyway if that is ever wrong.
         */
        fun invite(list: ListEntity) {
            val title = list.displayTitle
            _invite.value = InviteState.Working(title)

            viewModelScope.launch {
                _invite.value =
                    when (val result = sharing.invite(list.id)) {
                        is InviteResult.Created ->
                            InviteState.Ready(title, InviteLink.url(result.token))

                        InviteResult.NotYours ->
                            InviteState.Failed("Only the person who made this list can share it.")

                        InviteResult.Offline ->
                            InviteState.Failed("No connection. Try again when you have signal.")

                        is InviteResult.ServerProblem ->
                            InviteState.Failed("Could not make an invite: ${result.detail}")
                    }
            }
        }

        fun dismissInvite() {
            _invite.value = null
        }

        /**
         * Takes whatever was pasted — a link, or a whole shared message with a
         * link in it — because that is what comes out of a chat app.
         */
        fun join(pasted: String) {
            val token = InviteLink.tokenFrom(pasted)
            if (token == null) {
                _joined.value = "That does not look like an invite."
                return
            }

            viewModelScope.launch {
                _joined.value =
                    when (val result = sharing.join(token)) {
                        is JoinResult.Joined ->
                            if (result.alreadyMember) {
                                "You are already on that list."
                            } else {
                                "Joined. The list will appear in a moment."
                            }

                        JoinResult.BadInvite -> "That invite has expired. Ask for a new one."
                        JoinResult.Offline -> "No connection. Try again when you have signal."
                        is JoinResult.ServerProblem -> "Could not join: ${result.detail}"
                    }
            }
        }

        fun acceptInvitation() {
            invites.take()?.let(::join)
        }

        fun declineInvitation() {
            invites.take()
        }

        fun dismissJoined() {
            _joined.value = null
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
