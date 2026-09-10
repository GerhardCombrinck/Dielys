package za.co.dielys.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
        fun onEmail(value: String) =
            _form.update { it.copy(email = value, problem = null, linkSent = false) }

        fun submit() {
            val current = _form.value
            if (!current.canSubmit) return
            _form.update { it.copy(busy = true, problem = null) }

            viewModelScope.launch {
                val problem = requestMagicLink(current)
                _form.update {
                    if (problem == null) {
                        it.copy(busy = false, linkSent = true)
                    } else {
                        it.copy(busy = false, problem = problem)
                    }
                }
            }
        }

        /** Runs from [init], not from [submit] — a magic link has no form to be
         * busy on behalf of, so this only touches [_form] to surface a failure. */
        private suspend fun redeemMagicLink(token: String) {
            when (val result = sessions.redeemMagicLink(token)) {
                MagicLinkVerifyResult.Success -> {
                    _form.value = AuthUiState()
                    _signedIn.value = true
                }
                else -> _form.update { it.copy(problem = result.explain()) }
            }
        }

        private suspend fun requestMagicLink(form: AuthUiState): String? =
            when (val result = sessions.requestMagicLink(form.email.trim())) {
                MagicLinkRequestResult.Success -> null
                else -> result.explain()
            }

        /**
         * Clears the tokens only. The local replica and the outbox stay — an edit
         * made before signing out is still the user's, and signing back in sends it.
         */
        fun signOut() {
            sessions.signOut()
            _form.value = AuthUiState()
            _signedIn.value = false
        }
    }

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
        MagicLinkRequestResult.Success -> ""
        MagicLinkRequestResult.TooManyAttempts -> "Too many attempts. Try again later."
        MagicLinkRequestResult.Offline -> "No connection. Try again when you have signal."
        is MagicLinkRequestResult.ServerProblem -> "Could not send the link: $detail"
    }
