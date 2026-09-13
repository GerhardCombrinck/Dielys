package za.co.dielys.ui.lists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.dielys.R
import za.co.dielys.data.DielysRepository
import za.co.dielys.data.InviteResult
import za.co.dielys.data.JoinResult
import za.co.dielys.data.ListAccents
import za.co.dielys.data.PendingInvite
import za.co.dielys.data.SharingRepository
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.StringProvider
import za.co.dielys.domain.InviteLink
import javax.inject.Inject

/** A list row, with the item count the screen shows — joined from the tasks table
 * rather than carried on [ListEntity] itself, since nothing else needs it.
 *
 * [accent] is the palette index this phone picked for the list (#57), or null
 * for a list that has not been given one yet; the screen falls back to the
 * hashed colour for those rather than showing a blank dot for a frame. Kept as
 * an index and not a `Color`, so this stays a data type the UI happens to read
 * rather than one that imports Compose. */
data class ListRow(
    val list: ListEntity,
    val itemCount: Int,
    val accent: Int? = null,
)

/** The invite dialog, from the moment it opens to the moment the mail is sent. */
sealed interface InviteState {
    /** Asking who this list is for. No network call yet — opening the dialog
     * is free until the owner types an address and confirms. */
    data class EnteringEmail(
        val listId: String,
        val listTitle: String,
    ) : InviteState

    data class Working(
        val listTitle: String,
    ) : InviteState

    /** The server mailed the invite, scoped to [email] — only an account
     * signed in as that address can accept it. */
    data class Sent(
        val listTitle: String,
        val email: String,
    ) : InviteState

    data class Failed(
        val message: String,
    ) : InviteState
}

/**
 * Joining a list by invite, from the tap to the moment the list is on screen
 * (#61).
 *
 * Accepting only writes a membership on the server — the list itself arrives on
 * the sync that follows, seconds later. Without a state to show for those
 * seconds the screen said nothing at all, and the wait read as a tap that had
 * not worked.
 */
sealed interface JoinState {
    /** Asking the server whether the invite is good. */
    data object Checking : JoinState

    /**
     * Accepted. The list belongs to this account now, but this phone has not
     * been handed it yet: Room has no row for it, or a row with no title, until
     * the catch-up lands.
     */
    data class Fetching(
        val listId: String,
    ) : JoinState

    data class Failed(
        val message: String,
    ) : JoinState
}

/**
 * Why a join did not happen, in the words the screen uses (#61).
 *
 * Out here rather than on the view model because it is a mapping and not a
 * decision: nothing about it needs the class's state.
 */
private fun JoinResult.refusal(strings: StringProvider): String =
    when (this) {
        JoinResult.BadInvite -> strings.get(R.string.join_expired)
        JoinResult.WrongRecipient -> strings.get(R.string.join_wrong_account)
        JoinResult.Offline -> strings.get(R.string.error_offline)
        is JoinResult.ServerProblem -> strings.get(R.string.join_failed, detail)
        // The caller takes this branch first; a join that worked is not one.
        is JoinResult.Joined -> error("a join that worked is not a refusal")
    }

/**
 * Whether a joined list is really here yet (#61) — present, named, and not a
 * list the owner deleted while the invite was in the post. A row with a blank
 * title is one `/auth/memberships` has announced but whose changelog has not
 * landed, which on screen is "Untitled list" rather than the list.
 */
