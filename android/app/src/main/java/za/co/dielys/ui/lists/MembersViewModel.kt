package za.co.dielys.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import za.co.dielys.R
import za.co.dielys.data.Member
import za.co.dielys.data.MembersResult
import za.co.dielys.data.RemoveResult
import za.co.dielys.data.SharingRepository
import za.co.dielys.data.local.AccountIdentity
import za.co.dielys.data.local.StringProvider
import javax.inject.Inject

/**
 * The "shared with" sheet (#60), from the moment the people icon is tapped.
 *
 * Read live from the server rather than from Room: it is only ever on screen
 * while the sheet is open, and a stale answer about who can see your list is
 * worse than a moment of "checking…".
 */
sealed interface MembersState {
    val listId: String

    data class Loading(
        override val listId: String,
    ) : MembersState

    data class Loaded(
        override val listId: String,
        val members: List<Member>,
        /** This account, so the sheet knows which row is "you" and whether to
         *  offer Remove on the others or Leave on itself. */
        val meUserId: String?,
        val iAmOwner: Boolean,
        /** Set while a remove or leave is in flight, so the row it names can
         *  say so instead of the whole sheet going blank. */
        val working: String? = null,
    ) : MembersState

    data class Failed(
        override val listId: String,
        val message: String,
    ) : MembersState
}

/**
 * Who a list is shared with, and taking somebody off it (#60).
 *
 * Its own view model rather than more of [ListsViewModel]. The lists screen is
 * about what Room holds; this is a live conversation with the server that
 * happens to be started from one of its rows, and it shares no state with the
 * screen underneath — nothing here reads a list, and nothing on the lists
 * screen reads a member. Splitting it also took [ListsViewModel] back under
 * Detekt's function ceiling, which is the shape of the same observation.
 */
@HiltViewModel
class MembersViewModel
    @Inject
    constructor(
        private val sharing: SharingRepository,
        private val account: AccountIdentity,
        private val strings: StringProvider,
    ) : ViewModel() {
        /** The open sheet, or null when it is closed. */
        private val _members = MutableStateFlow<MembersState?>(null)
        val members: StateFlow<MembersState?> = _members.asStateFlow()

        /**
         * Opens showing "checking…" and asks the server, rather than opening on
         * whatever Room last heard: the member count in Room is a number, and
         * this question is about names.
         */
        fun open(listId: String) {
            _members.value = MembersState.Loading(listId)
            viewModelScope.launch { load(listId) }
        }

        fun dismiss() {
            _members.value = null
        }

        /**
         * Takes somebody off the list — the owner removing them, or this account
         * leaving. Reloads rather than removing the row locally: whether the
         * sheet should still be open at all depends on who just went, and the
         * server is the one that knows the answer.
         */
        fun remove(
            listId: String,
            userId: String,
        ) {
            val loaded = (_members.value as? MembersState.Loaded)?.takeIf { it.listId == listId }
            if (loaded != null) _members.value = loaded.copy(working = userId)

            // "Me" as the sheet worked it out, by email, before the stored id: a
            // session that predates the stored id would otherwise not know it
            // had just left, and reload a sheet for a list it is no longer on.
            val leaving = userId == (loaded?.meUserId ?: account.userId)
            viewModelScope.launch { take(listId, userId, leaving) }
        }

        /**
         * This account leaving [listId] from the list's own menu, without the
         * sheet ever opening (ADR 0006) — the way a member gets a list off their
         * phone now that deleting it is the owner's call.
         *
         * Nothing shows unless it goes wrong, and then the sheet opens on the
         * reason: that is already where a failed leave is explained, so it is
         * explained in the same place whichever way it was started.
         */
        fun leave(listId: String) {
            viewModelScope.launch {
                val me = account.userId ?: whoAmI(listId) ?: return@launch
                take(listId, me, leaving = true)
            }
        }

        private suspend fun take(
            listId: String,
            userId: String,
            leaving: Boolean,
        ) {
            val failed = if (leaving) R.string.leave_failed else R.string.members_failed
            when (val result = sharing.removeMember(listId, userId)) {
                // Leaving takes the sheet with it: there is no list left to be
                // shown the members of, and the row underneath is on its way out
                // with the next sync.
                RemoveResult.Removed -> if (leaving) _members.value = null else load(listId)

                // Only reachable if the membership changed underneath — nothing
                // offered is something the server would refuse — so showing what
                // is true now beats explaining the refusal.
                RemoveResult.NotAllowed -> load(listId)

                RemoveResult.Offline -> fail(listId, strings.get(R.string.error_offline), failed)
                is RemoveResult.ServerProblem -> fail(listId, result.detail, failed)
            }
        }

        /**
         * Which member of [listId] this account is, when no id was stored for it.
         * Null — with the reason on screen — when the server could not be asked;
         * null and silent when this account is already off the list, which is
         * what leaving was for.
         */
        private suspend fun whoAmI(listId: String): String? =
            when (val result = sharing.members(listId)) {
                is MembersResult.Loaded -> result.members.firstOrNull(::isMe)?.userId
                MembersResult.NotYours -> null
                MembersResult.Offline -> {
                    fail(listId, strings.get(R.string.error_offline), R.string.leave_failed)
                    null
                }
                is MembersResult.ServerProblem -> {
                    fail(listId, result.detail, R.string.leave_failed)
                    null
                }
            }

        private suspend fun load(listId: String) {
            when (val result = sharing.members(listId)) {
                is MembersResult.Loaded ->
                    _members.value =
                        MembersState.Loaded(
                            listId = listId,
                            members = result.members,
                            meUserId = result.members.firstOrNull(::isMe)?.userId,
                            iAmOwner = result.members.firstOrNull(::isMe)?.isOwner == true,
                        )

                // Already off this list. Nothing to show and nothing to do about
                // it here; the next sync takes the row off the screen too.
                MembersResult.NotYours -> _members.value = null
                MembersResult.Offline -> fail(listId, strings.get(R.string.error_offline))
                is MembersResult.ServerProblem -> fail(listId, result.detail)
            }
        }

        /**
         * Matched on the email rather than the id: the id is only stored from
         * the server's own login answer, and a session that predates that would
         * otherwise never recognise itself and would be offered Remove on its
         * own row.
         */
        private fun isMe(member: Member): Boolean =
            member.email.equals(account.email?.trim(), ignoreCase = true)

        private fun fail(
            listId: String,
            detail: String,
            template: Int = R.string.members_failed,
        ) {
            _members.value = MembersState.Failed(listId, strings.get(template, detail))
        }
    }
