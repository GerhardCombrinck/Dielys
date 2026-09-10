package za.co.dielys.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.dielys.data.MagicLinkRequestResult
import za.co.dielys.data.MagicLinkVerifyResult
import za.co.dielys.data.PendingMagicLink
import za.co.dielys.data.SessionRepository
import javax.inject.Inject

data class AuthUiState(
    val email: String = "",
    val busy: Boolean = false,
    /** True once a link has been mailed for the current [email] — the screen
     * swaps the field and button for a "check your email" message until the
     * email changes again or the link is tapped (ADR 0005: no password, no
     * separate sign-up, the server creates the account on first redeem). */
    val linkSent: Boolean = false,
    /** When the most recent send completed — gates the resend button behind a
     * cooldown, so mail that is just slow does not turn into three inboxed
     * links from one impatient tap. */
    val sentAtMillis: Long = 0L,
    /** True once polling `GET /auth/magic/status` has seen `delivered: true`
     * for the current send — replaces the "can take a few minutes" estimate
     * with a confirmation rather than leaving it up regardless of how the
     * send actually went (ADR 0005 follow-up). */
    val delivered: Boolean = false,
    /** Shown under the button. Null while nothing has gone wrong yet. */
    val problem: String? = null,
) {
    val canSubmit: Boolean get() = !busy && email.isNotBlank()
}

/**
 * Owns whether there is a session, which is the one thing that decides which half
 * of the app is on screen.
 *
 * Requesting a link is the only place the UI causes a network call at all, and
 * even here it does not make one: it asks [SessionRepository], which answers
 * with a result type rather than a transport exception (E1.2).
 */
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
        private val magicLinks: PendingMagicLink,
    ) : ViewModel() {
        private val _signedIn = MutableStateFlow(sessions.isSignedIn())
        val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

        /** Set from the sign-in form on success — there is no display name, just this. */
        val email: String? get() = sessions.email

        private val _form = MutableStateFlow(AuthUiState())
        val form: StateFlow<AuthUiState> = _form.asStateFlow()

        /** The one outstanding delivery poll, so a resend cancels the previous
         * send's loop instead of the two racing each other's updates to [_form]. */
        private var deliveryPoll: Job? = null

        init {
            // A tapped magic link redeems itself the moment it arrives — there is
            // no form to submit, unlike requesting one (ADR 0005). `take()`
            // clears it immediately so a later recomposition cannot redeem the
            // same one-shot token twice.
            viewModelScope.launch {
                magicLinks.pending.filterNotNull().collect { token ->
                    magicLinks.take()
                    redeemMagicLink(token)
                }
            }
        }

        /** Editing the email after a link was sent goes back to the form — the
         * mailed link was for whatever was typed before, not for this. */
        fun onEmail(value: String) {
            deliveryPoll?.cancel()
            _form.update {
                it.copy(
                    email = value,
                    problem = null,
                    linkSent = false,
                    delivered = false,
                )
            }
        }

        fun submit() {
            val current = _form.value
            if (!current.canSubmit) return
            deliveryPoll?.cancel()
            _form.update { it.copy(busy = true, problem = null, delivered = false) }

            viewModelScope.launch {
                when (val result = sessions.requestMagicLink(current.email.trim())) {
                    is MagicLinkRequestResult.Success -> {
                        _form.update {
                            it.copy(
                                busy = false,
                                linkSent = true,
                                sentAtMillis = System.currentTimeMillis(),
                            )
                        }
                        pollForDelivery(result.requestId)
                    }
                    else -> _form.update { it.copy(busy = false, problem = result.explain()) }
                }
            }
        }

        /** Checks `GET /auth/magic/status` every 10s — Brevo's own delivery
         * lag is the normal case, not a failure, so this is what lets the
         * screen say "delivered" instead of leaving a fixed time estimate up
         * regardless of how the send actually went. Gives up quietly after
         * [MAX_POLL_ATTEMPTS]; the static "can take a few minutes" copy is
         * still on screen at that point, so nothing is lost by stopping. */
        private fun pollForDelivery(requestId: String) {
            deliveryPoll =
                viewModelScope.launch {
                    repeat(MAX_POLL_ATTEMPTS) {
                        delay(POLL_INTERVAL_MS)
                        if (sessions.magicLinkDelivered(requestId)) {
                            _form.update { it.copy(delivered = true) }
                            return@launch
                        }
                    }
                }
        }

        /** Runs from [init], not from [submit] — a magic link has no form to be
         * busy on behalf of, so this only touches [_form] to surface a failure. */
        private suspend fun redeemMagicLink(token: String) {
            when (val result = sessions.redeemMagicLink(token)) {
                MagicLinkVerifyResult.Success -> {
                    deliveryPoll?.cancel()
                    _form.value = AuthUiState()
                    _signedIn.value = true
                }
                else -> _form.update { it.copy(problem = result.explain()) }
            }
        }

        /**
         * Clears the tokens only. The local replica and the outbox stay — an edit
         * made before signing out is still the user's, and signing back in sends it.
         */
        fun signOut() {
            deliveryPoll?.cancel()
            sessions.signOut()
            _form.value = AuthUiState()
            _signedIn.value = false
        }
    }

private const val POLL_INTERVAL_MS = 10_000L

/** 18 attempts at 10s apart is 3 minutes — long enough to cover Brevo lag
 * that is merely slow, short enough that a poll left running does not
 * outlive the "Check your email" screen by much if the person wanders off. */
private const val MAX_POLL_ATTEMPTS = 18

/** One message for "wrong", "already spent" and "expired" alike, the same
 * enumeration reasoning [MagicLinkRequestResult.explain] uses below. */
private fun MagicLinkVerifyResult.explain(): String =
    when (this) {
        MagicLinkVerifyResult.Success -> ""
        MagicLinkVerifyResult.InvalidOrExpired -> "That link is no longer valid. Request a new one."
        MagicLinkVerifyResult.Offline -> "No connection. Try again when you have signal."
        is MagicLinkVerifyResult.ServerProblem -> "Could not sign in: $detail"
    }

/**
 * Deliberately no "no such account" case: the server answers the same way
 * whether or not the email has one, since [SessionRepository.redeemMagicLink]
 * creates it on first use (ADR 0005) — there is nothing here for the screen to
 * leak either.
 */
private fun MagicLinkRequestResult.explain(): String =
    when (this) {
        is MagicLinkRequestResult.Success -> ""
        MagicLinkRequestResult.TooManyAttempts -> "Too many attempts. Try again later."
        MagicLinkRequestResult.Offline -> "No connection. Try again when you have signal."
        is MagicLinkRequestResult.ServerProblem -> "Could not send the link: $detail"
    }
