package za.co.dielys.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.dielys.data.SessionRepository
import za.co.dielys.data.SignInResult
import javax.inject.Inject

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    /** Shown under the button. Null while nothing has gone wrong yet. */
    val problem: String? = null,
) {
    val canSubmit: Boolean get() = !busy && email.isNotBlank() && password.isNotEmpty()
}

/**
 * Owns whether there is a session, which is the one thing that decides which half
 * of the app is on screen.
 *
 * Sign-in is the only place the UI causes a network call at all, and even here it
 * does not make one: it asks [SessionRepository], which answers with a
 * [SignInResult] rather than a transport exception (E1.2).
 */
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
    ) : ViewModel() {
        private val _signedIn = MutableStateFlow(sessions.isSignedIn())
        val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

        private val _form = MutableStateFlow(SignInUiState())
        val form: StateFlow<SignInUiState> = _form.asStateFlow()

        fun onEmail(value: String) = _form.update { it.copy(email = value, problem = null) }

        fun onPassword(value: String) = _form.update { it.copy(password = value, problem = null) }

        fun signIn() {
            val current = _form.value
            if (!current.canSubmit) return
            _form.update { it.copy(busy = true, problem = null) }

            viewModelScope.launch {
                when (val result = sessions.signIn(current.email.trim(), current.password)) {
                    SignInResult.Success -> {
                        // The password does not outlive the attempt.
                        _form.value = SignInUiState()
                        _signedIn.value = true
                    }

                    else -> _form.update { it.copy(busy = false, problem = result.explain()) }
                }
            }
        }

        /**
         * Clears the tokens only. The local replica and the outbox stay — an edit
         * made before signing out is still the user's, and signing back in sends it.
         */
        fun signOut() {
            sessions.signOut()
            _form.value = SignInUiState()
            _signedIn.value = false
        }
    }

/**
 * One message for a wrong password, a wrong email and an account that does not
 * exist, because the server deliberately cannot tell them apart either — three
 * messages would turn this screen into an account-enumeration oracle.
 */
private fun SignInResult.explain(): String =
    when (this) {
        SignInResult.Success -> ""
        SignInResult.InvalidCredentials -> "Email or password is wrong."
        SignInResult.Offline -> "No connection. Try again when you have signal."
        is SignInResult.ServerProblem -> "Could not sign in: $detail"
    }
