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
import za.co.dielys.data.SignUpResult
import javax.inject.Inject

enum class AuthMode {
    SignIn,
    SignUp,
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    /** Shown under the button. Null while nothing has gone wrong yet. */
    val problem: String? = null,
    val minPasswordLength: Int = 0,
) {
    val canSubmit: Boolean get() = !busy && email.isNotBlank() && password.isNotEmpty()
}

/**
 * Owns whether there is a session, which is the one thing that decides which half
 * of the app is on screen.
 *
 * Signing in and registering are the only places the UI causes a network call at
 * all, and even here it does not make one: it asks [SessionRepository], which
 * answers with a result type rather than a transport exception (E1.2).
 */
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
    ) : ViewModel() {
        private val _signedIn = MutableStateFlow(sessions.isSignedIn())
        val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

        /** Set from the sign-in form on success — there is no display name, just this. */
        val email: String? get() = sessions.email

        private val _form = MutableStateFlow(blank())
        val form: StateFlow<AuthUiState> = _form.asStateFlow()

        fun onEmail(value: String) = _form.update { it.copy(email = value, problem = null) }

        fun onPassword(value: String) = _form.update { it.copy(password = value, problem = null) }

        /** Keeps what has been typed: the two modes want the same two fields. */
        fun onMode(mode: AuthMode) = _form.update { it.copy(mode = mode, problem = null) }

        fun submit() {
            val current = _form.value
            if (!current.canSubmit) return
            _form.update { it.copy(busy = true, problem = null) }

            viewModelScope.launch {
                val problem =
                    when (current.mode) {
                        AuthMode.SignIn -> signIn(current)
                        AuthMode.SignUp -> signUp(current)
                    }

                if (problem == null) {
                    // The password does not outlive the attempt.
                    _form.value = blank()
                    _signedIn.value = true
                } else {
                    _form.update { it.copy(busy = false, problem = problem) }
                }
            }
        }

        private suspend fun signIn(form: AuthUiState): String? =
            when (val result = sessions.signIn(form.email.trim(), form.password)) {
                SignInResult.Success -> null
                else -> result.explain()
            }

        private suspend fun signUp(form: AuthUiState): String? =
            when (val result = sessions.signUp(form.email.trim(), form.password)) {
                SignUpResult.Success -> null
                else -> result.explain(sessions.minPasswordLength)
            }

        /**
         * Clears the tokens only. The local replica and the outbox stay — an edit
         * made before signing out is still the user's, and signing back in sends it.
         */
        fun signOut() {
            sessions.signOut()
            _form.value = blank()
            _signedIn.value = false
        }

        private fun blank() = AuthUiState(minPasswordLength = sessions.minPasswordLength)
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

/**
 * Registration can say the email is taken, and this screen says so — the server
 * answers `already-exists` and pretending otherwise would leave somebody
 * retyping a password that was never the problem. It is a known oracle, accepted
 * and rate limited (ADR 0004), and it does not apply to signing in.
 */
private fun SignUpResult.explain(minPasswordLength: Int): String =
    when (this) {
        SignUpResult.Success -> ""
        SignUpResult.EmailTaken -> "That email already has an account. Sign in instead."
        SignUpResult.PasswordTooShort -> "Use at least $minPasswordLength characters."
        SignUpResult.TooManyAttempts -> "Too many attempts. Try again later."
        SignUpResult.Offline -> "No connection. Try again when you have signal."
        is SignUpResult.ServerProblem -> "Could not create the account: $detail"
    }