private fun ListEntity?.hasArrived(): Boolean =
    this != null && deletedAt == null && title.isNotBlank()

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
        private val accents: ListAccents,
        private val sharing: SharingRepository,
        private val invites: PendingInvite,
        private val strings: StringProvider,
    ) : ViewModel() {
        /** Null until Room has answered — not the same as no lists, which gets the
         *  "make your first list" screen; this gets nothing (#65). */
        val lists: StateFlow<List<ListRow>?> =
            combine(
                repo.observeLists(),
                repo.observeItemCounts(),
                accents.observeAll(),
            ) { lists, counts, accents ->
                val byListId = counts.associate { it.listId to it.count }
                lists.map { ListRow(it, byListId[it.id] ?: 0, accents[it.id]) }
            }.asState(null)

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

        /**
         * The join in progress, or null when nothing is being joined (#61).
         * Cleared by [awaitList] the moment the list is on screen, so the
         * success case ends by showing the list rather than by asking for a tap.
         */
        private val _join = MutableStateFlow<JoinState?>(null)
        val join: StateFlow<JoinState?> = _join.asStateFlow()

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
         * The colour picked off the list's options menu (#57). Stays on this
         * phone — it is not a list mutation, so it never reaches the outbox and
         * the other person on a shared list keeps whatever they chose.
         */
        fun setAccent(
            listId: String,
            accent: Int,
        ) {
            viewModelScope.launch { accents.set(listId, accent) }
        }

        /**
         * L3: only the owner may invite, so the screen only offers this on lists
         * this account owns — and the server refuses anyway if that is ever wrong.
         * Opens the dialog asking who it's for; nothing is sent until [sendInvite].
         */
        fun invite(list: ListEntity) {
            _invite.value = InviteState.EnteringEmail(list.id, list.displayTitle(strings))
        }

        fun sendInvite(
            listId: String,
            listTitle: String,
            email: String,
        ) {
            val trimmed = email.trim()
            if (trimmed.isEmpty()) return
            _invite.value = InviteState.Working(listTitle)

            viewModelScope.launch {
                _invite.value =
                    when (val result = sharing.invite(listId, trimmed, listTitle)) {
                        InviteResult.Sent -> InviteState.Sent(listTitle, trimmed)

                        InviteResult.NotYours ->
                            InviteState.Failed("Only the person who made this list can share it.")

                        InviteResult.Offline ->
                            InviteState.Failed("No connection. Try again when you have signal.")

                        is InviteResult.ServerProblem ->
                            InviteState.Failed("Could not send the invite: ${result.detail}")
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
                _join.value = JoinState.Failed(strings.get(R.string.join_not_an_invite))
                return
            }

            _join.value = JoinState.Checking
            viewModelScope.launch {
                val result = sharing.join(token)
                if (result !is JoinResult.Joined) {
                    _join.value = JoinState.Failed(result.refusal(strings))
                    return@launch
                }

                // Accepting is not arriving, which is the whole of #61: the
                // server has written the membership, and the list itself only
                // comes down on the sync that follows. So the wait is for Room
                // to really have it (E1.2) — and for a *title*, not merely a
                // row: `/auth/memberships` writes the row first and the
                // changelog names it a moment later, so ending on the row alone
                // would end the wait on "Untitled list".
                //
                // `alreadyMember` is not told apart here on purpose. Accepting
                // an invite twice and accepting it once end in the same place,
                // looking at the list, and a phone that has been reinstalled is
                // already a member while still having nothing to show.
                //
                // No deadline: how long a spinner is worth watching is a
                // question about the dialog, and the dialog answers it.
                _join.value = JoinState.Fetching(result.listId)
                repo.observeList(result.listId).first { it.hasArrived() }

                // Only if this is still the join being waited on — the dialog
                // may have been dismissed and another invite accepted since,
                // and a list arriving late must not close somebody else's
                // spinner.
                if ((_join.value as? JoinState.Fetching)?.listId == result.listId) {
                    _join.value = null
                }
            }
        }

        fun acceptInvitation() {
            invites.take()?.let(::join)
        }

        fun declineInvitation() {
            invites.take()
        }

        fun dismissJoin() {
            _join.value = null
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

/**
 * A list discovered through `/auth/memberships` has no title until its
 * changelog arrives. Say so rather than inventing one.
 *
 * Two overloads, not a plain property: a composable reaches the same
 * localized resource through `LocalContext.current`, but a `ViewModel` may
 * not hold a `Context` (E1) — it goes through [StringProvider] instead.
 */
fun ListEntity.displayTitle(context: Context): String =
    title.ifBlank { context.getString(R.string.untitled_list) }

fun ListEntity.displayTitle(strings: StringProvider): String =
    title.ifBlank { strings.get(R.string.untitled_list) }
